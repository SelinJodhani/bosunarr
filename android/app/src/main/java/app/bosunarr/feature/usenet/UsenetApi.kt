package app.bosunarr.feature.usenet

import app.bosunarr.data.ServiceConfig
import app.bosunarr.data.ServiceType
import app.bosunarr.net.ServiceException
import app.bosunarr.net.fanOut
import app.bosunarr.net.userMessage
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap

/**
 * One API over SABnzbd and NZBGet, for the service screens, the Activity tab and the dashboard. Every call takes
 * the client's [ServiceConfig] and dispatches on its type; ids are the client's own (`nzo_id` / `NZBID`).
 */
object UsenetApi {
    /** How long the last good queue keeps being shown after a refresh fails (a blip shouldn't empty the screen). */
    private const val STALE_MS = 30_000L
    private val last = ConcurrentHashMap<String, Pair<Long, UsenetSnapshot>>()

    private fun unsupported(s: ServiceConfig): Nothing = throw ServiceException("${s.name} isn't a Usenet client", 400)

    /** Queue + client status. On failure, returns the last good snapshot (marked with the error) for a short while. */
    suspend fun queue(s: ServiceConfig): UsenetSnapshot = try {
        val snap = when (s.serviceType) {
            ServiceType.Sabnzbd -> Sab.queue(s)
            ServiceType.Nzbget -> Nzbget.queue(s)
            else -> unsupported(s)
        }
        last[s.id] = System.currentTimeMillis() to snap
        snap
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val prev = last[s.id]
        if (prev != null && System.currentTimeMillis() - prev.first < STALE_MS) {
            prev.second.copy(error = e.userMessage(), server = prev.second.server.copy(down = 0))
        } else throw e
    }

    /** Queues of several clients at once; failures are reported per client instead of failing the lot. */
    suspend fun queues(services: List<ServiceConfig>): List<Pair<ServiceConfig, Result<UsenetSnapshot>>> =
        fanOut(services.filter { isUsenet(it.serviceType) }) { queue(it) }

    /** Newest first. [limit] caps how many entries come back. */
    suspend fun history(s: ServiceConfig, limit: Int = 60, failedOnly: Boolean = false): List<NzbHistoryItem> = when (s.serviceType) {
        ServiceType.Sabnzbd -> Sab.history(s, limit, failedOnly)
        ServiceType.Nzbget -> Nzbget.history(s, limit, failedOnly)
        else -> unsupported(s)
    }

    /**
     * Totals across all configured Usenet clients (non-Usenet services in [services] are ignored), for the dashboard
     * Downloads card. Throws only when every client failed.
     */
    suspend fun summary(services: List<ServiceConfig>): UsenetSummary {
        val r = queues(services)
        val ok = r.mapNotNull { it.second.getOrNull() }
        val errors = r.mapNotNull { (s, x) -> x.exceptionOrNull()?.let { "${s.name}: ${it.userMessage()}" } } + ok.mapNotNull { it.error?.let { e -> "${it.serviceName}: $e" } }
        if (r.isNotEmpty() && ok.isEmpty()) throw ServiceException(r.first().second.exceptionOrNull()?.userMessage() ?: "Usenet clients are unreachable")
        val items = ok.flatMap { it.items }
        return UsenetSummary(
            clients = r.size,
            down = ok.sumOf { it.server.down },
            downloading = items.count { it.state == NzbState.Downloading },
            queued = items.count { it.state == NzbState.Queued || it.state == NzbState.Fetching },
            paused = items.count { it.state == NzbState.Paused },
            pausedClients = ok.count { it.server.paused },
            left = ok.sumOf { it.server.left },
            errors = errors,
        )
    }

    suspend fun categories(s: ServiceConfig): List<String> = when (s.serviceType) {
        ServiceType.Sabnzbd -> Sab.categories(s)
        ServiceType.Nzbget -> Nzbget.categories(s)
        else -> emptyList()
    }.sortedWith(String.CASE_INSENSITIVE_ORDER)

    // ------------------------------------------------------------------ whole client

    suspend fun pauseAll(s: ServiceConfig) = when (s.serviceType) {
        ServiceType.Sabnzbd -> Sab.pauseAll(s)
        ServiceType.Nzbget -> Nzbget.pauseAll(s)
        else -> unsupported(s)
    }

    suspend fun resumeAll(s: ServiceConfig) = when (s.serviceType) {
        ServiceType.Sabnzbd -> Sab.resumeAll(s)
        ServiceType.Nzbget -> Nzbget.resumeAll(s)
        else -> unsupported(s)
    }

