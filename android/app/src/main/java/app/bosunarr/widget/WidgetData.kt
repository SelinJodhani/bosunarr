package app.bosunarr.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.core.graphics.createBitmap
import app.bosunarr.app
import app.bosunarr.data.ServiceConfig
import app.bosunarr.data.ServiceType
import app.bosunarr.feature.activity.queue.ArrQueue
import app.bosunarr.feature.activity.queue.QueueEntry
import app.bosunarr.feature.calendar.CalStatus
import app.bosunarr.feature.calendar.CalendarApi
import app.bosunarr.nav.MovieRoute
import app.bosunarr.nav.SeriesRoute
import app.bosunarr.net.fanOut
import app.bosunarr.net.userMessage
import app.bosunarr.ui.common.sameOrigin
import coil3.SingletonImageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Scale
import coil3.toBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Loads what the widgets show, straight from the user's own services (the same calls the app makes).
 * Whisparr is never queried for display: it is skipped everywhere, and its downloads are filtered out of
 * qBittorrent's list (by Whisparr's queue and by category/tag).
 */
internal object WidgetData {
    private val ARR = setOf(ServiceType.Sonarr, ServiceType.Radarr)

    /** Services the widgets may mention (everything except Whisparr). */
    fun visibleServices(): List<ServiceConfig> = app.services.value.filter { it.serviceType != null && it.serviceType != ServiceType.Whisparr }

    private fun failureNote(failed: List<ServiceConfig>, total: Int): String? = when {
        failed.isEmpty() -> null
        failed.size == 1 -> "${failed[0].name} unreachable"
        else -> "${failed.size} of $total services unreachable"
    }

    // ------------------------------------------------------------------ Downloads

    @Serializable
    private data class TransferInfo(
        @SerialName("dl_info_speed") val down: Long = 0,
        @SerialName("up_info_speed") val up: Long = 0,
    )

    @Serializable
    private data class LightTorrent(
        val hash: String = "",
        val name: String = "",
        val progress: Double = 0.0,
        val dlspeed: Long = 0,
        val state: String = "",
        val category: String = "",
        val tags: String = "",
    )

    private class QbitState(val down: Long, val up: Long, val torrents: List<LightTorrent>)

    private val ACTIVE = setOf("downloading", "forcedDL", "metaDL", "forcedMetaDL", "stalledDL")

    private val STATE_LABEL = mapOf(
        "downloading" to "Downloading", "forcedDL" to "Downloading", "metaDL" to "Fetching metadata",
        "forcedMetaDL" to "Fetching metadata", "stalledDL" to "Stalled", "queuedDL" to "Queued",
        "checkingDL" to "Checking", "allocating" to "Allocating", "moving" to "Moving", "error" to "Error",
        "missingFiles" to "Files missing",
    )

    private suspend fun qbit(s: ServiceConfig): QbitState = coroutineScope {
        val info = async { app.client.get<TransferInfo>(s, "/api/v2/transfer/info") }
        // "downloading" = every incomplete torrent (stalled, queued and stopped ones too); stopped ones are dropped below.
        val list = app.client.get<List<LightTorrent>>(s, "/api/v2/torrents/info?filter=downloading&sort=dlspeed&reverse=true")
        val i = info.await()
        QbitState(i.down.coerceAtLeast(0), i.up.coerceAtLeast(0), list)
    }

    /** Hashes of downloads that belong to Whisparr (its queue), so they never show up on a widget. */
    private suspend fun whisparrHashes(): Set<String> {
        val ws = app.services.value.filter { it.serviceType == ServiceType.Whisparr }
        return fanOut(ws) { s ->
            val page = app.client.json(s, "/api/v3/queue?page=1&pageSize=1000") as? JsonObject
            (page?.get("records") as? JsonArray).orEmpty().mapNotNull { r ->
                ((r as? JsonObject)?.get("downloadId") as? JsonPrimitive)?.content?.lowercase()
            }
        }.flatMap { it.second.getOrNull().orEmpty() }.toSet()
    }

    private fun isWhisparr(t: LightTorrent) = t.category.contains("whisparr", true) || t.tags.contains("whisparr", true)

