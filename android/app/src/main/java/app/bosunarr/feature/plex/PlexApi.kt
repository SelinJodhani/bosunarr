package app.bosunarr.feature.plex

import app.bosunarr.app
import app.bosunarr.data.ServiceConfig
import app.bosunarr.feature.services.enc
import app.bosunarr.feature.services.rawCall
import app.bosunarr.feature.services.errorDetail
import app.bosunarr.net.ServiceException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

/*
 * Plex Media Server, JSON flavour (`Accept: application/json`, added by the client). Auth is the X-Plex-Token
 * header ([PlexHeaders.server]), which also works for `/photo/:/transcode` so posters load through ServiceImage.
 *   GET /                                   MediaContainer {friendlyName, version, platform, myPlexSubscription, machineIdentifier}
 *   GET /status/sessions                    MediaContainer.Metadata[] with User, Player, Session, TranscodeSession, Media
 *   GET /library/sections                   MediaContainer.Directory[] {key, title, type, refreshing}
 *   GET /library/sections/{id}/all?X-Plex-Container-Size=0   MediaContainer.totalSize
 *   GET /library/recentlyAdded              MediaContainer.Metadata[]
 *   GET /library/sections/{id|all}/refresh  scan
 *   GET /status/sessions/terminate?sessionId=&reason=   stop a stream (Plex Pass)
 * Plex mixes JSON numbers, booleans, "1"/"0" and numeric strings for the same attributes across versions, so
 * everything is read leniently from the element tree.
 */

/* ================================================================ lenient JSON helpers */

internal fun JsonElement?.ps(): String? =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.takeIf { it.isNotBlank() }

internal fun JsonElement?.pn(): Double? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toDoubleOrNull()

