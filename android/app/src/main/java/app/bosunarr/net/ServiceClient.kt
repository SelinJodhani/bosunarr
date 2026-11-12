package app.bosunarr.net

import app.bosunarr.data.ServiceConfig
import app.bosunarr.data.ServiceType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A service call that failed; `message` is user-facing. */
class ServiceException(message: String, val status: Int = 502) : Exception(message)

val AppJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = true
}

private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

/**
 * Talks to every configured service directly from the phone. Credentials are injected per service type;
 * qBittorrent uses a cookie session (one shared login per service, 60 s back-off after a failure so a wrong
 * password doesn't get the phone's IP banned).
 */
class ServiceClient(base: OkHttpClient) {
    val http: OkHttpClient = base.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val qbitSids = ConcurrentHashMap<String, String>()
    private val qbitFailures = ConcurrentHashMap<String, Pair<Long, String>>()
    private val qbitLocks = ConcurrentHashMap<String, Mutex>()
    /** Which address last worked for a service with a fallback (`altUrl`). */
    private val preferredBase = ConcurrentHashMap<String, String>()
    /** Transmission's CSRF token (`X-Transmission-Session-Id`), learned from its 409 answer. */
    private val trSessions = ConcurrentHashMap<String, String>()
    /** Deluge Web UI `_session_id` cookies, plus login back-off and locks like qBittorrent's. */
    private val delugeCookies = ConcurrentHashMap<String, String>()
    private val delugeFailures = ConcurrentHashMap<String, Pair<Long, String>>()
    private val delugeLocks = ConcurrentHashMap<String, Mutex>()

    /** Forget cached sessions and failures when a service's settings change. */
    fun reset(id: String) {
        qbitSids.remove(id); qbitFailures.remove(id); preferredBase.remove(id)
        trSessions.remove(id); delugeCookies.remove(id); delugeFailures.remove(id)
        app.bosunarr.feature.torrent.Transmission.reset(id)
    }

    fun baseUrl(s: ServiceConfig): String = preferredBase[s.id] ?: s.url

    fun authHeaders(s: ServiceConfig): Map<String, String> {
        val key = s.apiKey.orEmpty()
        return when (s.serviceType) {
            ServiceType.Bazarr -> mapOf("X-API-KEY" to key)
            ServiceType.Jellyfin -> mapOf(
                "Authorization" to "MediaBrowser Client=\"Bosunarr\", Device=\"Android\", DeviceId=\"bosunarr-android\", Version=\"1.0\", Token=\"$key\"",
            )
            ServiceType.Jellystat -> mapOf("x-api-token" to key)
            ServiceType.QBittorrent, null -> emptyMap()
            // SABnzbd only reads `apikey` from the query string (added in requestAt); NZBGet uses HTTP basic auth.
            ServiceType.Sabnzbd -> emptyMap()
            ServiceType.Nzbget -> mapOf("Authorization" to okhttp3.Credentials.basic(s.username.orEmpty(), s.password.orEmpty(), Charsets.UTF_8))
            // Transmission: HTTP basic auth only when RPC authentication is set up. Deluge: cookie (see requestAt).
            ServiceType.Transmission ->
                if (s.username.isNullOrEmpty() && s.password.isNullOrEmpty()) emptyMap()
                else mapOf("Authorization" to okhttp3.Credentials.basic(s.username.orEmpty(), s.password.orEmpty(), Charsets.UTF_8))
            ServiceType.Deluge -> emptyMap()
            else -> mapOf("X-Api-Key" to key)
        }
    }

    // ---------------------------------------------------------------- public API

    suspend fun text(s: ServiceConfig, path: String, method: String = "GET", body: RequestBody? = null): String =
        request(s, path, method, body)

    suspend fun <T> get(s: ServiceConfig, path: String, de: DeserializationStrategy<T>): T = decodeOffMain(s, request(s, path), de)

    /** Decode on a background thread: large lists (libraries, torrents) would otherwise jank the UI. */
    suspend fun <T> decodeOffMain(s: ServiceConfig, text: String, de: DeserializationStrategy<T>): T =
        withContext(Dispatchers.Default) { decode(s, text, de) }

    suspend inline fun <reified T> get(s: ServiceConfig, path: String): T = get(s, path, serializer<T>())

    suspend fun json(s: ServiceConfig, path: String): JsonElement = get(s, path, JsonElement.serializer())

