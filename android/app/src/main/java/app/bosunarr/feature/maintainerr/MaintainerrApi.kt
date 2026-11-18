package app.bosunarr.feature.maintainerr

import app.bosunarr.app
import app.bosunarr.data.ServiceConfig
import app.bosunarr.feature.services.enc
import app.bosunarr.net.ServiceException
import app.bosunarr.ui.common.parseInstant
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

/*
 * Maintainerr's REST API (checked against v3.32.0, apps/server/src). It has no auth of its own. Entities come back
 * as TypeORM rows: only the fields the app shows are modelled, everything defaulted (AppJson ignores unknown keys).
 * Several list endpoints answer an empty 200 when something fails server-side, so an empty body is an error here.
 */

/** GET /api/app/status (VersionResponse). */
@Serializable
data class MtVersion(val status: Int = 0, val version: String? = null, val commitTag: String? = null, val updateAvailable: Boolean = false)

/** A Collection row plus the `mediaCount` GET /api/collections adds. */
@Serializable
data class MtCollection(
    val id: Int = 0,
    val title: String = "",
    val description: String? = null,
    val isActive: Boolean = true,
    /** ServarrAction ordinal: what happens to an item when its time is up. */
    val arrAction: Int = 0,
    /** Days an item stays before it's handled; null = no timer (nothing is ever handled). */
    val deleteAfterDays: Int? = null,
    /** "movie", "show", "season" or "episode". */
    val type: String = "movie",
    val libraryId: String = "",
    val mediaCount: Int = 0,
    val totalSizeBytes: Long? = null,
    val handledMediaAmount: Int = 0,
    val handledMediaSizeBytes: Long = 0,
    val manualCollection: Boolean = false,
    val keepInMaintainerrOnly: Boolean = false,
)

/** MediaProviderIds: every id is a list of strings. */
@Serializable
data class MtProviderIds(val tmdb: List<String> = emptyList(), val tvdb: List<String> = emptyList(), val imdb: List<String> = emptyList())

/** The parent (show or season) Maintainerr attaches to a season or episode. */
@Serializable
data class MtParentItem(val id: String = "", val title: String = "", val year: Int? = null, val providerIds: MtProviderIds = MtProviderIds())

/** The server-agnostic MediaItem (Plex/Jellyfin/Emby) a collection row is hydrated with. */
@Serializable
data class MtMediaItem(
    val id: String = "",
    val title: String = "",
    val parentTitle: String? = null,
    val grandparentTitle: String? = null,
    val type: String = "",
    val year: Int? = null,
    /** Season number for a season, episode number for an episode. */
    val index: Int? = null,
    /** Season number for an episode. */
    val parentIndex: Int? = null,
    val providerIds: MtProviderIds = MtProviderIds(),
    val parentItem: MtParentItem? = null,
)

/** A CollectionMedia row with its media-server metadata (`mediaData`). */
@Serializable
data class MtCollectionMedia(
    val id: Int = 0,
    val collectionId: Int = 0,
    val mediaServerId: String = "",
    val tmdbId: Int? = null,
    val tvdbId: Int? = null,
    /** When its timer started; the item is handled `deleteAfterDays` later. Postponing moves this. */
    val addDate: String? = null,
    /** A poster URL (TMDB/TVDB) Maintainerr stored when the item joined. */
    @SerialName("image_path") val imagePath: String? = null,
    val isManual: Boolean? = null,
    val sizeBytes: Long? = null,
    val mediaData: MtMediaItem? = null,
)

/** `{ totalSize, items }` from the paged collection media and exclusion endpoints. */
@Serializable
data class MtPage<T>(val totalSize: Int = 0, val items: List<T> = emptyList())

/** An Exclusion row; `ruleGroupId` null means it's kept from every rule. `parent` is the item the user picked. */
@Serializable
data class MtExclusion(
    val id: Int = 0,
    val mediaServerId: String = "",
    val ruleGroupId: Int? = null,
    val parent: String? = null,
    val type: String? = null,
    val mediaData: MtMediaItem? = null,
)

/** A rule group (GET /api/rules) with its collection joined in. */
@Serializable
data class MtRuleGroup(
    val id: Int = 0,
    val name: String = "",
    val description: String? = null,
    val libraryId: String = "",
    val isActive: Boolean = true,
    val collectionId: Int? = null,
    val useRules: Boolean = true,
    val dataType: String? = null,
    /** Its own schedule, or null to follow the global rule handler schedule. */
    val ruleHandlerCronSchedule: String? = null,
    val rules: List<JsonElement> = emptyList(),
    val collection: MtCollection? = null,
)

