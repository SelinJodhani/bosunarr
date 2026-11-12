package app.bosunarr.feature.torrent

import app.bosunarr.data.ServiceConfig
import app.bosunarr.data.ServiceType
import app.bosunarr.feature.activity.qbit.Qbit
import app.bosunarr.feature.activity.qbit.QbitSnapshot
import app.bosunarr.feature.activity.qbit.TorrentDetails
import app.bosunarr.net.ServiceException
import app.bosunarr.net.userMessage
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap

/** qBittorrent, Transmission and Deluge: the clients the Activity tab's Torrents list (and their screens) manage. */
fun isTorrentClient(type: ServiceType?): Boolean =
    type == ServiceType.QBittorrent || type == ServiceType.Transmission || type == ServiceType.Deluge

/**
 * One API over every torrent client, dispatching on the service type. Everything is expressed in the qBittorrent
 * model ([QbitSnapshot], [app.bosunarr.feature.activity.qbit.Torrent], its state names and file priorities), so the
 * shared list, detail sheet and controller work unchanged; ids are info hashes for all three.
 */
object TorrentClients {
    /** How long the last good list keeps being shown after a sync fails (qBittorrent does the same in [Qbit]). */
    private const val STALE_MS = 30_000L
    private val last = ConcurrentHashMap<String, Pair<Long, QbitSnapshot>>()

    private fun unsupported(s: ServiceConfig): Nothing = throw ServiceException("${s.name} isn't a torrent client", 400)

    /** Alternative speed limits: qBittorrent and Transmission have them, Deluge doesn't (without plugins). */
    fun supportsAltSpeed(type: ServiceType?): Boolean = type == ServiceType.QBittorrent || type == ServiceType.Transmission

    suspend fun snapshot(s: ServiceConfig): QbitSnapshot {
        if (s.serviceType == ServiceType.QBittorrent) return Qbit.snapshot(s)
        return try {
            val snap = when (s.serviceType) {
                ServiceType.Transmission -> Transmission.snapshot(s)
                ServiceType.Deluge -> Deluge.snapshot(s)
                else -> unsupported(s)
            }
            last[s.id] = System.currentTimeMillis() to snap
            snap
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val prev = last[s.id]
            if (prev != null && System.currentTimeMillis() - prev.first < STALE_MS) {
                prev.second.copy(error = e.userMessage(), server = prev.second.server.copy(down = 0, up = 0))
            } else throw e
        }
    }

    /** Forget cached sync state (next call does a full sync). */
    fun reset(serviceId: String) {
        Qbit.reset(serviceId); Transmission.reset(serviceId); last.remove(serviceId)
    }

    /** "Test connection" for the JSON-RPC clients (qBittorrent uses the core client's GET probe). */
    suspend fun probe(s: ServiceConfig): String? = when (s.serviceType) {
        ServiceType.Transmission -> Transmission.probe(s)
        ServiceType.Deluge -> Deluge.probe(s)
        else -> unsupported(s)
    }

    suspend fun stop(s: ServiceConfig, hashes: Collection<String>) = when (s.serviceType) {
        ServiceType.QBittorrent -> Qbit.stop(s, hashes)
        ServiceType.Transmission -> Transmission.stop(s, hashes)
        ServiceType.Deluge -> Deluge.stop(s, hashes)
        else -> unsupported(s)
    }

    suspend fun start(s: ServiceConfig, hashes: Collection<String>) = when (s.serviceType) {
        ServiceType.QBittorrent -> Qbit.start(s, hashes)
        ServiceType.Transmission -> Transmission.start(s, hashes)
        ServiceType.Deluge -> Deluge.start(s, hashes)
        else -> unsupported(s)
    }

    suspend fun stopAll(s: ServiceConfig) = when (s.serviceType) {
        ServiceType.QBittorrent -> Qbit.stopAll(s)
        ServiceType.Transmission -> Transmission.stopAll(s)
        ServiceType.Deluge -> Deluge.stopAll(s)
        else -> unsupported(s)
    }

