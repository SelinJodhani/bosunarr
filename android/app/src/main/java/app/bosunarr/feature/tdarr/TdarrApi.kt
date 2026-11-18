package app.bosunarr.feature.tdarr

import app.bosunarr.app
import app.bosunarr.data.ServiceConfig
import app.bosunarr.net.ServiceException
import app.bosunarr.ui.common.bytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.abs

/*
 * Tdarr server API v2 (closed source; shapes taken from the API reference at tdarr.readme.io and from clients that
 * use it: Homepage's and Homarr's Tdarr widgets, the Home Assistant integration, homeylab/tdarr-exporter). The key
 * goes in `x-api-key`, only when auth is on. Reads that need parameters are POSTs with a `{"data": {…}}` body.
 *   GET  /api/v2/status                {status: "good", os, version, uptime (s)}
 *   GET  /api/v2/get-nodes             {nodeId: {_id, nodeName, nodePaused, workers: {workerId: {…}}, workerLimits, …}}
 *   POST /api/v2/cruddb                StatisticsJSONDB/statistics: {totalFileCount, sizeDiff (GB), table0Count…table6Count}
 *                                      LibrarySettingsJSONDB getAll: [{_id, name, …}]
 *                                      SettingsGlobalJSONDB/globalsettings: {pauseAllNodes, …}
 *   POST /api/v2/stats/get-pies        {pieStats: {totalFiles, sizeDiff, status: {transcode: [{name, value}], healthcheck: […]}, video: {codecs}}}
 *   POST /api/v2/client/status-tables  {array: [{_id, file, file_size (MB), video_codec_name, …}], totalCount}
 *   POST /api/v2/update-node           {data: {nodeID, nodeUpdates: {nodePaused}}}
 * Numbers are read leniently: some versions send counts and percentages as strings.
 */

/* ================================================================ lenient JSON helpers */

