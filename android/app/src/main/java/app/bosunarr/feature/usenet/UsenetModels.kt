package app.bosunarr.feature.usenet

import androidx.compose.runtime.Immutable
import app.bosunarr.data.ServiceType

/** Where a queued job is. Both clients' many states fold into these. */
enum class NzbState { Downloading, Queued, Paused, Fetching, Processing }

/**
 * Job priority. SABnzbd has Force/High/Normal/Low; NZBGet adds Very high and Very low. Use [priorities] to list
 * the ones a client supports.
 */
enum class NzbPriority(val label: String) {
    Force("Force"), VeryHigh("Very high"), High("High"), Normal("Normal"), Low("Low"), VeryLow("Very low"),
}

/** Final outcome of a history entry. [Processing] = still being verified/repaired/unpacked. */
enum class NzbOutcome { Success, Warning, Failed, Deleted, Processing }

/** One job in a Usenet client's queue. Sizes are bytes; [eta] is seconds (null when unknown). */
@Immutable
data class NzbItem(
    val serviceId: String,
    val serviceName: String,
    val type: ServiceType,
    /** SABnzbd `nzo_id`, or NZBGet `NZBID` as a string. */
    val id: String,
    val name: String,
    val category: String,
    val priority: NzbPriority,
    val state: NzbState,
    /** The client's own wording ("Downloading", "Repairing", "Fetching NZB"…). */
    val stateLabel: String,
    val size: Long,
    val left: Long,
    /** 0..1 */
    val progress: Float,
    val eta: Long?,
    /** Position in the client's queue (0 = top). */
    val index: Int,
    /** SABnzbd labels such as "DUPLICATE", "ENCRYPTED"; NZBGet health warnings. */
    val labels: List<String> = emptyList(),
) {
    val key: String get() = "$serviceId:$id"
}

/** One entry in a Usenet client's history. */
@Immutable
data class NzbHistoryItem(
    val serviceId: String,
    val serviceName: String,
    val type: ServiceType,
    val id: String,
    val name: String,
    val category: String,
    val size: Long,
    /** Epoch seconds; 0 when unknown. */
    val completedAt: Long,
    val outcome: NzbOutcome,
    val stateLabel: String,
    /** Why it failed / warned (SABnzbd `fail_message`, NZBGet status decoded). */
    val reason: String?,
    val downloadSeconds: Long,
    val canRetry: Boolean,
) {
    val key: String get() = "$serviceId:$id"
}

/** Client-wide numbers for the header. Speeds are bytes/s; [limit] 0 = no limit. */
@Immutable
data class NzbServer(
    val down: Long = 0,
    val limit: Long = 0,
    val paused: Boolean = false,
    val left: Long = 0,
    val timeLeft: Long? = null,
    val freeDisk: Long? = null,
    val version: String? = null,
    /** Downloaded today, when the client reports it. */
    val today: Long? = null,
)

@Immutable
data class UsenetSnapshot(
    val serviceId: String,
    val serviceName: String,
    val type: ServiceType,
    val server: NzbServer,
    val items: List<NzbItem>,
    /** Set when this is the last good snapshot shown after a failed refresh. */
    val error: String? = null,
)

/** Totals across every Usenet client, for the dashboard Downloads card. */
@Immutable
data class UsenetSummary(
    val clients: Int,
    val down: Long,
    val downloading: Int,
    val queued: Int,
    val paused: Int,
    /** Clients whose whole queue is paused. */
    val pausedClients: Int,
    val left: Long,
    val errors: List<String>,
)

/** A .nzb file picked on the phone. */
class NzbUpload(val name: String, val bytes: ByteArray)

fun isUsenet(type: ServiceType?): Boolean = type == ServiceType.Sabnzbd || type == ServiceType.Nzbget

/** Priorities a client can set, highest first. */
fun priorities(type: ServiceType): List<NzbPriority> = when (type) {
    ServiceType.Nzbget -> NzbPriority.entries
    else -> listOf(NzbPriority.Force, NzbPriority.High, NzbPriority.Normal, NzbPriority.Low)
}
