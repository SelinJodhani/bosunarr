package app.bosunarr.feature.usenet

import app.bosunarr.app
import app.bosunarr.data.ServiceConfig
import app.bosunarr.data.ServiceType
import app.bosunarr.net.ServiceException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * SABnzbd's `/api?mode=…&output=json`. The API key is added to every URL by the core client (SABnzbd only reads
 * it from the query string). SABnzbd sends most numbers as strings ("1277.65"), sizes in MiB, speeds in KiB/s and
 * free disk in GiB, so everything goes through the lenient helpers in UsenetJson.kt.
 */
internal object Sab {
    private const val MIB = 1024L * 1024
    private const val GIB = 1024L * 1024 * 1024

    private suspend fun call(s: ServiceConfig, query: String): JsonObject {
        val text = app.client.text(s, "/api?output=json&$query")
        return app.client.decodeOffMain(s, text, JsonElement.serializer()).obj()
            ?: throw ServiceException("${s.name} gave an unexpected answer")
    }

    /** Commands answer `{"status": true}` (or a `result`); the core client already turned `status: false` into an error. */
    private suspend fun command(s: ServiceConfig, query: String) { call(s, query) }

    private fun ids(list: Collection<String>) = list.joinToString(",") { enc(it) }

    // ------------------------------------------------------------------ reads

    suspend fun queue(s: ServiceConfig): UsenetSnapshot = parseQueue(s, call(s, "mode=queue&start=0&limit=500"))

    internal fun parseQueue(s: ServiceConfig, root: JsonObject): UsenetSnapshot {
        val q = root["queue"].obj() ?: throw ServiceException("${s.name} gave an unexpected answer")
        val kbps = q["kbpersec"].dbl() ?: 0.0
        val limitAbs = q["speedlimit_abs"].dbl() ?: 0.0
        val disks = listOfNotNull(q["diskspace1"].dbl(), q["diskspace2"].dbl()).filter { it >= 0 }
        val paused = q["paused"].bool() ?: (q["status"].text() == "Paused")
        val server = NzbServer(
            down = (kbps * 1024).toLong(),
            // With bandwidth_max set and no limit this is the max itself, which is still the effective cap.
            limit = limitAbs.toLong().coerceAtLeast(0),
            paused = paused,
            left = ((q["mbleft"].dbl() ?: 0.0) * MIB).toLong(),
            timeLeft = clock(q["timeleft"].text()),
            freeDisk = disks.minOrNull()?.let { (it * GIB).toLong() },
            version = q["version"].text(),
        )
        val items = q["slots"].arr().orEmpty().mapIndexedNotNull { i, el -> el.obj()?.let { item(s, it, i) } }
        return UsenetSnapshot(s.id, s.name, ServiceType.Sabnzbd, server, items)
    }

    private fun item(s: ServiceConfig, o: JsonObject, position: Int): NzbItem? {
        val id = o["nzo_id"].text() ?: return null
        val size = ((o["mb"].dbl() ?: 0.0) * MIB).toLong()
        val left = ((o["mbleft"].dbl() ?: 0.0) * MIB).toLong()
        val status = o["status"].text().orEmpty()
        val state = when (status.lowercase()) {
            "downloading" -> NzbState.Downloading
            "paused" -> NzbState.Paused
            "grabbing", "fetching", "propagating" -> NzbState.Fetching
            "queued", "idle", "" -> NzbState.Queued
            "checking", "quickcheck", "verifying", "repairing", "extracting", "moving", "running" -> NzbState.Processing
            else -> NzbState.Queued
        }
        val pct = o["percentage"].dbl() ?: if (size > 0) (size - left) * 100.0 / size else 0.0
        return NzbItem(
            serviceId = s.id,
            serviceName = s.name,
            type = ServiceType.Sabnzbd,
            id = id,
            name = o["filename"].text() ?: o["name"].text() ?: id,
            category = cat(o["cat"].text()),
            priority = priority(o["priority"]),
            state = state,
            stateLabel = status.ifEmpty { "Queued" },
            size = size,
            left = left,
            progress = (pct / 100.0).toFloat().coerceIn(0f, 1f),
            eta = if (state == NzbState.Paused) null else clock(o["timeleft"].text()),
            index = o["index"].lng()?.toInt() ?: position,
            labels = o["labels"].arr().orEmpty().mapNotNull { it.text() },
        )
    }

