package app.bosunarr.feature.torrent

import app.bosunarr.app
import app.bosunarr.data.ServiceConfig
import app.bosunarr.feature.activity.qbit.Qbit
import app.bosunarr.feature.activity.qbit.QbitServer
import app.bosunarr.feature.activity.qbit.QbitSnapshot
import app.bosunarr.feature.activity.qbit.Torrent
import app.bosunarr.feature.activity.qbit.TorrentDetails
import app.bosunarr.feature.activity.qbit.TorrentFile
import app.bosunarr.feature.activity.qbit.TorrentProperties
import app.bosunarr.feature.activity.qbit.TorrentTracker
import app.bosunarr.net.AppJson
import app.bosunarr.net.ServiceException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Deluge 2.x through its Web UI's JSON-RPC (`POST /json`, `{"method", "params", "id"}` → `{"result", "error", "id"}`).
 * The cookie login (`auth.login`, password only) lives in the core client. The Web UI proxies `core.*`/`label.*`
 * to a daemon only while it is connected to one, so the list call reconnects when the Web UI reports it isn't.
 *
 * Torrents are mapped onto the qBittorrent [Torrent] model; the Label plugin's label is the "category".
 */
object Deluge {
    private val ids = AtomicLong()
    private val locks = ConcurrentHashMap<String, Mutex>()

    internal val LIST_KEYS = listOf(
        "name", "hash", "state", "progress", "download_payload_rate", "upload_payload_rate", "eta", "total_wanted",
        "total_size", "total_done", "total_uploaded", "all_time_download", "ratio", "time_added", "completed_time",
        "num_seeds", "total_seeds", "num_peers", "total_peers", "download_location", "save_path", "tracker_host",
        "label", "active_time", "seeding_time", "distributed_copies", "is_finished",
    )

    private val DETAIL_KEYS = listOf(
        "files", "file_progress", "file_priorities", "trackers", "tracker_host", "tracker_status", "comment", "creator",
        "num_pieces", "piece_length", "total_done", "total_payload_download", "total_uploaded", "all_time_download",
        "ratio", "time_added", "completed_time", "active_time", "seeding_time", "next_announce", "download_location",
        "save_path", "total_size", "max_connections", "num_seeds", "num_peers", "last_seen_complete",
        "max_download_speed", "max_upload_speed",
    )

    // ------------------------------------------------------------------ transport

    private suspend fun call(s: ServiceConfig, method: String, vararg params: JsonElement): JsonElement {
        val body = buildJsonObject {
            put("method", method)
            put("params", JsonArray(params.toList()))
            put("id", ids.incrementAndGet())
        }
        return parseResponse(s.name, method, app.client.send(s, "/json", "POST", body))
    }

