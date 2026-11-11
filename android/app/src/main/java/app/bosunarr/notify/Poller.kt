package app.bosunarr.notify

import android.content.Context
import app.bosunarr.app
import app.bosunarr.data.ServiceConfig
import app.bosunarr.data.ServiceType
import app.bosunarr.net.fanOut
import app.bosunarr.net.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Per-service time budget: one slow or dead service can't hold up the rest (or the battery). */
private const val SERVICE_BUDGET_MS = 25_000L
/** At most this many extra Seerr title lookups per check (new requests only). */
private const val MAX_TITLE_LOOKUPS = 5

/** Seerr permission bits: ADMIN, MANAGE_REQUESTS. */
private const val SEERR_MANAGE = 2L or 16L

/**
 * One background check: fan out to every configured service concurrently, diff against the previous
 * snapshot, post notifications for what's new. Never throws (except cancellation).
 */
internal object Poller {
    private val running = Mutex()

    /** Returns a one-line summary of the run, or null if another run was already in progress. */
    suspend fun run(context: Context): String? {
        if (!running.tryLock()) return null
        try {
            return runLocked(context)
        } finally {
            running.unlock()
        }
    }

    private suspend fun runLocked(context: Context): String {
        val prefs = NotifyPrefs.get(context)
        val settings = prefs.settings.value
        val store = NotifyStateStore(context)
        if (!settings.enabled) {
            store.clear()
            return "Notifications are off"
        }
        // The encrypted store is read asynchronously at app start; wait for it (a cold start in the background).
        withTimeoutOrNull(10_000) { app.state.filterNotNull().first() } ?: return "Couldn't read your services".also(prefs::recordRun)

        val services = app.services.value.filter { eligible(it, settings) }
        val prev = store.read()
        val results = fanOut(services) { s ->
            val before = prev.services[s.id] ?: ServiceSnapshot()
            withTimeoutOrNull(SERVICE_BUDGET_MS) { check(s, before, settings) }
                ?: Check(before, emptyList(), reached = false, error = "${s.name} timed out")
        }.map { (s, r) -> s to r.getOrElse { Check(prev.services[s.id] ?: ServiceSnapshot(), emptyList(), false, it.userMessage()) } }

        // If every service failed, the phone most likely can't reach the server at all (off the LAN, no VPN):
        // that's not news about the services, so don't count it towards "unreachable".
        val checked = results.filter { it.second.reached != null }
        val phoneOffline = checked.size >= 2 && checked.all { it.second.reached == false }

        val events = mutableListOf<NotifyEvent>()
        val next = mutableMapOf<String, ServiceSnapshot>()
        for ((s, c) in results) {
            events += c.events
            var snap = c.snapshot
            val adult = s.serviceType == ServiceType.Whisparr
            when (c.reached) {
                true -> {
                    if (snap.downNotified && settings.health) events += NotifyEvent(
                        NotifyKind.Health, "down:${s.id}", "${s.name} is back online", "Reachable again.",
                        NotifyLinks.service(s.id), adult = adult,
                    )
                    snap = snap.copy(failures = 0, downNotified = false)
                }
                false -> if (!phoneOffline) {
                    val failures = snap.failures + 1
                    var notified = snap.downNotified
                    if (failures >= 2 && !notified && settings.health) {
                        events += NotifyEvent(
                            NotifyKind.Health, "down:${s.id}", "${s.name} is unreachable",
                            (c.error ?: "No answer") + " (failed $failures checks in a row).",
                            NotifyLinks.service(s.id), adult = adult,
                        )
                        notified = true
                    }
                    snap = snap.copy(failures = failures, downNotified = notified)
                }
                null -> {}
            }
            next[s.id] = snap
        }
        store.write(NotifySnapshot(next))
        Notifier.post(context, events)

        val down = checked.count { it.second.reached == false }
        val summary = buildString {
            append(if (services.isEmpty()) "No services to check" else "Checked ${services.size} service${if (services.size == 1) "" else "s"}")
            if (down > 0) append(if (phoneOffline) " · none reachable from here" else " · $down unreachable")
            append(" · ${events.size} new")
        }
        prefs.recordRun(summary)
        return summary
    }

    private fun eligible(s: ServiceConfig, settings: NotifySettings): Boolean = when (s.serviceType) {
        null -> false
        ServiceType.Whisparr -> settings.whisparr
        else -> true
    }

