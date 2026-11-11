package app.bosunarr.feature.activity.qbit

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** One torrent as qBittorrent describes it (`/torrents/info` and the `torrents` part of `/sync/maindata`). */
@Serializable
internal data class RawTorrent(
    val hash: String = "",
    val name: String = "",
    val progress: Double = 0.0,
    val dlspeed: Long = 0,
    val upspeed: Long = 0,
    val eta: Long = 8_640_000,
    val state: String = "unknown",
    val size: Long = 0,
    @SerialName("total_size") val totalSize: Long = 0,
    val downloaded: Long = 0,
    val uploaded: Long = 0,
    val ratio: Double = 0.0,
    val category: String = "",
    val tags: String = "",
    @SerialName("added_on") val addedOn: Long = 0,
    @SerialName("completion_on") val completionOn: Long = 0,
    @SerialName("num_seeds") val numSeeds: Int = 0,
    @SerialName("num_leechs") val numLeechs: Int = 0,
    @SerialName("num_complete") val numComplete: Int = 0,
    @SerialName("num_incomplete") val numIncomplete: Int = 0,
    @SerialName("save_path") val savePath: String = "",
    @SerialName("content_path") val contentPath: String = "",
    @SerialName("amount_left") val amountLeft: Long = 0,
    val tracker: String = "",
    @SerialName("magnet_uri") val magnetUri: String = "",
    @SerialName("time_active") val timeActive: Long = 0,
    @SerialName("seeding_time") val seedingTime: Long = 0,
    val availability: Double = -1.0,
    @SerialName("force_start") val forceStart: Boolean = false,
    @SerialName("seq_dl") val seqDl: Boolean = false,
    @SerialName("dl_limit") val dlLimit: Long = 0,
    @SerialName("up_limit") val upLimit: Long = 0,
)

/** What the list groups torrents by (drives colour, filters and the primary action). */
enum class TorrentGroup(val label: String) {
    Downloading("Downloading"), Seeding("Seeding"), Paused("Paused"), Completed("Completed"),
    Queued("Queued"), Checking("Checking"), Stalled("Stalled"), Errored("Error"),
}

private val GROUPS = mapOf(
    "error" to TorrentGroup.Errored, "missingFiles" to TorrentGroup.Errored,
    "uploading" to TorrentGroup.Seeding, "forcedUP" to TorrentGroup.Seeding, "stalledUP" to TorrentGroup.Seeding,
    "pausedUP" to TorrentGroup.Completed, "stoppedUP" to TorrentGroup.Completed,
    "pausedDL" to TorrentGroup.Paused, "stoppedDL" to TorrentGroup.Paused,
    "queuedDL" to TorrentGroup.Queued, "queuedUP" to TorrentGroup.Queued,
    "checkingDL" to TorrentGroup.Checking, "checkingUP" to TorrentGroup.Checking, "checkingResumeData" to TorrentGroup.Checking,
    "moving" to TorrentGroup.Checking, "allocating" to TorrentGroup.Checking,
    "stalledDL" to TorrentGroup.Stalled, "metaDL" to TorrentGroup.Stalled, "forcedMetaDL" to TorrentGroup.Stalled,
    "downloading" to TorrentGroup.Downloading, "forcedDL" to TorrentGroup.Downloading,
)

private val STATE_LABELS = mapOf(
    "downloading" to "Downloading", "forcedDL" to "Downloading (forced)", "metaDL" to "Fetching metadata",
    "forcedMetaDL" to "Fetching metadata (forced)", "stalledDL" to "Stalled", "queuedDL" to "Queued",
    "pausedDL" to "Paused", "stoppedDL" to "Paused", "uploading" to "Seeding", "forcedUP" to "Seeding (forced)",
    "stalledUP" to "Seeding", "queuedUP" to "Queued to seed", "pausedUP" to "Completed", "stoppedUP" to "Completed",
    "checkingDL" to "Checking", "checkingUP" to "Checking", "checkingResumeData" to "Checking resume data",
    "moving" to "Moving", "allocating" to "Allocating", "error" to "Error", "missingFiles" to "Files missing",
)

fun torrentStateLabel(state: String): String = STATE_LABELS[state]
    ?: state.replace(Regex("([a-z])([A-Z])"), "$1 $2").replaceFirstChar { it.uppercase() }

