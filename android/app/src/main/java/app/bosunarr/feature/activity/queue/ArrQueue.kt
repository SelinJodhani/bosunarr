package app.bosunarr.feature.activity.queue

import androidx.compose.runtime.Immutable
import app.bosunarr.app
import app.bosunarr.data.ServiceConfig
import app.bosunarr.data.ServiceType
import app.bosunarr.nav.AlbumRoute
import app.bosunarr.nav.ArtistRoute
import app.bosunarr.nav.MovieRoute
import app.bosunarr.nav.SeriesRoute
import kotlinx.serialization.Serializable
import java.net.URI

@Serializable
internal data class ArrImage(val coverType: String = "", val url: String? = null, val remoteUrl: String? = null)

@Serializable
internal data class ArrStatusMessage(val title: String? = null, val messages: List<String> = emptyList())

@Serializable
internal data class ArrQualityName(val name: String? = null)

@Serializable
internal data class ArrQuality(val quality: ArrQualityName? = null)

@Serializable
internal data class ArrSeries(val title: String = "", val images: List<ArrImage> = emptyList())

@Serializable
internal data class ArrEpisode(val seasonNumber: Int = 0, val episodeNumber: Int = 0, val title: String? = null)

@Serializable
internal data class ArrMovie(val title: String = "", val year: Int = 0, val images: List<ArrImage> = emptyList())

@Serializable
internal data class LidarrArtist(val artistName: String = "", val images: List<ArrImage> = emptyList())

@Serializable
internal data class LidarrAlbum(val title: String = "", val releaseDate: String? = null, val images: List<ArrImage> = emptyList())

@Serializable
internal data class ArrQueueRecord(
    val id: Int = 0,
    val title: String = "",
    val size: Double = 0.0,
    val sizeleft: Double = 0.0,
    val status: String = "",
    val timeleft: String? = null,
    val trackedDownloadState: String? = null,
    val trackedDownloadStatus: String? = null,
    val statusMessages: List<ArrStatusMessage> = emptyList(),
    val errorMessage: String? = null,
    val protocol: String? = null,
    val downloadClient: String? = null,
    val downloadId: String? = null,
    val quality: ArrQuality? = null,
    val seriesId: Int? = null,
    val movieId: Int? = null,
    val series: ArrSeries? = null,
    val episode: ArrEpisode? = null,
    val movie: ArrMovie? = null,
    // Lidarr (v1)
    val artistId: Int? = null,
    val albumId: Int? = null,
    val artist: LidarrArtist? = null,
    val album: LidarrAlbum? = null,
)

@Serializable
internal data class ArrQueuePage(val records: List<ArrQueueRecord> = emptyList(), val totalRecords: Int = 0)

enum class QueueHealth { Ok, Warning, Error }

/** One Sonarr/Radarr/Lidarr queue item. [key] is unique across services. */
@Immutable
data class QueueEntry(
    val serviceId: String,
    val serviceName: String,
    val queueId: Int,
    val isEpisode: Boolean,
    val title: String,
    val subtitle: String?,
    val progress: Float,
    val size: Long,
    val sizeLeft: Long,
    /** Arr's tracked state (downloading, importPending, importing, failedPending…) refined by the client status. */
    val state: String,
    val health: QueueHealth,
    val messages: List<String>,
    val timeLeft: String?,
    val protocol: String?,
    val client: String?,
    val quality: String?,
    val poster: String?,
    /** Library item: Sonarr series id or Radarr movie id. */
    val libraryId: Int?,
    val downloadId: String?,
    /** Lidarr: [title] is "Artist – Album", [libraryId] the artist, [albumId] the album. */
    val music: Boolean = false,
    val albumId: Int? = null,
) {
    val key: String get() = "$serviceId:$queueId"
    val stateLabel: String get() = queueStateLabel(state)

    /** Where tapping the row goes: the series, movie or album (artist when the album is unknown). */
    val route: Any? get() = when {
        music -> albumId?.takeIf { it > 0 }?.let { AlbumRoute(serviceId, it) } ?: libraryId?.takeIf { it > 0 }?.let { ArtistRoute(serviceId, it) }
        libraryId == null -> null
        isEpisode -> SeriesRoute(serviceId, libraryId)
        else -> MovieRoute(serviceId, libraryId)
    }

    val openLabel: String get() = when {
        music -> if ((albumId ?: 0) > 0) "Open album" else "Open artist"
        isEpisode -> "Open series"
        else -> "Open movie"
    }
}

private val QUEUE_STATE = mapOf(
    "downloading" to "Downloading", "importPending" to "Waiting to import", "importBlocked" to "Import blocked",
    "importing" to "Importing", "imported" to "Imported", "failedPending" to "Failed", "failed" to "Failed",
    "ignored" to "Ignored", "delay" to "Delayed", "queued" to "Queued", "paused" to "Paused",
    "completed" to "Completed", "warning" to "Warning", "downloadClientUnavailable" to "Client unavailable",
    "fallback" to "Fallback",
)

fun queueStateLabel(state: String): String = QUEUE_STATE[state]
    ?: state.replace(Regex("([a-z])([A-Z])"), "$1 $2").replaceFirstChar { it.uppercase() }

/** Arr time spans look like "1.02:03:04" (days.hours:minutes:seconds) or "00:12:34". */
fun arrTimeLeft(t: String?): String? {
    val m = Regex("^(?:(\\d+)\\.)?(\\d+):(\\d+):(\\d+)").find(t ?: return null) ?: return null
    val d = m.groupValues[1].toIntOrNull() ?: 0
    val h = m.groupValues[2].toInt()
    val min = m.groupValues[3].toInt()
    return when {
        d > 0 -> "${d}d ${h}h"
        h > 0 -> "${h}h ${min}m"
        min > 0 -> "${min}m"
        else -> "<1m"
    }
}