    /** POST/PUT/DELETE with an optional JSON body; returns the raw response text (often empty). */
    suspend fun send(s: ServiceConfig, path: String, method: String = "POST", body: JsonElement? = null): String =
        request(s, path, method, (body?.toString() ?: if (method == "DELETE") null else "{}")?.toRequestBody(JSON_TYPE))

    suspend inline fun <reified B, reified T> send(s: ServiceConfig, path: String, method: String, body: B): T {
        val text = request(s, path, method, AppJson.encodeToString(serializer<B>(), body).toRequestBody("application/json; charset=utf-8".toMediaType()))
        return decodeOffMain(s, text, serializer<T>())
    }

    /** POST an `application/x-www-form-urlencoded` body (qBittorrent's API). */
    suspend fun form(s: ServiceConfig, path: String, fields: Map<String, String>): String =
        request(s, path, "POST", FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build())

    /** POST a multipart body (qBittorrent `torrents/add` with .torrent files). */
    suspend fun multipart(s: ServiceConfig, path: String, body: MultipartBody): String = request(s, path, "POST", body)

    fun <T> decode(s: ServiceConfig, text: String, de: DeserializationStrategy<T>): T {
        val t = text.trimStart()
        if (t.startsWith("<")) throw ServiceException("${s.name} answered with a web page instead of its API — check the address")
        return try {
            AppJson.decodeFromString(de, text)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw ServiceException("${s.name} gave an unexpected answer")
        }
    }

    /** Authenticated, shape-checked probe used by "Test connection" and the health widget. */
    suspend fun test(s: ServiceConfig): TestResult {
        val started = System.nanoTime()
        val type = s.serviceType ?: throw ServiceException("Unknown service type")
        // Server is SSH, not HTTP: connect + authenticate against the pinned host key.
        if (type == ServiceType.Server) return TestResult(null, app.bosunarr.feature.server.ServerProbe.status(s))
        // JSON-RPC over POST (Transmission session handshake, Deluge login + daemon connection).
        if (type == ServiceType.Transmission || type == ServiceType.Deluge) {
            val v = app.bosunarr.feature.torrent.TorrentClients.probe(s)
            return TestResult(v, ((System.nanoTime() - started) / 1_000_000).toInt())
        }
        val probe = PROBES.getValue(type)
        val text = request(s, probe.path)
        if (text.trimStart().startsWith("<")) throw ServiceException("${s.name} answered with a web page instead of its API — check the address")
        val version = if (type == ServiceType.QBittorrent) {
            text.trim().takeIf { Regex("^v?\\d+\\.\\d+").containsMatchIn(it) }
                ?: throw ServiceException("${s.name} gave an unexpected answer — is this really qBittorrent?")
        } else {
            val el = runCatching { AppJson.parseToJsonElement(text) }.getOrNull()
            if (el == null || !probe.valid(el)) throw ServiceException("${s.name} gave an unexpected answer — is this really ${type.label}?")
            probe.version(el)
        }
        return TestResult(version, ((System.nanoTime() - started) / 1_000_000).toInt())
    }

    // ---------------------------------------------------------------- internals

    /** Network I/O (including reading the body) always runs on Dispatchers.IO, whatever thread calls it. */
    suspend fun request(s: ServiceConfig, path: String, method: String = "GET", body: RequestBody? = null, retried: Boolean = false): String =
        withContext(Dispatchers.IO) { requestOnIo(s, path, method, body, retried) }

    private suspend fun requestOnIo(s: ServiceConfig, path: String, method: String, body: RequestBody?, retried: Boolean): String {
        val bases = listOfNotNull(baseUrl(s), s.url, s.altUrl?.takeIf { it.isNotBlank() }).distinct()
        var lastError: ServiceException? = null
        for (base in bases) {
            try {
                return requestAt(s, base, path, method, body, retried).also {
                    if (base != s.url || preferredBase.containsKey(s.id)) preferredBase[s.id] = base
                }
            } catch (e: Unreachable) {
                lastError = ServiceException(e.message ?: "${s.name} is unreachable", 504)
            }
        }
        throw lastError ?: ServiceException("${s.name} is unreachable", 504)
    }

    private class Unreachable(message: String) : Exception(message)