/** A torrent of one qBittorrent service, ready for the UI. [key] is unique across services. */
@Immutable
data class Torrent(
    val serviceId: String,
    val serviceName: String,
    val hash: String,
    val name: String,
    val progress: Double,
    val dlspeed: Long,
    val upspeed: Long,
    val eta: Long,
    val state: String,
    val size: Long,
    val downloaded: Long,
    val uploaded: Long,
    val ratio: Double,
    val category: String,
    val tags: List<String>,
    val addedOn: Long,
    val completedOn: Long,
    val seeds: Int,
    val peers: Int,
    val seedsTotal: Int,
    val peersTotal: Int,
    val savePath: String,
    val amountLeft: Long,
    val tracker: String,
    val magnetUri: String,
    val timeActive: Long,
    val seedingTime: Long,
    val availability: Double,
) {
    val key: String get() = "$serviceId:$hash"
    val group: TorrentGroup get() = GROUPS[state] ?: if (progress >= 1) TorrentGroup.Completed else TorrentGroup.Downloading
    val isDone: Boolean get() = progress >= 1.0
    /** Stopped by the user (paused before completion, or completed and not seeding). */
    val isStopped: Boolean get() = state.startsWith("paused") || state.startsWith("stopped")
    val isActive: Boolean get() = dlspeed > 0 || upspeed > 0
    val stateLabel: String get() = torrentStateLabel(state)

    companion object {
        internal fun of(serviceId: String, serviceName: String, r: RawTorrent) = Torrent(
            serviceId = serviceId,
            serviceName = serviceName,
            hash = r.hash,
            name = r.name.ifBlank { r.hash },
            progress = r.progress.coerceIn(0.0, 1.0),
            dlspeed = r.dlspeed.coerceAtLeast(0),
            upspeed = r.upspeed.coerceAtLeast(0),
            eta = r.eta,
            state = r.state,
            size = if (r.size > 0) r.size else r.totalSize,
            downloaded = r.downloaded,
            uploaded = r.uploaded,
            ratio = r.ratio.coerceAtLeast(0.0),
            category = r.category,
            tags = r.tags.split(',').map { it.trim() }.filter { it.isNotEmpty() },
            addedOn = r.addedOn,
            completedOn = r.completionOn.coerceAtLeast(0),
            seeds = r.numSeeds,
            peers = r.numLeechs,
            seedsTotal = r.numComplete,
            peersTotal = r.numIncomplete,
            savePath = r.savePath,
            amountLeft = r.amountLeft,
            tracker = r.tracker,
            magnetUri = r.magnetUri,
            timeActive = r.timeActive,
            seedingTime = r.seedingTime,
            availability = r.availability,
        )
    }
}

/** `server_state` of `/sync/maindata`, merged across incremental updates. */
@Serializable
internal data class RawServerState(
    @SerialName("dl_info_speed") val dlSpeed: Long = 0,
    @SerialName("up_info_speed") val upSpeed: Long = 0,
    @SerialName("dl_info_data") val dlData: Long = 0,
    @SerialName("up_info_data") val upData: Long = 0,
    @SerialName("alltime_dl") val allTimeDl: Long = 0,
    @SerialName("alltime_ul") val allTimeUl: Long = 0,
    @SerialName("global_ratio") val globalRatio: String? = null,
    @SerialName("free_space_on_disk") val freeSpace: Long? = null,
    @SerialName("dht_nodes") val dhtNodes: Long = 0,
    @SerialName("total_peer_connections") val peers: Long = 0,
    @SerialName("connection_status") val connectionStatus: String = "unknown",
    @SerialName("dl_rate_limit") val dlRateLimit: Long = 0,
    @SerialName("up_rate_limit") val upRateLimit: Long = 0,
    @SerialName("use_alt_speed_limits") val altSpeed: Boolean = false,
    val queueing: Boolean = false,
)

/** One qBittorrent's transfer state. */
@Immutable
data class QbitServer(
    val down: Long = 0,
    val up: Long = 0,
    val sessionDown: Long = 0,
    val sessionUp: Long = 0,
    val allTimeDown: Long = 0,
    val allTimeUp: Long = 0,
    val ratio: Double? = null,
    val freeSpace: Long? = null,
    val dhtNodes: Long = 0,
    val peers: Long = 0,
    /** connected | firewalled | disconnected | unknown */
    val connection: String = "unknown",
    val downLimit: Long = 0,
    val upLimit: Long = 0,
    val altSpeed: Boolean = false,
)

