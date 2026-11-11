package app.bosunarr.ui.common

import androidx.compose.runtime.mutableStateMapOf
import app.bosunarr.app
import app.bosunarr.data.ServiceConfig
import app.bosunarr.net.fanOut

/** Result of a lightweight connection check ([app.bosunarr.net.ServiceClient.test]). */
data class ServiceStatus(val ok: Boolean, val version: String?, val ms: Int?, val error: String?)

/**
 * App-wide cache of service reachability, shared by the dashboard and Settings so each screen shows the last
 * known state immediately (no "Checking…" flash) and checks aren't repeated on every visit.
 * Entries are keyed by service id + addresses, so editing a service invalidates its status.
 */
object ServiceStatuses {
    private data class Entry(val status: ServiceStatus, val at: Long)

    private val entries = mutableStateMapOf<String, Entry>()

    private fun key(s: ServiceConfig) = "${s.id}|${s.url}|${s.altUrl}|${s.apiKey.hashCode()}|${s.username}|${s.password.hashCode()}"

    /** Last known status (observable from composition), or null if never checked. */
    fun get(s: ServiceConfig): ServiceStatus? = entries[key(s)]?.status

    /** Checks every service in parallel, stores and returns the results by service id. */
    suspend fun check(services: List<ServiceConfig>): Map<String, ServiceStatus> {
        val now = System.currentTimeMillis()
        return fanOut(services) { app.client.test(it) }.associate { (s, r) ->
            val st = r.fold(
                { ServiceStatus(true, it.version, it.ms, null) },
                { ServiceStatus(false, null, null, it.friendlyMessage()) },
            )
            entries[key(s)] = Entry(st, now)
            s.id to st
        }
    }

    /** Re-checks only the services whose status is missing or older than [maxAgeMs]. */
    suspend fun refreshStale(services: List<ServiceConfig>, maxAgeMs: Long = 60_000) {
        val now = System.currentTimeMillis()
        val stale = services.filter { s -> entries[key(s)]?.let { now - it.at > maxAgeMs } ?: true }
        if (stale.isNotEmpty()) check(stale)
    }
}
