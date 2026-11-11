package app.bosunarr.feature.services

import app.bosunarr.app
import app.bosunarr.data.ServiceConfig
import app.bosunarr.data.ServiceType
import app.bosunarr.net.AppJson
import app.bosunarr.net.ServiceException
import app.bosunarr.net.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InterruptedIOException
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

/**
 * A request with a longer timeout than [app.bosunarr.net.ServiceClient] allows (20 s read): Prowlarr searches and
 * "test all", Bazarr's subtitle search (answers only when the provider search is done). Returns status + body
 * without throwing on HTTP errors so callers can read validation payloads.
 */
internal suspend fun rawCall(s: ServiceConfig, path: String, method: String = "GET", json: String? = null, timeoutSec: Long = 90): Pair<Int, String> =
    withContext(Dispatchers.IO) { rawCallOnIo(s, path, method, json, timeoutSec) }

private suspend fun rawCallOnIo(s: ServiceConfig, path: String, method: String, json: String?, timeoutSec: Long): Pair<Int, String> {
    val http = app.client.http.newBuilder()
        .readTimeout(timeoutSec, TimeUnit.SECONDS)
        .callTimeout(timeoutSec + 10, TimeUnit.SECONDS)
        .build()
    val body = when {
        json != null -> json.toRequestBody(JSON_TYPE)
        method == "GET" || method == "DELETE" -> null
        else -> ByteArray(0).toRequestBody(null)
    }
    val bases = listOfNotNull(app.client.baseUrl(s), s.url, s.altUrl?.takeIf { it.isNotBlank() }).distinct()
    var res: okhttp3.Response? = null
    var failure: IOException? = null
    for (base in bases) {
        val b = Request.Builder().url(base + path).method(method, body).header("Accept", "application/json")
        app.client.authHeaders(s).forEach { (k, v) -> b.header(k, v) }
        try {
            res = http.newCall(b.build()).await()
            break
        } catch (e: IOException) {
            failure = e
        }
    }
    if (res == null) {
        val e = failure
        throw ServiceException(if (e is InterruptedIOException) "${s.name} timed out" else "${s.name} is unreachable", 504)
    }
    return res.use { it.code to it.body?.string().orEmpty() }
}

/** [rawCall] that throws a user-facing [ServiceException] on any non-2xx answer. */
internal suspend fun longCall(s: ServiceConfig, path: String, method: String = "GET", json: String? = null, timeoutSec: Long = 90): String {
    val (code, text) = rawCall(s, path, method, json, timeoutSec)
    if (code in 200..299) return text
    if (code == 401 || code == 403) throw ServiceException("${s.name} rejected the credentials", 401)
    if (code in 300..399) throw ServiceException("${s.name} redirected to another page (a login?) — check the URL", code)
    throw ServiceException(errorDetail(text) ?: "${s.name} returned HTTP $code", code)
}

/** Best-effort message from an error body: `{message}`, `{error}`, `[{errorMessage}]`. */
internal fun errorDetail(text: String): String? = runCatching {
    when (val el = AppJson.parseToJsonElement(text)) {
        is JsonObject -> (el["message"] ?: el["error"] ?: el["errorMessage"]).str()
        is JsonArray -> el.mapNotNull { (it as? JsonObject)?.get("errorMessage").str() }.distinct().joinToString(" · ").ifBlank { null }
        else -> null
    }
}.getOrNull()?.takeIf { it.isNotBlank() && it.length < 300 }

internal fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun JsonElement?.num(): Double? = (this as? JsonPrimitive)?.content?.toDoubleOrNull()
internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject
internal fun JsonElement?.arr(): JsonArray? = this as? JsonArray

internal fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")

/* ---------------------------------------------------------------- arr images */

@Serializable
data class ArrImage(val coverType: String = "", val url: String? = null, val remoteUrl: String? = null)

/**
 * Path of an arr's cached cover (`images[].url`, never `remoteUrl`: TMDb is blocked in some regions) relative to
 * the service address, for [app.bosunarr.ui.common.Poster]. The arr includes its URL base ("/sonarr/MediaCover/…"),
 * which the configured address already has, so strip it. [size] picks the arr's pre-scaled variant (250 / 500).
 */
fun arrImagePath(s: ServiceConfig, images: List<ArrImage>?, type: String = "poster", size: Int? = null): String? {
    val raw = images?.firstOrNull { it.coverType == type }?.url?.takeIf { it.isNotBlank() } ?: return null
    if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
    var p = if (raw.startsWith("/")) raw else "/$raw"
    for (base in listOfNotNull(app.client.baseUrl(s), s.url, s.altUrl).distinct()) {
        val bp = runCatching { URI(base).path.orEmpty().trimEnd('/') }.getOrDefault("")
        if (bp.isNotEmpty() && p.startsWith("$bp/")) {
            p = p.removePrefix(bp)
            break
        }
    }
    if (size != null) p = p.replace("/$type.jpg", "/$type-$size.jpg")
    return p
}

/** An image path and the service that serves it. */
data class ImageRef(val service: ServiceConfig, val path: String)

private val TMDB_FILE = Regex("image\\.tmdb\\.org/t/p/\\w+(/[\\w.-]+)$")

/**
 * The arr's cached poster. Some endpoints (history, calendar with `includeSeries`) return the series/movie
 * without converting its images to the arr's local cache, so `url` is the remote TVDB/TMDb address, which may
 * be blocked from the phone: with [ownerId] (series/movie id) use the arr's own cover cache
 * (`/MediaCover/<id>/poster-<size>.jpg`) instead. For items the arr hasn't cached art for yet (often upcoming
 * ones) only a TMDb `remoteUrl` exists: fetch it through Jellyseerr/Overseerr's image proxy when one is
 * configured (as the web app does), else no image.
 */
fun arrPoster(s: ServiceConfig, images: List<ArrImage>?, size: Int = 500, ownerId: Int = 0): ImageRef? {
    val img = images?.firstOrNull { it.coverType == "poster" }
    val url = img?.url?.takeIf { it.isNotBlank() }
    val remoteOnly = url == null || url.startsWith("http://") || url.startsWith("https://")
    if (!remoteOnly) arrImagePath(s, images, "poster", size)?.let { return ImageRef(s, it) }
    if (url != null && ownerId > 0) return ImageRef(s, "/MediaCover/$ownerId/poster-$size.jpg")
    val remote = img?.remoteUrl ?: url ?: return null
    val file = TMDB_FILE.find(remote)?.groupValues?.get(1)
    val seerr = app.servicesOf(ServiceType.Jellyseerr, ServiceType.Overseerr).firstOrNull()
    if (file != null && seerr != null) return ImageRef(seerr, "/imageproxy/tmdb/t/p/${if (size <= 250) "w185" else "w342"}$file")
    return if (url != null) ImageRef(s, url) else null
}

/** "S01E05" */
fun episodeCode(season: Int, episode: Int): String = "S%02dE%02d".format(season, episode)
