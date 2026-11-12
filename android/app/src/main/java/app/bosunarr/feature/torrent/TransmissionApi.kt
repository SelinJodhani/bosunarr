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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Transmission over its JSON RPC (`{"method", "arguments", "tag"}` → `{"result": "success", "arguments"}`).
 * The session-id handshake (409) and basic auth live in the core client.
 *
 * Torrents are mapped onto the qBittorrent [Torrent] model (its state names drive the shared list UI), with
 * Transmission's labels as tags and the first label as the "category". Ids are info hashes, which every
 * Transmission method accepts in `ids`.
 *
 * Polling asks for `ids: "recently-active"` between full syncs, so an idle list costs a few bytes per poll.
 */
object Transmission {
    private val tags = AtomicLong()

    private class SyncState(
        val byId: HashMap<Long, Torrent> = HashMap(),
        var fullAt: Long = 0,
        var name: String = "",
        var session: JsonObject? = null,
        var freeSpace: Long? = null,
        var sessionAt: Long = 0,
    )

    private val states = ConcurrentHashMap<String, SyncState>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** A full list at least this often; incremental (`recently-active`) syncs in between. */
    private const val FULL_SYNC_MS = 30_000L
    /** session-get + free-space change rarely: refresh them this often (and after an alt-speed switch). */
    private const val SESSION_MS = 15_000L

    internal val LIST_FIELDS = listOf(
        "id", "hashString", "name", "status", "percentDone", "rateDownload", "rateUpload", "eta", "totalSize",
        "sizeWhenDone", "leftUntilDone", "downloadedEver", "uploadedEver", "uploadRatio", "error", "errorString",
        "addedDate", "doneDate", "labels", "downloadDir", "peersConnected", "peersSendingToUs", "peersGettingFromUs",
        "magnetLink", "secondsDownloading", "secondsSeeding", "metadataPercentComplete",
    )

    private val DETAIL_FIELDS = listOf(
        "hashString", "files", "fileStats", "trackerStats", "pieceCount", "pieceSize", "pieces", "comment", "creator",
        "dateCreated", "downloadDir", "corruptEver", "downloadedEver", "uploadedEver", "uploadRatio", "peersConnected",
        "peer-limit", "secondsDownloading", "secondsSeeding", "addedDate", "doneDate", "totalSize", "activityDate",
        "downloadLimit", "downloadLimited", "uploadLimit", "uploadLimited",
    )

    /**
     * The RPC path to add to the service URL: none when the URL already is the RPC endpoint (`…/rpc`), `/rpc` when it
     * ends at `/transmission`, else the default `/transmission/rpc`.
     */
    internal fun rpcPath(url: String): String {
        val u = url.trimEnd('/')
        return when {
            u.endsWith("/rpc") -> ""
            u.endsWith("/transmission") -> "/rpc"
            else -> "/transmission/rpc"
        }
    }

    // ------------------------------------------------------------------ transport

    private suspend fun call(s: ServiceConfig, method: String, args: JsonObject? = null): JsonObject {
        val body = buildJsonObject {
            put("method", method)
            if (args != null) put("arguments", args)
            put("tag", tags.incrementAndGet())
        }
        return parseResponse(s.name, app.client.send(s, rpcPath(s.url), "POST", body))
    }

