package app.bosunarr.feature.usenet

import app.bosunarr.app
import app.bosunarr.data.ServiceConfig
import app.bosunarr.data.ServiceType
import app.bosunarr.net.ServiceException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * NZBGet over JSON-RPC (`POST /jsonrpc`, positional params only, HTTP basic auth added by the core client).
 * 64-bit sizes come as `…Lo`/`…Hi` 32-bit halves in bytes (with a rounded `…MB` alongside); speeds are bytes/s,
 * except `rate`, which takes KiB/s.
 */
internal object Nzbget {
    private suspend fun rpc(s: ServiceConfig, method: String, vararg params: Any?): JsonElement {
        val body = buildJsonObject {
            put("method", method)
            put("params", JsonArray(params.map { json(it) }))
            put("id", 1)
        }
        val text = app.client.send(s, "/jsonrpc", "POST", body)
        val o = app.client.decodeOffMain(s, text, JsonElement.serializer()).obj()
            ?: throw ServiceException("${s.name} gave an unexpected answer")
        val err = o["error"]
        if (err != null && err !is JsonNull) {
            val msg = err.obj()?.let { it["message"].text() ?: it["faultString"].text() } ?: err.text()
            throw ServiceException("${s.name}: ${msg ?: "the request failed"}", 400)
        }
        return o["result"] ?: JsonNull
    }

    private fun json(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is JsonElement -> v
        is String -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is Collection<*> -> JsonArray(v.map { json(it) })
        else -> JsonPrimitive(v.toString())
    }

    /** `editqueue(Command, Param, IDs)` — the 3-argument form of NZBGet 18+. */
    private suspend fun edit(s: ServiceConfig, command: String, param: String, ids: Collection<String>) {
        val numeric = ids.mapNotNull { it.toIntOrNull() }
        if (numeric.isEmpty()) return
        if (rpc(s, "editqueue", command, param, numeric).bool() == false) throw ServiceException("${s.name} refused the change", 400)
    }

    private suspend fun ok(s: ServiceConfig, method: String, vararg params: Any?) {
        if (rpc(s, method, *params).bool() == false) throw ServiceException("${s.name} refused the change", 400)
    }

    // ------------------------------------------------------------------ reads

    suspend fun queue(s: ServiceConfig): UsenetSnapshot = coroutineScope {
        val groupsD = async { rpc(s, "listgroups", 0) }
        val statusD = async { rpc(s, "status") }
        val st = statusD.await().obj() ?: throw ServiceException("${s.name} gave an unexpected answer")
        val groups = groupsD.await().arr() ?: throw ServiceException("${s.name} gave an unexpected answer")
        parseQueue(s, st, groups)
    }

    internal fun parseQueue(s: ServiceConfig, st: JsonObject, groups: JsonArray): UsenetSnapshot {
        // DownloadRateLo/Hi since 24.2; DownloadRate (deprecated) before.
        val rate = if (st.containsKey("DownloadRateLo")) loHi(st, "DownloadRate") else st["DownloadRate"].lng() ?: 0
        val paused = st["DownloadPaused"].bool() == true || st["Download2Paused"].bool() == true
        var cumulative = 0L
        val items = groups.mapIndexedNotNull { i, el ->
            val o = el.obj() ?: return@mapIndexedNotNull null
            val it = item(s, o, i)
            // NZBGet has no per-job ETA: like its web UI, add up what's left in queue order at the current speed.
            if (it.state == NzbState.Downloading || it.state == NzbState.Queued) {
                cumulative += it.left
                if (!paused && rate > 0) it.copy(eta = cumulative / rate) else it
            } else it
        }
        val left = cumulative
        return UsenetSnapshot(
            serviceId = s.id,
            serviceName = s.name,
            type = ServiceType.Nzbget,
            server = NzbServer(
                down = rate,
                limit = st["DownloadLimit"].lng()?.coerceAtLeast(0) ?: 0,
                paused = paused,
                left = left.coerceAtLeast(0),
                timeLeft = if (!paused && rate > 0 && left > 0) left / rate else null,
                freeDisk = if (st.containsKey("FreeDiskSpaceLo") || st.containsKey("FreeDiskSpaceMB")) loHi(st, "FreeDiskSpace") else null,
                today = if (st.containsKey("DaySizeLo") || st.containsKey("DaySizeMB")) loHi(st, "DaySize") else null,
            ),
            items = items,
        )
    }