    private suspend fun requestAt(s: ServiceConfig, base: String, path: String, method: String, body: RequestBody?, retried: Boolean): String {
        val isQbit = s.serviceType == ServiceType.QBittorrent
        val builder = Request.Builder().url(withQueryAuth(s, base + path)).method(method, body).header("Accept", "application/json")
        authHeaders(s).forEach { (k, v) -> builder.header(k, v) }
        if (isQbit) {
            builder.header("Cookie", qbitSids[s.id] ?: qbitLogin(s, base))
            builder.header("Referer", base)
        }
        if (s.serviceType == ServiceType.Transmission) trSessions[s.id]?.let { builder.header(TR_SESSION, it) }
        if (s.serviceType == ServiceType.Deluge) builder.header("Cookie", delugeCookies[s.id] ?: delugeLogin(s, base))
        val res = try {
            http.newCall(builder.build()).await()
        } catch (e: IOException) {
            throw Unreachable(if (e is SocketTimeoutException) "${s.name} timed out" else "${s.name} is unreachable")
        }
        res.use {
            val text = it.body?.string().orEmpty()
            if (isQbit && it.code == 403 && !retried) {
                qbitSids.remove(s.id)
                return requestAt(s, base, path, method, body, true)
            }
            // Transmission answers 409 with a fresh session id until the request carries it.
            if (s.serviceType == ServiceType.Transmission && it.code == 409 && !retried) {
                val sid = it.header(TR_SESSION)
                if (!sid.isNullOrBlank()) {
                    trSessions[s.id] = sid
                    return requestAt(s, base, path, method, body, true)
                }
            }
            // Deluge answers 200 with error code 1 once the session cookie expired: log in again once.
            if (s.serviceType == ServiceType.Deluge && it.isSuccessful && !retried && delugeUnauthenticated(text)) {
                delugeCookies.remove(s.id)
                return requestAt(s, base, path, method, body, true)
            }
            if (it.code in 300..399) throw ServiceException("${s.name} redirected to another page (a login?) — check the URL")
            if (it.code == 401 || it.code == 403) throw ServiceException("${s.name} rejected the credentials", 401)
            if (it.isSuccessful && s.serviceType == ServiceType.Sabnzbd) sabError(s, text)?.let { e -> throw e }
            if (!it.isSuccessful) {
                val detail = runCatching {
                    val el = AppJson.parseToJsonElement(text)
                    (el as? kotlinx.serialization.json.JsonObject)?.let { o ->
                        (o["message"] ?: o["error"] ?: o["errorMessage"])?.let { m -> (m as? kotlinx.serialization.json.JsonPrimitive)?.content }
                    } ?: (el as? kotlinx.serialization.json.JsonArray)?.firstOrNull()?.let { f ->
                        ((f as? kotlinx.serialization.json.JsonObject)?.get("errorMessage") as? kotlinx.serialization.json.JsonPrimitive)?.content
                    }
                }.getOrNull()
                throw ServiceException(detail?.takeIf { d -> d.isNotBlank() && d.length < 200 } ?: "${s.name} returned HTTP ${it.code}", it.code)
            }
            return text
        }
    }

    /** SABnzbd takes its key only as the `apikey` query parameter. The URL is never logged. */
    private fun withQueryAuth(s: ServiceConfig, url: String): String {
        if (s.serviceType != ServiceType.Sabnzbd) return url
        val parsed = url.toHttpUrlOrNull() ?: return url
        return parsed.newBuilder().setQueryParameter("apikey", s.apiKey.orEmpty()).build().toString()
    }

    /** SABnzbd 4.x answers 200 with `{"status": false, "error": "…"}` (or plain `error: …`) for a bad key or call. */
    private fun sabError(s: ServiceConfig, text: String): ServiceException? {
        val t = text.trim()
        if (t.length > 2_000) return null
        val msg = if (t.startsWith("error:", ignoreCase = true)) t.substringAfter(':').trim()
        else if (t.startsWith("{")) runCatching {
            val o = AppJson.parseToJsonElement(t) as? kotlinx.serialization.json.JsonObject
            val status = (o?.get("status") as? kotlinx.serialization.json.JsonPrimitive)?.content
            if (status == "false") (o.get("error") as? kotlinx.serialization.json.JsonPrimitive)?.content else null
        }.getOrNull() else null
        if (msg.isNullOrBlank()) return null
        return if (msg.contains("API Key", ignoreCase = true)) ServiceException("${s.name} rejected the credentials", 401)
        else ServiceException("${s.name}: ${msg.take(160)}", 400)
    }