/** GET /api/rules/execute/status. */
@Serializable
data class MtExecuteStatus(
    val processingQueue: Boolean = false,
    val executingRuleGroupId: Int? = null,
    val pendingRuleGroupIds: List<Int> = emptyList(),
    val queue: List<Int> = emptyList(),
)

@Serializable
data class MtBulkResult(val mediaId: String = "", val code: Int = 0, val message: String? = null)

@Serializable
data class MtBulkResponse(val results: List<MtBulkResult> = emptyList())

@Serializable
data class MtPostponed(val addDate: String? = null, val deleteAfterDays: Int? = null, val deletionDate: String? = null)

@Serializable
private data class MtImage(val url: String? = null)

/* ================================================================ pure helpers (unit-tested) */

/** ServarrAction ordinals (packages/contracts/src/collections/servarr-action.ts). */
object MtAction {
    const val DELETE = 0
    const val UNMONITOR_DELETE_ALL = 1
    const val UNMONITOR_DELETE_EXISTING = 2
    const val UNMONITOR = 3
    const val DO_NOTHING = 4
    const val DELETE_SHOW_IF_EMPTY = 5
    const val UNMONITOR_SHOW_IF_EMPTY = 6
    const val CHANGE_QUALITY_PROFILE = 7
}

/**
 * The window the collection handler really uses: a quality-profile change has no timer (always due now), and any
 * stored window on it is left over from an earlier action. Mirrors effectiveDeletionWindow in collection-worker.service.ts.
 */
fun effectiveWindow(c: MtCollection): Int? = if (c.arrAction == MtAction.CHANGE_QUALITY_PROFILE) 0 else c.deleteAfterDays

/** Whether the collection handler acts on this collection's items at all (it skips inactive, "Do nothing" and no-timer ones). */
fun actsOnItems(c: MtCollection): Boolean = c.isActive && c.arrAction != MtAction.DO_NOTHING && effectiveWindow(c) != null

/**
 * When Maintainerr handles an item: its addDate plus the window in calendar days (getCollectionDeleteDate). The server
 * adds days in its own time zone; the phone's is the best guess we have, and only matters across a DST change.
 */
fun handleDate(addDate: Instant, windowDays: Int, zone: ZoneId = ZoneId.systemDefault()): Instant =
    addDate.atZone(zone).plusDays(windowDays.toLong()).toInstant()

/** Whole days until [at], rounded up like Maintainerr's own "days left" badge; 0 or less means the next run takes it. */
fun daysUntil(at: Instant, now: Instant = Instant.now()): Int = ceil((at.toEpochMilli() - now.toEpochMilli()) / 86_400_000.0).toInt()

/** Days left for one item in [c], or null when that collection never acts on it. */
fun daysLeft(c: MtCollection, item: MtCollectionMedia, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): Int? {
    if (!actsOnItems(c)) return null
    val window = effectiveWindow(c) ?: return null
    val added = parseInstant(item.addDate) ?: return null
    return daysUntil(handleDate(added, window, zone), now)
}

/** "6 days left", "1 day left"; an overdue item waits for the collection handler's next run. */
fun daysLeftText(days: Int): String = when {
    days <= 0 -> "Goes on the next run"
    days == 1 -> "1 day left"
    else -> "$days days left"
}

/** What happens to an item when its time is up, in a few words, for the collection's media type. */
fun actionLabel(arrAction: Int, type: String = "movie"): String = when (arrAction) {
    MtAction.DELETE -> when (type) {
        "show" -> "Show deleted"
        "season" -> "Season deleted"
        "episode" -> "Episode deleted"
        else -> "Deleted"
    }
    MtAction.UNMONITOR_DELETE_ALL -> "Unmonitored, files deleted"
    MtAction.UNMONITOR_DELETE_EXISTING -> "Unmonitored, existing episodes deleted"
    MtAction.UNMONITOR -> "Unmonitored, files kept"
    MtAction.DO_NOTHING -> "Nothing (listed only)"
    MtAction.DELETE_SHOW_IF_EMPTY -> "Season deleted, and the show if empty"
    MtAction.UNMONITOR_SHOW_IF_EMPTY -> "Season unmonitored, and the show if empty"
    MtAction.CHANGE_QUALITY_PROFILE -> "Quality profile changed"
    else -> "Handled"
}

