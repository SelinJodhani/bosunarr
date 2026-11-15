package app.bosunarr.feature.cleanuparr

import app.bosunarr.app
import app.bosunarr.data.ServiceConfig
import app.bosunarr.data.ServiceType
import app.bosunarr.feature.services.enc
import app.bosunarr.net.ServiceException
import app.bosunarr.net.fanOut
import app.bosunarr.ui.common.parseInstant
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

/*
 * Cleanuparr v2 API (X-Api-Key, the per-user key from Settings → Account). Enums arrive as strings. Only the fields
 * the app shows; everything defaulted (AppJson ignores unknown keys).
 */

@Serializable
data class ClPage<T>(val items: List<T> = emptyList(), val page: Int = 1, val pageSize: Int = 0, val totalCount: Int = 0, val totalPages: Int = 0)

@Serializable
data class ClEvent(
    val id: String = "",
    val timestamp: String? = null,
    val eventType: String = "",
    val message: String = "",
    val severity: String = "",
    val isDryRun: Boolean = false,
    val itemTitle: String? = null,
    val itemHash: String? = null,
    val strikeCount: Int? = null,
    val deleteReason: String? = null,
    val cleanReason: String? = null,
    val searchReason: String? = null,
    val grabbedItems: List<String> = emptyList(),
)

@Serializable
data class ClStrikeDetail(val id: String = "", val type: String = "", val createdAt: String? = null, val isDryRun: Boolean = false)

@Serializable
data class ClStrikeItem(
    val downloadItemId: String = "",
    val downloadId: String = "",
    val title: String = "",
    val totalStrikes: Int = 0,
    val strikesByType: Map<String, Int> = emptyMap(),
    val latestStrikeAt: String? = null,
    val firstStrikeAt: String? = null,
    val isMarkedForRemoval: Boolean = false,
    val isRemoved: Boolean = false,
    val isReturning: Boolean = false,
    val hasDryRunStrikes: Boolean = false,
    val strikes: List<ClStrikeDetail> = emptyList(),
)

@Serializable
data class ClManualEvent(
    val id: String = "",
    val timestamp: String? = null,
    val message: String = "",
    val severity: String = "",
    val type: String = "",
    val itemTitle: String? = null,
    val isResolved: Boolean = false,
    val downloadClientName: String? = null,
)

@Serializable
data class ClJob(
    val name: String = "",
    val status: String = "",
    val schedule: String? = null,
    val nextRunTime: String? = null,
    val previousRunTime: String? = null,
    val jobType: String = "",
)

@Serializable
data class ClCount(val total: Int = 0, val byType: Map<String, Int> = emptyMap(), val byReason: Map<String, Int> = emptyMap())

@Serializable
data class ClSearches(val total: Int = 0, val completed: Int = 0, val failed: Int = 0, val grabbed: Int = 0)

@Serializable
data class ClStats(
    val events: ClCount = ClCount(),
    val strikes: ClCount = ClCount(),
    val removals: ClCount = ClCount(),
    val cleaned: ClCount = ClCount(),
    val searches: ClSearches = ClSearches(),
)

@Serializable
data class ClApplication(val version: String? = null, val startTime: String? = null)

@Serializable
data class ClStatus(val application: ClApplication = ClApplication())

/* ================================================================ pure helpers (unit-tested) */

/**
 * Cleanuparr's strike limit per strike type, read from the queue-cleaner config and the stall/slow rules (the
 * strictest enabled rule wins). Null for a type with no known limit (dead torrent limits live per download client).
 * A limit of 0 means that check is off.
 */
fun strikeLimits(queueCleaner: JsonObject?, stallRules: JsonArray?, slowRules: JsonArray?): Map<String, Int> = buildMap {
    fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.content?.toIntOrNull()
    fun JsonElement?.bool(): Boolean = (this as? JsonPrimitive)?.content == "true"
    queueCleaner?.let { q ->
        (q["failedImport"] as? JsonObject)?.get("maxStrikes").int()?.let { put("FailedImport", it) }
        q["downloadingMetadataMaxStrikes"].int()?.let { put("DownloadingMetadata", it) }
        (q["errored"] as? JsonObject)?.get("maxStrikes").int()?.let { put("Errored", it) }
    }
    fun minEnabled(rules: JsonArray?): Int? = rules.orEmpty().mapNotNull { it as? JsonObject }
        .filter { it["enabled"].bool() }.mapNotNull { it["maxStrikes"].int() }.filter { it > 0 }.minOrNull()
    minEnabled(stallRules)?.let { put("Stalled", it) }
    minEnabled(slowRules)?.let { put("SlowSpeed", it); put("SlowTime", it) }
}

