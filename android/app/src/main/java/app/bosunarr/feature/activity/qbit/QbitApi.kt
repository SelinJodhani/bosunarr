package app.bosunarr.feature.activity.qbit

import app.bosunarr.app
import app.bosunarr.data.ServiceConfig
import app.bosunarr.net.AppJson
import app.bosunarr.net.ServiceException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * qBittorrent over its Web API. Login/cookies are handled by the core client.
 *
 * The torrent list comes from `/sync/maindata`, which after the first call only sends what changed since the
 * previous response id (`rid`). Polling every couple of seconds over mobile data then costs a few hundred bytes
 * instead of the whole torrent list each time. The merged state is kept here per service and shared by every
 * screen (Activity, the service screen) so they never fetch twice.
 */
object Qbit {
    private class SyncState(
        var rid: Long = 0,
        val torrents: HashMap<String, JsonObject> = HashMap(),
        val decoded: HashMap<String, Torrent> = HashMap(),
        val server: HashMap<String, JsonElement> = HashMap(),
        val categories: HashMap<String, JsonElement> = HashMap(),
        val tags: MutableSet<String> = HashSet(),
        var name: String = "",
        var okAt: Long = 0,
        var last: QbitSnapshot? = null,
    )

    private val states = ConcurrentHashMap<String, SyncState>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** How long the last good list keeps being shown after a sync fails (a blip shouldn't empty the screen). */
    private const val STALE_MS = 30_000L

    /**
     * Sync with one qBittorrent. On failure, returns the last good snapshot (marked with the error) for a short
     * while, then rethrows.
     */
    // Decoding a big first sync is real work: keep it off the main thread (loaders run on Main).
    suspend fun snapshot(s: ServiceConfig): QbitSnapshot = withContext(Dispatchers.Default) { syncLocked(s) }

    private suspend fun syncLocked(s: ServiceConfig): QbitSnapshot = locks.getOrPut(s.id) { Mutex() }.withLock {
        val prev = states[s.id]
        try {
            val md = app.client.json(s, "/api/v2/sync/maindata?rid=${prev?.rid ?: 0}") as? JsonObject
                ?: throw ServiceException("${s.name} gave an unexpected answer")
            val rid = (md["rid"] as? JsonPrimitive)?.longOrNull ?: throw ServiceException("${s.name} gave an unexpected answer")
            val full = (md["full_update"] as? JsonPrimitive)?.booleanOrNull == true || prev == null
            val st = if (full || prev == null) SyncState() else prev
            if (st.name != s.name) st.decoded.clear() // renamed: rows carry the service name
            st.rid = rid
            st.name = s.name
            merge(st, md, s)
            val snap = build(st, s)
            st.okAt = System.currentTimeMillis()
            st.last = snap
            states[s.id] = st
            snap
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val last = prev?.last
            if (last != null && System.currentTimeMillis() - prev.okAt < STALE_MS) {
                last.copy(error = e.message ?: "${s.name} is unreachable", server = last.server.copy(down = 0, up = 0))
            } else throw e
        }
    }

    /** Forget the synced state (next call does a full sync). */
    fun reset(serviceId: String) { states.remove(serviceId) }

    private fun merge(st: SyncState, md: JsonObject, s: ServiceConfig) {
        (md["torrents"] as? JsonObject)?.forEach { (hash, el) ->
            val patch = el as? JsonObject ?: return@forEach
            val old = st.torrents[hash]
            st.torrents[hash] = if (old == null) patch else JsonObject(old + patch)
            st.decoded.remove(hash)
        }
        (md["torrents_removed"] as? JsonArray)?.forEach { el ->
            val h = (el as? JsonPrimitive)?.content ?: return@forEach
            st.torrents.remove(h); st.decoded.remove(h)
        }
        (md["server_state"] as? JsonObject)?.let { st.server.putAll(it) }
        (md["categories"] as? JsonObject)?.forEach { (name, el) ->
            val old = st.categories[name] as? JsonObject
            val patch = el as? JsonObject
            st.categories[name] = if (old != null && patch != null) JsonObject(old + patch) else el
        }
        (md["categories_removed"] as? JsonArray)?.forEach { el -> (el as? JsonPrimitive)?.content?.let { st.categories.remove(it) } }
        (md["tags"] as? JsonArray)?.forEach { el -> (el as? JsonPrimitive)?.content?.let { st.tags.add(it) } }
        (md["tags_removed"] as? JsonArray)?.forEach { el -> (el as? JsonPrimitive)?.content?.let { st.tags.remove(it) } }
    }

    private fun build(st: SyncState, s: ServiceConfig): QbitSnapshot {
        // Only torrents that changed since the last sync are decoded again; unchanged ones keep their
        // instance, so Compose can skip their rows.
        val list = ArrayList<Torrent>(st.torrents.size)
        for ((hash, obj) in st.torrents) {
            val t = st.decoded[hash] ?: runCatching {
                val raw = AppJson.decodeFromJsonElement<RawTorrent>(obj)
                Torrent.of(s.id, s.name, raw.copy(hash = hash))
            }.getOrNull()?.also { st.decoded[hash] = it } ?: continue
            list += t
        }
        val ss = runCatching { AppJson.decodeFromJsonElement<RawServerState>(JsonObject(st.server.filterValues { it !is JsonNull })) }.getOrDefault(RawServerState())
        // The client's global rate is the truth (it includes overhead), but some setups hand back zeros while
        // torrents are moving; fall back to the per-torrent sum then.
        val down = if (ss.dlSpeed > 0) ss.dlSpeed else list.sumOf { it.dlspeed }
        val up = if (ss.upSpeed > 0) ss.upSpeed else list.sumOf { it.upspeed }
        val server = QbitServer(
            down = down, up = up,
            sessionDown = ss.dlData, sessionUp = ss.upData,
            allTimeDown = ss.allTimeDl, allTimeUp = ss.allTimeUl,
            ratio = ss.globalRatio?.toDoubleOrNull(),
            freeSpace = ss.freeSpace,
            dhtNodes = ss.dhtNodes, peers = ss.peers,
            connection = ss.connectionStatus,
            downLimit = ss.dlRateLimit.coerceAtLeast(0), upLimit = ss.upRateLimit.coerceAtLeast(0),
            altSpeed = ss.altSpeed,
        )
        return QbitSnapshot(
            serviceId = s.id,
            serviceName = s.name,
            server = server,
            torrents = list,
            categories = st.categories.keys.sortedWith(String.CASE_INSENSITIVE_ORDER),
            tags = st.tags.sortedWith(String.CASE_INSENSITIVE_ORDER),
        )
    }

    // ------------------------------------------------------------------ actions

    private suspend fun post(s: ServiceConfig, name: String, fields: Map<String, String>, fallback: String? = null): String = try {
        app.client.form(s, "/api/v2/torrents/$name", fields)
    } catch (e: ServiceException) {
        if (fallback != null && e.status == 404) app.client.form(s, "/api/v2/torrents/$fallback", fields) else throw e
    }

    private fun hashes(list: Collection<String>) = list.joinToString("|")

    /** qBittorrent 5 renamed pause/resume to stop/start; try the new name, fall back to the old one. */
    suspend fun stop(s: ServiceConfig, hashes: Collection<String>) { post(s, "stop", mapOf("hashes" to hashes(hashes)), "pause") }
    suspend fun start(s: ServiceConfig, hashes: Collection<String>) { post(s, "start", mapOf("hashes" to hashes(hashes)), "resume") }
    suspend fun stopAll(s: ServiceConfig) { post(s, "stop", mapOf("hashes" to "all"), "pause") }
    suspend fun startAll(s: ServiceConfig) { post(s, "start", mapOf("hashes" to "all"), "resume") }
    suspend fun recheck(s: ServiceConfig, hashes: Collection<String>) { post(s, "recheck", mapOf("hashes" to hashes(hashes))) }
    suspend fun reannounce(s: ServiceConfig, hashes: Collection<String>) { post(s, "reannounce", mapOf("hashes" to hashes(hashes))) }
    suspend fun delete(s: ServiceConfig, hashes: Collection<String>, deleteFiles: Boolean) {
        post(s, "delete", mapOf("hashes" to hashes(hashes), "deleteFiles" to deleteFiles.toString()))
    }
    suspend fun setCategory(s: ServiceConfig, hashes: Collection<String>, category: String) {
        post(s, "setCategory", mapOf("hashes" to hashes(hashes), "category" to category))
    }
    suspend fun setFilePriority(s: ServiceConfig, hash: String, indexes: Collection<Int>, priority: Int) {
        post(s, "filePrio", mapOf("hash" to hash, "id" to indexes.joinToString("|"), "priority" to priority.toString()))
    }

    suspend fun categories(s: ServiceConfig): List<String> {
        val el = app.client.json(s, "/api/v2/torrents/categories") as? JsonObject ?: return emptyList()
        return el.keys.sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    suspend fun details(s: ServiceConfig, hash: String): TorrentDetails = withContext(Dispatchers.Default) { detailsOf(s, hash) }

    private suspend fun detailsOf(s: ServiceConfig, hash: String): TorrentDetails {
        val q = "hash=" + URLEncoder.encode(hash, "UTF-8")
        val props = runCatching { app.client.get<TorrentProperties>(s, "/api/v2/torrents/properties?$q") }
        val trackers = runCatching { app.client.get<List<TorrentTracker>>(s, "/api/v2/torrents/trackers?$q") }
        val files = runCatching { app.client.get<List<TorrentFile>>(s, "/api/v2/torrents/files?$q") }
        listOf(props, trackers, files).forEach { r -> (r.exceptionOrNull() as? CancellationException)?.let { throw it } }
        if (props.isFailure && trackers.isFailure && files.isFailure) throw props.exceptionOrNull()!!
        return TorrentDetails(
            properties = props.getOrNull(),
            trackers = trackers.getOrDefault(emptyList()),
            files = files.getOrDefault(emptyList()).mapIndexed { i, f -> if (f.index < 0) f.copy(index = i) else f },
        )
    }

    /**
     * Turn alternative speed limits on or off. qBittorrent 5+ has `setSpeedLimitsMode` (sets the mode directly, so
     * two taps can't cancel out); older versions only toggle, so read the mode first and toggle only if it differs.
     */
    suspend fun setAltSpeed(s: ServiceConfig, enabled: Boolean) {
        try {
            app.client.form(s, "/api/v2/transfer/setSpeedLimitsMode", mapOf("mode" to if (enabled) "1" else "0"))
        } catch (e: ServiceException) {
            if (e.status != 404) throw e
            val mode = app.client.text(s, "/api/v2/transfer/speedLimitsMode").trim()
            if (mode != "0" && mode != "1") throw ServiceException("${s.name} gave an unexpected answer")
            if ((mode == "1") != enabled) app.client.form(s, "/api/v2/transfer/toggleSpeedLimitsMode", emptyMap())
        }
    }

    /** A .torrent file picked on the phone. */
    class TorrentFileUpload(val name: String, val bytes: ByteArray)

    private val MAGNET = Regex("^magnet:\\?.*xt=urn:bt(ih|mh):", RegexOption.IGNORE_CASE)
    private val HTTP = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE)
    private val BARE_HASH = Regex("^[a-fA-F0-9]{40}$")

    /** Split pasted text into links; bare 40-hex info hashes become magnet links. Throws with a readable reason. */
    fun parseLinks(text: String): List<String> {
        val lines = text.split('\n', '\r').map { it.trim() }.filter { it.isNotEmpty() }
        return lines.map { l ->
            when {
                MAGNET.containsMatchIn(l) || HTTP.matches(l) -> l
                BARE_HASH.matches(l) -> "magnet:?xt=urn:btih:${l.lowercase()}"
                else -> throw ServiceException("Not a magnet link or http(s) URL: ${l.take(60)}", 400)
            }
        }
    }

    suspend fun add(
        s: ServiceConfig,
        links: List<String>,
        files: List<TorrentFileUpload>,
        category: String?,
        savePath: String?,
        paused: Boolean,
    ) {
        val fields = buildMap {
            if (links.isNotEmpty()) put("urls", links.joinToString("\n"))
            if (!category.isNullOrBlank()) put("category", category)
            if (!savePath.isNullOrBlank()) put("savepath", savePath.trim())
            if (paused) {
                put("paused", "true") // 4.x
                put("stopped", "true") // 5.x
            }
        }
        val res = if (files.isEmpty()) {
            app.client.form(s, "/api/v2/torrents/add", fields)
        } else {
            val type = "application/x-bittorrent".toMediaType()
            val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
                fields.forEach { (k, v) -> addFormDataPart(k, v) }
                files.forEach { f -> addFormDataPart("torrents", f.name, f.bytes.toRequestBody(type)) }
            }.build()
            app.client.multipart(s, "/api/v2/torrents/add", body)
        }
        if (res.contains("fail", ignoreCase = true)) throw ServiceException("qBittorrent rejected the torrent", 400)
    }
}