    /** Result for one service: its new snapshot, events, and whether it answered (null = nothing was asked). */
    private class Check(val snapshot: ServiceSnapshot, val events: List<NotifyEvent>, val reached: Boolean?, val error: String?)

    /** Tracks whether any call to a service got through, and the first error. */
    private class Calls {
        var made = 0
        var ok = false
        var error: String? = null

        suspend fun <T> attempt(block: suspend () -> T): T? {
            made++
            return try {
                block().also { ok = true }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (error == null) error = e.userMessage()
                null
            }
        }
    }

    private suspend fun check(s: ServiceConfig, prev: ServiceSnapshot, settings: NotifySettings): Check {
        val calls = Calls()
        val events = mutableListOf<NotifyEvent>()
        var snap = prev
        when (s.serviceType) {
            ServiceType.Sonarr, ServiceType.Radarr, ServiceType.Whisparr -> snap = checkArr(s, snap, settings, calls, events)
            ServiceType.Prowlarr -> snap = checkHealth(s, "/api/v1/health", snap, settings, calls, events)
            ServiceType.Jellyseerr, ServiceType.Overseerr -> snap = checkSeerr(s, snap, settings, calls, events)
            ServiceType.QBittorrent -> snap = checkQbit(s, snap, settings, calls, events)
            else -> {}
        }
        // Services with nothing else to ask (Jellyfin, Bazarr…) still get a reachability probe when health is on.
        if (calls.made == 0 && settings.health) calls.attempt { app.client.test(s) }
        return Check(snap, events, if (calls.made == 0) null else calls.ok, calls.error)
    }

    /* ------------------------------------------------------------------ Sonarr / Radarr / Whisparr */

    private suspend fun checkArr(s: ServiceConfig, prev: ServiceSnapshot, settings: NotifySettings, calls: Calls, events: MutableList<NotifyEvent>): ServiceSnapshot {
        val sonarr = s.serviceType == ServiceType.Sonarr
        val adult = s.serviceType == ServiceType.Whisparr
        val include = if (sonarr) "includeSeries=true&includeEpisode=true" else "includeMovie=true"
        var snap = prev
        val failedDownloadIds = mutableSetOf<String>()

        if (settings.downloaded || settings.failed) {
            val page = calls.attempt { app.client.json(s, "/api/v3/history?page=1&pageSize=100&sortKey=date&sortDirection=descending&$include") }
            if (page != null) {
                val records = page.obj()?.get("records").arr()
                val last = prev.historyId
                if (last != null) {
                    val fresh = records.filter { (it.int("id") ?: 0) > last }.sortedBy { it.int("id") ?: 0 }
                    if (settings.downloaded) {
                        events += importEvents(s, fresh.filter { it.str("eventType") == "downloadFolderImported" }, sonarr, adult)
                    }
                    if (settings.failed) fresh.filter { it.str("eventType") == "downloadFailed" }.forEach { r ->
                        r.str("downloadId")?.let(failedDownloadIds::add)
                        events += NotifyEvent(
                            NotifyKind.Failed, "fail:${s.id}:h${r.int("id")}",
                            "Download failed · ${itemLabel(r, sonarr)}",
                            listOfNotNull(r.obj("data")?.str("message")?.takeIf { it.isNotBlank() } ?: r.str("sourceTitle"), s.name).joinToString(" · "),
                            NotifyLinks.ACTIVITY, adult = adult,
                        )
                    }
                }
                val maxId = records.mapNotNull { it.int("id") }.maxOrNull() ?: 0
                snap = snap.copy(historyId = maxOf(maxId, last ?: 0))
            }
        } else snap = snap.copy(historyId = null)

        if (settings.failed) {
            val queue = calls.attempt { app.client.json(s, "/api/v3/queue?page=1&pageSize=200&$include") }
            if (queue != null) {
                val problems = queue.obj()?.get("records").arr().filter { it.str("trackedDownloadStatus")?.lowercase() in setOf("warning", "error") }
                val seen = prev.problemQueue
                if (seen != null) problems
                    .filter { (it.int("id") ?: 0) !in seen && it.str("downloadId") !in failedDownloadIds }
                    .forEach { q -> events += queueProblem(s, q, sonarr, adult) }
                snap = snap.copy(problemQueue = problems.mapNotNull { it.int("id") }.toSet())
            }
        } else snap = snap.copy(problemQueue = null)

        return checkHealth(s, "/api/v3/health", snap, settings, calls, events)
    }