    /** Pause the whole queue; the client resumes by itself after [minutes]. */
    suspend fun pauseFor(s: ServiceConfig, minutes: Int) = when (s.serviceType) {
        ServiceType.Sabnzbd -> Sab.pauseFor(s, minutes)
        ServiceType.Nzbget -> Nzbget.pauseFor(s, minutes)
        else -> unsupported(s)
    }

    /** [bytesPerSec] 0 = no limit. */
    suspend fun setSpeedLimit(s: ServiceConfig, bytesPerSec: Long) = when (s.serviceType) {
        ServiceType.Sabnzbd -> Sab.setSpeedLimit(s, bytesPerSec)
        ServiceType.Nzbget -> Nzbget.setSpeedLimit(s, bytesPerSec)
        else -> unsupported(s)
    }

    // ------------------------------------------------------------------ queue items

    suspend fun pause(s: ServiceConfig, ids: Collection<String>) = when (s.serviceType) {
        ServiceType.Sabnzbd -> Sab.pause(s, ids)
        ServiceType.Nzbget -> Nzbget.pause(s, ids)
        else -> unsupported(s)
    }

    suspend fun resume(s: ServiceConfig, ids: Collection<String>) = when (s.serviceType) {
        ServiceType.Sabnzbd -> Sab.resume(s, ids)
        ServiceType.Nzbget -> Nzbget.resume(s, ids)
        else -> unsupported(s)
    }

    /**
     * Remove jobs from the queue. [purge]: SABnzbd also deletes the downloaded files; NZBGet removes the job without
     * leaving a history entry (its partial files are cleaned up either way, per its settings).
     */
    suspend fun delete(s: ServiceConfig, ids: Collection<String>, purge: Boolean) = when (s.serviceType) {
        ServiceType.Sabnzbd -> Sab.delete(s, ids, purge)
        ServiceType.Nzbget -> Nzbget.delete(s, ids, purge)
        else -> unsupported(s)
    }

    /** [ids] in their current queue order. */
    suspend fun moveTop(s: ServiceConfig, ids: List<String>) = when (s.serviceType) {
        ServiceType.Sabnzbd -> Sab.moveTop(s, ids)
        ServiceType.Nzbget -> Nzbget.moveTop(s, ids)
        else -> unsupported(s)
    }

    suspend fun setPriority(s: ServiceConfig, ids: Collection<String>, priority: NzbPriority) = when (s.serviceType) {
        ServiceType.Sabnzbd -> Sab.setPriority(s, ids, priority)
        ServiceType.Nzbget -> Nzbget.setPriority(s, ids, priority)
        else -> unsupported(s)
    }

    /** Empty [category] = none / the client's default. */
    suspend fun setCategory(s: ServiceConfig, ids: Collection<String>, category: String) = when (s.serviceType) {
        ServiceType.Sabnzbd -> Sab.setCategory(s, ids, category)
        ServiceType.Nzbget -> Nzbget.setCategory(s, ids, category)
        else -> unsupported(s)
    }

    // ------------------------------------------------------------------ history

    /** [purge]: SABnzbd also deletes files of failed jobs; NZBGet deletes for good instead of hiding the entry. */
    suspend fun deleteHistory(s: ServiceConfig, ids: Collection<String>, purge: Boolean) = when (s.serviceType) {
        ServiceType.Sabnzbd -> Sab.deleteHistory(s, ids, purge)
        ServiceType.Nzbget -> Nzbget.deleteHistory(s, ids, purge)
        else -> unsupported(s)
    }

    /** SABnzbd retries the failed job with what it already has; NZBGet downloads it again from scratch. */
    suspend fun retry(s: ServiceConfig, id: String) = when (s.serviceType) {
        ServiceType.Sabnzbd -> Sab.retry(s, id)
        ServiceType.Nzbget -> Nzbget.retry(s, id)
        else -> unsupported(s)
    }

    // ------------------------------------------------------------------ add

    /** [priority] null = the category's default. */
    suspend fun addUrl(s: ServiceConfig, url: String, category: String?, priority: NzbPriority?, paused: Boolean) = when (s.serviceType) {
        ServiceType.Sabnzbd -> Sab.addUrl(s, url, category, priority, paused)
        ServiceType.Nzbget -> Nzbget.addUrl(s, url, category, priority, paused)
        else -> unsupported(s)
    }

    suspend fun addFile(s: ServiceConfig, file: NzbUpload, category: String?, priority: NzbPriority?, paused: Boolean) = when (s.serviceType) {
        ServiceType.Sabnzbd -> Sab.addFile(s, file, category, priority, paused)
        ServiceType.Nzbget -> Nzbget.addFile(s, file, category, priority, paused)
        else -> unsupported(s)
    }

    /** Forget the cached last-good queue (e.g. after the service was edited). */
    fun reset(serviceId: String) { last.remove(serviceId) }
}