/** Whether the action frees disk space (deletes files), for warning colours. */
fun deletesFiles(arrAction: Int): Boolean = arrAction in setOf(
    MtAction.DELETE, MtAction.UNMONITOR_DELETE_ALL, MtAction.UNMONITOR_DELETE_EXISTING, MtAction.DELETE_SHOW_IF_EMPTY,
)

/** "Deleted after 30 days", "Unmonitored right away", "No timer". */
fun timerText(c: MtCollection): String {
    val w = effectiveWindow(c)
    val action = actionLabel(c.arrAction, c.type)
    return when {
        c.arrAction == MtAction.DO_NOTHING -> "Listed only, nothing happens"
        w == null -> "No timer: nothing is handled"
        w == 0 -> "$action on the next run"
        w == 1 -> "$action after 1 day"
        else -> "$action after $w days"
    }
}

/** Display title: "Heat (1995)", "The Show · Season 2", "The Show · S02E05 · Name". */
fun mediaTitle(m: MtMediaItem?, fallback: String = "Unknown item"): String {
    if (m == null) return fallback
    val title = m.title.ifBlank { fallback }
    return when (m.type) {
        "season" -> listOfNotNull(m.parentTitle?.takeIf { it.isNotBlank() } ?: m.parentItem?.title?.takeIf { it.isNotBlank() }, if (m.index != null) "Season ${m.index}" else title).joinToString(" · ")
        "episode" -> listOfNotNull(
            m.grandparentTitle?.takeIf { it.isNotBlank() } ?: m.parentItem?.title?.takeIf { it.isNotBlank() },
            if (m.parentIndex != null && m.index != null) "S%02dE%02d".format(m.parentIndex, m.index) else null,
            title,
        ).joinToString(" · ")
        else -> if (m.year != null && m.year > 0) "$title (${m.year})" else title
    }
}

/** The poster Maintainerr stored, when it's a full URL (older rows may hold nothing usable). */
fun storedPoster(item: MtCollectionMedia): String? =
    item.imagePath?.takeIf { it.startsWith("https://") || it.startsWith("http://") }

/**
 * Maintainerr's own poster lookup for an item without a stored one (what its web UI calls):
 * `/api/metadata/image/{movie|show}?tmdbId=…&tvdbId=…`, plus `itemId` for a season or episode so it can find the show.
 * Null when there's no provider id to look up.
 */
fun posterLookupPath(item: MtCollectionMedia): String? {
    val m = item.mediaData
    val type = m?.type?.ifBlank { null } ?: "movie"
    val tmdb = m?.providerIds?.tmdb?.firstOrNull() ?: item.tmdbId?.toString()
    val tvdb = m?.providerIds?.tvdb?.firstOrNull() ?: item.tvdbId?.toString()
    val params = buildList {
        tmdb?.let { add("tmdbId=" + enc(it)) }
        tvdb?.let { add("tvdbId=" + enc(it)) }
        if (isEmpty()) return null
        if (type == "season" || type == "episode") add("itemId=" + enc(item.mediaServerId))
    }
    val kind = if (type == "movie") "movie" else "show"
    return "/api/metadata/image/$kind?" + params.joinToString("&")
}

/** A ReturnStatus-style bulk answer as an error when the item wasn't handled (Maintainerr answers 201 either way). */
fun bulkFailure(r: MtBulkResponse, mediaId: String): String? {
    val res = r.results.firstOrNull { it.mediaId == mediaId } ?: r.results.firstOrNull() ?: return "Maintainerr didn't say whether it worked"
    if (res.code == 1) return null
    return res.message?.removePrefix("Failed - ")?.replaceFirstChar { it.uppercase() }?.takeIf { it.isNotBlank() && it != "Failed" }
        ?: "Maintainerr couldn't do that"
}

/* ================================================================ screen models */

data class MtLeavingItem(val media: MtCollectionMedia, val title: String, val daysLeft: Int?, val poster: String?)

/** One collection's items, soonest first, with the rule group that fills it (null for a manual collection). */
data class MtLeavingGroup(val collection: MtCollection, val rule: MtRuleGroup?, val items: List<MtLeavingItem>, val total: Int) {
    val soonest: Int? get() = items.mapNotNull { it.daysLeft }.minOrNull()
}