internal fun JsonElement?.pb(): Boolean? = when ((this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.lowercase()) {
    "true", "1" -> true
    "false", "0" -> false
    else -> null
}

internal fun JsonElement?.po(): JsonObject? = this as? JsonObject

/** Child elements that may be an array (JSON) or a single object (some proxies / old versions). */
internal fun JsonObject.list(vararg keys: String): List<JsonObject> = keys.flatMap { k ->
    when (val v = this[k]) {
        is JsonArray -> v.mapNotNull { it as? JsonObject }
        is JsonObject -> listOf(v)
        else -> emptyList()
    }
}

private fun container(el: JsonElement, name: String): JsonObject =
    el.po()?.get("MediaContainer").po() ?: throw ServiceException("$name gave an unexpected answer")

/* ================================================================ server */

data class PlexServerInfo(
    val name: String?,
    val version: String?,
    val platform: String?,
    val plexPass: Boolean?,
    val owner: String?,
    val machineId: String?,
    val transcodes: Int?,
)

internal fun parsePlexRoot(el: JsonElement, name: String = "Plex"): PlexServerInfo {
    val c = container(el, name)
    return PlexServerInfo(
        name = c["friendlyName"].ps(),
        version = c["version"].ps(),
        platform = listOfNotNull(c["platform"].ps(), c["platformVersion"].ps()).joinToString(" ").ifBlank { null },
        plexPass = c["myPlexSubscription"].pb(),
        owner = c["myPlexUsername"].ps(),
        machineId = c["machineIdentifier"].ps(),
        transcodes = c["transcoderActiveVideoSessions"].pn()?.toInt(),
    )
}

/** "1.41.2.9200-c6bbc1b53" → "1.41.2.9200" (the build hash is noise in a subtitle). */
internal fun shortPlexVersion(v: String?): String? = v?.substringBefore('-')?.ifBlank { null }

/* ================================================================ images */

/** A poster for [thumb] (a server path like `/library/metadata/1/thumb/169…`), scaled by Plex. */
internal fun plexImage(thumb: String?, width: Int = 240, height: Int = 360): String? {
    val t = thumb?.takeIf { it.startsWith("/") } ?: return null
    return "/photo/:/transcode?width=$width&height=$height&minSize=1&upscale=1&url=${enc(t)}"
}

/* ================================================================ now playing */

enum class PlexDecision(val label: String) { DirectPlay("Direct play"), DirectStream("Direct stream"), Transcode("Transcode") }

data class PlexSession(
    val key: String,
    /** `Session.id`, needed to stop the stream; null when the server didn't send one. */
    val sessionId: String?,
    val user: String,
    val device: String,
    val title: String,
    val subtitle: String?,
    val poster: String?,
    val positionSec: Double,
    val durationSec: Double,
    val state: String,
    val decision: PlexDecision,
    val detail: String?,
    /** Hardware transcoding API in use ("vaapi", "qsv/nvenc"), when there is one. */
    val hw: String?,
    val location: String?,
    val bandwidthKbps: Int?,
) {
    val paused get() = state == "paused"
}

private fun episodeCode(season: Int?, episode: Int?): String? =
    if (season != null && episode != null) "S%02dE%02d".format(season, episode) else null

private fun mediaTitles(m: JsonObject): Triple<String, String?, String?> {
    val type = m["type"].ps()
    val title = m["title"].ps()
    return when (type) {
        "episode" -> Triple(
            m["grandparentTitle"].ps() ?: title ?: "Unknown",
            listOfNotNull(episodeCode(m["parentIndex"].pn()?.toInt(), m["index"].pn()?.toInt()), title).joinToString(" · ").ifBlank { null },
            m["grandparentThumb"].ps() ?: m["parentThumb"].ps() ?: m["thumb"].ps(),
        )
        "track" -> Triple(
            title ?: "Unknown",
            listOfNotNull(m["grandparentTitle"].ps(), m["parentTitle"].ps()).joinToString(" · ").ifBlank { null },
            m["parentThumb"].ps() ?: m["thumb"].ps() ?: m["grandparentThumb"].ps(),
        )
        else -> Triple(title ?: "Unknown", m["year"].pn()?.toInt()?.toString(), m["thumb"].ps() ?: m["grandparentThumb"].ps())
    }
}

private fun codec(v: String?) = v?.uppercase()

internal fun parsePlexSessions(el: JsonElement, name: String = "Plex"): List<PlexSession> =
    container(el, name).list("Metadata", "Video", "Track").mapIndexed { i, m ->
        val user = m["User"].po()
        val player = m["Player"].po()
        val session = m["Session"].po()
        val ts = m["TranscodeSession"].po()
        val media = m.list("Media").firstOrNull()
        val part = media?.list("Part")?.firstOrNull()
        val (title, subtitle, thumb) = mediaTitles(m)
        val videoDecision = ts?.get("videoDecision").ps()
        val audioDecision = ts?.get("audioDecision").ps()
        val decision = when {
            videoDecision == "transcode" || audioDecision == "transcode" -> PlexDecision.Transcode
            ts != null -> PlexDecision.DirectStream
            part?.get("decision").ps() == "transcode" -> PlexDecision.Transcode
            part?.get("decision").ps() == "copy" -> PlexDecision.DirectStream
            else -> PlexDecision.DirectPlay
        }
        val bandwidth = session?.get("bandwidth").pn()?.toInt()
        val detail = if (ts == null || decision == PlexDecision.DirectPlay) null else listOfNotNull(
            when (videoDecision) {
                null -> null
                "copy", "directplay" -> "Video direct"
                else -> listOfNotNull(codec(ts["sourceVideoCodec"].ps()), codec(ts["videoCodec"].ps())).distinct().joinToString(" → ").ifBlank { null }?.let { "Video $it" } ?: "Video converted"
            },
            when (audioDecision) {
                null -> null
                "copy", "directplay" -> "Audio direct"
                else -> listOfNotNull(codec(ts["sourceAudioCodec"].ps()), codec(ts["audioCodec"].ps())).distinct().joinToString(" → ").ifBlank { null }?.let { "Audio $it" } ?: "Audio converted"
            },
            codec(ts["container"].ps()),
            ts["speed"].pn()?.takeIf { it > 0 }?.let { "%.1f×".format(it) },
        ).joinToString(" · ").ifBlank { null }
        val hw = if (ts == null) null else listOfNotNull(ts["transcodeHwDecoding"].ps(), ts["transcodeHwEncoding"].ps())
            .filter { !it.equals("none", true) && it != "0" && it != "false" }.distinct().joinToString("/").ifBlank { null }
        PlexSession(
            key = session?.get("id").ps() ?: m["sessionKey"].ps() ?: "${user?.get("title").ps()}:${m["ratingKey"].ps()}:$i",
            sessionId = session?.get("id").ps(),
            user = user?.get("title").ps() ?: "Someone",
            device = listOfNotNull(player?.get("product").ps(), player?.get("title").ps()).distinct().joinToString(" · "),
            title = title,
            subtitle = subtitle,
            poster = plexImage(thumb),
            positionSec = (m["viewOffset"].pn() ?: 0.0) / 1000,
            durationSec = (m["duration"].pn() ?: 0.0) / 1000,
            state = player?.get("state").ps() ?: "playing",
            decision = decision,
            detail = detail,
            hw = hw,
            location = session?.get("location").ps()?.uppercase(),
            bandwidthKbps = bandwidth,
        )
    }

/* ================================================================ libraries */

data class PlexSection(val key: String, val title: String, val type: String, val refreshing: Boolean)

private val SECTION_KEY = Regex("^\\d+$")

internal fun parsePlexSections(el: JsonElement, name: String = "Plex"): List<PlexSection> =
    container(el, name).list("Directory").mapNotNull { d ->
        val key = d["key"].ps()?.takeIf { SECTION_KEY.matches(it) } ?: return@mapNotNull null
        PlexSection(key, d["title"].ps() ?: "Library", d["type"].ps() ?: "mixed", d["refreshing"].pb() == true)
    }

/** `totalSize` of a paged listing (asked with a page size of 0), falling back to `size`. */
internal fun parsePlexTotal(el: JsonElement): Int? = el.po()?.get("MediaContainer").po()?.let { c -> (c["totalSize"] ?: c["size"]).pn()?.toInt() }

data class PlexLibrary(val section: PlexSection, val count: Int?, val episodes: Int?)

/** Plex's numeric item types for `type=` filters. */
private const val TYPE_EPISODE = 4

internal fun libraryNoun(type: String, n: Int?): String = when (type) {
    "movie" -> if (n == 1) "movie" else "movies"
    "show" -> if (n == 1) "show" else "shows"
    "artist" -> if (n == 1) "artist" else "artists"
    "photo" -> "items"
    else -> "items"
}

/* ================================================================ recently added */

data class PlexRecent(val key: String, val title: String, val subtitle: String?, val poster: String?, val addedAt: Instant?)

internal fun parsePlexRecent(el: JsonElement, name: String = "Plex"): List<PlexRecent> =
    container(el, name).list("Metadata", "Directory", "Video").mapIndexed { i, m ->
        val type = m["type"].ps()
        val title = m["title"].ps()
        val (t, sub, thumb) = when (type) {
            "season" -> Triple(m["parentTitle"].ps() ?: title ?: "Untitled", title, m["thumb"].ps() ?: m["parentThumb"].ps())
            "episode" -> Triple(
                m["grandparentTitle"].ps() ?: title ?: "Untitled",
                episodeCode(m["parentIndex"].pn()?.toInt(), m["index"].pn()?.toInt()) ?: title,
                m["grandparentThumb"].ps() ?: m["parentThumb"].ps() ?: m["thumb"].ps(),
            )
            "album" -> Triple(title ?: "Untitled", m["parentTitle"].ps(), m["thumb"].ps() ?: m["parentThumb"].ps())
            else -> Triple(title ?: "Untitled", m["year"].pn()?.toInt()?.toString(), m["thumb"].ps())
        }
        PlexRecent(
            key = m["ratingKey"].ps() ?: "$t:$i",
            title = t,
            subtitle = sub,
            poster = plexImage(thumb),
            addedAt = m["addedAt"].pn()?.toLong()?.let { Instant.ofEpochSecond(it) },
        )
    }.distinctBy { it.key }

/* ================================================================ loaders */

data class PlexOverview(val info: PlexServerInfo, val libraries: List<PlexLibrary>)

internal suspend fun loadPlexOverview(s: ServiceConfig): PlexOverview = coroutineScope {
    val c = app.client
    val root = async { parsePlexRoot(c.json(s, "/"), s.name) }
    val sections = parsePlexSections(c.json(s, "/library/sections"), s.name)
    val libs = sections.map { sec ->
        async {
            val page = "X-Plex-Container-Start=0&X-Plex-Container-Size=0"
            // Counts are nice-to-have; a slow or odd library mustn't fail the screen.
            val count = runCatching { parsePlexTotal(c.json(s, "/library/sections/${sec.key}/all?$page")) }.getOrNull()
            val episodes = if (sec.type != "show") null
            else runCatching { parsePlexTotal(c.json(s, "/library/sections/${sec.key}/all?type=$TYPE_EPISODE&$page")) }.getOrNull()
            PlexLibrary(sec, count, episodes)
        }
    }.awaitAll()
    PlexOverview(root.await(), libs)
}

internal suspend fun loadPlexSessions(s: ServiceConfig): List<PlexSession> =
    parsePlexSessions(app.client.json(s, "/status/sessions"), s.name)

internal suspend fun loadPlexRecent(s: ServiceConfig): List<PlexRecent> =
    parsePlexRecent(app.client.json(s, "/library/recentlyAdded?X-Plex-Container-Start=0&X-Plex-Container-Size=24"), s.name)

/** Scan one library ([key]) or all of them (null). */
internal suspend fun plexScan(s: ServiceConfig, key: String?) {
    require(key == null || SECTION_KEY.matches(key))
    app.client.text(s, "/library/sections/${key ?: "all"}/refresh")
}

/** Stop a stream. Plex allows this only for a Plex Pass server owner: 401/403 get a plain explanation. */
internal suspend fun plexTerminate(s: ServiceConfig, sessionId: String, reason: String) {
    val (code, text) = rawCall(s, "/status/sessions/terminate?sessionId=${enc(sessionId)}&reason=${enc(reason)}", timeoutSec = 20)
    when {
        code in 200..299 -> Unit
        code == 401 || code == 403 -> throw ServiceException("Stopping streams needs Plex Pass and the server owner's token", code)
        code == 404 -> throw ServiceException("That stream has already ended", code)
        else -> throw ServiceException(errorDetail(text) ?: "${s.name} returned HTTP $code", code)
    }
}