    private fun item(s: ServiceConfig, o: JsonObject, position: Int): NzbItem {
        val id = o["NZBID"].lng()?.toString() ?: o["LastID"].lng()?.toString() ?: position.toString()
        val status = o["Status"].text().orEmpty()
        val state = when (status) {
            "QUEUED" -> NzbState.Queued
            "PAUSED" -> NzbState.Paused
            "DOWNLOADING" -> NzbState.Downloading
            "FETCHING" -> NzbState.Fetching
            else -> NzbState.Processing
        }
        val fileSize = loHi(o, "FileSize")
        val remaining = loHi(o, "RemainingSize")
        val pausedSize = loHi(o, "PausedSize")
        // Paused par files count as "remaining" but won't be fetched unless needed, so leave them out (as the web UI does).
        val total = (if (pausedSize in 1 until remaining) fileSize - pausedSize else fileSize).coerceAtLeast(0)
        val left = (if (pausedSize in 1 until remaining) remaining - pausedSize else remaining).coerceAtLeast(0)
        val progress = when {
            state == NzbState.Processing -> (o["PostStageProgress"].lng() ?: 0) / 1000f
            total > 0 -> 1f - left.toFloat() / total
            else -> 0f
        }
        val health = o["Health"].lng()
        val critical = o["CriticalHealth"].lng()
        val labels = buildList {
            if (health != null && health < 1000) {
                add(if (critical != null && health < critical) "HEALTH CRITICAL" else "HEALTH ${health / 10}.${health % 10}%")
            }
        }
        return NzbItem(
            serviceId = s.id,
            serviceName = s.name,
            type = ServiceType.Nzbget,
            id = id,
            name = o["NZBName"].text() ?: o["NZBNicename"].text() ?: o["NZBFilename"].text() ?: id,
            category = o["Category"].text().orEmpty(),
            priority = priority(o["MaxPriority"].lng() ?: 0),
            state = state,
            stateLabel = if (state == NzbState.Processing) o["PostInfoText"].text()?.takeIf { it.isNotBlank() } ?: statusLabel(status) else statusLabel(status),
            size = total,
            left = if (state == NzbState.Processing) 0 else left,
            progress = progress.coerceIn(0f, 1f),
            eta = null,
            index = position,
            labels = labels,
        )
    }

    suspend fun history(s: ServiceConfig, limit: Int, failedOnly: Boolean): List<NzbHistoryItem> =
        parseHistory(s, rpc(s, "history", false).arr() ?: throw ServiceException("${s.name} gave an unexpected answer"), limit, failedOnly)

    internal fun parseHistory(s: ServiceConfig, list: JsonArray, limit: Int, failedOnly: Boolean): List<NzbHistoryItem> {
        return list.asSequence().mapNotNull { el ->
            val o = el.obj() ?: return@mapNotNull null
            val kind = o["Kind"].text() ?: "NZB"
            if (kind == "DUP") return@mapNotNull null
            val id = (o["NZBID"].lng() ?: o["ID"].lng())?.toString() ?: return@mapNotNull null
            val status = o["Status"].text().orEmpty()
            val outcome = when (status.substringBefore('/')) {
                "SUCCESS" -> NzbOutcome.Success
                "WARNING" -> NzbOutcome.Warning
                "FAILURE" -> NzbOutcome.Failed
                "DELETED" -> NzbOutcome.Deleted
                else -> NzbOutcome.Processing
            }
            NzbHistoryItem(
                serviceId = s.id,
                serviceName = s.name,
                type = ServiceType.Nzbget,
                id = id,
                name = o["Name"].text() ?: o["NZBName"].text() ?: id,
                category = o["Category"].text().orEmpty(),
                size = loHi(o, "FileSize"),
                completedAt = o["HistoryTime"].lng() ?: 0,
                outcome = outcome,
                stateLabel = when (outcome) {
                    NzbOutcome.Success -> "Completed"
                    NzbOutcome.Warning -> "Warning"
                    NzbOutcome.Failed -> "Failed"
                    NzbOutcome.Deleted -> "Deleted"
                    NzbOutcome.Processing -> "Processing"
                },
                reason = if (outcome == NzbOutcome.Success) null else reason(status, o),
                downloadSeconds = o["DownloadTimeSec"].lng() ?: 0,
                // Re-download from scratch; only real NZBs (a failed URL fetch has nothing to retry with).
                canRetry = kind == "NZB" && (outcome == NzbOutcome.Failed || outcome == NzbOutcome.Warning),
            )
        }.filter { !failedOnly || it.outcome == NzbOutcome.Failed }.take(limit).toList()
    }