/** "Stuck on metadata", "Failed import"… for a strike type (StrikeType / the strike part of an EventType). */
fun strikeLabel(type: String): String = when (type.removeSuffix("Strike")) {
    "Stalled" -> "Stalled"
    "DownloadingMetadata" -> "Stuck on metadata"
    "FailedImport" -> "Failed import"
    "SlowSpeed" -> "Too slow"
    "SlowTime" -> "Taking too long"
    "DeadTorrent" -> "Dead torrent"
    "Errored" -> "Errored"
    else -> splitCamel(type)
}

/** What an item's strikes look like on a torrent row: the type closest to its limit. */
data class StrikeBadge(val count: Int, val limit: Int?, val label: String, val marked: Boolean) {
    val text: String get() = (if (limit != null && limit > 0) "$count of $limit strikes" else if (count == 1) "1 strike" else "$count strikes") + " · $label"
    /** One more strike removes it (or it's already marked). */
    val critical: Boolean get() = marked || (limit != null && limit > 0 && count >= limit - 1)
}

fun badgeFor(item: ClStrikeItem, limits: Map<String, Int>): StrikeBadge? {
    if (item.isRemoved || item.totalStrikes <= 0) return null
    val worst = item.strikesByType.filterValues { it > 0 }.maxByOrNull { (type, n) ->
        val lim = limits[type]?.takeIf { it > 0 }
        if (lim != null) n.toDouble() / lim else n / 100.0
    } ?: return null
    return StrikeBadge(worst.value, limits[worst.key], strikeLabel(worst.key), item.isMarkedForRemoval)
}

fun splitCamel(s: String): String = s.replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase().replaceFirstChar { it.uppercase() }

/** Event type → short label for the activity list. */
fun eventLabel(type: String): String = when (type) {
    "QueueItemDeleted" -> "Removed from queue"
    "DownloadMarkedForDeletion" -> "Marked for removal"
    "DownloadCleaned" -> "Cleaned up"
    "CategoryChanged" -> "Category changed"
    "SearchTriggered" -> "Searched for a replacement"
    "StrikeReset" -> "Strikes cleared"
    "ForceImported" -> "Force imported"
    "DownloadStopped" -> "Stopped"
    "SeekerCycleCompleted" -> "Search cycle done"
    else -> if (type.endsWith("Strike")) "Strike · " + strikeLabel(type) else splitCamel(type)
}

/** Activity filter → Cleanuparr event types (null = everything). */
enum class ClFilter(val label: String, val types: List<String>?) {
    All("All", null),
    Strikes("Strikes", listOf("FailedImportStrike", "StalledStrike", "DownloadingMetadataStrike", "SlowSpeedStrike", "SlowTimeStrike", "DeadTorrentStrike", "ErroredStrike")),
    Removals("Removals", listOf("QueueItemDeleted", "DownloadMarkedForDeletion", "DownloadCleaned")),
    Searches("Searches", listOf("SearchTriggered", "SeekerCycleCompleted")),
}

/** "QueueCleaner" → "Queue cleaner". */
fun jobLabel(type: String): String = when (type) {
    "MalwareBlocker" -> "Malware blocker"
    "BlacklistSynchronizer" -> "Blocklist sync"
    "CustomFormatScoreSyncer" -> "Custom format sync"
    else -> splitCamel(type)
}

/* ================================================================ network */

suspend fun clStatus(s: ServiceConfig): ClStatus = app.client.get(s, "/api/status")

suspend fun clStats(s: ServiceConfig, hours: Int): ClStats = app.client.get(s, "/api/v2/stats?hours=$hours")

suspend fun clJobs(s: ServiceConfig): List<ClJob> = app.client.get(s, "/api/jobs")

suspend fun clTriggerJob(s: ServiceConfig, jobType: String) {
    app.client.send(s, "/api/jobs/${enc(jobType)}/trigger", "POST")
}