    suspend fun history(s: ServiceConfig, limit: Int, failedOnly: Boolean): List<NzbHistoryItem> =
        parseHistory(s, call(s, "mode=history&start=0&limit=$limit" + if (failedOnly) "&failed_only=1" else ""))

    internal fun parseHistory(s: ServiceConfig, root: JsonObject): List<NzbHistoryItem> {
        val h = root["history"].obj()
            ?: throw ServiceException("${s.name} gave an unexpected answer")
        return h["slots"].arr().orEmpty().mapNotNull { el ->
            val o = el.obj() ?: return@mapNotNull null
            val id = o["nzo_id"].text() ?: return@mapNotNull null
            val status = o["status"].text().orEmpty()
            val outcome = when (status.lowercase()) {
                "completed" -> NzbOutcome.Success
                "failed" -> NzbOutcome.Failed
                "deleted" -> NzbOutcome.Deleted
                else -> NzbOutcome.Processing
            }
            val failMessage = o["fail_message"].text()?.takeIf { it.isNotBlank() }
            NzbHistoryItem(
                serviceId = s.id,
                serviceName = s.name,
                type = ServiceType.Sabnzbd,
                id = id,
                name = o["name"].text() ?: o["nzb_name"].text() ?: id,
                category = cat(o["category"].text()),
                size = o["bytes"].lng() ?: 0,
                completedAt = o["completed"].lng() ?: 0,
                outcome = outcome,
                stateLabel = status.ifEmpty { "Unknown" },
                reason = failMessage,
                downloadSeconds = o["download_time"].lng() ?: 0,
                // `retry` is 1 when the failed job's files are still there to retry with.
                canRetry = outcome == NzbOutcome.Failed && (o["retry"]?.let { r -> (r.lng() ?: 0) > 0 || r.bool() == true } ?: true),
            )
        }
    }

    suspend fun categories(s: ServiceConfig): List<String> =
        call(s, "mode=get_cats")["categories"].arr().orEmpty().mapNotNull { it.text() }.filter { it.isNotBlank() && it != "*" }

    // ------------------------------------------------------------------ actions

    suspend fun pauseAll(s: ServiceConfig) = command(s, "mode=pause")
    suspend fun resumeAll(s: ServiceConfig) = command(s, "mode=resume")
    /** Pause the whole queue for [minutes]; SABnzbd resumes on its own. */
    suspend fun pauseFor(s: ServiceConfig, minutes: Int) = command(s, "mode=config&name=set_pause&value=$minutes")

    suspend fun pause(s: ServiceConfig, ids: Collection<String>) = command(s, "mode=queue&name=pause&value=${ids(ids)}")
    suspend fun resume(s: ServiceConfig, ids: Collection<String>) = command(s, "mode=queue&name=resume&value=${ids(ids)}")

    suspend fun delete(s: ServiceConfig, ids: Collection<String>, deleteFiles: Boolean) =
        command(s, "mode=queue&name=delete&value=${ids(ids)}" + if (deleteFiles) "&del_files=1" else "")

    /** `ids` in queue order; moved bottom-up so they keep their relative order at the top. */
    suspend fun moveTop(s: ServiceConfig, ids: List<String>) {
        ids.asReversed().forEach { command(s, "mode=switch&value=${enc(it)}&value2=0") }
    }

    suspend fun setPriority(s: ServiceConfig, ids: Collection<String>, p: NzbPriority) {
        val v = when (p) {
            NzbPriority.Force -> 2
            NzbPriority.VeryHigh, NzbPriority.High -> 1
            NzbPriority.Normal -> 0
            NzbPriority.Low, NzbPriority.VeryLow -> -1
        }
        ids.forEach { command(s, "mode=queue&name=priority&value=${enc(it)}&value2=$v") }
    }