/** Collections that will act on their items, soonest first; items within each soonest first. */
fun leavingOrder(groups: List<MtLeavingGroup>): List<MtLeavingGroup> =
    groups.sortedWith(compareBy<MtLeavingGroup> { it.soonest ?: Int.MAX_VALUE }.thenBy { it.collection.title.lowercase() })
        .map { g -> g.copy(items = g.items.sortedBy { it.daysLeft ?: Int.MAX_VALUE }) }

/* ================================================================ network */

/** Read a list/page endpoint that answers an empty 200 when it fails server-side. */
private suspend inline fun <reified T> getOrFail(s: ServiceConfig, path: String): T {
    val text = app.client.request(s, path)
    if (text.isBlank()) throw ServiceException("${s.name} couldn't read that (check Maintainerr's logs)")
    return app.client.decodeOffMain(s, text, kotlinx.serialization.serializer<T>())
}

suspend fun mtVersion(s: ServiceConfig): MtVersion = app.client.get(s, "/api/app/status")

suspend fun mtCollections(s: ServiceConfig): List<MtCollection> = getOrFail(s, "/api/collections")

suspend fun mtRuleGroups(s: ServiceConfig): List<MtRuleGroup> = getOrFail(s, "/api/rules")

suspend fun mtExecuteStatus(s: ServiceConfig): MtExecuteStatus = app.client.get(s, "/api/rules/execute/status")

/** One page of a collection's items, soonest-to-go first (`deleteSoonest` asc pages by addDate in SQL). */
suspend fun mtCollectionMedia(s: ServiceConfig, collectionId: Int, size: Int): MtPage<MtCollectionMedia> =
    getOrFail(s, "/api/collections/media/$collectionId/content/1?size=$size&sort=deleteSoonest&sortOrder=asc")

/** What's kept from a collection's rule: its own exclusions plus the ones kept from every rule. */
suspend fun mtExclusions(s: ServiceConfig, collectionId: Int, size: Int = 100): MtPage<MtExclusion> =
    getOrFail(s, "/api/collections/exclusions/$collectionId/content/1?size=$size")

/** Poster URLs found through Maintainerr's metadata lookup, by service + media id ("" = none found). */
private val posterCache = ConcurrentHashMap<String, String>()
private val posterLookups = Semaphore(4)

private suspend fun lookupPoster(s: ServiceConfig, item: MtCollectionMedia): String? {
    storedPoster(item)?.let { return it }
    val key = "${s.id}:${item.mediaServerId}"
    posterCache[key]?.let { return it.ifEmpty { null } }
    val path = posterLookupPath(item) ?: return null
    val url = posterLookups.withPermit {
        runCatching {
            val text = app.client.request(s, path)
            if (text.isBlank()) null else app.client.decode(s, text, MtImage.serializer()).url
        }.getOrNull()
    }
    posterCache[key] = url.orEmpty()
    return url?.ifEmpty { null }
}

/** Every collection Maintainerr will act on, with its items soonest first (up to [perCollection] each). */
suspend fun loadLeaving(s: ServiceConfig, perCollection: Int = 50): List<MtLeavingGroup> = coroutineScope {
    val rules = async { runCatching { mtRuleGroups(s) }.getOrDefault(emptyList()) }
    val acting = mtCollections(s).filter { actsOnItems(it) && it.mediaCount > 0 }
    val ruleByCollection = rules.await().filter { it.collectionId != null }.associateBy { it.collectionId }
    val groups = acting.map { c ->
        async {
            val page = mtCollectionMedia(s, c.id, perCollection)
            val items = page.items.map { m ->
                async { MtLeavingItem(m, mediaTitle(m.mediaData, m.mediaServerId), daysLeft(c, m), lookupPoster(s, m)) }
            }.awaitAll()
            MtLeavingGroup(c, ruleByCollection[c.id], items, maxOf(page.totalSize, c.mediaCount))
        }
    }.awaitAll()
    leavingOrder(groups)
}

/** Collections joined with the rule group that fills each (Rules tab). */
data class MtRuleRow(val collection: MtCollection?, val rule: MtRuleGroup?) {
    val title: String get() = rule?.name?.ifBlank { null } ?: collection?.title ?: "Untitled"
    val active: Boolean get() = (rule?.isActive ?: true) && (collection?.isActive ?: true)
    /** A rule run needs an active rule group with a library (rules.controller.ts executeRule). */
    val canRun: Boolean get() = rule != null && rule.isActive && rule.libraryId.isNotBlank()
}

