package app.bosunarr.feature.plex

import android.content.Context
import android.os.Build
import app.bosunarr.BuildConfig
import app.bosunarr.app
import app.bosunarr.feature.services.enc
import app.bosunarr.net.AppJson
import app.bosunarr.net.ServiceException
import app.bosunarr.net.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant
import java.util.UUID

/** Headers Plex wants on every call. The token goes only to the configured server (and plex.tv for sign-in). */
object PlexHeaders {
    const val PRODUCT = "Bosunarr"

    /** For the user's server: the same fixed client id as Jellyfin's DeviceId (the token is what authorises). */
    fun server(token: String): Map<String, String> = mapOf(
        "X-Plex-Token" to token,
        "X-Plex-Client-Identifier" to "bosunarr-android",
        "X-Plex-Product" to PRODUCT,
        "X-Plex-Platform" to "Android",
    )

    /**
     * For plex.tv: a per-install id, so signing in on a second phone doesn't replace the first one's device
     * entry (and its token) in the account's Authorized Devices.
     */
    fun account(clientId: String, token: String? = null): Map<String, String> = buildMap {
        put("Accept", "application/json")
        put("X-Plex-Client-Identifier", clientId)
        put("X-Plex-Product", PRODUCT)
        put("X-Plex-Version", BuildConfig.VERSION_NAME)
        put("X-Plex-Platform", "Android")
        put("X-Plex-Device", Build.MODEL.orEmpty().ifBlank { "Android" })
        put("X-Plex-Device-Name", "$PRODUCT (${Build.MODEL.orEmpty().ifBlank { "Android" }})")
        if (token != null) put("X-Plex-Token", token)
    }

    /** Stable per-install client id for plex.tv (random, not tied to the device). */
    fun installId(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences("plex", Context.MODE_PRIVATE)
        prefs.getString("client_id", null)?.let { return it }
        val id = "bosunarr-" + UUID.randomUUID().toString()
        prefs.edit().putString("client_id", id).apply()
        return id
    }
}

/* ================================================================ plex.tv PIN sign-in */

/*
 * plex.tv PIN flow (https://plex.tv/api/v2):
 *   POST /pins                → {id, code, expiresAt, authToken: null}
 *   user approves at app.plex.tv/auth#?clientID=&code= (or types the 4-character code at plex.tv/link)
 *   GET  /pins/{id}           → authToken once approved (404 once expired)
 *   GET  /resources           → the account's servers, each with its own accessToken and connections
 */

data class PlexPin(val id: Long, val code: String, val authToken: String?, val expiresAt: Instant?)

data class PlexServerChoice(val name: String, val token: String?, val url: String?, val owned: Boolean)

internal fun parsePlexPin(el: JsonElement): PlexPin {
    val o = el.po() ?: throw ServiceException("plex.tv gave an unexpected answer")
    return PlexPin(
        id = o["id"].pn()?.toLong() ?: throw ServiceException("plex.tv gave an unexpected answer"),
        code = o["code"].ps() ?: throw ServiceException("plex.tv gave an unexpected answer"),
        authToken = o["authToken"].ps(),
        expiresAt = o["expiresAt"].ps()?.let { runCatching { Instant.parse(it) }.getOrNull() },
    )
}

/**
 * Servers on the account, owned ones first. The URL prefers a direct LAN address over plain http (as the other
 * services are usually set up); a remote-only server gets its plex.direct https address. Relays are skipped.
 */
internal fun parsePlexResources(el: JsonElement): List<PlexServerChoice> =
    (el as? JsonArray).orEmpty().mapNotNull { it.po() }
        .filter { r -> r["provides"].ps().orEmpty().split(',').any { it.trim() == "server" } }
        .map { r ->
            val conns = r.list("connections").filter { it["relay"].pb() != true }
            val local = conns.firstOrNull { it["local"].pb() == true && it["IPv6"].pb() != true && it["address"].ps() != null && it["port"].pn() != null }
            val url = if (local != null) "http://${local["address"].ps()}:${local["port"].pn()!!.toInt()}"
            else conns.firstOrNull { it["local"].pb() != true }?.get("uri").ps()
            PlexServerChoice(
                name = r["name"].ps() ?: "Plex Media Server",
                token = r["accessToken"].ps(),
                url = url,
                owned = r["owned"].pb() == true,
            )
        }
        .sortedByDescending { it.owned }

/** The auth page that approves [pin] in one tap when the browser is signed in to Plex. */
fun plexAuthUrl(clientId: String, pin: PlexPin): String =
    "https://app.plex.tv/auth#?clientID=${enc(clientId)}&code=${enc(pin.code)}&context%5Bdevice%5D%5Bproduct%5D=${enc(PlexHeaders.PRODUCT)}"

object PlexTv {
    private const val BASE = "https://plex.tv/api/v2"

    private suspend fun call(path: String, headers: Map<String, String>, post: Boolean = false): Pair<Int, String> = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(BASE + path)
        headers.forEach { (k, v) -> b.header(k, v) }
        if (post) b.post(ByteArray(0).toRequestBody(null))
        val res = try {
            app.client.http.newCall(b.build()).await()
        } catch (e: IOException) {
            throw ServiceException("Couldn't reach plex.tv — check your connection", 504)
        }
        res.use { it.code to it.body?.string().orEmpty() }
    }

    private fun parse(text: String): JsonElement =
        runCatching { AppJson.parseToJsonElement(text) }.getOrElse { throw ServiceException("plex.tv gave an unexpected answer") }

    suspend fun createPin(clientId: String): PlexPin {
        val (code, text) = call("/pins", PlexHeaders.account(clientId), post = true)
        if (code !in 200..299) throw ServiceException("plex.tv refused the sign-in request (HTTP $code)", code)
        return parsePlexPin(parse(text))
    }

    /** The pin's current state, or null once it has expired. */
    suspend fun checkPin(clientId: String, id: Long): PlexPin? {
        val (code, text) = call("/pins/$id", PlexHeaders.account(clientId))
        if (code == 404) return null
        if (code !in 200..299) throw ServiceException("plex.tv returned HTTP $code", code)
        return parsePlexPin(parse(text))
    }

    suspend fun servers(clientId: String, token: String): List<PlexServerChoice> {
        val (code, text) = call("/resources?includeHttps=1&includeRelay=0", PlexHeaders.account(clientId, token))
        if (code == 401) throw ServiceException("plex.tv rejected the sign-in", 401)
        if (code !in 200..299) throw ServiceException("plex.tv returned HTTP $code", code)
        return parsePlexResources(parse(text))
    }
}