    private fun QueueEntry.link(): String = libraryId?.let { if (isEpisode) DeepLink.series(serviceId, it) else DeepLink.movie(serviceId, it) }
        ?: DeepLink.ACTIVITY

    suspend fun downloads(prev: DownloadsSnapshot?): DownloadsSnapshot = coroutineScope {
        val now = System.currentTimeMillis()
        val all = app.services.value
        val qbits = all.filter { it.serviceType == ServiceType.QBittorrent }
        val arrs = all.filter { it.serviceType in ARR }
        if (qbits.isEmpty() && arrs.isEmpty()) return@coroutineScope DownloadsSnapshot(at = now, okAt = now, configured = false)

        val hiddenJob = async { runCatching { whisparrHashes() }.getOrDefault(emptySet()) }
        val queueJob = async { fanOut(arrs) { ArrQueue.load(it) } }
        val qbitRes = fanOut(qbits) { qbit(it) }
        val queueRes = queueJob.await()
        val hidden = hiddenJob.await()

        val failed = (qbitRes + queueRes).filter { it.second.isFailure }.map { it.first }
        val total = qbits.size + arrs.size
        if (failed.size == total) {
            val msg = (qbitRes + queueRes).firstNotNullOfOrNull { it.second.exceptionOrNull() }?.userMessage() ?: "Unreachable"
            return@coroutineScope (prev ?: DownloadsSnapshot()).copy(at = now, configured = true, hasQbit = qbits.isNotEmpty(), error = msg, partial = null)
        }

        val states = qbitRes.mapNotNull { it.second.getOrNull() }
        val queue = queueRes.flatMap { it.second.getOrNull().orEmpty() }
        val byHash = queue.filter { it.downloadId != null }.associateBy { it.downloadId!! }

        val torrents = states.flatMap { it.torrents }
            .filter { t -> t.hash.lowercase() !in hidden && !isWhisparr(t) && t.progress < 1.0 }
            .filterNot { it.state.startsWith("paused") || it.state.startsWith("stopped") }
        val items = ArrayList<Pair<DownloadItem, Double>>()
        val matched = HashSet<String>()
        for (t in torrents) {
            val q = byHash[t.hash.lowercase()]
            if (q != null) matched += q.key
            items += DownloadItem(
                title = q?.title ?: t.name.ifBlank { t.hash },
                subtitle = q?.subtitle?.takeIf { q.isEpisode },
                progress = t.progress.toFloat().coerceIn(0f, 1f),
                speed = t.dlspeed.coerceAtLeast(0),
                state = STATE_LABEL[t.state] ?: t.state,
                link = q?.link() ?: DeepLink.ACTIVITY,
            ) to (t.dlspeed.toDouble() * 1_000 + t.progress)
        }
        // Arr queue items that aren't a known torrent (Usenet, another client, or qBittorrent not configured here).
        for (q in queue) {
            if (q.key in matched || q.downloadId?.let { it in hidden } == true) continue
            if (q.state !in setOf("downloading", "queued", "delay", "paused") || q.progress >= 1f) continue
            items += DownloadItem(
                title = q.title,
                subtitle = q.subtitle?.takeIf { q.isEpisode },
                progress = q.progress,
                state = q.stateLabel,
                link = q.link(),
            ) to q.progress.toDouble()
        }
        items.sortByDescending { it.second }
        val active = torrents.count { it.state in ACTIVE } + queue.count { q ->
            q.key !in matched && q.state == "downloading" && q.progress < 1f && q.downloadId?.let { it in hidden } != true
        }
        DownloadsSnapshot(
            at = now, okAt = now, configured = true, hasQbit = qbits.isNotEmpty(),
            down = states.sumOf { it.down }, up = states.sumOf { it.up },
            active = active,
            items = items.take(6).map { it.first },
            partial = failureNote(failed, total),
        )
    }

    // ------------------------------------------------------------------ Up next

    private const val POSTER_W = 120
    private const val POSTER_H = 180

