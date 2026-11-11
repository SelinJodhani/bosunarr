package app.bosunarr.demo

import app.bosunarr.net.AppJson
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/* ================================================================ catalog (assets/demo/catalog.json) */

@Serializable
internal data class DCast(val name: String, val character: String? = null)

@Serializable
internal data class DMovie(
    val key: String,
    val title: String,
    val year: Int,
    val tmdbId: Int,
    val imdbId: String? = null,
    val runtime: Int,
    val genres: List<String> = emptyList(),
    val studio: String,
    val certification: String? = null,
    val rating: Double,
    val votes: Long = 0,
    val rt: Int? = null,
    val rtAudience: Int? = null,
    val released: String,
    val homeInDays: Int? = null,
    val tagline: String? = null,
    val overview: String,
    val director: String? = null,
    val cast: List<DCast> = emptyList(),
    val radarr: String = "none",
    val quality: String = "Bluray-1080p",
    val size: Long = 0,
    val added: Int? = null,
    val views: Int = 0,
)

@Serializable
internal data class DEpisode(
    val n: Int,
    val title: String,
    val aired: String? = null,
    val file: Boolean = false,
    val downloading: Boolean = false,
    val finale: String? = null,
)

@Serializable
internal data class DSeries(
    val key: String,
    val title: String,
    val year: Int,
    val tvdbId: Int,
    val tmdbId: Int,
    val network: String,
    val status: String,
    val runtime: Int,
    val genres: List<String> = emptyList(),
    val certification: String? = null,
    val rating: Double,
    val votes: Long = 0,
    val overview: String,
    val cast: List<DCast> = emptyList(),
    val live: Boolean = false,
    val quality: String = "WEBDL-1080p",
    val episodeSize: Long = 0,
    val episodes: List<DEpisode> = emptyList(),
)

@Serializable
internal data class DCatalog(val movies: List<DMovie>, val series: List<DSeries>)

/* ================================================================ library state */

/** A movie in the demo Radarr. */
internal class LibMovie(val id: Int, val m: DMovie, var monitored: Boolean, var hasFile: Boolean, val added: Instant, var fileAdded: Instant?) {
    val homeRelease: LocalDate? get() = m.homeInDays?.let { DemoWorld.today().plusDays(it.toLong()) }
    val upcoming: Boolean get() = homeRelease?.isAfter(DemoWorld.today()) == true
}

internal class LibEpisode(
    val id: Int,
    val seriesId: Int,
    val season: Int,
    val n: Int,
    val title: String,
    val air: Instant,
    var hasFile: Boolean,
    var monitored: Boolean,
    val finale: String?,
    var fileAdded: Instant?,
)

internal class LibSeries(val id: Int, val s: DSeries, var monitored: Boolean, val added: Instant, val episodes: MutableList<LibEpisode>) {
    val seasonMonitored = mutableMapOf<Int, Boolean>()
}

/** One event in an arr's history. */
internal class HistEvent(
    val id: Int,
    val service: String,
    val type: String,
    val at: Instant,
    val movieId: Int? = null,
    val episodeId: Int? = null,
    val source: String,
    val quality: String,
    val data: Map<String, String> = emptyMap(),
)

/**
 * The whole fake world, rebuilt every time the demo starts. Times are relative to when it was built (and to today),
 * so the calendar and "Up next" are never stale. All mutation happens under the [DemoWorld] lock.
 */
internal class World(val cat: DCatalog) {
    val started: Instant = Instant.now()
    val zone: ZoneId = ZoneId.systemDefault()

    val movies = mutableListOf<LibMovie>()
    val series = mutableListOf<LibSeries>()
    val history = mutableListOf<HistEvent>()
    var nextId = 1000

    fun movieByKey(key: String) = movies.firstOrNull { it.m.key == key }
    fun movie(id: Int) = movies.firstOrNull { it.id == id }
    fun seriesById(id: Int) = series.firstOrNull { it.id == id }
    fun episode(id: Int) = series.firstNotNullOfOrNull { s -> s.episodes.firstOrNull { it.id == id } }
    fun catalogMovie(key: String) = cat.movies.firstOrNull { it.key == key }
    fun catalogSeries(key: String) = cat.series.firstOrNull { it.key == key }

    // Built at the end of init, once the library exists.
    val torrents: DemoTorrents
    val seerr: DemoSeerrState
    val misc: DemoMiscState