suspend fun clAttention(s: ServiceConfig): List<ClManualEvent> =
    app.client.get<ClPage<ClManualEvent>>(s, "/api/manualevents?page=1&pageSize=50&isResolved=false").items

suspend fun clResolve(s: ServiceConfig, id: String) {
    app.client.send(s, "/api/manualevents/${enc(id)}/resolve", "POST")
}

/** One page of events; Cleanuparr filters by a single event type, so a multi-type filter merges per-type pages. */
suspend fun clEvents(s: ServiceConfig, filter: ClFilter, page: Int, pageSize: Int = 30): ClPage<ClEvent> {
    val types = filter.types ?: return app.client.get(s, "/api/events?page=$page&pageSize=$pageSize")
    val pages = coroutineScope {
        types.map { t -> async { runCatching { app.client.get<ClPage<ClEvent>>(s, "/api/events?page=$page&pageSize=$pageSize&eventType=${enc(t)}") }.getOrNull() } }
            .map { it.await() }
    }.filterNotNull()
    val items = pages.flatMap { it.items }.sortedByDescending { parseInstant(it.timestamp) ?: Instant.EPOCH }
    return ClPage(items, page, pageSize, pages.sumOf { it.totalCount }, pages.maxOfOrNull { it.totalPages } ?: 0)
}

suspend fun clStrikes(s: ServiceConfig): List<ClStrikeItem> = app.client.get<ClPage<ClStrikeItem>>(s, "/api/strikes?page=1&pageSize=100").items

suspend fun clForgive(s: ServiceConfig, downloadItemId: String) {
    app.client.send(s, "/api/strikes/${enc(downloadItemId)}", "DELETE")
}

suspend fun clLimits(s: ServiceConfig): Map<String, Int> = coroutineScope {
    val q = async { runCatching { app.client.json(s, "/api/configuration/queue_cleaner") as? JsonObject }.getOrNull() }
    val stall = async { runCatching { app.client.json(s, "/api/queue-rules/stall") as? JsonArray }.getOrNull() }
    val slow = async { runCatching { app.client.json(s, "/api/queue-rules/slow") as? JsonArray }.getOrNull() }
    strikeLimits(q.await(), stall.await(), slow.await())
}

/** Strike badges for every torrent Cleanuparr is watching, by lower-case info hash, across all Cleanuparr services. */
suspend fun loadStrikeBadges(): Map<String, StrikeBadge> {
    val cls = app.services.value.filter { it.serviceType == ServiceType.Cleanuparr }
    if (cls.isEmpty()) return emptyMap()
    return fanOut(cls) { s ->
        coroutineScope {
            val limits = async { clLimits(s) }
            val items = clStrikes(s)
            val l = limits.await()
            items.mapNotNull { i -> badgeFor(i, l)?.let { i.downloadId.lowercase() to it } }
        }
    }.flatMap { it.second.getOrNull().orEmpty() }.toMap()
}

/** The dashboard's Cleanup card: the last 7 days across every Cleanuparr, plus downloads one strike from removal. */
data class CleanupSummary(val strikes: Int, val removed: Int, val replacements: Int, val atRisk: Int, val errors: List<String>)

suspend fun loadCleanupSummary(cls: List<ServiceConfig>): CleanupSummary {
    val r = fanOut(cls) { s ->
        coroutineScope {
            val stats = async { clStats(s, 168) }
            val limits = async { runCatching { clLimits(s) }.getOrDefault(emptyMap()) }
            val items = runCatching { clStrikes(s) }.getOrDefault(emptyList())
            stats.await() to items.count { badgeFor(it, limits.await())?.critical == true }
        }
    }
    val ok = r.mapNotNull { it.second.getOrNull() }
    val errors = r.mapNotNull { (s, x) -> x.exceptionOrNull()?.let { "${s.name}: ${it.message ?: "unreachable"}" } }
    if (ok.isEmpty() && errors.isNotEmpty()) throw ServiceException(errors.first())
    return CleanupSummary(
        strikes = ok.sumOf { it.first.strikes.total },
        removed = ok.sumOf { it.first.removals.total },
        replacements = ok.sumOf { it.first.searches.total },
        atRisk = ok.sumOf { it.second },
        errors = errors,
    )
}