private fun pad(n: Int) = n.toString().padStart(2, '0')

object ArrQueue {
    private fun api(s: ServiceConfig) = if (s.serviceType == ServiceType.Lidarr) "/api/v1" else "/api/v3"

    suspend fun load(s: ServiceConfig): List<QueueEntry> {
        if (s.serviceType == ServiceType.Lidarr) {
            val page = app.client.get<ArrQueuePage>(s, "/api/v1/queue?page=1&pageSize=1000&includeArtist=true&includeAlbum=true")
            return page.records.map { q -> entry(s, q, false) }
        }
        val episode = s.serviceType == ServiceType.Sonarr
        val include = if (episode) "includeSeries=true&includeEpisode=true" else "includeMovie=true"
        val page = app.client.get<ArrQueuePage>(s, "/api/v3/queue?page=1&pageSize=1000&$include")
        return page.records.map { q -> entry(s, q, episode) }
    }

    private fun entry(s: ServiceConfig, q: ArrQueueRecord, episode: Boolean): QueueEntry {
        val messages = buildList {
            q.errorMessage?.takeIf { it.isNotBlank() }?.let { add(it) }
            q.statusMessages.forEach { m -> if (m.messages.isNotEmpty()) addAll(m.messages) else m.title?.takeIf { it.isNotBlank() }?.let { add(it) } }
        }.distinct()
        val health = when (q.trackedDownloadStatus) {
            "error" -> QueueHealth.Error
            "warning" -> QueueHealth.Warning
            else -> QueueHealth.Ok
        }
        // The arr says "downloading" for anything still in the client; the client status says whether it's
        // actually queued, paused or delayed.
        val tracked = q.trackedDownloadState
        val state = when {
            q.status in setOf("queued", "paused", "delay") && (tracked == null || tracked == "downloading") -> q.status
            tracked != null -> tracked
            q.status.isNotBlank() -> q.status
            else -> "unknown"
        }
        val size = q.size.toLong().coerceAtLeast(0)
        val left = q.sizeleft.toLong().coerceIn(0, maxOf(size, 0))
        val ep = q.episode
        if (s.serviceType == ServiceType.Lidarr) {
            val artist = q.artist?.artistName?.takeIf { it.isNotBlank() }
            val album = q.album?.title?.takeIf { it.isNotBlank() }
            return QueueEntry(
                serviceId = s.id, serviceName = s.name, queueId = q.id, isEpisode = false,
                title = listOfNotNull(artist, album).joinToString(" – ").ifBlank { q.title },
                subtitle = q.title.takeIf { it.isNotBlank() },
                progress = if (size > 0) (1f - left.toFloat() / size).coerceIn(0f, 1f) else 0f,
                size = size, sizeLeft = left, state = state, health = health, messages = messages,
                timeLeft = arrTimeLeft(q.timeleft), protocol = q.protocol, client = q.downloadClient?.takeIf { it.isNotBlank() },
                quality = q.quality?.quality?.name,
                poster = poster(s, q.artist?.images),
                libraryId = q.artistId, downloadId = q.downloadId?.lowercase(),
                music = true, albumId = q.albumId,
            )
        }
        return QueueEntry(
            serviceId = s.id,
            serviceName = s.name,
            queueId = q.id,
            isEpisode = episode,
            title = q.series?.title?.takeIf { it.isNotBlank() }
                ?: q.movie?.let { if (it.year > 0) "${it.title} (${it.year})" else it.title }?.takeIf { it.isNotBlank() }
                ?: q.title,
            subtitle = if (ep != null) "S${pad(ep.seasonNumber)}E${pad(ep.episodeNumber)}" + (ep.title?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
            else q.title.takeIf { it.isNotBlank() },
            progress = if (size > 0) (1f - left.toFloat() / size).coerceIn(0f, 1f) else 0f,
            size = size,
            sizeLeft = left,
            state = state,
            health = health,
            messages = messages,
            timeLeft = arrTimeLeft(q.timeleft),
            protocol = q.protocol,
            client = q.downloadClient?.takeIf { it.isNotBlank() },
            quality = q.quality?.quality?.name,
            poster = poster(s, q.series?.images ?: q.movie?.images),
            libraryId = if (episode) q.seriesId else q.movieId,
            downloadId = q.downloadId?.lowercase(),
        )
    }

    /** The arr's cached poster (small variant), or the TMDb original when it hasn't cached one yet. */
    private fun poster(s: ServiceConfig, images: List<ArrImage>?): String? {
        val im = images?.firstOrNull { it.coverType == "poster" } ?: return null
        val url = im.url
        if (url.isNullOrBlank()) {
            return im.remoteUrl?.let { Regex("image\\.tmdb\\.org/t/p/\\w+(/[\\w.-]+)$").find(it) }
                ?.let { "https://image.tmdb.org/t/p/w185${it.groupValues[1]}" }
        }
        if (url.startsWith("http")) return url
        // Arr image urls include the URL base ("/sonarr/MediaCover/…"); the service URL already has it.
        val base = runCatching { URI(s.url).path.orEmpty().trimEnd('/') }.getOrDefault("")
        var p = if (url.startsWith("/")) url else "/$url"
        if (base.isNotEmpty() && p.startsWith("$base/")) p = p.removePrefix(base)
        return p.replace("/poster.jpg", "/poster-250.jpg")
    }

    /** Remove one queue item. */
    suspend fun remove(s: ServiceConfig, queueId: Int, removeFromClient: Boolean, blocklist: Boolean) {
        app.client.send(s, "${api(s)}/queue/$queueId?removeFromClient=$removeFromClient&blocklist=$blocklist", "DELETE")
    }
}