    init {
        val now = started
        // Radarr: every title that isn't "none", ids in catalog order.
        var id = 1
        for (m in cat.movies) {
            if (m.radarr == "none") continue
            val has = m.radarr == "downloaded"
            val fileAt = m.added?.let { now.minus(days(it + 0.3 * rand(m.key))) }
            val addedAt = fileAt?.minus(hours(2.0)) ?: now.minus(days(3.0 + 20 * rand(m.key + "a")))
            movies += LibMovie(id++, m, monitored = true, hasFile = has, added = addedAt, fileAdded = if (has) fileAt else null)
        }
        // Sonarr.
        var sid = 1
        for (s in cat.series) {
            val eps = mutableListOf<LibEpisode>()
            val airs = airTimes(s)
            for (e in s.episodes) {
                val air = airs.getValue(e.n)
                eps += LibEpisode(
                    id = sid * 1000 + e.n, seriesId = sid, season = 1, n = e.n, title = e.title, air = air,
                    hasFile = e.file, monitored = true, finale = e.finale,
                    fileAdded = if (!e.file) null else if (air.isAfter(now.minus(days(120.0)))) air.plus(hours(0.8 + rand(s.key + e.n))) else now.minus(days(200 + 30 * rand(s.key + e.n))),
                )
            }
            series += LibSeries(sid, s, monitored = true, added = (eps.mapNotNull { it.fileAdded }.minOrNull() ?: now).minus(hours(3.0)), episodes = eps)
            sid++
        }
        // Superman's last short is left unmonitored, so the library shows a mix.
        series.firstOrNull { it.s.key == "superman-1941" }?.episodes?.lastOrNull()?.monitored = false
        buildHistory()
        torrents = DemoTorrents(this)
        seerr = DemoSeerrState(this)
        misc = DemoMiscState(this)
    }

    /** Episodes with fixed dates keep them; a live series airs twice a week around today (next one tomorrow). */
    fun airTimes(s: DSeries): Map<Int, Instant> {
        if (!s.live) return s.episodes.associate { e ->
            e.n to (e.aired?.let { LocalDate.parse(it) } ?: LocalDate.of(s.year, 1, 1)).atTime(LocalTime.of(20, 0)).atZone(zone).toInstant()
        }
        val anchor = s.episodes.firstOrNull { !it.file && !it.downloading && s.episodes.indexOf(it) > s.episodes.indexOfLast { e -> e.file || e.downloading } }?.n
            ?: s.episodes.last().n
        val out = mutableMapOf<Int, LocalDate>()
        out[anchor] = today().plusDays(1)
        for (n in anchor - 1 downTo s.episodes.first().n) out[n] = out.getValue(n + 1).minusDays(if (n % 2 == 0) 3 else 4)
        for (n in anchor + 1..s.episodes.last().n) out[n] = out.getValue(n - 1).plusDays(if (n % 2 == 0) 3 else 4)
        return out.mapValues { (_, d) -> d.atTime(LocalTime.of(21, 0)).atZone(zone).toInstant() }
    }

    fun today(): LocalDate = LocalDate.now(zone)

    private fun buildHistory() {
        var hid = 1
        fun add(service: String, type: String, at: Instant, movieId: Int? = null, episodeId: Int? = null, source: String, quality: String, data: Map<String, String> = emptyMap()) {
            history += HistEvent(hid++, service, type, at, movieId, episodeId, source, quality, data)
        }
        for (m in movies) {
            val rel = releaseName(m.m)
            val f = m.fileAdded
            if (f != null) {
                add("radarr", "grabbed", f.minus(hours(0.6)), movieId = m.id, source = rel, quality = m.m.quality, data = mapOf("indexer" to indexerFor(m.m.key), "downloadClient" to "qBittorrent", "downloadClientName" to "qBittorrent"))
                add("radarr", "downloadFolderImported", f, movieId = m.id, source = rel, quality = m.m.quality, data = mapOf("downloadClient" to "qBittorrent", "downloadClientName" to "qBittorrent"))
            }
        }
        movieByKey("plan-9-from-outer-space")?.let { m ->
            add("radarr", "grabbed", started.minus(days(6.2)), movieId = m.id, source = "Plan.9.from.Outer.Space.1957.720p.BluRay.x264-OLDREEL", quality = "Bluray-720p", data = mapOf("indexer" to "Public Domain Torrents", "downloadClientName" to "qBittorrent"))
            add("radarr", "downloadFailed", started.minus(days(6.0)), movieId = m.id, source = "Plan.9.from.Outer.Space.1957.720p.BluRay.x264-OLDREEL", quality = "Bluray-720p", data = mapOf("message" to "Download failed: torrent has no seeders left", "downloadClientName" to "qBittorrent"))
            add("radarr", "grabbed", started.minus(days(1.4)), movieId = m.id, source = releaseName(m.m), quality = m.m.quality, data = mapOf("indexer" to "Internet Archive", "downloadClientName" to "qBittorrent"))
        }
        for (key in listOf("sprite-fright", "coffee-run", "spring")) movieByKey(key)?.let { m ->
            add("radarr", "grabbed", started.minus(hours(1.0 + 20 * rand(key))), movieId = m.id, source = releaseName(m.m), quality = m.m.quality, data = mapOf("indexer" to indexerFor(key), "downloadClientName" to "qBittorrent"))
        }
        for (s in series) for (e in s.episodes) {
            val rel = releaseName(s.s, e)
            val f = e.fileAdded
            if (f != null && f.isAfter(started.minus(days(90.0)))) {
                add("sonarr", "grabbed", f.minus(hours(0.4)), episodeId = e.id, source = rel, quality = s.s.quality, data = mapOf("indexer" to "Internet Archive", "downloadClientName" to "qBittorrent"))
                add("sonarr", "downloadFolderImported", f, episodeId = e.id, source = rel, quality = s.s.quality, data = mapOf("downloadClientName" to "qBittorrent"))
            } else if (f != null) {
                add("sonarr", "seriesFolderImported", f, episodeId = e.id, source = rel, quality = s.s.quality)
            }
            val src = s.s.episodes.firstOrNull { it.n == e.n }
            if (src?.downloading == true) add("sonarr", "grabbed", e.air.plus(hours(0.5)), episodeId = e.id, source = rel, quality = s.s.quality, data = mapOf("indexer" to "Internet Archive", "downloadClientName" to "qBittorrent"))
        }
        series.firstOrNull { it.s.live }?.episodes?.firstOrNull { !it.hasFile && it.air.isBefore(started) && s(it).downloading.not() }?.let { e ->
            val s = seriesById(e.seriesId)!!
            add("sonarr", "grabbed", e.air.plus(hours(0.5)), episodeId = e.id, source = releaseName(s.s, e).replace("720p", "1080p"), quality = "WEBDL-1080p", data = mapOf("indexer" to "Academic Torrents", "downloadClientName" to "qBittorrent"))
            add("sonarr", "downloadFailed", e.air.plus(hours(3.0)), episodeId = e.id, source = releaseName(s.s, e).replace("720p", "1080p"), quality = "WEBDL-1080p", data = mapOf("message" to "Download failed: the torrent stalled at 12% with no peers", "downloadClientName" to "qBittorrent"))
        }
        nextId = hid + 100
    }