    /** The `arguments` of a successful answer; throws with Transmission's own message otherwise. */
    internal fun parseResponse(name: String, text: String): JsonObject {
        if (text.trimStart().startsWith("<")) throw ServiceException("$name answered with a web page instead of its API — check the address")
        val o = runCatching { AppJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: throw ServiceException("$name gave an unexpected answer")
        val result = o["result"].jText() ?: throw ServiceException("$name gave an unexpected answer — is this really Transmission?")
        if (result != "success") throw ServiceException("$name: ${result.take(160)}", 400)
        return o["arguments"].jObj() ?: JsonObject(emptyMap())
    }

    private fun ids(hashes: Collection<String>) = JsonArray(hashes.map { JsonPrimitive(it) })
    private fun strings(vararg v: String) = JsonArray(v.map { JsonPrimitive(it) })
    private fun strings(v: List<String>) = JsonArray(v.map { JsonPrimitive(it) })

    // ------------------------------------------------------------------ snapshot

    /** "Test connection": a session-get that also proves this is Transmission. */
    suspend fun probe(s: ServiceConfig): String? {
        val a = call(s, "session-get", buildJsonObject { put("fields", strings("version", "rpc-version")) })
        if (a["rpc-version"] == null && a["version"] == null) throw ServiceException("${s.name} gave an unexpected answer — is this really Transmission?")
        return a["version"].jText()?.substringBefore(' ')
    }

    suspend fun snapshot(s: ServiceConfig): QbitSnapshot = withContext(Dispatchers.Default) {
        locks.getOrPut(s.id) { Mutex() }.withLock { sync(s) }
    }

    fun reset(serviceId: String) { states.remove(serviceId) }

    private suspend fun sync(s: ServiceConfig): QbitSnapshot {
        val now = System.currentTimeMillis()
        val prev = states[s.id]
        val full = prev == null || prev.name != s.name || now - prev.fullAt > FULL_SYNC_MS
        val st = if (full || prev == null) SyncState() else prev
        val args = buildJsonObject {
            put("fields", strings(LIST_FIELDS))
            if (!full) put("ids", "recently-active")
        }
        val tg = call(s, "torrent-get", args)
        mergeTorrents(st.byId, tg, s)
        if (full) st.fullAt = now
        st.name = s.name
        val stats = call(s, "session-stats")
        if (st.session == null || now - st.sessionAt > SESSION_MS) {
            val session = call(s, "session-get")
            st.session = session
            st.freeSpace = freeSpace(s, session)
            st.sessionAt = now
        }
        states[s.id] = st
        val list = st.byId.values.toList()
        val labels = list.flatMap { it.tags }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
        return QbitSnapshot(
            serviceId = s.id,
            serviceName = s.name,
            server = serverOf(stats, st.session, list, st.freeSpace),
            torrents = list,
            categories = labels,
            tags = labels,
        )
    }

    private suspend fun freeSpace(s: ServiceConfig, session: JsonObject): Long? {
        // Older versions report it in session-get; 4.x prefers the free-space method.
        session["download-dir-free-space"].jLng()?.takeIf { it >= 0 }?.let { return it }
        val dir = session["download-dir"].jText() ?: return null
        return runCatching { call(s, "free-space", buildJsonObject { put("path", dir) })["size-bytes"].jLng() }.getOrNull()?.takeIf { it >= 0 }
    }

    /** Apply a torrent-get answer (full or `recently-active`, which also lists `removed` ids) to [byId]. */
    internal fun mergeTorrents(byId: MutableMap<Long, Torrent>, args: JsonObject, s: ServiceConfig) {
        args["removed"].jArr()?.forEach { el -> el.jLng()?.let { byId.remove(it) } }
        args["torrents"].jArr()?.forEach { el ->
            val o = el.jObj() ?: return@forEach
            val id = o["id"].jLng() ?: return@forEach
            torrentOf(s, o)?.let { byId[id] = it }
        }
    }

    /**
     * One torrent-get entry as a [Torrent]. Status: 0 stopped, 1 check-wait, 2 checking, 3 download-wait,
     * 4 downloading, 5 seed-wait, 6 seeding. `error` 3 is a local error (tracker warnings/errors don't stop it).
     */
    internal fun torrentOf(s: ServiceConfig, o: JsonObject): Torrent? {
        val hash = o["hashString"].jText()?.lowercase() ?: return null
        val pct = (o["percentDone"].jDbl() ?: 0.0).coerceIn(0.0, 1.0)
        val done = pct >= 1.0
        val dl = (o["rateDownload"].jLng() ?: 0).coerceAtLeast(0)
        val ul = (o["rateUpload"].jLng() ?: 0).coerceAtLeast(0)
        val meta = o["metadataPercentComplete"].jDbl() ?: 1.0
        val state = when {
            o["error"].jInt() == 3 -> "error"
            else -> when (o["status"].jInt()) {
                0 -> if (done) "stoppedUP" else "stoppedDL"
                1, 2 -> if (done) "checkingUP" else "checkingDL"
                3 -> "queuedDL"
                4 -> when {
                    meta < 1.0 -> "metaDL"
                    dl == 0L -> "stalledDL"
                    else -> "downloading"
                }
                5 -> "queuedUP"
                6 -> if (ul > 0) "uploading" else "stalledUP"
                else -> "unknown"
            }
        }
        val labels = o["labels"].jArr()?.mapNotNull { it.jText()?.trim()?.takeIf { l -> l.isNotEmpty() } }.orEmpty()
        val total = o["totalSize"].jLng() ?: 0
        val wanted = o["sizeWhenDone"].jLng()?.takeIf { it > 0 } ?: total
        val dlSecs = o["secondsDownloading"].jLng() ?: 0
        val seedSecs = o["secondsSeeding"].jLng() ?: 0
        return Torrent(
            serviceId = s.id,
            serviceName = s.name,
            hash = hash,
            name = o["name"].jText()?.takeIf { it.isNotBlank() } ?: hash,
            progress = pct,
            dlspeed = dl,
            upspeed = ul,
            eta = o["eta"].jLng()?.takeIf { it >= 0 } ?: NO_ETA,
            state = state,
            size = wanted,
            downloaded = o["downloadedEver"].jLng() ?: 0,
            uploaded = o["uploadedEver"].jLng() ?: 0,
            ratio = (o["uploadRatio"].jDbl() ?: 0.0).coerceAtLeast(0.0),
            category = labels.firstOrNull().orEmpty(),
            tags = labels,
            addedOn = o["addedDate"].jLng() ?: 0,
            completedOn = (o["doneDate"].jLng() ?: 0).coerceAtLeast(0),
            seeds = o["peersSendingToUs"].jInt() ?: 0,
            peers = o["peersGettingFromUs"].jInt() ?: 0,
            // Swarm sizes only come with trackerStats, which is too heavy to poll for every torrent.
            seedsTotal = -1,
            peersTotal = -1,
            savePath = o["downloadDir"].jText().orEmpty(),
            amountLeft = o["leftUntilDone"].jLng() ?: 0,
            tracker = "",
            magnetUri = o["magnetLink"].jText().orEmpty(),
            timeActive = dlSecs + seedSecs,
            seedingTime = seedSecs,
            availability = -1.0,
        )
    }

    /** Transfer state from session-stats (+ session-get for limits, alt speed and units). */
    internal fun serverOf(stats: JsonObject, session: JsonObject?, torrents: List<Torrent>, freeSpace: Long?): QbitServer {
        val cur = stats["current-stats"].jObj()
        val cum = stats["cumulative-stats"].jObj()
        // Speed limits are in kB/s, where a kB is `units.speed-bytes` bytes (1000 unless changed).
        val unit = session?.get("units").jObj()?.get("speed-bytes").jLng()?.takeIf { it > 0 } ?: 1000L
        val alt = session?.get("alt-speed-enabled").jBool() == true
        fun limit(altKey: String, key: String, enabledKey: String): Long = when {
            session == null -> 0
            alt -> (session[altKey].jLng() ?: 0) * unit
            session[enabledKey].jBool() == true -> (session[key].jLng() ?: 0) * unit
            else -> 0
        }
        val allDown = cum?.get("downloadedBytes").jLng() ?: 0
        val allUp = cum?.get("uploadedBytes").jLng() ?: 0
        return QbitServer(
            down = stats["downloadSpeed"].jLng() ?: torrents.sumOf { it.dlspeed },
            up = stats["uploadSpeed"].jLng() ?: torrents.sumOf { it.upspeed },
            sessionDown = cur?.get("downloadedBytes").jLng() ?: 0,
            sessionUp = cur?.get("uploadedBytes").jLng() ?: 0,
            allTimeDown = allDown,
            allTimeUp = allUp,
            ratio = if (allDown > 0) allUp.toDouble() / allDown else null,
            freeSpace = freeSpace,
            dhtNodes = 0,
            peers = torrents.sumOf { (it.seeds + it.peers).toLong() },
            connection = "unknown",
            downLimit = limit("alt-speed-down", "speed-limit-down", "speed-limit-down-enabled").coerceAtLeast(0),
            upLimit = limit("alt-speed-up", "speed-limit-up", "speed-limit-up-enabled").coerceAtLeast(0),
            altSpeed = alt,
        )
    }

    // ------------------------------------------------------------------ actions

    private suspend fun act(s: ServiceConfig, method: String, hashes: Collection<String>?, extra: JsonObject? = null) {
        call(s, method, buildJsonObject {
            if (hashes != null) put("ids", ids(hashes))
            extra?.forEach { (k, v) -> put(k, v) }
        })
    }

    suspend fun start(s: ServiceConfig, hashes: Collection<String>) = act(s, "torrent-start", hashes)
    suspend fun stop(s: ServiceConfig, hashes: Collection<String>) = act(s, "torrent-stop", hashes)
    /** No `ids` means every torrent. */
    suspend fun startAll(s: ServiceConfig) = act(s, "torrent-start", null)
    suspend fun stopAll(s: ServiceConfig) = act(s, "torrent-stop", null)
    suspend fun recheck(s: ServiceConfig, hashes: Collection<String>) = act(s, "torrent-verify", hashes)
    suspend fun reannounce(s: ServiceConfig, hashes: Collection<String>) = act(s, "torrent-reannounce", hashes)
    suspend fun delete(s: ServiceConfig, hashes: Collection<String>, deleteFiles: Boolean) =
        act(s, "torrent-remove", hashes, buildJsonObject { put("delete-local-data", deleteFiles) })

    /** The "category" is the label list reduced to one label (or none). */
    suspend fun setCategory(s: ServiceConfig, hashes: Collection<String>, category: String) =
        act(s, "torrent-set", hashes, buildJsonObject { put("labels", if (category.isEmpty()) JsonArray(emptyList()) else strings(category)) })

    /** qBittorrent priorities in: 0 skip, 1 normal, 6/7 high (Transmission has low/normal/high). */
    suspend fun setFilePriority(s: ServiceConfig, hash: String, indexes: Collection<Int>, priority: Int) {
        val idx = JsonArray(indexes.map { JsonPrimitive(it) })
        act(s, "torrent-set", listOf(hash), buildJsonObject {
            if (priority == 0) put("files-unwanted", idx)
            else {
                put("files-wanted", idx)
                put(if (priority >= 6) "priority-high" else "priority-normal", idx)
            }
        })
    }

    /** Labels in use (Transmission has no list of defined labels). */
    suspend fun categories(s: ServiceConfig): List<String> {
        val a = call(s, "torrent-get", buildJsonObject { put("fields", strings("labels")) })
        return a["torrents"].jArr().orEmpty().flatMap { t -> t.jObj()?.get("labels").jArr()?.mapNotNull { it.jText()?.takeIf { l -> l.isNotBlank() } }.orEmpty() }
            .distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    suspend fun setAltSpeed(s: ServiceConfig, enabled: Boolean) {
        call(s, "session-set", buildJsonObject { put("alt-speed-enabled", enabled) })
        states[s.id]?.sessionAt = 0
    }

    suspend fun add(
        s: ServiceConfig,
        links: List<String>,
        files: List<Qbit.TorrentFileUpload>,
        category: String?,
        savePath: String?,
        paused: Boolean,
    ) {
        fun args(source: Pair<String, String>) = buildJsonObject {
            put(source.first, source.second)
            put("paused", paused)
            if (!savePath.isNullOrBlank()) put("download-dir", savePath.trim())
            if (!category.isNullOrEmpty()) put("labels", strings(category))
        }
        val sources = links.map { "filename" to it } + files.map { "metainfo" to Base64.getEncoder().encodeToString(it.bytes) }
        for (src in sources) {
            val a = call(s, "torrent-add", args(src))
            if (a["torrent-added"] == null && a["torrent-duplicate"] == null) throw ServiceException("${s.name} didn't add the torrent", 400)
        }
        states[s.id]?.fullAt = 0
    }

    // ------------------------------------------------------------------ details

    suspend fun details(s: ServiceConfig, hash: String): TorrentDetails {
        val a = call(s, "torrent-get", buildJsonObject {
            put("ids", ids(listOf(hash)))
            put("fields", strings(DETAIL_FIELDS))
        })
        val t = a["torrents"].jArr()?.firstOrNull().jObj() ?: throw ServiceException("${s.name} no longer has this torrent", 404)
        return withContext(Dispatchers.Default) { detailsOf(t, System.currentTimeMillis() / 1000) }
    }

    internal fun detailsOf(t: JsonObject, nowSecs: Long): TorrentDetails {
        val dlSecs = t["secondsDownloading"].jLng() ?: 0
        val seedSecs = t["secondsSeeding"].jLng() ?: 0
        val downloaded = t["downloadedEver"].jLng() ?: 0
        val uploaded = t["uploadedEver"].jLng() ?: 0
        val pieceCount = t["pieceCount"].jLng() ?: -1
        val have = t["pieces"].jText()?.let { b64 ->
            runCatching { Base64.getDecoder().decode(b64).sumOf { Integer.bitCount(it.toInt() and 0xFF) }.toLong() }.getOrNull()
        }
        val stats = t["trackerStats"].jArr().orEmpty().mapNotNull { it.jObj() }
        val nextAnnounce = stats.mapNotNull { it["nextAnnounceTime"].jLng()?.takeIf { n -> n > nowSecs } }.minOrNull()
        val props = TorrentProperties(
            savePath = t["downloadDir"].jText().orEmpty(),
            creationDate = t["dateCreated"].jLng()?.takeIf { it > 0 } ?: -1,
            pieceSize = t["pieceSize"].jLng() ?: -1,
            comment = t["comment"].jText().orEmpty(),
            totalWasted = t["corruptEver"].jLng() ?: 0,
            totalUploaded = uploaded,
            totalDownloaded = downloaded,
            upLimit = if (t["uploadLimited"].jBool() == true) (t["uploadLimit"].jLng() ?: 0) * 1000 else -1,
            dlLimit = if (t["downloadLimited"].jBool() == true) (t["downloadLimit"].jLng() ?: 0) * 1000 else -1,
            timeElapsed = dlSecs + seedSecs,
            seedingTime = seedSecs,
            connections = t["peersConnected"].jInt() ?: 0,
            connectionsLimit = t["peer-limit"].jInt() ?: 0,
            shareRatio = (t["uploadRatio"].jDbl() ?: 0.0).coerceAtLeast(0.0),
            additionDate = t["addedDate"].jLng()?.takeIf { it > 0 } ?: -1,
            completionDate = t["doneDate"].jLng()?.takeIf { it > 0 } ?: -1,
            createdBy = t["creator"].jText().orEmpty(),
            dlSpeedAvg = if (dlSecs > 0) downloaded / dlSecs else 0,
            upSpeedAvg = if (dlSecs + seedSecs > 0) uploaded / (dlSecs + seedSecs) else 0,
            lastSeen = -1,
            piecesHave = if (have != null && pieceCount > 0) have.coerceAtMost(pieceCount) else -1,
            piecesNum = if (have != null) pieceCount else -1,
            reannounce = nextAnnounce?.let { it - nowSecs } ?: 0,
            totalSize = t["totalSize"].jLng() ?: -1,
        )
        val trackers = stats.map { tr ->
            val succeeded = tr["lastAnnounceSucceeded"].jBool() == true
            val announced = tr["hasAnnounced"].jBool() == true
            // announceState: 0 inactive, 1 waiting, 2 queued, 3 active (announcing now).
            val status = when {
                tr["announceState"].jInt() == 3 -> 3
                succeeded -> 2
                announced -> 4
                tr["announceState"].jInt() == 0 -> 0
                else -> 1
            }
            val result = tr["lastAnnounceResult"].jText().orEmpty()
            TorrentTracker(
                url = tr["announce"].jText().orEmpty(),
                status = status,
                tier = tr["tier"]?.let { JsonPrimitive(it.jInt() ?: 0) },
                peers = tr["lastAnnouncePeerCount"].jInt() ?: -1,
                seeds = tr["seederCount"].jInt() ?: -1,
                leeches = tr["leecherCount"].jInt() ?: -1,
                downloaded = tr["downloadCount"].jInt() ?: -1,
                msg = if (result.equals("Success", ignoreCase = true)) "" else result,
            )
        }
        val fileStats = t["fileStats"].jArr().orEmpty()
        val files = t["files"].jArr().orEmpty().mapIndexedNotNull { i, el ->
            val f = el.jObj() ?: return@mapIndexedNotNull null
            val fs = fileStats.getOrNull(i).jObj()
            val size = f["length"].jLng() ?: 0
            val doneBytes = f["bytesCompleted"].jLng() ?: 0
            val wanted = fs?.get("wanted").jBool() ?: true
            TorrentFile(
                index = i,
                name = f["name"].jText().orEmpty(),
                size = size,
                progress = if (size > 0) (doneBytes.toDouble() / size).coerceIn(0.0, 1.0) else 1.0,
                // Transmission priority: -1 low, 0 normal, 1 high.
                priority = if (!wanted) 0 else if ((fs?.get("priority").jInt() ?: 0) > 0) 6 else 1,
            )
        }
        return TorrentDetails(props, trackers, files)
    }
}