/** Rule groups (with their collections) plus collections no rule group fills, active first. */
fun ruleRows(collections: List<MtCollection>, rules: List<MtRuleGroup>): List<MtRuleRow> {
    val byId = collections.associateBy { it.id }
    val fromRules = rules.map { r -> MtRuleRow(r.collectionId?.let { byId[it] } ?: r.collection, r) }
    val claimed = rules.mapNotNull { it.collectionId }.toSet()
    val orphans = collections.filter { it.id !in claimed }.map { MtRuleRow(it, null) }
    return (fromRules + orphans).sortedWith(compareBy<MtRuleRow> { !it.active }.thenBy { it.title.lowercase() })
}

data class MtRulesOverview(val rows: List<MtRuleRow>, val status: MtExecuteStatus?)

suspend fun loadRules(s: ServiceConfig): MtRulesOverview = coroutineScope {
    val rules = async { mtRuleGroups(s) }
    val status = async { runCatching { mtExecuteStatus(s) }.getOrNull() }
    val collections = mtCollections(s)
    MtRulesOverview(ruleRows(collections, rules.await()), status.await())
}

/** An exclusion as shown on the Kept tab, with what it's kept from. */
data class MtKept(val exclusion: MtExclusion, val title: String, val from: String, val collectionId: Int?)

/** Everything kept, across every collection a rule fills; an exclusion kept from every rule shows once. */
suspend fun loadKept(s: ServiceConfig): List<MtKept> = coroutineScope {
    val rules = mtRuleGroups(s).filter { it.collectionId != null }
    val ruleById = rules.associateBy { it.id }
    val pages = rules.map { r -> async { runCatching { mtExclusions(s, r.collectionId!!) }.getOrNull() } }.awaitAll()
    if (rules.isNotEmpty() && pages.all { it == null }) throw ServiceException("${s.name} couldn't list what's kept")
    pages.filterNotNull().flatMap { it.items }.distinctBy { it.id }.map { e ->
        val rule = e.ruleGroupId?.let { ruleById[it] }
        MtKept(e, mediaTitle(e.mediaData, e.mediaServerId), rule?.name?.let { "Kept from “$it”" } ?: "Kept from every rule", rule?.collectionId)
    }.sortedBy { it.title.lowercase() }
}

/**
 * Keep an item: exclude it from [collectionId]'s rule (or from every rule when null) and take it out of the
 * collection(s), as the web UI's Exclude does (POST /api/rules/exclusions/bulk, action 0).
 */
suspend fun mtKeep(s: ServiceConfig, mediaServerId: String, collectionId: Int?) = bulkExclusion(s, mediaServerId, collectionId, exclude = true)

/** Undo a keep (action 1): the next rule run may collect the item again. */
suspend fun mtUnkeep(s: ServiceConfig, mediaServerId: String, collectionId: Int?) = bulkExclusion(s, mediaServerId, collectionId, exclude = false)

/** The bulk endpoint, not POST /api/rules/exclusion: it's what the web UI uses, and it also takes the item out of the collection. */
private suspend fun bulkExclusion(s: ServiceConfig, mediaServerId: String, collectionId: Int?, exclude: Boolean) {
    val body = buildJsonObject {
        putJsonArray("mediaIds") { add(JsonPrimitive(mediaServerId)) }
        if (collectionId != null) put("collectionId", collectionId)
        put("action", if (exclude) 0 else 1)
    }
    val text = app.client.send(s, "/api/rules/exclusions/bulk", "POST", body)
    val res = app.client.decode(s, text, MtBulkResponse.serializer())
    bulkFailure(res, mediaServerId)?.let { throw ServiceException(it) }
}

/** Push an item's deadline out by [days] (from today if it's already overdue). Returns the new handling date. */
suspend fun mtPostpone(s: ServiceConfig, collectionId: Int, mediaServerId: String, days: Int): MtPostponed {
    val body = buildJsonObject {
        put("collectionId", collectionId)
        put("mediaId", mediaServerId)
        put("days", days)
    }
    return app.client.decode(s, app.client.send(s, "/api/collections/media/postpone", "POST", body), MtPostponed.serializer())
}

/** Queue every active rule group (POST /api/rules/execute). Only updates collections; deleting is the handler's job. */
suspend fun mtRunAllRules(s: ServiceConfig) {
    app.client.send(s, "/api/rules/execute", "POST")
}

/** Queue one rule group (POST /api/rules/:id/execute). */
suspend fun mtRunRule(s: ServiceConfig, ruleGroupId: Int) {
    app.client.send(s, "/api/rules/$ruleGroupId/execute", "POST")
}