    private suspend fun qbitLogin(s: ServiceConfig, base: String): String {
        val lock = qbitLocks.getOrPut(s.id) { Mutex() }
        return lock.withLock {
            qbitSids[s.id]?.let { return@withLock it }
            qbitFailures[s.id]?.let { (at, msg) -> if (System.currentTimeMillis() - at < 60_000) throw ServiceException(msg, 401) }
            val req = Request.Builder().url("$base/api/v2/auth/login")
                .post(FormBody.Builder().add("username", s.username.orEmpty()).add("password", s.password.orEmpty()).build())
                .header("Referer", base).header("Origin", base)
                .build()
            val res = try {
                http.newCall(req).await()
            } catch (e: IOException) {
                throw Unreachable(if (e is SocketTimeoutException) "${s.name} timed out" else "${s.name} is unreachable")
            }
            res.use {
                val text = it.body?.string().orEmpty().trim()
                val sid = it.headers("Set-Cookie").firstNotNullOfOrNull { c -> SID_COOKIE.find(c) }
                // 4.x answers 200 "Ok."; 5.2+ answers 204 with no body. Success = 2xx + a session cookie.
                if (it.isSuccessful && text != "Fails." && sid != null) {
                    qbitFailures.remove(s.id)
                    val cookie = "${sid.groupValues[1]}=${sid.groupValues[2]}"
                    qbitSids[s.id] = cookie
                    return@withLock cookie
                }
                val message = when {
                    it.code == 403 && text.contains("banned", true) -> "qBittorrent has temporarily banned this device after failed logins — wait and try again"
                    it.isSuccessful && text != "Fails." -> "qBittorrent accepted the login but sent no session cookie"
                    else -> "qBittorrent login failed — check username and password"
                }
                qbitFailures[s.id] = System.currentTimeMillis() to message
                throw ServiceException(message, 401)
            }
        }
    }

    /** Deluge Web UI login (`auth.login` with the password only); the answer sets a `_session_id` cookie. */
    private suspend fun delugeLogin(s: ServiceConfig, base: String): String {
        val lock = delugeLocks.getOrPut(s.id) { Mutex() }
        return lock.withLock {
            delugeCookies[s.id]?.let { return@withLock it }
            delugeFailures[s.id]?.let { (at, msg) -> if (System.currentTimeMillis() - at < 15_000) throw ServiceException(msg, 401) }
            val payload = kotlinx.serialization.json.buildJsonObject {
                put("method", kotlinx.serialization.json.JsonPrimitive("auth.login"))
                put("params", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(s.password.orEmpty()))))
                put("id", kotlinx.serialization.json.JsonPrimitive(0))
            }
            val req = Request.Builder().url("$base/json").post(payload.toString().toRequestBody(JSON_TYPE)).header("Accept", "application/json").build()
            val res = try {
                http.newCall(req).await()
            } catch (e: IOException) {
                throw Unreachable(if (e is SocketTimeoutException) "${s.name} timed out" else "${s.name} is unreachable")
            }
            res.use {
                val text = it.body?.string().orEmpty()
                if (it.code in 300..399) throw ServiceException("${s.name} redirected to another page (a login?) — check the URL")
                if (text.trimStart().startsWith("<")) throw ServiceException("${s.name} answered with a web page instead of its API — check the address")
                if (!it.isSuccessful) throw ServiceException("${s.name} returned HTTP ${it.code}", it.code)
                val ok = runCatching { (AppJson.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject)?.get("result")?.toString() == "true" }.getOrDefault(false)
                val cookie = it.headers("Set-Cookie").firstNotNullOfOrNull { c -> DELUGE_COOKIE.find(c) }
                if (ok && cookie != null) {
                    delugeFailures.remove(s.id)
                    val value = "_session_id=${cookie.groupValues[1]}"
                    delugeCookies[s.id] = value
                    return@withLock value
                }
                val message = if (ok) "Deluge accepted the login but sent no session cookie" else "Deluge login failed — check the password"
                delugeFailures[s.id] = System.currentTimeMillis() to message
                throw ServiceException(message, 401)
            }
        }
    }

    /** `{"result": null, "error": {"message": "Not authenticated", "code": 1}, "id": …}` */
    private fun delugeUnauthenticated(text: String): Boolean {
        if (text.length > 1_000 || !text.contains("\"error\"")) return false
        val err = runCatching { ((AppJson.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject)?.get("error") as? kotlinx.serialization.json.JsonObject) }.getOrNull()
        return (err?.get("code") as? kotlinx.serialization.json.JsonPrimitive)?.content == "1"
    }

    companion object {
        /** qBittorrent ≤4.x names its cookie `SID`; 5.x uses `QBT_SID_<port>`. */
        private val SID_COOKIE = Regex("""(?:^|[\s,;])((?:QBT_)?SID(?:_\d+)?)=([^;,\s]+)""")
        private val DELUGE_COOKIE = Regex("""(?:^|[\s,;])_session_id=([^;,\s]+)""")
        private const val TR_SESSION = "X-Transmission-Session-Id"
    }
}

