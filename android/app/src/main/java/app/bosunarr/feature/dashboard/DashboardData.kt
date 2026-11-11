package app.bosunarr.feature.dashboard

import app.bosunarr.app
import app.bosunarr.data.ServiceConfig
import app.bosunarr.data.ServiceType
import app.bosunarr.feature.services.ArrImage
import app.bosunarr.feature.services.ImageRef
import app.bosunarr.feature.services.arrPoster
import app.bosunarr.feature.services.enc
import app.bosunarr.feature.services.episodeCode
import app.bosunarr.nav.MovieRoute
import app.bosunarr.nav.SeriesRoute
import app.bosunarr.net.ServiceException
import app.bosunarr.net.fanOut
import app.bosunarr.net.userMessage
import app.bosunarr.ui.common.parseInstant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max

/* ================================================================ helpers */

/** Results of the services that answered, plus one message per service that didn't. All failed → throws. */
data class Partial<T>(val ok: List<Pair<ServiceConfig, T>>, val errors: List<String>)

suspend fun <T> each(services: List<ServiceConfig>, block: suspend (ServiceConfig) -> T): Partial<T> {
    val r = fanOut(services, block)
    val ok = r.mapNotNull { (s, x) -> x.getOrNull()?.let { s to it } }
    val errors = r.mapNotNull { (_, x) -> x.exceptionOrNull()?.userMessage() }
    if (services.isNotEmpty() && ok.isEmpty()) throw ServiceException(errors.firstOrNull() ?: "Couldn't load")
    return Partial(ok, errors)
}

/* ================================================================ services */

data class ServiceStatus(val ok: Boolean, val version: String?, val ms: Int?, val error: String?)

suspend fun loadStatuses(services: List<ServiceConfig>): Map<String, ServiceStatus> =
    fanOut(services) { app.client.test(it) }.associate { (s, r) ->
        s.id to r.fold(
            { ServiceStatus(true, it.version, it.ms, null) },
            { ServiceStatus(false, null, null, it.userMessage()) },
        )
    }

/* ================================================================ downloads */

@Serializable
private data class QbTransfer(@SerialName("dl_info_speed") val down: Long = 0, @SerialName("up_info_speed") val up: Long = 0)

@Serializable
private data class QbTorrent(val hash: String = "", val progress: Double = 0.0, val dlspeed: Long = 0, val state: String = "")

@Serializable
private data class QueueStatus(
    val totalCount: Int? = null,
    val count: Int? = null,
    val errors: Boolean = false,
    val warnings: Boolean = false,
    val unknownErrors: Boolean = false,
    val unknownWarnings: Boolean = false,
)

data class Downloads(
    val hasQbit: Boolean,
    val down: Long,
    val up: Long,
    val downloading: Int,
    val seeding: Int,
    val hasArr: Boolean,
    val queue: Int,
    val queueErrors: Boolean,
    val queueWarnings: Boolean,
    val errors: List<String>,
)

private val DL_STATES = setOf("downloading", "forcedDL", "metaDL", "forcedMetaDL", "stalledDL", "queuedDL", "allocating", "checkingDL")
private val UP_STATES = setOf("uploading", "forcedUP", "stalledUP", "queuedUP")

private data class QbPart(val down: Long, val up: Long, val dl: Int, val seed: Int)

suspend fun loadDownloads(qbits: List<ServiceConfig>, arrs: List<ServiceConfig>): Downloads {
    val all = qbits + arrs
    val r = fanOut(all) { s ->
        if (s.serviceType == ServiceType.QBittorrent) {
            val info = app.client.get<QbTransfer>(s, "/api/v2/transfer/info")
            val active = app.client.get<List<QbTorrent>>(s, "/api/v2/torrents/info?filter=active")
            QbPart(
                info.down, info.up,
                active.count { it.state in DL_STATES || (it.progress < 1 && it.dlspeed > 0) },
                active.count { it.state in UP_STATES && !(it.progress < 1 && it.dlspeed > 0) },
            )
        } else app.client.get<QueueStatus>(s, "/api/v3/queue/status")
    }
    val errors = r.mapNotNull { (_, x) -> x.exceptionOrNull()?.userMessage() }
    if (all.isNotEmpty() && errors.size == all.size) throw ServiceException(errors.first())
    val q = r.mapNotNull { it.second.getOrNull() as? QbPart }
    val a = r.mapNotNull { it.second.getOrNull() as? QueueStatus }
    return Downloads(
        hasQbit = qbits.isNotEmpty(),
        down = q.sumOf { it.down },
        up = q.sumOf { it.up },
        downloading = q.sumOf { it.dl },
        seeding = q.sumOf { it.seed },
        hasArr = arrs.isNotEmpty(),
        queue = a.sumOf { it.totalCount ?: it.count ?: 0 },
        queueErrors = a.any { it.errors || it.unknownErrors },
        queueWarnings = a.any { it.warnings || it.unknownWarnings },
        errors = errors,
    )
}