    suspend fun upNext(context: Context, prev: UpNextSnapshot?): UpNextSnapshot {
        val now = System.currentTimeMillis()
        val arrs = app.services.value.filter { it.serviceType in ARR }
        if (arrs.isEmpty()) return UpNextSnapshot(at = now, okAt = now, configured = false)
        val data = try {
            CalendarApi.load(arrs, pastDays = 0, aheadDays = 30)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return (prev ?: UpNextSnapshot()).copy(at = now, configured = true, error = e.userMessage(), partial = null)
        }
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val recent = Instant.ofEpochMilli(now).minusSeconds(3600)
        val entries = data.entries.filter { e ->
            e.monitored && e.status != CalStatus.Downloaded &&
                (e.time?.let { !it.isBefore(recent) } ?: !e.day.isBefore(today))
        }.take(6)

        val items = entries.map { e ->
            val link = when (val r = e.primary.route) {
                is SeriesRoute -> DeepLink.series(r.serviceId, r.id)
                is MovieRoute -> DeepLink.movie(r.serviceId, r.id)
                else -> DeepLink.CALENDAR
            }
            val poster = e.posterService?.let { s -> e.poster?.let { p -> runCatching { posterFile(context, s, p) }.getOrNull() } }
            UpNextItem(
                id = e.id,
                title = e.title,
                subtitle = if (e.isEpisode) e.episodeLabel else e.releaseLabel,
                day = e.day.toEpochDay(),
                time = e.time?.toEpochMilli(),
                poster = poster,
                link = link,
            )
        }
        WidgetStore.prunePosters(context, items.mapNotNull { it.poster }.toSet())
        return UpNextSnapshot(
            at = now, okAt = now, configured = true, items = items,
            partial = failureNote(data.errors.map { it.service }, arrs.size),
        )
    }

    /**
     * Fetch a poster through the app's Coil loader (its disk cache, no-redirect OkHttp client), shrink it to a
     * small RGB_565 thumbnail and keep it as a file for the widget. Credentials are only attached when the URL is
     * on the service's own address (same rule as ServiceImage), never for other hosts.
     */
    private suspend fun posterFile(context: Context, s: ServiceConfig, path: String): String? {
        val url = if (path.startsWith("http")) path else app.client.baseUrl(s) + path
        val name = sha(s.id + "|" + path) + ".jpg"
        val file = File(WidgetStore.posterDir(context), name)
        if (file.exists() && file.length() > 0) return name
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(POSTER_W, POSTER_H)
            .scale(Scale.FILL)
            .allowHardware(false)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .apply {
                if (listOfNotNull(s.url, s.altUrl, app.client.baseUrl(s)).any { sameOrigin(it, url) }) {
                    val h = NetworkHeaders.Builder()
                    app.client.authHeaders(s).forEach { (k, v) -> h[k] = v }
                    httpHeaders(h.build())
                }
            }
            .build()
        val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return null
        val src = result.image.toBitmap()
        val thumb = centerCrop(src, POSTER_W, POSTER_H)
        val tmp = File(file.path + ".tmp")
        tmp.outputStream().use { thumb.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        tmp.renameTo(file)
        WidgetStore.putPoster(name, thumb)
        return name
    }

    private fun centerCrop(src: Bitmap, w: Int, h: Int): Bitmap {
        val out = createBitmap(w, h, Bitmap.Config.RGB_565)
        val scale = maxOf(w.toFloat() / src.width, h.toFloat() / src.height)
        val cw = (w / scale).toInt().coerceAtMost(src.width)
        val ch = (h / scale).toInt().coerceAtMost(src.height)
        val left = (src.width - cw) / 2
        val top = (src.height - ch) / 2
        Canvas(out).drawBitmap(src, Rect(left, top, left + cw, top + ch), Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun sha(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).take(12).joinToString("") { "%02x".format(it) }

    // ------------------------------------------------------------------ Services status

    suspend fun status(): StatusSnapshot {
        val now = System.currentTimeMillis()
        val services = visibleServices()
        if (services.isEmpty()) return StatusSnapshot(at = now, okAt = now)
        val results = fanOut(services) { app.client.test(it) }
        val list = results.map { (s, r) ->
            ServiceHealth(s.id, s.name, s.type, r.isSuccess, r.exceptionOrNull()?.userMessage())
        }
        return StatusSnapshot(at = now, okAt = now, services = list)
    }
}