data class TestResult(val version: String?, val ms: Int)

private class Probe(val path: String, val valid: (JsonElement) -> Boolean, val version: (JsonElement) -> String? = { null })

private fun JsonElement.obj() = this as? kotlinx.serialization.json.JsonObject
private fun JsonElement?.str(): String? = (this as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
private val hasVersion: (JsonElement) -> Boolean = { it.obj()?.get("version").str() != null }
private val version: (JsonElement) -> String? = { it.obj()?.get("version").str() }

private val PROBES = mapOf(
    ServiceType.Sonarr to Probe("/api/v3/system/status", hasVersion, version),
    ServiceType.Radarr to Probe("/api/v3/system/status", hasVersion, version),
    // Whisparr v3 is Radarr-based (v2 was Sonarr-based); both answer /api/v3/system/status.
    ServiceType.Whisparr to Probe("/api/v3/system/status", hasVersion, version),
    ServiceType.Prowlarr to Probe("/api/v1/system/status", hasVersion, version),
    ServiceType.Bazarr to Probe("/api/system/status", { it.obj()?.get("data")?.obj() != null }, { it.obj()?.get("data")?.obj()?.get("bazarr_version").str() }),
    // /settings/about needs an admin API key (/status is public).
    ServiceType.Jellyseerr to Probe("/api/v1/settings/about", hasVersion, version),
    ServiceType.Overseerr to Probe("/api/v1/settings/about", hasVersion, version),
    ServiceType.QBittorrent to Probe("/api/v2/app/version", { true }),
    ServiceType.Jellyfin to Probe("/System/Info", { it.obj()?.get("Version").str() != null }, { it.obj()?.get("Version").str() }),
    // Needs x-api-token. Jellystat has no cheap version endpoint.
    ServiceType.Jellystat to Probe("/api/getLibraries", { it is kotlinx.serialization.json.JsonArray }),
    // `queue` needs the API key (`version` doesn't), and its answer carries the version too.
    ServiceType.Sabnzbd to Probe("/api?mode=queue&limit=1&output=json", { it.obj()?.get("queue")?.obj() != null }, { it.obj()?.get("queue")?.obj()?.get("version").str() }),
    // JSON-RPC over GET (`version` is a "safe" method); basic auth is checked on every call.
    ServiceType.Nzbget to Probe("/jsonrpc/version", { it.obj()?.get("result").str() != null }, { it.obj()?.get("result").str() }),
    ServiceType.Wizarr to Probe("/api/status", { (it.obj()?.get("users") as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() != null }),
)

/** OkHttp call as a cancellable suspend function. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (!cont.isCancelled) cont.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) { cont.resume(response) { _, _, _ -> response.close() } }
    })
}

/** Run [block] for every service concurrently; failures are collected per service instead of failing the lot. */
suspend fun <T> fanOut(services: List<ServiceConfig>, block: suspend (ServiceConfig) -> T): List<Pair<ServiceConfig, Result<T>>> = coroutineScope {
    val jobs: List<Pair<ServiceConfig, Deferred<Result<T>>>> = services.map { s ->
        s to async {
            try { Result.success(block(s)) } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
        }
    }
    jobs.map { (s, d) -> s to d.await() }
}

/** User-facing message for any error. */
fun Throwable.userMessage(): String = when (this) {
    is ServiceException -> message ?: "Something went wrong"
    is IOException -> "Network error — ${message ?: "check your connection"}"
    else -> message ?: "Something went wrong"
}