/* ================================================================ up next (calendar) */

@Serializable
private data class CalSeries(val id: Int = 0, val title: String = "", val tvdbId: Int = 0, val images: List<ArrImage> = emptyList())

@Serializable
private data class CalEpisode(
    val id: Int = 0,
    val seriesId: Int = 0,
    val seasonNumber: Int = 0,
    val episodeNumber: Int = 0,
    val title: String? = null,
    val airDateUtc: String? = null,
    val hasFile: Boolean = false,
    val monitored: Boolean = true,
    val series: CalSeries? = null,
)

@Serializable
private data class CalMovie(
    val id: Int = 0,
    val title: String = "",
    val year: Int = 0,
    val tmdbId: Int = 0,
    val inCinemas: String? = null,
    val digitalRelease: String? = null,
    val physicalRelease: String? = null,
    val hasFile: Boolean = false,
    val monitored: Boolean = true,
    val images: List<ArrImage> = emptyList(),
)

data class UpNextItem(
    val key: String,
    val title: String,
    val subtitle: String,
    val at: Instant,
    /** Movie releases are calendar dates, not times. */
    val allDay: Boolean,
    val hasFile: Boolean,
    val service: ServiceConfig,
    val poster: ImageRef?,
    val route: Any,
)

data class UpNext(val items: List<UpNextItem>, val errors: List<String>)

suspend fun loadUpNext(arrs: List<ServiceConfig>, days: Long = 14, limit: Int = 12): UpNext {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val start = today.atStartOfDay(zone).toInstant()
    val end = today.plusDays(days).atStartOfDay(zone).toInstant()
    val p = each(arrs) { s ->
        if (s.serviceType == ServiceType.Sonarr) {
            val eps = app.client.get<List<CalEpisode>>(s, "/api/v3/calendar?start=${enc(start.toString())}&end=${enc(end.toString())}&includeSeries=true")
            eps.mapNotNull { e ->
                val at = parseInstant(e.airDateUtc) ?: return@mapNotNull null
                val sr = e.series ?: return@mapNotNull null
                if (!e.monitored) return@mapNotNull null
                UpNextItem(
                    key = if (sr.tvdbId > 0) "e:${sr.tvdbId}:${e.seasonNumber}:${e.episodeNumber}" else "e:${s.id}:${e.id}",
                    title = sr.title,
                    subtitle = episodeCode(e.seasonNumber, e.episodeNumber) + (e.title?.takeIf { it.isNotBlank() && it != "TBA" }?.let { " · $it" } ?: ""),
                    at = at,
                    allDay = false,
                    hasFile = e.hasFile,
                    service = s,
                    poster = arrPoster(s, sr.images),
                    route = SeriesRoute(s.id, e.seriesId),
                )
            }
        } else {
            // Release dates are calendar dates stored as UTC midnight: query a day either side.
            val qs = start.minusSeconds(86_400).toString()
            val qe = end.plusSeconds(86_400).toString()
            val movies = app.client.get<List<CalMovie>>(s, "/api/v3/calendar?start=${enc(qs)}&end=${enc(qe)}")
            movies.filter { it.monitored }.flatMap { m ->
                listOf("In cinemas" to m.inCinemas, "Digital release" to m.digitalRelease, "Physical release" to m.physicalRelease).mapNotNull { (label, raw) ->
                    val day = raw?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@mapNotNull null
                    if (day.isBefore(today) || !day.isBefore(today.plusDays(days))) return@mapNotNull null
                    UpNextItem(
                        key = if (m.tmdbId > 0) "m:${m.tmdbId}:$label" else "m:${s.id}:${m.id}:$label",
                        title = m.title,
                        subtitle = label,
                        at = day.atStartOfDay(zone).toInstant(),
                        allDay = true,
                        hasFile = m.hasFile,
                        service = s,
                        poster = arrPoster(s, m.images),
                        route = MovieRoute(s.id, m.id),
                    )
                }
            }
        }
    }
    val items = p.ok.flatMap { it.second }
        .filter { it.allDay || it.at >= start }
        .distinctBy { it.key }
        .sortedWith(compareBy({ it.at }, { it.title.lowercase() }))
        .take(limit)
    return UpNext(items, p.errors)
}

/* ================================================================ recently added */

@Serializable
private data class HistSeries(val id: Int = 0, val title: String = "", val images: List<ArrImage> = emptyList())

@Serializable
private data class HistEpisode(val seasonNumber: Int = 0, val episodeNumber: Int = 0, val title: String? = null)

