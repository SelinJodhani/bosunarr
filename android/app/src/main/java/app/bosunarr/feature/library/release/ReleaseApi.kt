package app.bosunarr.feature.library.release

import app.bosunarr.app
import app.bosunarr.data.ServiceConfig
import app.bosunarr.feature.services.arr
import app.bosunarr.feature.services.longCall
import app.bosunarr.feature.services.num
import app.bosunarr.feature.services.obj
import app.bosunarr.feature.services.str
import app.bosunarr.net.AppJson
import app.bosunarr.net.ServiceException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What an interactive search is for. Sonarr: one episode (`episodeId`) or a season (`seriesId` + `seasonNumber`,
 * which returns season packs and single episodes). Radarr / Whisparr: a movie (`movieId`).
 */
data class ReleaseTarget(
    val movieId: Int = 0,
    val seriesId: Int = 0,
    val episodeId: Int = 0,
    val seasonNumber: Int = -1,
) {
    val isMovie get() = movieId > 0
    val isEpisode get() = episodeId > 0
    val isSeason get() = !isEpisode && seriesId > 0 && seasonNumber >= 0
}

/** One release from `GET /api/v3/release` (Sonarr v3/v4, Radarr v3+), only the fields the screen shows. */
data class Release(
    val guid: String,
    val indexerId: Int,
    val indexer: String,
    val title: String,
    val quality: String?,
    /** The arr's quality rank within the profile (higher is better). */
    val qualityWeight: Int,
    val resolution: Int,
    val size: Long,
    val ageMinutes: Double?,
    /** "torrent", "usenet" or "unknown". */
    val protocol: String,
    val seeders: Int?,
    val leechers: Int?,
    /** Usenet grabs, when the indexer reports them (not every arr version passes this through). */
    val grabs: Int?,
    val languages: List<String>,
    val customFormatScore: Int,
    val customFormats: List<String>,
    val rejected: Boolean,
    val temporarilyRejected: Boolean,
    val rejections: List<String>,
    val releaseGroup: String?,
    val infoUrl: String?,
    val indexerFlags: List<String>,
    /** Sonarr: "S01E05", "S01E05-E06", "Season 1" (pack); null for movies or when unparsed. */
    val episodeLabel: String?,
    /** Position in the arr's answer: its own ranking of the releases (best first). */
    val order: Int,
) {
    val key: String get() = "${indexerId}_$guid"
    val isTorrent get() = protocol == "torrent"
    val isUsenet get() = protocol == "usenet"
}

object ReleaseApi {

    /** Indexer searches run sequentially-ish on the arr and each indexer can take its own timeout: be patient. */
    private const val SEARCH_TIMEOUT_SEC = 180L
    /** Grabbing fetches the .torrent / .nzb from the indexer and hands it to the client. */
    private const val GRAB_TIMEOUT_SEC = 60L

    suspend fun search(s: ServiceConfig, t: ReleaseTarget): List<Release> {
        val query = when {
            t.isMovie -> "movieId=${t.movieId}"
            t.isEpisode -> "episodeId=${t.episodeId}"
            t.isSeason -> "seriesId=${t.seriesId}&seasonNumber=${t.seasonNumber}"
            else -> throw ServiceException("Nothing to search for")
        }
        val text = longCall(s, "/api/v3/release?$query", timeoutSec = SEARCH_TIMEOUT_SEC)
        return withContext(Dispatchers.Default) {
            val arr = runCatching { AppJson.parseToJsonElement(text) }.getOrNull() as? JsonArray
                ?: throw ServiceException("${s.name} gave an unexpected answer")
            arr.mapIndexedNotNull { i, el -> (el as? JsonObject)?.let { parse(it, i) } }
        }
    }

