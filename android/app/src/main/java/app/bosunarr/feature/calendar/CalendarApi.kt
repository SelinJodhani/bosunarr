package app.bosunarr.feature.calendar

import app.bosunarr.app
import app.bosunarr.data.ServiceConfig
import app.bosunarr.data.ServiceType
import app.bosunarr.feature.library.ArrImage
import app.bosunarr.feature.library.ArrMovie
import app.bosunarr.feature.library.LibraryApi
import app.bosunarr.feature.library.UHD
import app.bosunarr.feature.library.arrImage
import app.bosunarr.nav.MovieRoute
import app.bosunarr.nav.SeriesRoute
import app.bosunarr.net.ServiceException
import app.bosunarr.net.fanOut
import app.bosunarr.ui.common.friendlyMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Serializable
data class CalSeries(
    val title: String = "",
    val year: Int = 0,
    val tvdbId: Int = 0,
    val network: String? = null,
    val images: List<ArrImage> = emptyList(),
    val monitored: Boolean = true,
    val qualityProfileId: Int? = null,
    val runtime: Int = 0,
)

@Serializable
data class CalEpisode(
    val id: Int = 0,
    val seriesId: Int = 0,
    val seasonNumber: Int = 0,
    val episodeNumber: Int = 0,
    val title: String? = null,
    val airDateUtc: String? = null,
    val hasFile: Boolean = false,
    val monitored: Boolean = false,
    val runtime: Int = 0,
    val finaleType: String? = null,
    val series: CalSeries? = null,
)

enum class CalStatus(val label: String) { Downloaded("Downloaded"), Missing("Missing"), Upcoming("Upcoming") }

enum class Release(val label: String) { Airing("Episode"), Cinema("In cinemas"), Digital("Digital release"), Physical("Physical release") }

/** One instance's copy of an entry (Radarr + Radarr 4K both list the same movie). */
data class CalCopy(val service: ServiceConfig, val is4k: Boolean, val status: CalStatus, val monitored: Boolean, val route: Any)

data class CalEntry(
    /** Unique across instances and release types. */
    val id: String,
    val isEpisode: Boolean,
    val releases: List<Release>,
    val title: String,
    val year: Int?,
    /** "S01E03 · Title" for episodes. */
    val episodeLabel: String?,
    /** premiere / season finale … */
    val marker: String?,
    val day: LocalDate,
    /** Air time for episodes; null for (all-day) movie releases. */
    val time: Instant?,
    val runtime: Int?,
    val network: String?,
    val poster: String?,
    val posterService: ServiceConfig?,
    val copies: List<CalCopy>,
) {
    val primary: CalCopy get() = copies.first()
    val monitored: Boolean get() = copies.any { it.monitored }
    val is4k: Boolean get() = copies.any { it.is4k }

    /** Downloaded only when every monitored copy is; missing if any is; otherwise upcoming. */
    val status: CalStatus
        get() {
            val relevant = copies.filter { it.monitored }.ifEmpty { copies }
            return when {
                relevant.all { it.status == CalStatus.Downloaded } -> CalStatus.Downloaded
                relevant.any { it.status == CalStatus.Missing } -> CalStatus.Missing
                else -> CalStatus.Upcoming
            }
        }

    val releaseLabel: String
        get() {
            val home = listOf(Release.Digital, Release.Physical).filter { it in releases }
            val parts = mutableListOf<String>()
            if (Release.Airing in releases) parts += Release.Airing.label
            if (Release.Cinema in releases) parts += Release.Cinema.label
            if (home.size == 2) parts += "Digital & physical release" else if (home.size == 1) parts += home[0].label
            return parts.joinToString(" · ")
        }
}

data class CalendarData(val start: LocalDate, val end: LocalDate, val entries: List<CalEntry>, val errors: List<LibraryApi.ServiceError>)

object CalendarApi {
    private fun pad(n: Int) = n.toString().padStart(2, '0')
    private fun enc(i: Instant) = URLEncoder.encode(i.toString(), "UTF-8")

    /** Entries from [pastDays] ago (start of that day) to [aheadDays] from today, merged across instances. */
    suspend fun load(services: List<ServiceConfig>, pastDays: Long, aheadDays: Long = 28): CalendarData = withContext(Dispatchers.Default) {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val startDay = today.minusDays(pastDays)
        val endDay = today.plusDays(aheadDays)
        val start = startDay.atStartOfDay(zone).toInstant()
        val end = endDay.plusDays(1).atStartOfDay(zone).toInstant()

        val results = fanOut(services) { s ->
            if (s.serviceType == ServiceType.Sonarr) episodes(s, start, end, zone) else movies(s, start, end, startDay, endDay)
        }
        val errors = results.mapNotNull { (s, r) -> r.exceptionOrNull()?.let { LibraryApi.ServiceError(s, it.friendlyMessage()) } }
        if (services.isNotEmpty() && errors.size == services.size) throw ServiceException(errors.first().message)

        // The same title in several instances (e.g. Radarr + Radarr 4K) becomes one entry; the non-4K copy is primary.
        val merged = LinkedHashMap<String, CalEntry>()
        for (e in results.flatMap { it.second.getOrNull().orEmpty() }) {
            val hit = merged[e.id]
            if (hit == null) {
                merged[e.id] = e
                continue
            }
            if (hit.copies.any { c -> e.copies.any { it.service.id == c.service.id } }) continue
            val copies = (hit.copies + e.copies).sortedBy { it.is4k }
            val primaryIsNew = copies.first() === e.copies.first()
            merged[e.id] = hit.copy(
                copies = copies,
                poster = if (primaryIsNew) e.poster ?: hit.poster else hit.poster ?: e.poster,
                posterService = if (primaryIsNew && e.poster != null) e.posterService else if (hit.poster != null) hit.posterService else e.posterService,
                network = hit.network ?: e.network,
                runtime = hit.runtime ?: e.runtime,
            )
        }
        val entries = merged.values.sortedWith(
            compareBy<CalEntry> { it.day }
                .thenBy { it.time != null } // all-day releases first
                .thenBy { it.time }
                .thenBy { it.title.lowercase() },
        )
        CalendarData(startDay, endDay, entries, errors)
    }