    /** Categories are config options `CategoryN.Name`. `config` needs the full-access (Control) user. */
    suspend fun categories(s: ServiceConfig): List<String> {
        val cfg = rpc(s, "config").arr().orEmpty()
        val re = Regex("^Category\\d+\\.Name$", RegexOption.IGNORE_CASE)
        return cfg.mapNotNull { el ->
            val o = el.obj() ?: return@mapNotNull null
            if (re.matches(o["Name"].text().orEmpty())) o["Value"].text()?.takeIf { it.isNotBlank() } else null
        }.distinct()
    }

    // ------------------------------------------------------------------ actions

    suspend fun pauseAll(s: ServiceConfig) = ok(s, "pausedownload")
    suspend fun resumeAll(s: ServiceConfig) = ok(s, "resumedownload")
    suspend fun pauseFor(s: ServiceConfig, minutes: Int) {
        ok(s, "pausedownload")
        ok(s, "scheduleresume", minutes * 60)
    }

    suspend fun pause(s: ServiceConfig, ids: Collection<String>) = edit(s, "GroupPause", "", ids)
    suspend fun resume(s: ServiceConfig, ids: Collection<String>) = edit(s, "GroupResume", "", ids)

    /** GroupDelete keeps a history entry; GroupFinalDelete removes the job without one. */
    suspend fun delete(s: ServiceConfig, ids: Collection<String>, final: Boolean) =
        edit(s, if (final) "GroupFinalDelete" else "GroupDelete", "", ids)

    suspend fun moveTop(s: ServiceConfig, ids: List<String>) = edit(s, "GroupMoveTop", "", ids)

    suspend fun setPriority(s: ServiceConfig, ids: Collection<String>, p: NzbPriority) =
        edit(s, "GroupSetPriority", priorityValue(p).toString(), ids)

    /** GroupApplyCategory also re-applies the category's post-processing settings (what the web UI does). */
    suspend fun setCategory(s: ServiceConfig, ids: Collection<String>, category: String) =
        edit(s, "GroupApplyCategory", category, ids)

    /** `rate` takes KiB/s; 0 = unlimited. */
    suspend fun setSpeedLimit(s: ServiceConfig, bytesPerSec: Long) =
        ok(s, "rate", if (bytesPerSec <= 0) 0 else (bytesPerSec / 1024).coerceAtLeast(1).toInt())

    /** HistoryDelete hides the entry (kept for duplicate checks); HistoryFinalDelete removes it for good. */
    suspend fun deleteHistory(s: ServiceConfig, ids: Collection<String>, final: Boolean) =
        edit(s, if (final) "HistoryFinalDelete" else "HistoryDelete", "", ids)

    suspend fun retry(s: ServiceConfig, id: String) = edit(s, "HistoryRedownload", "", listOf(id))

    /**
     * `append(Filename, Content, Category, Priority, AddToTop, AddPaused, DupeKey, DupeScore, DupeMode)`. Content is a
     * URL or the base64 NZB. The trailing AutoCategory / PPParameters arguments are optional and left out, which
     * works on both old (≤24) and new NZBGet.
     */
    private suspend fun append(s: ServiceConfig, filename: String, content: String, category: String?, priority: NzbPriority?, paused: Boolean, what: String) {
        val id = rpc(
            s, "append",
            filename, content, category.orEmpty(), priorityValue(priority ?: NzbPriority.Normal),
            false, paused, "", 0, "SCORE",
        ).lng() ?: 0
        if (id <= 0) throw ServiceException("${s.name} rejected $what", 400)
    }