    /**
     * `POST /api/v3/release`. The arr finds the release in its search cache by `indexerId` + `guid` (both required by
     * its validator); the cache lives ~30 min, after which it answers 404 "try searching again". The movie / series /
     * episode ids are only read when the arr couldn't map the release itself (an "Unknown series/movie" rejection),
     * so sending them makes those grabbable too. Returns the name of the download client it most likely went to.
     */
    suspend fun grab(s: ServiceConfig, t: ReleaseTarget, r: Release): String? {
        val body = buildJsonObject {
            put("guid", r.guid)
            put("indexerId", r.indexerId)
            if (t.isMovie) put("movieId", t.movieId)
            if (t.seriesId > 0) put("seriesId", t.seriesId)
            if (t.isEpisode) put("episodeId", t.episodeId)
        }
        longCall(s, "/api/v3/release", "POST", body.toString(), timeoutSec = GRAB_TIMEOUT_SEC)
        // Have the arr look at its download clients now so the queue shows the new download straight away.
        try {
            app.client.send(s, "/api/v3/command", "POST", buildJsonObject { put("name", "RefreshMonitoredDownloads") })
        } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        return try { clientFor(s, r.protocol) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }

    /**
     * The enabled client for [protocol] with the best (lowest) priority, as the arr picks it. Null when several share
     * that priority (the arr round-robins between them) or none match.
     */
    private suspend fun clientFor(s: ServiceConfig, protocol: String): String? {
        val list = app.client.json(s, "/api/v3/downloadclient") as? JsonArray ?: return null
        val enabled = list.mapNotNull { it.obj() }
            .filter { (it["enable"] as? JsonPrimitive)?.content == "true" && protocolOf(it["protocol"]) == protocol }
        val best = enabled.minOfOrNull { it["priority"].num() ?: 1.0 } ?: return null
        val top = enabled.filter { (it["priority"].num() ?: 1.0) == best }.mapNotNull { it["name"].str() }.distinct()
        return top.singleOrNull()
    }

    /* ------------------------------------------------------------ parsing */

    /** `"torrent"` / `"usenet"` (strings in v3+), or the enum number (1 usenet, 2 torrent) some builds return. */
    private fun protocolOf(e: JsonElement?): String {
        val p = e as? JsonPrimitive ?: return "unknown"
        return when (p.content.lowercase()) {
            "torrent", "2" -> "torrent"
            "usenet", "1" -> "usenet"
            else -> "unknown"
        }
    }

    private fun JsonElement?.int(): Int? = num()?.toInt()

    private fun parse(o: JsonObject, order: Int): Release? {
        val guid = o["guid"].str()?.takeIf { it.isNotBlank() } ?: return null
        val indexerId = o["indexerId"].int() ?: return null
        val q = o["quality"].obj()?.get("quality").obj()
        // Sonarr v4 / Radarr: `languages: [{id, name}]`; Sonarr v3: a single `language`.
        val languages = (o["languages"].arr()?.mapNotNull { it.obj()?.get("name").str() } ?: listOfNotNull(o["language"].obj()?.get("name").str()))
            .filter { it.isNotBlank() && !it.equals("unknown", true) }
        // Rejections are strings (v3/v4); newer builds may send `{reason, message}` objects.
        val rejections = o["rejections"].arr()?.mapNotNull { el ->
            (el as? JsonPrimitive)?.takeIf { it.isString }?.content ?: el.obj()?.let { it["message"].str() ?: it["reason"].str() }
        }.orEmpty().filter { it.isNotBlank() }.distinct()
        val rejected = (o["rejected"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: rejections.isNotEmpty()
        val ageMinutes = o["ageMinutes"].num() ?: o["ageHours"].num()?.times(60) ?: o["age"].num()?.times(1440)
        val protocol = protocolOf(o["protocol"])
        val flags = when (val f = o["indexerFlags"]) {
            is JsonArray -> f.mapNotNull { it.str() }
            else -> emptyList() // a bit field on some versions: not worth decoding
        }
        return Release(
            guid = guid,
            indexerId = indexerId,
            indexer = o["indexer"].str().orEmpty(),
            title = o["title"].str().orEmpty().ifBlank { guid },
            quality = q?.get("name").str(),
            qualityWeight = o["qualityWeight"].int() ?: 0,
            resolution = q?.get("resolution").int() ?: 0,
            size = o["size"].num()?.toLong() ?: 0L,
            ageMinutes = ageMinutes,
            protocol = protocol,
            seeders = if (protocol == "torrent") o["seeders"].int() else null,
            leechers = if (protocol == "torrent") o["leechers"].int() else null,
            grabs = o["grabs"].int(),
            languages = languages,
            customFormatScore = o["customFormatScore"].int() ?: 0,
            customFormats = o["customFormats"].arr()?.mapNotNull { it.obj()?.get("name").str() }.orEmpty(),
            rejected = rejected,
            temporarilyRejected = (o["temporarilyRejected"] as? JsonPrimitive)?.content == "true",
            rejections = rejections,
            releaseGroup = o["releaseGroup"].str()?.takeIf { it.isNotBlank() },
            infoUrl = (o["infoUrl"].str() ?: o["commentUrl"].str())?.takeIf { it.startsWith("http") },
            indexerFlags = flags,
            episodeLabel = episodeLabel(o),
            order = order,
        )
    }

    private fun pad(n: Int) = n.toString().padStart(2, '0')

    private fun episodeLabel(o: JsonObject): String? {
        val season = o["mappedSeasonNumber"].int() ?: o["seasonNumber"].int() ?: return null
        if ((o["fullSeason"] as? JsonPrimitive)?.content == "true") return if (season == 0) "Specials pack" else "Season $season pack"
        val eps = (o["mappedEpisodeNumbers"].arr() ?: o["episodeNumbers"].arr())?.mapNotNull { it.int() }.orEmpty().sorted()
        if (eps.isEmpty()) return null
        return if (eps.size == 1) "S${pad(season)}E${pad(eps[0])}" else "S${pad(season)}E${pad(eps.first())}-E${pad(eps.last())}"
    }
}