    /** The `result` of an answer (JsonNull for none); throws a readable error for `error`. */
    internal fun parseResponse(name: String, method: String, text: String): JsonElement {
        if (text.trimStart().startsWith("<")) throw ServiceException("$name answered with a web page instead of its API — check the address")
        val o = runCatching { AppJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: throw ServiceException("$name gave an unexpected answer — is this really the Deluge Web UI?")
        if (!o.containsKey("result") && !o.containsKey("error")) throw ServiceException("$name gave an unexpected answer — is this really the Deluge Web UI?")
        val err = o["error"].jObj()
        if (err != null) {
            val msg = err["message"].jText().orEmpty()
            throw when (err["code"].jInt()) {
                1 -> ServiceException("$name rejected the credentials", 401)
                // Remote methods only exist while the Web UI is connected to a daemon (and label.* with the plugin on).
                2 -> if (method.startsWith("label.")) ServiceException("$name: the Label plugin isn't enabled", 400)
                else ServiceException(notConnected(name), 503)
                else -> ServiceException("$name: ${msg.substringAfter(": ").ifBlank { msg }.take(160).ifBlank { "the request failed" }}", 400)
            }
        }
        return o["result"] ?: JsonNull
    }

    internal fun notConnected(name: String) =
        "$name's Web UI isn't connected to a Deluge daemon — connect one in the Web UI's Connection Manager"

    private fun str(v: String) = JsonPrimitive(v)
    private fun strs(v: Collection<String>) = JsonArray(v.map { JsonPrimitive(it) })

    // ------------------------------------------------------------------ daemon connection

    /**
     * Connect the Web UI to a daemon: the only one in its host list, or else the first one that is online.
     * `web.get_hosts` → `[[id, host, port, user], …]`; `web.get_host_status(id)` → `[id, "Online"|"Offline"|"Connected", version]`.
     */
    private suspend fun connect(s: ServiceConfig) = locks.getOrPut(s.id) { Mutex() }.withLock {
        if (call(s, "web.connected").jBool() == true) return@withLock
        val hosts = call(s, "web.get_hosts").jArr().orEmpty().mapNotNull { it.jArr()?.firstOrNull().jText() }
        if (hosts.isEmpty()) throw ServiceException(notConnected(s.name), 503)
        val target = hosts.singleOrNull() ?: hosts.firstOrNull { id ->
            runCatching { call(s, "web.get_host_status", str(id)).jArr()?.getOrNull(1).jText() }.getOrNull()
                ?.let { it.equals("Online", true) || it.equals("Connected", true) } == true
        } ?: throw ServiceException(notConnected(s.name), 503)
        call(s, "web.connect", str(target))
        if (call(s, "web.connected").jBool() != true) throw ServiceException(notConnected(s.name), 503)
    }

    private suspend fun ensureConnected(s: ServiceConfig) {
        if (call(s, "web.connected").jBool() != true) connect(s)
    }

    /** "Test connection": log in, make sure a daemon is connected, and read its version. */
    suspend fun probe(s: ServiceConfig): String? {
        ensureConnected(s)
        return runCatching { call(s, "daemon.info").jText() }.getOrNull()
    }

    // ------------------------------------------------------------------ snapshot

    suspend fun snapshot(s: ServiceConfig): QbitSnapshot {
        val keys = strs(LIST_KEYS)
        var ui = call(s, "web.update_ui", keys, JsonObject(emptyMap())).jObj() ?: throw ServiceException("${s.name} gave an unexpected answer")
        if (ui["connected"].jBool() == false) {
            connect(s)
            ui = call(s, "web.update_ui", keys, JsonObject(emptyMap())).jObj() ?: throw ServiceException("${s.name} gave an unexpected answer")
            if (ui["connected"].jBool() == false) throw ServiceException(notConnected(s.name), 503)
        }
        val totals = runCatching {
            call(s, "core.get_session_status", strs(listOf("total_payload_download", "total_payload_upload"))).jObj()
        }.getOrNull()
        return withContext(Dispatchers.Default) { snapshotOf(s, ui, totals) }
    }

    /** `web.update_ui` → `{"connected", "torrents": {hash: status}, "filters": {"label": [[name, count], …]}, "stats": {…}}` */
    internal fun snapshotOf(s: ServiceConfig, ui: JsonObject, totals: JsonObject?): QbitSnapshot {
        val torrents = ui["torrents"].jObj()?.mapNotNull { (hash, el) -> el.jObj()?.let { torrentOf(s, hash, it) } }.orEmpty()
        val labels = (
            ui["filters"].jObj()?.get("label").jArr().orEmpty().mapNotNull { it.jArr()?.firstOrNull().jText() } +
                torrents.map { it.category }
            ).filter { it.isNotBlank() && it != "All" && !it.equals("No Label", true) }
            .distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
        return QbitSnapshot(
            serviceId = s.id,
            serviceName = s.name,
            server = serverOf(ui["stats"].jObj(), totals, torrents),
            torrents = torrents,
            categories = labels,
            tags = emptyList(),
        )
    }

    /** States: Downloading, Seeding, Paused, Checking, Queued, Error, Allocating, Moving. `progress` is 0–100. */
    internal fun torrentOf(s: ServiceConfig, hash: String, o: JsonObject): Torrent {
        val pct = ((o["progress"].jDbl() ?: 0.0) / 100.0).coerceIn(0.0, 1.0)
        val done = pct >= 1.0 || o["is_finished"].jBool() == true
        val dl = (o["download_payload_rate"].jLng() ?: 0).coerceAtLeast(0)
        val ul = (o["upload_payload_rate"].jLng() ?: 0).coerceAtLeast(0)
        val total = o["total_size"].jLng() ?: 0
        val wanted = o["total_wanted"].jLng()?.takeIf { it > 0 } ?: total
        val state = when (o["state"].jText()) {
            "Downloading" -> when {
                total == 0L -> "metaDL"
                dl == 0L -> "stalledDL"
                else -> "downloading"
            }
            "Seeding" -> if (ul > 0) "uploading" else "stalledUP"
            "Paused" -> if (done) "stoppedUP" else "stoppedDL"
            "Checking" -> if (done) "checkingUP" else "checkingDL"
            "Queued" -> if (done) "queuedUP" else "queuedDL"
            "Error" -> "error"
            "Allocating" -> "allocating"
            "Moving" -> "moving"
            else -> "unknown"
        }
        val id = (o["hash"].jText() ?: hash).lowercase()
        val doneBytes = o["total_done"].jLng() ?: 0
        return Torrent(
            serviceId = s.id,
            serviceName = s.name,
            hash = id,
            name = o["name"].jText()?.takeIf { it.isNotBlank() } ?: id,
            progress = if (done && o["state"].jText() != "Checking") 1.0 else pct,
            dlspeed = dl,
            upspeed = ul,
            eta = o["eta"].jLng()?.takeIf { it > 0 } ?: NO_ETA,
            state = state,
            size = wanted,
            downloaded = o["all_time_download"].jLng()?.takeIf { it > 0 } ?: doneBytes,
            uploaded = o["total_uploaded"].jLng() ?: 0,
            ratio = (o["ratio"].jDbl() ?: 0.0).coerceAtLeast(0.0),
            category = o["label"].jText().orEmpty(),
            tags = emptyList(),
            addedOn = o["time_added"].jLng() ?: 0,
            completedOn = (o["completed_time"].jLng() ?: 0).coerceAtLeast(0),
            seeds = o["num_seeds"].jInt() ?: 0,
            peers = o["num_peers"].jInt() ?: 0,
            seedsTotal = o["total_seeds"].jInt() ?: -1,
            peersTotal = o["total_peers"].jInt() ?: -1,
            savePath = o["download_location"].jText() ?: o["save_path"].jText().orEmpty(),
            amountLeft = (wanted - doneBytes).coerceAtLeast(0),
            tracker = o["tracker_host"].jText().orEmpty(),
            magnetUri = "",
            timeActive = o["active_time"].jLng() ?: 0,
            seedingTime = o["seeding_time"].jLng() ?: 0,
            availability = o["distributed_copies"].jDbl() ?: -1.0,
        )
    }

    /** `stats` of web.update_ui: rates in B/s, limits in KiB/s (-1 = unlimited). */
    internal fun serverOf(stats: JsonObject?, totals: JsonObject?, torrents: List<Torrent>): QbitServer {
        fun limit(key: String) = (stats?.get(key).jDbl() ?: -1.0).let { if (it > 0) (it * 1024).toLong() else 0L }
        return QbitServer(
            down = stats?.get("download_rate").jLng() ?: torrents.sumOf { it.dlspeed },
            up = stats?.get("upload_rate").jLng() ?: torrents.sumOf { it.upspeed },
            sessionDown = totals?.get("total_payload_download").jLng() ?: 0,
            sessionUp = totals?.get("total_payload_upload").jLng() ?: 0,
            allTimeDown = 0,
            allTimeUp = 0,
            ratio = null,
            freeSpace = stats?.get("free_space").jLng()?.takeIf { it >= 0 },
            dhtNodes = stats?.get("dht_nodes").jLng() ?: 0,
            peers = stats?.get("num_connections").jLng() ?: 0,
            connection = when (stats?.get("has_incoming_connections").jBool()) {
                true -> "connected"
                false -> "firewalled"
                null -> "unknown"
            },
            downLimit = limit("max_download"),
            upLimit = limit("max_upload"),
            altSpeed = false,
        )
    }

    // ------------------------------------------------------------------ actions

    suspend fun start(s: ServiceConfig, hashes: Collection<String>) { call(s, "core.resume_torrent", strs(hashes)) }
    suspend fun stop(s: ServiceConfig, hashes: Collection<String>) { call(s, "core.pause_torrent", strs(hashes)) }
    suspend fun startAll(s: ServiceConfig) { all(s).takeIf { it.isNotEmpty() }?.let { start(s, it) } }
    suspend fun stopAll(s: ServiceConfig) { all(s).takeIf { it.isNotEmpty() }?.let { stop(s, it) } }
    suspend fun recheck(s: ServiceConfig, hashes: Collection<String>) { call(s, "core.force_recheck", strs(hashes)) }
    suspend fun reannounce(s: ServiceConfig, hashes: Collection<String>) { call(s, "core.force_reannounce", strs(hashes)) }

    private suspend fun all(s: ServiceConfig): List<String> =
        call(s, "core.get_session_state").jArr().orEmpty().mapNotNull { it.jText() }

    suspend fun delete(s: ServiceConfig, hashes: Collection<String>, deleteFiles: Boolean) {
        hashes.forEach { call(s, "core.remove_torrent", str(it), JsonPrimitive(deleteFiles)) }
    }

    /** Label plugin: an empty label removes it. */
    suspend fun setCategory(s: ServiceConfig, hashes: Collection<String>, category: String) {
        hashes.forEach { call(s, "label.set_torrent", str(it), str(category)) }
    }

    suspend fun categories(s: ServiceConfig): List<String> = runCatching {
        call(s, "label.get_labels").jArr().orEmpty().mapNotNull { it.jText()?.takeIf { l -> l.isNotBlank() } }
    }.getOrDefault(emptyList()).sortedWith(String.CASE_INSENSITIVE_ORDER)

    /** Deluge priorities: 0 skip, 1 low, 4 normal, 7 high; set as the whole list via set_torrent_options. */
    suspend fun setFilePriority(s: ServiceConfig, hash: String, indexes: Collection<Int>, priority: Int) {
        val status = call(s, "core.get_torrent_status", str(hash), strs(listOf("file_priorities"))).jObj()
        val current = status?.get("file_priorities").jArr()?.map { it.jInt() ?: 4 } ?: throw ServiceException("${s.name} gave an unexpected answer")
        val target = toDelugePriority(priority)
        val updated = current.mapIndexed { i, p -> if (i in indexes) target else p }
        call(s, "core.set_torrent_options", strs(listOf(hash)), buildJsonObject { put("file_priorities", JsonArray(updated.map { JsonPrimitive(it) })) })
    }

    internal fun toDelugePriority(qbit: Int) = when {
        qbit <= 0 -> 0
        qbit >= 7 -> 7
        qbit >= 6 -> 6
        else -> 4
    }

    internal fun fromDelugePriority(p: Int) = when {
        p <= 0 -> 0
        p >= 7 -> 7
        p >= 5 -> 6
        else -> 1
    }

    suspend fun add(
        s: ServiceConfig,
        links: List<String>,
        files: List<Qbit.TorrentFileUpload>,
        category: String?,
        savePath: String?,
        paused: Boolean,
    ) {
        ensureConnected(s)
        val options = buildJsonObject {
            put("add_paused", paused)
            if (!savePath.isNullOrBlank()) put("download_location", savePath.trim())
        }
        val added = mutableListOf<String>()
        for (link in links) {
            val r = if (link.startsWith("magnet:", ignoreCase = true)) call(s, "core.add_torrent_magnet", str(link), options)
            else call(s, "core.add_torrent_url", str(link), options)
            r.jText()?.let { added += it }
        }
        for (f in files) {
            call(s, "core.add_torrent_file", str(f.name), str(Base64.getEncoder().encodeToString(f.bytes)), options).jText()?.let { added += it }
        }
        if (!category.isNullOrEmpty() && added.isNotEmpty()) setCategory(s, added, category)
    }

    // ------------------------------------------------------------------ details

    suspend fun details(s: ServiceConfig, hash: String): TorrentDetails {
        val st = call(s, "core.get_torrent_status", str(hash), strs(DETAIL_KEYS)).jObj()
        if (st == null || st.isEmpty()) throw ServiceException("${s.name} no longer has this torrent", 404)
        return withContext(Dispatchers.Default) { detailsOf(st) }
    }

    internal fun detailsOf(t: JsonObject): TorrentDetails {
        val active = t["active_time"].jLng() ?: 0
        val seeding = t["seeding_time"].jLng() ?: 0
        val downloaded = t["all_time_download"].jLng()?.takeIf { it > 0 } ?: t["total_payload_download"].jLng() ?: t["total_done"].jLng() ?: 0
        val uploaded = t["total_uploaded"].jLng() ?: 0
        fun limit(key: String) = (t[key].jDbl() ?: -1.0).let { if (it > 0) (it * 1024).toLong() else -1L }
        val props = TorrentProperties(
            savePath = t["download_location"].jText() ?: t["save_path"].jText().orEmpty(),
            creationDate = -1,
            pieceSize = t["piece_length"].jLng() ?: -1,
            comment = t["comment"].jText().orEmpty(),
            totalUploaded = uploaded,
            totalDownloaded = downloaded,
            upLimit = limit("max_upload_speed"),
            dlLimit = limit("max_download_speed"),
            timeElapsed = active,
            seedingTime = seeding,
            connections = (t["num_seeds"].jInt() ?: 0) + (t["num_peers"].jInt() ?: 0),
            connectionsLimit = t["max_connections"].jInt()?.coerceAtLeast(0) ?: 0,
            shareRatio = (t["ratio"].jDbl() ?: 0.0).coerceAtLeast(0.0),
            additionDate = t["time_added"].jLng()?.takeIf { it > 0 } ?: -1,
            completionDate = t["completed_time"].jLng()?.takeIf { it > 0 } ?: -1,
            createdBy = t["creator"].jText().orEmpty(),
            dlSpeedAvg = if (active - seeding > 0) downloaded / (active - seeding) else 0,
            upSpeedAvg = if (active > 0) uploaded / active else 0,
            lastSeen = t["last_seen_complete"].jLng()?.takeIf { it > 0 } ?: -1,
            // Deluge reports the piece count but not how many are done.
            piecesHave = -1,
            piecesNum = -1,
            reannounce = t["next_announce"].jLng()?.coerceAtLeast(0) ?: 0,
            totalSize = t["total_size"].jLng() ?: -1,
        )
        val host = t["tracker_host"].jText().orEmpty()
        val trackerStatus = t["tracker_status"].jText().orEmpty()
        val trackers = t["trackers"].jArr().orEmpty().mapNotNull { el ->
            val tr = el.jObj() ?: return@mapNotNull null
            val url = tr["url"].jText() ?: return@mapNotNull null
            // Deluge only reports the status of the tracker it last talked to.
            val current = host.isNotEmpty() && url.contains(host, ignoreCase = true)
            val failing = (tr["fails"].jInt() ?: 0) > 0 || (current && trackerStatus.startsWith("Error", true))
            val status = when {
                tr["updating"].jBool() == true -> 3
                failing -> 4
                tr["verified"].jBool() == true || (current && trackerStatus.contains("OK", true)) -> 2
                else -> 1
            }
            TorrentTracker(
                url = url,
                status = status,
                tier = tr["tier"]?.let { JsonPrimitive(it.jInt() ?: 0) },
                seeds = tr["scrape_complete"].jInt() ?: -1,
                leeches = tr["scrape_incomplete"].jInt() ?: -1,
                downloaded = tr["scrape_downloaded"].jInt() ?: -1,
                msg = (tr["message"].jText()?.takeIf { it.isNotBlank() } ?: if (current) trackerStatus.takeIf { !it.contains("OK", true) } else null).orEmpty(),
            )
        }
        val progress = t["file_progress"].jArr().orEmpty()
        val prios = t["file_priorities"].jArr().orEmpty()
        val files = t["files"].jArr().orEmpty().mapIndexedNotNull { i, el ->
            val f = el.jObj() ?: return@mapIndexedNotNull null
            val index = f["index"].jInt() ?: i
            TorrentFile(
                index = index,
                name = f["path"].jText().orEmpty(),
                size = f["size"].jLng() ?: 0,
                progress = (progress.getOrNull(index).jDbl() ?: 0.0).coerceIn(0.0, 1.0),
                priority = fromDelugePriority(prios.getOrNull(index).jInt() ?: 4),
            )
        }
        return TorrentDetails(props, trackers, files)
    }
}