    private fun importEvents(s: ServiceConfig, imports: List<JsonElement>, sonarr: Boolean, adult: Boolean): List<NotifyEvent> {
        if (!sonarr) return imports.map { r ->
            val movieId = r.int("movieId") ?: 0
            NotifyEvent(
                NotifyKind.Downloaded, "dl:${s.id}:${r.int("id")}",
                "${itemLabel(r, false)} downloaded",
                listOfNotNull(quality(r), s.name).joinToString(" · "),
                if (movieId > 0) NotifyLinks.movie(s.id, movieId) else NotifyLinks.ACTIVITY, adult = adult,
            )
        }
        // Episodes of one series in the same check (a season pack) become one notification.
        return imports.groupBy { it.int("seriesId") ?: 0 }.map { (seriesId, eps) ->
            val link = if (seriesId > 0) NotifyLinks.series(s.id, seriesId) else NotifyLinks.ACTIVITY
            val q = eps.firstNotNullOfOrNull { quality(it) }
            if (eps.size == 1) {
                val r = eps[0]
                NotifyEvent(
                    NotifyKind.Downloaded, "dl:${s.id}:${r.int("id")}",
                    "${itemLabel(r, true)} downloaded",
                    listOfNotNull(r.obj("episode")?.str("title"), q, s.name).joinToString(" · "),
                    link, adult = adult,
                )
            } else {
                val codes = eps.mapNotNull { episodeCode(it) }.distinct()
                NotifyEvent(
                    NotifyKind.Downloaded, "dl:${s.id}:s$seriesId:${eps.last().int("id")}",
                    "${seriesTitle(eps[0])} · ${eps.size} episodes downloaded",
                    listOfNotNull(codes.take(6).joinToString(", ") + if (codes.size > 6) "…" else "", q, s.name).filter { it.isNotBlank() }.joinToString(" · "),
                    link, adult = adult,
                )
            }
        }
    }

    private fun queueProblem(s: ServiceConfig, q: JsonElement, sonarr: Boolean, adult: Boolean): NotifyEvent {
        val state = q.str("trackedDownloadState")?.lowercase()
        val label = when {
            state == "importblocked" || state == "importpending" -> "Import blocked"
            state == "failedpending" || state == "failed" -> "Download failed"
            q.str("trackedDownloadStatus")?.lowercase() == "error" -> "Download error"
            else -> "Download needs attention"
        }
        val message = q.obj()?.get("statusMessages").arr()
            .flatMap { m -> m.obj()?.get("messages").arr().mapNotNull { (it as? JsonPrimitive)?.content } }
            .firstOrNull { it.isNotBlank() }
            ?: q.str("errorMessage")?.takeIf { it.isNotBlank() }
            ?: q.str("title")
        return NotifyEvent(
            NotifyKind.Failed, "queue:${s.id}:${q.int("id")}",
            "$label · ${itemLabel(q, sonarr)}",
            listOfNotNull(message, s.name).joinToString(" · "),
            NotifyLinks.ACTIVITY, adult = adult,
        )
    }

    private fun seriesTitle(r: JsonElement): String =
        r.obj("series")?.str("title") ?: r.str("sourceTitle") ?: r.str("title") ?: "An episode"

    private fun episodeCode(r: JsonElement): String? {
        val ep = r.obj("episode") ?: return null
        val season = ep.int("seasonNumber") ?: return null
        val number = ep.int("episodeNumber") ?: return null
        return "S%02dE%02d".format(season, number)
    }

    /** "Dune (2021)", "The Bear S03E04", falling back to the release name. */
    private fun itemLabel(r: JsonElement, sonarr: Boolean): String {
        if (sonarr) {
            val series = r.obj("series")?.str("title") ?: return r.str("sourceTitle") ?: r.str("title") ?: "An episode"
            return listOfNotNull(series, episodeCode(r)).joinToString(" ")
        }
        val movie = r.obj("movie")
        val title = movie?.str("title") ?: return r.str("sourceTitle") ?: r.str("title") ?: "A movie"
        val year = movie.int("year")?.takeIf { it > 0 }
        return if (year != null) "$title ($year)" else title
    }