@Serializable
private data class HistMovie(val id: Int = 0, val title: String = "", val year: Int = 0, val images: List<ArrImage> = emptyList())

@Serializable
private data class HistRecord(
    val id: Int = 0,
    val date: String? = null,
    val seriesId: Int? = null,
    val movieId: Int? = null,
    val series: HistSeries? = null,
    val episode: HistEpisode? = null,
    val movie: HistMovie? = null,
)

@Serializable
private data class HistPage(val records: List<HistRecord> = emptyList())

data class RecentItem(
    val key: String,
    val title: String,
    val subtitle: String?,
    val at: Instant?,
    val service: ServiceConfig,
    val poster: ImageRef?,
    /** Library route, or null (Jellyfin items have no detail screen). */
    val route: Any?,
)

data class Recent(val items: List<RecentItem>, val errors: List<String>)

/** Imports from Sonarr/Radarr history (grouped per series), newest first. */
suspend fun loadRecentFromArrs(arrs: List<ServiceConfig>, limit: Int = 16): Recent {
    val p = each(arrs) { s ->
        val sonarr = s.serviceType == ServiceType.Sonarr
        // downloadFolderImported (3) + folder imports (Sonarr 2 / Radarr 7).
        val ev = if (sonarr) "&eventType=3&eventType=2" else "&eventType=3&eventType=7"
        val include = if (sonarr) "includeSeries=true&includeEpisode=true" else "includeMovie=true"
        val page = app.client.get<HistPage>(s, "/api/v3/history?page=1&pageSize=50&sortKey=date&sortDirection=descending&$include$ev")
        if (sonarr) {
            page.records.filter { it.series != null && (it.seriesId ?: it.series.id) > 0 }
                .groupBy { it.seriesId ?: it.series!!.id }
                .map { (seriesId, recs) ->
                    val first = recs.first()
                    val ep = first.episode
                    RecentItem(
                        key = "s:${s.id}:$seriesId",
                        title = first.series!!.title,
                        subtitle = if (recs.size > 1) "${recs.size} episodes" else ep?.let { episodeCode(it.seasonNumber, it.episodeNumber) },
                        at = parseInstant(first.date),
                        service = s,
                        poster = arrPoster(s, first.series.images),
                        route = SeriesRoute(s.id, seriesId),
                    )
                }
        } else {
            page.records.filter { it.movie != null && (it.movieId ?: it.movie.id) > 0 }
                .distinctBy { it.movieId ?: it.movie!!.id }
                .map { h ->
                    val m = h.movie!!
                    val id = h.movieId ?: m.id
                    RecentItem(
                        key = "m:${s.id}:$id",
                        title = m.title,
                        subtitle = m.year.takeIf { it > 0 }?.toString(),
                        at = parseInstant(h.date),
                        service = s,
                        poster = arrPoster(s, m.images),
                        route = MovieRoute(s.id, id),
                    )
                }
        }
    }
    val items = p.ok.flatMap { it.second }.distinctBy { it.key }.sortedByDescending { it.at ?: Instant.EPOCH }.take(limit)
    return Recent(items, p.errors)
}

@Serializable
private data class JfPolicy(@SerialName("IsAdministrator") val admin: Boolean = false, @SerialName("IsDisabled") val disabled: Boolean = false)

@Serializable
private data class JfUser(@SerialName("Id") val id: String = "", @SerialName("Policy") val policy: JfPolicy = JfPolicy())

@Serializable
private data class JfItem(
    @SerialName("Id") val id: String = "",
    @SerialName("Name") val name: String? = null,
    @SerialName("Type") val type: String? = null,
    @SerialName("SeriesName") val seriesName: String? = null,
    @SerialName("SeriesId") val seriesId: String? = null,
    @SerialName("ProductionYear") val year: Int? = null,
    @SerialName("DateCreated") val created: String? = null,
    @SerialName("ImageTags") val imageTags: Map<String, String> = emptyMap(),
    @SerialName("ChildCount") val childCount: Int? = null,
)