    private fun s(e: LibEpisode): DEpisode = seriesById(e.seriesId)!!.s.episodes.first { it.n == e.n }

    fun addHistory(service: String, type: String, movieId: Int? = null, episodeId: Int? = null, source: String, quality: String, data: Map<String, String> = emptyMap()) {
        history += HistEvent(nextId++, service, type, Instant.now(), movieId, episodeId, source, quality, data)
    }

    /** Called on every request: torrents that finished since are imported into the library. */
    fun tick() {
        torrents.tick()
    }
}

internal fun indexerFor(key: String): String = when {
    rand("idx$key") < 0.55 -> "Internet Archive"
    rand("idx$key") < 0.85 -> "Public Domain Torrents"
    else -> "Academic Torrents"
}

private fun qualityParts(q: String): Pair<String, String> {
    val res = Regex("(\\d{3,4}p)").find(q)?.value ?: "1080p"
    val src = when {
        q.startsWith("Bluray", true) -> "BluRay"
        q.startsWith("WEBDL", true) -> "WEB-DL"
        q.startsWith("WEBRip", true) -> "WEBRip"
        else -> "HDTV"
    }
    return res to src
}

internal fun releaseName(m: DMovie): String {
    val (res, src) = qualityParts(m.quality)
    val group = if (m.studio.contains("Blender") || m.key == "sita-sings-the-blues") "OPENMOVIE" else "ARCHiVE"
    val codec = if (res == "2160p") "DDP5.1.HDR.H.265" else if (src == "BluRay") "FLAC2.0.x264" else "AAC2.0.H.264"
    return "${dotted(m.title)}.${m.year}.$res.$src.$codec-$group"
}

internal fun releaseName(s: DSeries, e: LibEpisode): String {
    val (res, src) = qualityParts(s.quality)
    return "${dotted(s.title)}.${s.year}.S%02dE%02d.${dotted(e.title)}.$res.$src.AAC2.0.H.264-ARCHiVE".format(e.season, e.n)
}

internal fun resolutionOf(q: String): Int = Regex("(\\d{3,4})p").find(q)?.groupValues?.get(1)?.toInt() ?: 1080

/** Entry point for every demo request. */
internal object DemoWorld {
    @Volatile private var world: World? = null

    fun reset() = synchronized(this) { world = null }

    fun get(): World = world ?: synchronized(this) {
        world ?: World(AppJson.decodeFromString(DCatalog.serializer(), DemoAssets.text("catalog.json"))).also { world = it }
    }

    fun today(): LocalDate = LocalDate.now()

    fun handle(r: DemoRequest): DemoResponse {
        val w = get()
        return synchronized(w) {
            w.tick()
            when (r.service) {
                "sonarr", "radarr" -> DemoArr.handle(w, r)
                "qbittorrent" -> DemoQbit.handle(w, r)
                "jellyseerr" -> DemoSeerr.handle(w, r)
                "jellyfin" -> DemoJellyfin.handle(w, r)
                "jellystat" -> DemoJellystat.handle(w, r)
                "prowlarr" -> DemoProwlarr.handle(w, r)
                "bazarr" -> DemoBazarr.handle(w, r)
                "wizarr" -> DemoWizarr.handle(w, r)
                else -> null
            } ?: DemoResponse.notFound("The demo ${r.service} doesn't know ${r.method} ${r.path}")
        }
    }
}