    /** "2160p" when the arr reports a resolution, else the quality name ("WEBDL-1080p"). */
    private fun quality(r: JsonElement): String? {
        val q = r.obj("quality")?.obj("quality") ?: return null
        return q.int("resolution")?.takeIf { it > 0 }?.let { "${it}p" } ?: q.str("name")?.takeIf { it.isNotBlank() && it != "Unknown" }
    }

    /** New error-level health checks (Sonarr/Radarr/Whisparr /api/v3, Prowlarr /api/v1). */
    private suspend fun checkHealth(s: ServiceConfig, path: String, prev: ServiceSnapshot, settings: NotifySettings, calls: Calls, events: MutableList<NotifyEvent>): ServiceSnapshot {
        if (!settings.health) return prev.copy(healthErrors = null)
        val list = calls.attempt { app.client.json(s, path) } ?: return prev
        val errors = list.arr().filter { it.str("type")?.lowercase() == "error" }
            .associateBy { "${it.str("source")}:${it.str("message")}".hashCode().toString(16) }
        val seen = prev.healthErrors
        if (seen != null) {
            val fresh = errors.filterKeys { it !in seen }.values.mapNotNull { it.str("message") }
            if (fresh.isNotEmpty()) events += NotifyEvent(
                NotifyKind.Health, "health:${s.id}:${fresh.joinToString().hashCode()}",
                if (fresh.size == 1) "${s.name} health error" else "${s.name}: ${fresh.size} health errors",
                fresh.joinToString("\n"),
                NotifyLinks.service(s.id), adult = s.serviceType == ServiceType.Whisparr,
            )
        }
        return prev.copy(healthErrors = errors.keys)
    }

    /* ------------------------------------------------------------------ Seerr */

