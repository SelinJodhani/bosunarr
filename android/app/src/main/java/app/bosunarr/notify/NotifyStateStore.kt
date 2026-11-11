package app.bosunarr.notify

import android.content.Context
import app.bosunarr.net.AppJson
import kotlinx.serialization.Serializable
import java.io.File

/**
 * What the previous check saw, per service, so the next one can tell what's new. Only ids, counters and
 * timestamps (no titles, no credentials). A `null` facet means "no baseline yet": the next check records one
 * without notifying, so turning an event on never floods the shade with old history.
 */
@Serializable
data class ServiceSnapshot(
    /** Sonarr/Radarr: highest history record id seen. */
    val historyId: Int? = null,
    /** Sonarr/Radarr: queue item ids that were in warning/error. */
    val problemQueue: Set<Int>? = null,
    /** *arr health: keys of current error-level checks. */
    val healthErrors: Set<String>? = null,
    /** Seerr: pending request ids (newest 50). */
    val pendingRequests: Set<Int>? = null,
    /** Seerr: available request ids of the signed-in user (newest 50). */
    val availableRequests: Set<Int>? = null,
    /** qBittorrent: newest `completion_on` (epoch seconds) seen. */
    val torrentCompletedOn: Long? = null,
    /** Consecutive failed checks, and whether "unreachable" was posted for this outage. */
    val failures: Int = 0,
    val downNotified: Boolean = false,
)

@Serializable
data class NotifySnapshot(val services: Map<String, ServiceSnapshot> = emptyMap())

/** A small JSON file in the app's private files dir (excluded from backup like the rest of the app). */
internal class NotifyStateStore(context: Context) {
    private val file = File(context.filesDir, "notify-state.json")

    fun read(): NotifySnapshot = runCatching {
        if (file.exists()) AppJson.decodeFromString(NotifySnapshot.serializer(), file.readText()) else NotifySnapshot()
    }.getOrDefault(NotifySnapshot())

    fun write(s: NotifySnapshot) {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(AppJson.encodeToString(NotifySnapshot.serializer(), s))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }

    fun clear() { runCatching { file.delete() } }
}