/** Everything known about one qBittorrent after a sync. */
@Immutable
data class QbitSnapshot(
    val serviceId: String,
    val serviceName: String,
    val server: QbitServer,
    val torrents: List<Torrent>,
    val categories: List<String>,
    val tags: List<String>,
    /** Set when this is the last good data and the latest sync failed. */
    val error: String? = null,
)

@Serializable
data class TorrentProperties(
    @SerialName("save_path") val savePath: String = "",
    @SerialName("creation_date") val creationDate: Long = -1,
    @SerialName("piece_size") val pieceSize: Long = -1,
    val comment: String = "",
    @SerialName("total_wasted") val totalWasted: Long = 0,
    @SerialName("total_uploaded") val totalUploaded: Long = 0,
    @SerialName("total_downloaded") val totalDownloaded: Long = 0,
    @SerialName("up_limit") val upLimit: Long = -1,
    @SerialName("dl_limit") val dlLimit: Long = -1,
    @SerialName("time_elapsed") val timeElapsed: Long = 0,
    @SerialName("seeding_time") val seedingTime: Long = 0,
    @SerialName("nb_connections") val connections: Int = 0,
    @SerialName("nb_connections_limit") val connectionsLimit: Int = 0,
    @SerialName("share_ratio") val shareRatio: Double = 0.0,
    @SerialName("addition_date") val additionDate: Long = -1,
    @SerialName("completion_date") val completionDate: Long = -1,
    @SerialName("created_by") val createdBy: String = "",
    @SerialName("dl_speed_avg") val dlSpeedAvg: Long = 0,
    @SerialName("up_speed_avg") val upSpeedAvg: Long = 0,
    @SerialName("last_seen") val lastSeen: Long = -1,
    @SerialName("pieces_have") val piecesHave: Long = -1,
    @SerialName("pieces_num") val piecesNum: Long = -1,
    val reannounce: Long = 0,
    @SerialName("total_size") val totalSize: Long = -1,
)

@Serializable
data class TorrentTracker(
    val url: String = "",
    val status: Int = 0,
    /** Number for real trackers; an empty string (4.x) or -1 for the DHT/PeX/LSD pseudo-entries. */
    val tier: JsonElement? = null,
    @SerialName("num_peers") val peers: Int = -1,
    @SerialName("num_seeds") val seeds: Int = -1,
    @SerialName("num_leeches") val leeches: Int = -1,
    @SerialName("num_downloaded") val downloaded: Int = -1,
    val msg: String = "",
) {
    val isPseudo: Boolean get() = url.startsWith("** [")
    val statusLabel: String get() = when (status) {
        0 -> "Disabled"
        1 -> "Not contacted"
        2 -> "Working"
        3 -> "Updating"
        4 -> "Not working"
        else -> "Unknown"
    }
}

@Serializable
data class TorrentFile(
    val index: Int = -1,
    val name: String = "",
    val size: Long = 0,
    val progress: Double = 0.0,
    /** 0 skip, 1 normal, 6 high, 7 maximum. */
    val priority: Int = 1,
    val availability: Double = -1.0,
)

@Immutable
data class TorrentDetails(
    val properties: TorrentProperties?,
    val trackers: List<TorrentTracker>,
    val files: List<TorrentFile>,
)

/** File priority values qBittorrent accepts, in menu order. */
val FILE_PRIORITIES = listOf(0 to "Skip", 1 to "Normal", 6 to "High", 7 to "Maximum")
fun filePriorityLabel(p: Int) = FILE_PRIORITIES.firstOrNull { it.first == p }?.second ?: if (p > 1) "High" else "Normal"

private val WORDS = mapOf(
    "tv" to "TV", "4k" to "4K", "uhd" to "UHD", "hd" to "HD", "sonarr" to "Sonarr", "radarr" to "Radarr",
    "lidarr" to "Lidarr", "readarr" to "Readarr", "whisparr" to "Whisparr", "anime" to "Anime",
)

/** "radarr-4k" → "Radarr 4K". Only for display; the raw value is what gets sent. */
fun categoryLabel(c: String): String {
    if (c.isEmpty()) return "Uncategorized"
    if (c.any { it.isUpperCase() } || c.contains('/')) return c
    return c.split(Regex("[-_\\s.]+")).filter { it.isNotEmpty() }
        .joinToString(" ") { w -> WORDS[w] ?: w.replaceFirstChar { it.uppercase() } }
}