    private suspend fun checkSeerr(s: ServiceConfig, prev: ServiceSnapshot, settings: NotifySettings, calls: Calls, events: MutableList<NotifyEvent>): ServiceSnapshot {
        if (!settings.requests && !settings.available) return prev.copy(pendingRequests = null, availableRequests = null)
        var snap = prev
        val me = calls.attempt { app.client.json(s, "/api/v1/auth/me") }.obj() ?: return prev
        val meId = me.int("id")
        val canManage = ((me.long("permissions") ?: 0L) and SEERR_MANAGE) != 0L
        var lookups = 0
        suspend fun title(r: JsonElement): String {
            val type = mediaType(r)
            val tmdb = r.obj("media")?.int("tmdbId") ?: 0
            val fallback = if (type == "tv") "a series" else "a movie"
            if (tmdb <= 0 || lookups >= MAX_TITLE_LOOKUPS) return fallback
            lookups++
            val info = try {
                app.client.json(s, "/api/v1/$type/$tmdb").obj()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            return info?.str("title") ?: info?.str("name") ?: fallback
        }

        if (settings.requests && canManage) {
            val page = calls.attempt { app.client.json(s, "/api/v1/request?take=50&skip=0&filter=pending&sort=added") }
            if (page != null) {
                val results = page.obj()?.get("results").arr()
                val seen = prev.pendingRequests
                if (seen != null) results.filter { (it.int("id") ?: 0) !in seen }.forEach { r ->
                    val who = userLabel(r.obj("requestedBy"))
                    val t = title(r)
                    val detail = buildList {
                        add(if (mediaType(r) == "tv") "Series" else "Movie")
                        seasonsLabel(r)?.let(::add)
                        if (r.bool("is4k") == true) add("4K")
                        add("waiting for approval")
                    }.joinToString(" · ")
                    events += NotifyEvent(
                        NotifyKind.Request, "req:${s.id}:${r.int("id")}", "$who requested $t", detail,
                        mediaLink(r), reviewLink = NotifyLinks.REQUESTS,
                    )
                }
                snap = snap.copy(pendingRequests = results.mapNotNull { it.int("id") }.toSet())
            }
        } else snap = snap.copy(pendingRequests = null)

        if (settings.available && meId != null) {
            val page = calls.attempt { app.client.json(s, "/api/v1/request?take=50&skip=0&filter=available&sort=modified") }
            if (page != null) {
                val mine = page.obj()?.get("results").arr().filter { it.obj("requestedBy")?.int("id") == meId }
                val seen = prev.availableRequests
                if (seen != null) mine.filter { (it.int("id") ?: 0) !in seen }.forEach { r ->
                    val t = title(r)
                    events += NotifyEvent(
                        NotifyKind.Available, "avail:${s.id}:${r.int("id")}",
                        "${t.replaceFirstChar { it.uppercase() }} is available",
                        "Your request is ready to watch · ${s.name}",
                        mediaLink(r),
                    )
                }
                snap = snap.copy(availableRequests = mine.mapNotNull { it.int("id") }.toSet())
            }
        } else snap = snap.copy(availableRequests = null)
        return snap
    }

    private fun mediaType(r: JsonElement): String = (r.str("type") ?: r.obj("media")?.str("mediaType"))?.takeIf { it == "tv" } ?: "movie"

    private fun mediaLink(r: JsonElement): String {
        val tmdb = r.obj("media")?.int("tmdbId") ?: 0
        return if (tmdb > 0) NotifyLinks.media(mediaType(r), tmdb) else NotifyLinks.REQUESTS
    }

    private fun userLabel(u: JsonObject?): String =
        listOf("displayName", "username", "jellyfinUsername", "plexUsername", "email").firstNotNullOfOrNull { u?.str(it)?.takeIf { v -> v.isNotBlank() } } ?: "Someone"

    private fun seasonsLabel(r: JsonElement): String? {
        val seasons = r.obj()?.get("seasons").arr().mapNotNull { it.int("seasonNumber") }.sorted()
        return when {
            seasons.isEmpty() -> null
            seasons.size == 1 -> "Season ${seasons[0]}"
            seasons.zipWithNext().all { (a, b) -> b == a + 1 } -> "Seasons ${seasons.first()}–${seasons.last()}"
            else -> "${seasons.size} seasons"
        }
    }

    /* ------------------------------------------------------------------ qBittorrent */

    private suspend fun checkQbit(s: ServiceConfig, prev: ServiceSnapshot, settings: NotifySettings, calls: Calls, events: MutableList<NotifyEvent>): ServiceSnapshot {
        if (!settings.torrents) return prev.copy(torrentCompletedOn = null)
        // A plain list call (not the Activity screen's incremental sync, which keeps its own rid).
        val list = calls.attempt { app.client.json(s, "/api/v2/torrents/info?filter=completed&sort=completion_on&reverse=true&limit=30") } ?: return prev
        val torrents = list.arr()
        val newest = torrents.mapNotNull { it.long("completion_on")?.takeIf { on -> on > 0 } }.maxOrNull() ?: 0L
        val last = prev.torrentCompletedOn
        if (last != null) torrents.filter { (it.long("completion_on") ?: 0L) > last }.sortedBy { it.long("completion_on") }.forEach { t ->
            val adult = looksAdult(t)
            if (adult && !settings.whisparr) return@forEach
            val name = t.str("name") ?: "A torrent"
            events += NotifyEvent(
                NotifyKind.Torrent, "tor:${s.id}:${t.str("hash")}", name,
                listOfNotNull("Download complete", t.str("category")?.takeIf { it.isNotBlank() }, s.name).joinToString(" · "),
                NotifyLinks.ACTIVITY, adult = adult,
            )
        }
        return prev.copy(torrentCompletedOn = maxOf(newest, last ?: 0L))
    }

    /** Whisparr's downloads, by the category/tags people give them; kept off the shade unless Whisparr is enabled. */
    private fun looksAdult(t: JsonElement): Boolean {
        val text = "${t.str("category").orEmpty()} ${t.str("tags").orEmpty()}".lowercase()
        return ADULT_WORDS.any { it in text }
    }

    private val ADULT_WORDS = listOf("whisparr", "xxx", "adult", "porn")
}

/* ------------------------------------------------------------------ lenient JSON helpers */

private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
private fun JsonElement?.obj(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject
private fun JsonElement?.arr(): List<JsonElement> = (this as? JsonArray).orEmpty()
private fun JsonElement?.prim(key: String): JsonPrimitive? = (this as? JsonObject)?.get(key) as? JsonPrimitive
private fun JsonElement?.str(key: String): String? = prim(key)?.takeIf { it.isString }?.content
private fun JsonElement?.int(key: String): Int? = prim(key)?.content?.toIntOrNull()
private fun JsonElement?.long(key: String): Long? = prim(key)?.content?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() }
private fun JsonElement?.bool(key: String): Boolean? = prim(key)?.content?.toBooleanStrictOrNull()