private fun JsonElement?.ts(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.takeIf { it.isNotBlank() }
private fun JsonElement?.tn(): Double? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toDoubleOrNull()
private fun JsonElement?.ti(): Int? = tn()?.toInt()
private fun JsonElement?.tb(): Boolean? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toBooleanStrictOrNull()
private fun JsonElement?.tobj(): JsonObject? = this as? JsonObject
private fun JsonElement?.ta(): List<JsonObject> = (this as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

/** Tdarr's GB and MB figures, as bytes. Binary units, so [bytes] shows the same number Tdarr's UI shows. */
internal fun gbToBytes(gb: Double): Long = (gb * 1024 * 1024 * 1024).toLong()
internal fun mbToBytes(mb: Double): Long = (mb * 1024 * 1024).toLong()

private suspend fun post(s: ServiceConfig, path: String, data: JsonObject): JsonElement {
    val text = app.client.send(s, "/api/v2$path", "POST", buildJsonObject { put("data", data) })
    return app.client.decodeOffMain(s, text, JsonElement.serializer())
}

private suspend fun cruddb(s: ServiceConfig, collection: String, mode: String, docId: String? = null): JsonElement =
    post(s, "/cruddb", buildJsonObject {
        put("collection", collection)
        put("mode", mode)
        if (docId != null) put("docID", docId)
    })

/* ================================================================ server status */

data class TdStatus(val version: String?, val os: String?, val uptimeSeconds: Long?, val status: String?) {
    val healthy: Boolean get() = status.equals("good", ignoreCase = true)
}

internal fun parseTdStatus(el: JsonElement): TdStatus {
    val o = el.tobj() ?: throw ServiceException("Tdarr gave an unexpected answer")
    return TdStatus(o["version"].ts(), o["os"].ts(), o["uptime"].tn()?.toLong(), o["status"].ts())
}

internal suspend fun tdStatus(s: ServiceConfig): TdStatus = parseTdStatus(app.client.json(s, "/api/v2/status"))

/* ================================================================ statistics */

/**
 * Library-wide totals. Tdarr's tables: 0 hold, 1 transcode queue, 2 transcoded or not required, 3 transcode errors
 * and cancelled, 4 health check queue, 5 healthy, 6 health check errors and cancelled.
 */
data class TdStats(
    val files: Int,
    val transcodes: Int,
    val healthChecks: Int,
    val savedGb: Double,
    val hold: Int,
    val transcodeQueue: Int,
    val transcodeDone: Int,
    val transcodeErrors: Int,
    val healthQueue: Int,
    val healthDone: Int,
    val healthErrors: Int,
) {
    val queue: Int get() = transcodeQueue + healthQueue
    val errors: Int get() = transcodeErrors + healthErrors
}

internal fun parseTdStats(el: JsonElement): TdStats {
    val o = el.tobj() ?: throw ServiceException("Tdarr gave an unexpected answer")
    // Newer versions add tableNViewableCount, which leaves out files of disabled libraries (what Tdarr's tables show).
    fun table(n: Int) = (o["table${n}ViewableCount"].ti() ?: o["table${n}Count"].ti()) ?: 0
    return TdStats(
        files = o["totalFileCount"].ti() ?: 0,
        transcodes = o["totalTranscodeCount"].ti() ?: 0,
        healthChecks = o["totalHealthCheckCount"].ti() ?: 0,
        savedGb = o["sizeDiff"].tn() ?: 0.0,
        hold = table(0),
        transcodeQueue = table(1),
        transcodeDone = table(2),
        transcodeErrors = table(3),
        healthQueue = table(4),
        healthDone = table(5),
        healthErrors = table(6),
    )
}

internal suspend fun tdStats(s: ServiceConfig): TdStats = parseTdStats(cruddb(s, "StatisticsJSONDB", "getById", "statistics"))

/** "12.4 GB saved" or, when transcodes made files bigger overall, "1.2 GB larger". */
internal fun savedLabel(gb: Double): String {
    val size = bytes(gbToBytes(abs(gb)))
    return if (gb < 0) "$size larger" else "$size saved"
}

/* ================================================================ nodes + workers */

data class TdWorker(
    val id: String,
    /** transcodecpu, transcodegpu, healthcheckcpu, healthcheckgpu. */
    val type: String,
    val file: String?,
    val percent: Double?,
    val fps: Double?,
    val etaSeconds: Long?,
    val status: String?,
    val step: String?,
    val idle: Boolean,
    val originalBytes: Long?,
    val estimatedBytes: Long?,
    val outputBytes: Long?,
)

data class TdNode(
    val id: String,
    val name: String,
    val paused: Boolean,
    val address: String?,
    val workers: List<TdWorker>,
    /** Worker limits by type (`transcodecpu` → 2). */
    val limits: Map<String, Int>,
    val cpuPercent: Double?,
    val memUsedGb: Double?,
    val memTotalGb: Double?,
) {
    val busy: List<TdWorker> get() = workers.filter { !it.idle && !it.file.isNullOrBlank() }
}

/** "0:01:30" (or "1:02:03:04" with days) → seconds; null for anything else ("", "Calculating…"). */
internal fun parseEta(eta: String?): Long? {
    val parts = eta?.trim()?.split(':') ?: return null
    if (parts.size < 2) return null
    val nums = parts.map { it.trim().toLongOrNull() ?: return null }
    val mults = listOf(1L, 60L, 3600L, 86400L)
    if (nums.size > mults.size) return null
    return nums.reversed().mapIndexed { i, n -> n * mults[i] }.sum()
}

/** "Transcode · GPU", "Health check · CPU". */
internal fun workerTypeLabel(type: String): String {
    val t = type.lowercase()
    val job = when {
        t.startsWith("transcode") -> "Transcode"
        t.startsWith("healthcheck") -> "Health check"
        else -> return type.ifBlank { "Worker" }
    }
    val hw = when {
        t.endsWith("gpu") -> "GPU"
        t.endsWith("cpu") -> "CPU"
        else -> null
    }
    return listOfNotNull(job, hw).joinToString(" · ")
}

internal fun parseTdNodes(el: JsonElement): List<TdNode> {
    val o = el.tobj() ?: throw ServiceException("Tdarr gave an unexpected answer")
    return o.entries.mapNotNull { (key, v) ->
        val n = v.tobj() ?: return@mapNotNull null
        val workers = n["workers"].tobj()?.entries.orEmpty().mapNotNull { (wk, wv) ->
            val w = wv.tobj() ?: return@mapNotNull null
            TdWorker(
                id = w["_id"].ts() ?: wk,
                type = w["workerType"].ts().orEmpty(),
                file = w["file"].ts(),
                percent = w["percentage"].tn(),
                fps = w["fps"].tn(),
                etaSeconds = parseEta(w["ETA"].ts()),
                status = w["status"].ts(),
                step = w["lastPluginDetails"].tobj()?.get("number").ts(),
                idle = w["idle"].tb() ?: false,
                originalBytes = w["originalfileSizeInGbytes"].tn()?.takeIf { it > 0 }?.let(::gbToBytes),
                estimatedBytes = w["estSize"].tn()?.takeIf { it > 0 }?.let(::gbToBytes),
                outputBytes = w["outputFileSizeInGbytes"].tn()?.takeIf { it > 0 }?.let(::gbToBytes),
            )
        }.sortedWith(compareBy({ it.type }, { it.id }))
        val os = n["resStats"].tobj()?.get("os").tobj()
        TdNode(
            id = n["_id"].ts() ?: key,
            name = n["nodeName"].ts() ?: n["_id"].ts() ?: key,
            paused = n["nodePaused"].tb() ?: false,
            address = n["remoteAddress"].ts(),
            workers = workers,
            limits = n["workerLimits"].tobj()?.entries.orEmpty().mapNotNull { (k, l) -> l.ti()?.let { k to it } }.toMap(),
            cpuPercent = os?.get("cpuPerc").tn(),
            memUsedGb = os?.get("memUsedGB").tn(),
            memTotalGb = os?.get("memTotalGB").tn(),
        )
    }.sortedBy { it.name.lowercase() }
}

internal suspend fun tdNodes(s: ServiceConfig): List<TdNode> = parseTdNodes(app.client.json(s, "/api/v2/get-nodes"))

internal suspend fun tdSetNodePaused(s: ServiceConfig, nodeId: String, paused: Boolean) {
    app.client.send(s, "/api/v2/update-node", "POST", buildJsonObject {
        put("data", buildJsonObject {
            put("nodeID", nodeId)
            put("nodeUpdates", buildJsonObject { put("nodePaused", paused) })
        })
    })
}

/** The "Pause all" switch in Tdarr's header: a global setting that stops every node taking new work. */
internal suspend fun tdPausedAll(s: ServiceConfig): Boolean =
    cruddb(s, "SettingsGlobalJSONDB", "getById", "globalsettings").tobj()?.get("pauseAllNodes").tb() ?: false

internal suspend fun tdSetPausedAll(s: ServiceConfig, paused: Boolean) {
    app.client.send(s, "/api/v2/cruddb", "POST", buildJsonObject {
        put("data", buildJsonObject {
            put("collection", "SettingsGlobalJSONDB")
            put("mode", "update")
            put("docID", "globalsettings")
            put("obj", buildJsonObject { put("pauseAllNodes", paused) })
        })
    })
}

/* ================================================================ libraries */

data class TdLibrary(
    val id: String,
    val name: String,
    val files: Int?,
    val savedGb: Double?,
    /** Transcode status name (lower case: "queued", "transcode success", "not required", …) → files. */
    val transcode: Map<String, Int>,
    /** Health check status name (lower case: "queued", "success", "error", …) → files. */
    val health: Map<String, Int>,
    /** Video codecs, most files first. */
    val codecs: List<Pair<String, Int>>,
) {
    val queued: Int get() = (transcode["queued"] ?: 0) + (health["queued"] ?: 0)
    val transcoded: Int get() = transcode["transcode success"] ?: 0
    val notRequired: Int get() = transcode["not required"] ?: 0
    val errors: Int get() = (transcode["transcode error"] ?: 0) + (health["error"] ?: 0)
    val loaded: Boolean get() = files != null
}

internal fun parseTdLibraries(el: JsonElement): List<Pair<String, String>> =
    el.ta().mapNotNull { l -> l["_id"].ts()?.let { id -> id to (l["name"].ts() ?: id) } }

private fun slices(el: JsonElement?): Map<String, Int> =
    el.ta().mapNotNull { x -> x["name"].ts()?.let { n -> n.lowercase() to (x["value"].ti() ?: 0) } }
        .groupBy({ it.first }, { it.second }).mapValues { (_, v) -> v.sum() }

/** One library's pie stats; [id]/[name] come from the library list. */
internal fun parseTdPies(id: String, name: String, el: JsonElement): TdLibrary {
    val p = el.tobj()?.get("pieStats").tobj() ?: throw ServiceException("Tdarr gave an unexpected answer")
    val status = p["status"].tobj()
    return TdLibrary(
        id = id,
        name = name,
        files = p["totalFiles"].ti() ?: 0,
        savedGb = p["sizeDiff"].tn(),
        transcode = slices(status?.get("transcode")),
        // Spelled `healthcheck` by the servers Homarr talks to, `healthCheck` in other clients: accept both.
        health = slices(status?.get("healthcheck") ?: status?.get("healthCheck")),
        codecs = p["video"].tobj()?.get("codecs").ta().mapNotNull { c -> c["name"].ts()?.let { it to (c["value"].ti() ?: 0) } }
            .sortedByDescending { it.second },
    )
}

internal suspend fun tdLibraries(s: ServiceConfig): List<TdLibrary> = coroutineScope {
    val libs = parseTdLibraries(cruddb(s, "LibrarySettingsJSONDB", "getAll"))
    libs.map { (id, name) ->
        async {
            // One library failing (older servers without stats/get-pies) still lists the rest by name.
            runCatching { parseTdPies(id, name, post(s, "/stats/get-pies", buildJsonObject { put("libraryId", id) })) }
                .getOrElse { e ->
                    if (e is CancellationException) throw e
                    TdLibrary(id, name, null, null, emptyMap(), emptyMap(), emptyList())
                }
        }
    }.awaitAll()
}

/* ================================================================ queue + errors */

/** A list from Tdarr's status tables. */
enum class TdTable(val table: String, val label: String, val empty: String) {
    TranscodeQueue("table1", "Transcode queue", "Nothing waiting to transcode"),
    HealthQueue("table4", "Health check queue", "Nothing waiting for a health check"),
    TranscodeErrors("table3", "Transcode errors", "No transcode errors"),
    HealthErrors("table6", "Health check errors", "No health check errors"),
}

data class TdFile(
    val id: String,
    val path: String,
    val sizeBytes: Long?,
    val codec: String?,
    val resolution: String?,
    val container: String?,
    val transcode: String?,
    val health: String?,
) {
    val name: String get() = path.substringAfterLast('/').substringAfterLast('\\')
    val folder: String? get() = path.dropLast(name.length).trimEnd('/', '\\').ifBlank { null }
}

data class TdFilePage(val items: List<TdFile>, val total: Int)

internal fun parseTdFiles(el: JsonElement): TdFilePage {
    val o = el.tobj() ?: throw ServiceException("Tdarr gave an unexpected answer")
    val items = o["array"].ta().map { f ->
        val path = f["file"].ts() ?: f["_id"].ts() ?: "Unknown file"
        TdFile(
            id = f["_id"].ts() ?: path,
            path = path,
            sizeBytes = f["file_size"].tn()?.takeIf { it > 0 }?.let(::mbToBytes),
            codec = f["video_codec_name"].ts(),
            resolution = f["video_resolution"].ts(),
            container = f["container"].ts(),
            transcode = f["TranscodeDecisionMaker"].ts(),
            health = f["HealthCheck"].ts(),
        )
    }
    return TdFilePage(items, o["totalCount"].ti() ?: items.size)
}

internal suspend fun tdFiles(s: ServiceConfig, table: TdTable, pageSize: Int): TdFilePage =
    parseTdFiles(
        post(s, "/client/status-tables", buildJsonObject {
            put("start", 0)
            put("pageSize", pageSize)
            put("filters", buildJsonArray {})
            put("sorts", buildJsonArray {})
            put("opts", buildJsonObject { put("table", table.table) })
        }),
    )