    suspend fun startAll(s: ServiceConfig) = when (s.serviceType) {
        ServiceType.QBittorrent -> Qbit.startAll(s)
        ServiceType.Transmission -> Transmission.startAll(s)
        ServiceType.Deluge -> Deluge.startAll(s)
        else -> unsupported(s)
    }

    suspend fun recheck(s: ServiceConfig, hashes: Collection<String>) = when (s.serviceType) {
        ServiceType.QBittorrent -> Qbit.recheck(s, hashes)
        ServiceType.Transmission -> Transmission.recheck(s, hashes)
        ServiceType.Deluge -> Deluge.recheck(s, hashes)
        else -> unsupported(s)
    }

    suspend fun reannounce(s: ServiceConfig, hashes: Collection<String>) = when (s.serviceType) {
        ServiceType.QBittorrent -> Qbit.reannounce(s, hashes)
        ServiceType.Transmission -> Transmission.reannounce(s, hashes)
        ServiceType.Deluge -> Deluge.reannounce(s, hashes)
        else -> unsupported(s)
    }

    suspend fun delete(s: ServiceConfig, hashes: Collection<String>, deleteFiles: Boolean) = when (s.serviceType) {
        ServiceType.QBittorrent -> Qbit.delete(s, hashes, deleteFiles)
        ServiceType.Transmission -> Transmission.delete(s, hashes, deleteFiles)
        ServiceType.Deluge -> Deluge.delete(s, hashes, deleteFiles)
        else -> unsupported(s)
    }

    /** qBittorrent category; Transmission: the label list set to just this one; Deluge: the Label plugin's label. */
    suspend fun setCategory(s: ServiceConfig, hashes: Collection<String>, category: String) = when (s.serviceType) {
        ServiceType.QBittorrent -> Qbit.setCategory(s, hashes, category)
        ServiceType.Transmission -> Transmission.setCategory(s, hashes, category)
        ServiceType.Deluge -> Deluge.setCategory(s, hashes, category)
        else -> unsupported(s)
    }

    /** [priority] uses qBittorrent's values (0 skip, 1 normal, 6 high, 7 maximum); mapped per client. */
    suspend fun setFilePriority(s: ServiceConfig, hash: String, indexes: Collection<Int>, priority: Int) = when (s.serviceType) {
        ServiceType.QBittorrent -> Qbit.setFilePriority(s, hash, indexes, priority)
        ServiceType.Transmission -> Transmission.setFilePriority(s, hash, indexes, priority)
        ServiceType.Deluge -> Deluge.setFilePriority(s, hash, indexes, priority)
        else -> unsupported(s)
    }

    suspend fun categories(s: ServiceConfig): List<String> = when (s.serviceType) {
        ServiceType.QBittorrent -> Qbit.categories(s)
        ServiceType.Transmission -> Transmission.categories(s)
        ServiceType.Deluge -> Deluge.categories(s)
        else -> emptyList()
    }

    suspend fun details(s: ServiceConfig, hash: String): TorrentDetails = when (s.serviceType) {
        ServiceType.QBittorrent -> Qbit.details(s, hash)
        ServiceType.Transmission -> Transmission.details(s, hash)
        ServiceType.Deluge -> Deluge.details(s, hash)
        else -> unsupported(s)
    }

    suspend fun setAltSpeed(s: ServiceConfig, enabled: Boolean) = when (s.serviceType) {
        ServiceType.QBittorrent -> Qbit.setAltSpeed(s, enabled)
        ServiceType.Transmission -> Transmission.setAltSpeed(s, enabled)
        else -> throw ServiceException("${s.name} has no alternative speed limits", 400)
    }

    suspend fun add(
        s: ServiceConfig,
        links: List<String>,
        files: List<Qbit.TorrentFileUpload>,
        category: String?,
        savePath: String?,
        paused: Boolean,
    ) = when (s.serviceType) {
        ServiceType.QBittorrent -> Qbit.add(s, links, files, category, savePath, paused)
        ServiceType.Transmission -> Transmission.add(s, links, files, category, savePath, paused)
        ServiceType.Deluge -> Deluge.add(s, links, files, category, savePath, paused)
        else -> unsupported(s)
    }
}