/** Jellyfin's "Latest media" for the first enabled administrator (what its dashboard shows). */
suspend fun loadRecentFromJellyfin(jfs: List<ServiceConfig>, limit: Int = 16): Recent {
    val p = each(jfs) { s ->
        val users = app.client.get<List<JfUser>>(s, "/Users")
        val u = users.firstOrNull { it.policy.admin && !it.policy.disabled } ?: users.firstOrNull { !it.policy.disabled } ?: return@each emptyList()
        val items = app.client.get<List<JfItem>>(s, "/Items/Latest?userId=${enc(u.id)}&Limit=$limit&Fields=DateCreated&EnableImageTypes=Primary&GroupItems=true")
        items.filter { it.id.isNotBlank() }.map { item ->
            val isEp = item.type == "Episode"
            val imageId = if (isEp && item.seriesId != null) item.seriesId else item.id
            RecentItem(
                key = "jf:${s.id}:${item.id}",
                title = (if (isEp) item.seriesName else null) ?: item.name ?: "Untitled",
                subtitle = when {
                    isEp -> item.name
                    item.type == "Series" && (item.childCount ?: 0) > 0 -> "${item.childCount} new"
                    else -> item.year?.toString()
                },
                at = parseInstant(item.created),
                service = s,
                poster = ImageRef(s, "/Items/$imageId/Images/Primary?maxHeight=450"),
                route = null,
            )
        }
    }
    return Recent(p.ok.flatMap { it.second }.distinctBy { it.key }.sortedByDescending { it.at ?: Instant.EPOCH }.take(limit), p.errors)
}

/* ================================================================ requests */

@Serializable
data class SeerrCount(
    val total: Int = 0,
    val movie: Int = 0,
    val tv: Int = 0,
    val pending: Int = 0,
    val approved: Int = 0,
    val declined: Int = 0,
    val processing: Int = 0,
    val available: Int = 0,
)

data class Requests(val counts: SeerrCount, val errors: List<String>)

suspend fun loadRequests(seerrs: List<ServiceConfig>): Requests {
    val p = each(seerrs) { app.client.get<SeerrCount>(it, "/api/v1/request/count") }
    val c = p.ok.map { it.second }
    return Requests(
        SeerrCount(
            total = c.sumOf { it.total }, movie = c.sumOf { it.movie }, tv = c.sumOf { it.tv },
            pending = c.sumOf { it.pending }, approved = c.sumOf { it.approved }, declined = c.sumOf { it.declined },
            processing = c.sumOf { it.processing }, available = c.sumOf { it.available },
        ),
        p.errors,
    )
}

/* ================================================================ disk space */

@Serializable
data class DiskSpace(val path: String = "", val label: String? = null, val freeSpace: Long = 0, val totalSpace: Long = 0)

data class Disk(val path: String, val free: Long, val total: Long)

data class Disks(val disks: List<Disk>, val errors: List<String>)

private val INTERNAL_MOUNT = Regex("^/(config|app)(/|$)")

/**
 * The same physical disk is reported by every arr, a moment apart and often under different mount paths: same
 * path, or same size with nearly the same free space, counts as one disk. Container mounts (/config, /app) skipped.
 */
fun dedupeDisks(spaces: List<DiskSpace>): List<Disk> {
    val out = mutableListOf<Disk>()
    for (d in spaces) {
        val norm = d.path.replace('\\', '/').trimEnd('/').lowercase()
        if (d.totalSpace <= 0 || INTERNAL_MOUNT.containsMatchIn(norm)) continue
        val i = out.indexOfFirst { x ->
            x.path == d.path || (x.total == d.totalSpace && abs(x.free - d.freeSpace) <= max(1024L * 1024 * 1024, (d.totalSpace * 0.001).toLong()))
        }
        if (i >= 0) out[i] = out[i].copy(free = minOf(out[i].free, d.freeSpace))
        else out += Disk(d.path, d.freeSpace, d.totalSpace)
    }
    return out.sortedByDescending { it.total }
}

suspend fun loadDisks(arrs: List<ServiceConfig>): Disks {
    val p = each(arrs) { app.client.get<List<DiskSpace>>(it, "/api/v3/diskspace") }
    return Disks(dedupeDisks(p.ok.flatMap { it.second }), p.errors)
}

/* ================================================================ health */

@Serializable
data class ArrHealthCheck(val source: String? = null, val type: String? = null, val message: String? = null)

data class HealthIssue(val key: String, val service: ServiceConfig, val type: String, val source: String?, val message: String)

data class Health(val issues: List<HealthIssue>, val errors: List<String>)

private fun rank(type: String) = when (type) { "error" -> 0; "warning" -> 1; else -> 2 }

suspend fun loadHealth(services: List<ServiceConfig>): Health {
    val p = each(services) { s ->
        val path = if (s.serviceType == ServiceType.Prowlarr) "/api/v1/health" else "/api/v3/health"
        app.client.get<List<ArrHealthCheck>>(s, path)
    }
    val issues = p.ok.flatMap { (s, list) ->
        list.mapIndexedNotNull { i, h ->
            val t = h.type?.lowercase().orEmpty()
            val msg = h.message?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
            if (t != "error" && t != "warning" && t != "notice") return@mapIndexedNotNull null
            HealthIssue("${s.id}:$i:${h.source}", s, t, h.source, msg)
        }
    }.sortedBy { rank(it.type) }
    return Health(issues, p.errors)
}