    suspend fun setCategory(s: ServiceConfig, ids: Collection<String>, category: String) {
        val c = category.ifBlank { "*" }
        ids.forEach { command(s, "mode=change_cat&value=${enc(it)}&value2=${enc(c)}") }
    }

    /** [bytesPerSec] 0 removes the limit (SABnzbd treats an absolute 0 as unlimited). */
    suspend fun setSpeedLimit(s: ServiceConfig, bytesPerSec: Long) {
        // A bare number from 1 to 100 means a percentage to SABnzbd, so always send an absolute "<n>K".
        val v = if (bytesPerSec <= 0) "0" else "${(bytesPerSec / 1024).coerceAtLeast(1)}K"
        command(s, "mode=config&name=speedlimit&value=$v")
    }

    suspend fun deleteHistory(s: ServiceConfig, ids: Collection<String>, deleteFiles: Boolean) =
        command(s, "mode=history&name=delete&value=${ids(ids)}" + if (deleteFiles) "&del_files=1" else "")

    suspend fun retry(s: ServiceConfig, id: String) = command(s, "mode=retry&value=${enc(id)}")

    private fun addParams(category: String?, priority: NzbPriority?, paused: Boolean): String = buildString {
        if (!category.isNullOrBlank()) append("&cat=").append(enc(category))
        val p = when {
            paused -> -2
            priority == null -> -100
            priority == NzbPriority.Force -> 2
            priority == NzbPriority.High || priority == NzbPriority.VeryHigh -> 1
            priority == NzbPriority.Normal -> 0
            else -> -1
        }
        append("&priority=").append(p)
    }

    suspend fun addUrl(s: ServiceConfig, url: String, category: String?, priority: NzbPriority?, paused: Boolean) {
        val r = call(s, "mode=addurl&name=${enc(url)}" + addParams(category, priority, paused))
        if (r["status"].bool() == false) throw ServiceException("${s.name} rejected the link", 400)
    }

    suspend fun addFile(s: ServiceConfig, file: NzbUpload, category: String?, priority: NzbPriority?, paused: Boolean) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("mode", "addfile")
            .addFormDataPart("output", "json")
            .apply {
                if (!category.isNullOrBlank()) addFormDataPart("cat", category)
                addFormDataPart("priority", addParams(null, priority, paused).substringAfter("priority="))
            }
            .addFormDataPart("name", file.name, file.bytes.toRequestBody("application/x-nzb".toMediaType()))
            .build()
        val text = app.client.multipart(s, "/api", body)
        val r = app.client.decodeOffMain(s, text, JsonElement.serializer()).obj()
        val ids = r?.get("nzo_ids").arr()
        if (r == null || r["status"].bool() == false || (ids != null && ids.isEmpty())) throw ServiceException("${s.name} rejected ${file.name}", 400)
    }

    // ------------------------------------------------------------------ parsing helpers

    /** SABnzbd's "*" is "the default category", i.e. none picked. */
    private fun cat(c: String?): String = c?.takeIf { it != "*" && it != "None" }.orEmpty()

    /** SABnzbd sends the priority as text ("High") in recent versions and as a number in older ones. */
    private fun priority(el: JsonElement?): NzbPriority {
        val t = el.text()?.trim().orEmpty()
        t.toIntOrNull()?.let { n ->
            return when {
                n >= 2 -> NzbPriority.Force
                n == 1 -> NzbPriority.High
                n == -1 -> NzbPriority.Low
                else -> NzbPriority.Normal
            }
        }
        return when (t.lowercase()) {
            "force" -> NzbPriority.Force
            "high" -> NzbPriority.High
            "low" -> NzbPriority.Low
            else -> NzbPriority.Normal
        }
    }

    /** "0:16:44" or "1:02:16:44" (days first) → seconds; null for zero or unparseable. */
    internal fun clock(t: String?): Long? {
        val parts = t?.trim()?.split(':')?.map { it.trim().toLongOrNull() ?: return null } ?: return null
        if (parts.isEmpty() || parts.size > 4) return null
        val weights = listOf(1L, 60L, 3600L, 86400L)
        val secs = parts.asReversed().withIndex().sumOf { (i, v) -> v * weights[i] }
        return secs.takeIf { it > 0 }
    }
}