    private suspend fun episodes(s: ServiceConfig, start: Instant, end: Instant, zone: ZoneId): List<CalEntry> = coroutineScope {
        val names = async { LibraryApi.profileNames(s) }
        val eps: List<CalEpisode> = app.client.get(s, "/api/v3/calendar?start=${enc(start)}&end=${enc(end)}&unmonitored=true&includeSeries=true")
        val profiles = names.await()
        val now = Instant.now()
        eps.mapNotNull { e ->
            val sr = e.series ?: return@mapNotNull null
            val at = e.airDateUtc?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return@mapNotNull null
            val profile = sr.qualityProfileId?.let { profiles[it] }
            val marker = when (e.finaleType) {
                "series" -> "Series finale"
                "season" -> "Season finale"
                "midseason" -> "Midseason finale"
                else -> if (e.episodeNumber == 1) (if (e.seasonNumber == 1) "Series premiere" else "Premiere") else null
            }
            val status = when {
                e.hasFile -> CalStatus.Downloaded
                at.isAfter(now) -> CalStatus.Upcoming
                else -> CalStatus.Missing
            }
            CalEntry(
                id = if (sr.tvdbId > 0) "e:${sr.tvdbId}:${e.seasonNumber}:${e.episodeNumber}" else "e:${s.id}:${e.id}",
                isEpisode = true,
                releases = listOf(Release.Airing),
                title = sr.title,
                year = sr.year.takeIf { it > 0 },
                episodeLabel = "S${pad(e.seasonNumber)}E${pad(e.episodeNumber)}" + (e.title?.takeIf { it.isNotBlank() && it != "TBA" }?.let { " · $it" } ?: ""),
                marker = marker,
                day = at.atZone(zone).toLocalDate(),
                time = at,
                runtime = (e.runtime.takeIf { it > 0 } ?: sr.runtime.takeIf { it > 0 }),
                network = sr.network?.takeIf { it.isNotBlank() },
                poster = arrImage(s, sr.images, "poster", 250, e.seriesId),
                posterService = s,
                copies = listOf(
                    CalCopy(
                        s, UHD.containsMatchIn(s.name) || UHD.containsMatchIn(profile.orEmpty()), status,
                        e.monitored && sr.monitored, SeriesRoute(s.id, e.seriesId),
                    ),
                ),
            )
        }
    }

    private suspend fun movies(s: ServiceConfig, start: Instant, end: Instant, startDay: LocalDate, endDay: LocalDate): List<CalEntry> = coroutineScope {
        val names = async { LibraryApi.profileNames(s) }
        // Release dates are calendar dates (stored as UTC midnight): ask a day either side, then place them by date.
        val qStart = start.minus(Duration.ofDays(1))
        val qEnd = end.plus(Duration.ofDays(1))
        val movies: List<ArrMovie> = app.client.get(s, "/api/v3/calendar?start=${enc(qStart)}&end=${enc(qEnd)}&unmonitored=true")
        val profiles = names.await()
        val today = LocalDate.now()
        val out = mutableListOf<CalEntry>()
        for (m in movies) {
            val profile = m.qualityProfileId?.let { profiles[it] }
            val is4k = UHD.containsMatchIn(s.name) || UHD.containsMatchIn(profile.orEmpty()) || (m.movieFile?.quality?.quality?.resolution ?: 0) >= 2160
            // Group this movie's releases by day: digital + physical on the same day become one entry.
            val byDay = linkedMapOf<LocalDate, MutableList<Release>>()
            for ((release, raw) in listOf(Release.Cinema to m.inCinemas, Release.Digital to m.digitalRelease, Release.Physical to m.physicalRelease)) {
                val day = raw?.takeIf { it.length >= 10 }?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() } ?: continue
                if (day.isBefore(startDay) || day.isAfter(endDay)) continue
                byDay.getOrPut(day) { mutableListOf() } += release
            }
            for ((day, releases) in byDay) {
                val status = when {
                    m.hasFile -> CalStatus.Downloaded
                    // A cinema date passing doesn't make a movie downloadable; Radarr's isAvailable knows.
                    day.isAfter(today) || !m.isAvailable -> CalStatus.Upcoming
                    else -> CalStatus.Missing
                }
                out += CalEntry(
                    id = (if (m.tmdbId > 0) "m:${m.tmdbId}" else "m:${s.id}:${m.id}") + ":$day",
                    isEpisode = false,
                    releases = releases,
                    title = m.title,
                    year = m.year.takeIf { it > 0 },
                    episodeLabel = null,
                    marker = null,
                    day = day,
                    time = null,
                    runtime = m.runtime.takeIf { it > 0 },
                    network = m.studio?.takeIf { it.isNotBlank() },
                    poster = arrImage(s, m.images, "poster", 250, m.id),
                    posterService = s,
                    copies = listOf(CalCopy(s, is4k, status, m.monitored, MovieRoute(s.id, m.id))),
                )
            }
        }
        out
    }
}