    suspend fun addUrl(s: ServiceConfig, url: String, category: String?, priority: NzbPriority?, paused: Boolean) =
        append(s, "", url, category, priority, paused, "the link")

    suspend fun addFile(s: ServiceConfig, file: NzbUpload, category: String?, priority: NzbPriority?, paused: Boolean) =
        append(s, file.name, Base64.getEncoder().encodeToString(file.bytes), category, priority, paused, file.name)

    // ------------------------------------------------------------------ parsing helpers

    /** Bytes from `<prefix>Lo`/`<prefix>Hi` (unsigned 32-bit halves), falling back to `<prefix>MB`. */
    private fun loHi(o: JsonObject, prefix: String): Long {
        val lo = o["${prefix}Lo"].lng()
        if (lo != null) {
            val hi = o["${prefix}Hi"].lng() ?: 0
            return (hi shl 32) or (lo and 0xFFFFFFFFL)
        }
        return (o["${prefix}MB"].lng() ?: 0) * 1024 * 1024
    }

    private fun priority(n: Long): NzbPriority = when {
        n >= 900 -> NzbPriority.Force
        n >= 100 -> NzbPriority.VeryHigh
        n >= 50 -> NzbPriority.High
        n > -50 -> NzbPriority.Normal
        n > -100 -> NzbPriority.Low
        else -> NzbPriority.VeryLow
    }

    private fun priorityValue(p: NzbPriority): Int = when (p) {
        NzbPriority.Force -> 900
        NzbPriority.VeryHigh -> 100
        NzbPriority.High -> 50
        NzbPriority.Normal -> 0
        NzbPriority.Low -> -50
        NzbPriority.VeryLow -> -100
    }

    private fun statusLabel(status: String) = when (status) {
        "QUEUED" -> "Queued"
        "PAUSED" -> "Paused"
        "DOWNLOADING" -> "Downloading"
        "FETCHING" -> "Fetching NZB"
        "PP_QUEUED" -> "Waiting to process"
        "LOADING_PARS" -> "Loading pars"
        "VERIFYING_SOURCES", "VERIFYING_REPAIRED" -> "Verifying"
        "REPAIRING" -> "Repairing"
        "RENAMING", "POST_UNPACK_RENAMING", "POST_DOWNLOAD_RENAMING" -> "Renaming"
        "UNPACKING" -> "Unpacking"
        "MOVING" -> "Moving"
        "EXECUTING_SCRIPT" -> "Running script"
        "PP_FINISHED" -> "Finishing"
        else -> status.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    private fun reason(status: String, o: JsonObject): String? {
        val health = o["Health"].lng()
        return when (status) {
            "FAILURE/PAR" -> "Par repair failed"
            "FAILURE/UNPACK" -> "Unpack failed"
            "FAILURE/MOVE" -> "Moving files failed"
            "FAILURE/SCAN" -> "The NZB couldn't be read"
            "FAILURE/BAD" -> "Marked as bad"
            "FAILURE/HEALTH" -> "Too many missing articles" + (health?.let { " (health ${it / 10}.${it % 10}%)" } ?: "")
            "FAILURE/FETCH" -> "Couldn't fetch the NZB"
            "WARNING/SCRIPT" -> "A post-processing script failed"
            "WARNING/SPACE" -> "Not enough disk space to unpack"
            "WARNING/PASSWORD" -> "Archive is password-protected"
            "WARNING/DAMAGED" -> "Damaged — repair needed"
            "WARNING/REPAIRABLE" -> "Repairable, but not repaired"
            "WARNING/HEALTH" -> "Health below critical" + (health?.let { " (${it / 10}.${it % 10}%)" } ?: "")
            "WARNING/SKIPPED" -> "Skipped"
            "DELETED/MANUAL" -> "Deleted manually"
            "DELETED/DUPE" -> "Deleted as a duplicate"
            "DELETED/COPY" -> "Deleted — an identical copy exists"
            "DELETED/GOOD" -> "Deleted — another copy succeeded"
            else -> status.takeIf { it.isNotBlank() }
        }
    }
}
