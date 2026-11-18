package app.bosunarr.feature.autobrr

import app.bosunarr.app
import app.bosunarr.data.ServiceConfig
import app.bosunarr.feature.services.enc
import app.bosunarr.net.ServiceException
import app.bosunarr.ui.common.parseInstant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant

/*
 * Autobrr REST API under /api (X-API-Token header; models from internal/domain and internal/http at v1.90.0). JSON
 * keys are Go's snake_case json tags. Go serialises nil slices as null and zero times as "0001-01-01T00:00:00Z", so
 * every list defaults to empty (AppJson coerces null) and every time goes through [abInstant].
 */

/** GET /api/config (internal/http/config.go configJson): only the build info the app shows. */
@Serializable
data class AbConfig(val version: String = "", val commit: String = "", val date: String = "")

/** GET /api/release/stats (domain.ReleaseStats): all-time counts. */
@Serializable
data class AbStats(
    @SerialName("total_count") val total: Long = 0,
    @SerialName("filtered_count") val filtered: Long = 0,
    @SerialName("filter_rejected_count") val filterRejected: Long = 0,
    @SerialName("push_approved_count") val approved: Long = 0,
    @SerialName("push_rejected_count") val rejected: Long = 0,
    @SerialName("push_error_count") val errors: Long = 0,
)

/** One day of GET /api/release/stats/activity (domain.ReleaseActivityDaily). */
@Serializable
data class AbDay(
    val date: String = "",
    @SerialName("matched_count") val matched: Long = 0,
    @SerialName("push_approved_count") val approved: Long = 0,
    @SerialName("push_rejected_count") val rejected: Long = 0,
    @SerialName("push_error_count") val errors: Long = 0,
)

@Serializable
data class AbActivity(val days: Int = 0, val daily: List<AbDay> = emptyList())

/** A row of GET /api/release/stats/filters or /indexers (domain.ReleaseFilterStats / ReleaseIndexerStats). */
@Serializable
data class AbTopRow(
    val filter: String = "",
    val indexer: String = "",
    @SerialName("matched_count") val matched: Long = 0,
    @SerialName("push_approved_count") val approved: Long = 0,
)

/** /stats/filters and /stats/indexers: Autobrr keeps the top 10 only. */
@Serializable
data class AbTop(val days: Int = 0, val top: List<AbTopRow> = emptyList())

@Serializable
data class AbIndexerRef(val id: Int = 0, val name: String = "", val identifier: String = "")

/** domain.ReleaseActionStatus: what one filter action (send to qBittorrent, push to Sonarr…) made of a release. */
@Serializable
data class AbActionStatus(
    val id: Long = 0,
    val status: String = "",
    val action: String = "",
    val type: String = "",
    val client: String = "",
    val filter: String = "",
    val rejections: List<String> = emptyList(),
    val timestamp: String? = null,
)

/** domain.Release, trimmed to what a phone row needs. */
@Serializable
data class AbRelease(
    val id: Long = 0,
    @SerialName("filter_status") val filterStatus: String = "",
    val rejections: List<String> = emptyList(),
    val indexer: AbIndexerRef = AbIndexerRef(),
    val filter: String = "",
    val protocol: String = "",
    val implementation: String = "",
    val timestamp: String? = null,
    val name: String = "",
    val title: String = "",
    val size: Long = 0,
    @SerialName("info_url") val infoUrl: String = "",
    @SerialName("action_status") val actions: List<AbActionStatus> = emptyList(),
)

/** GET /api/release (domain.FindReleasesResponse); `count` is the total matching the query. */
@Serializable
data class AbReleasePage(val data: List<AbRelease> = emptyList(), val count: Long = 0, @SerialName("next_cursor") val nextCursor: Long = 0)

/** GET /api/filters (domain.Filter), the list fields only. */
@Serializable
data class AbFilter(
    val id: Int = 0,
    val name: String = "",
    val enabled: Boolean = false,
    val priority: Int = 0,
    @SerialName("actions_count") val actionsCount: Int = 0,
    @SerialName("actions_enabled_count") val actionsEnabled: Int = 0,
    @SerialName("is_auto_updated") val autoUpdated: Boolean = false,
    val indexers: List<AbIndexerRef> = emptyList(),
)

/** domain.IrcChannelWithHealth. `state` is the channel state machine's name ("Monitoring", "InviteFailed"…). */
@Serializable
data class AbChannel(
    val id: Long = 0,
    val enabled: Boolean = false,
    val name: String = "",
    val detached: Boolean = false,
    val state: String = "",
    val monitoring: Boolean = false,
    @SerialName("monitoring_since") val monitoringSince: String? = null,
    @SerialName("last_announce") val lastAnnounce: String? = null,
    @SerialName("connection_errors") val errors: List<String> = emptyList(),
)

/** GET /api/irc (domain.IrcNetworkWithHealth). Passwords arrive redacted and aren't modelled. */
@Serializable
data class AbNetwork(
    val id: Long = 0,
    val name: String = "",
    val enabled: Boolean = false,
    val server: String = "",
    val port: Int = 0,
    val tls: Boolean = false,
    val nick: String = "",
    @SerialName("current_nick") val currentNick: String = "",
    @SerialName("use_bouncer") val useBouncer: Boolean = false,
    val channels: List<AbChannel> = emptyList(),
    val connected: Boolean = false,
    @SerialName("connected_since") val connectedSince: String? = null,
    @SerialName("connection_errors") val errors: List<String> = emptyList(),
    val healthy: Boolean = false,
)

/** GET /api/feeds (domain.Feed). The feed's API key and cookie are in the answer too; deliberately not modelled. */
@Serializable
data class AbFeed(
    val id: Int = 0,
    val name: String = "",
    val indexer: AbIndexerRef = AbIndexerRef(),
    val type: String = "",
    val enabled: Boolean = false,
    val interval: Int = 0,
    @SerialName("last_run") val lastRun: String? = null,
    @SerialName("next_run") val nextRun: String? = null,
)

/* ================================================================ pure helpers (unit-tested) */

/** Go's zero time ("0001-01-01T00:00:00Z") means "never": anything before 1971 is treated as unset. */
fun abInstant(iso: String?): Instant? = parseInstant(iso)?.takeIf { it.epochSecond > 365L * 86_400 }

/** Push status (domain.ReleasePushStatus) → label. */
fun pushStatusLabel(status: String): String = when (status) {
    "PUSH_APPROVED" -> "Approved"
    "PUSH_REJECTED" -> "Rejected"
    "PUSH_ERROR" -> "Error"
    "PENDING" -> "Pending"
    else -> status.lowercase().replaceFirstChar { it.uppercase() }
}

/** What happened to a release overall, from its actions. */
enum class AbOutcome(val label: String) {
    Approved("Grabbed"), Rejected("Rejected"), Error("Error"), Pending("Pending"), NoAction("Matched")
}

/**
 * One outcome per release: grabbed if any action was approved (it reached a client), else an error beats a rejection
 * (it needs fixing; a rejection is usually the arr saying "not an upgrade"). No actions at all: matched only.
 */
fun releaseOutcome(r: AbRelease): AbOutcome {
    val statuses = r.actions.map { it.status }
    return when {
        "PUSH_APPROVED" in statuses -> AbOutcome.Approved
        "PUSH_ERROR" in statuses -> AbOutcome.Error
        "PUSH_REJECTED" in statuses -> AbOutcome.Rejected
        "PENDING" in statuses -> AbOutcome.Pending
        else -> AbOutcome.NoAction
    }
}

/**
 * Why a release didn't go through, one line per failed action ("Sonarr: not an upgrade…"), falling back to the
 * release's own rejections. Empty when nothing was rejected.
 */
fun rejectionReasons(r: AbRelease): List<String> {
    val fromActions = r.actions.filter { it.status == "PUSH_REJECTED" || it.status == "PUSH_ERROR" }.flatMap { a ->
        val who = a.action.ifBlank { a.client.ifBlank { actionTypeLabel(a.type) } }.ifBlank { "Action" }
        val reasons = a.rejections.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (reasons.isEmpty()) listOf("$who: ${pushStatusLabel(a.status).lowercase()}") else reasons.map { "$who: $it" }
    }
    return fromActions.ifEmpty { r.rejections.map { it.trim() }.filter { it.isNotEmpty() }.distinct() }
}

/** domain.ActionType → the client or app it sends to. */
fun actionTypeLabel(type: String): String = when (type) {
    "QBITTORRENT" -> "qBittorrent"
    "DELUGE_V1", "DELUGE_V2" -> "Deluge"
    "RTORRENT" -> "rTorrent"
    "TRANSMISSION" -> "Transmission"
    "PORLA" -> "Porla"
    "ARIA2" -> "aria2"
    "WATCH_FOLDER" -> "Watch folder"
    "WEBHOOK" -> "Webhook"
    "EXEC" -> "Run program"
    "RADARR" -> "Radarr"
    "SONARR" -> "Sonarr"
    "LIDARR" -> "Lidarr"
    "WHISPARR", "WHISPARR_V3" -> "Whisparr"
    "READARR" -> "Readarr"
    "SPORTARR" -> "Sportarr"
    "SABNZBD" -> "SABnzbd"
    "NZBGET" -> "NZBGet"
    "TEST" -> "Test"
    else -> type.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
}

/** Release-list filter → `push_status` query value (null = everything). */
enum class AbReleaseFilter(val label: String, val pushStatus: String?) {
    All("All", null), Approved("Grabbed", "PUSH_APPROVED"), Rejected("Rejected", "PUSH_REJECTED"), Errors("Errors", "PUSH_ERROR"),
}

/** Sum of the daily buckets: the Overview's numbers for a period. */
data class AbTotals(val matched: Long, val approved: Long, val rejected: Long, val errors: Long)

fun AbActivity.totals(): AbTotals = AbTotals(daily.sumOf { it.matched }, daily.sumOf { it.approved }, daily.sumOf { it.rejected }, daily.sumOf { it.errors })

/** All-time stats in the same shape. "Matched" is every stored release: Autobrr only keeps releases a filter matched. */
fun AbStats.totals(): AbTotals = AbTotals(total, approved, rejected, errors)

/**
 * Per-filter counts by name. Autobrr only returns its top 10, so a filter missing from a full list has an unknown
 * count (null); missing from a shorter list, it matched nothing (zero).
 */
fun filterCount(top: AbTop?, name: String): AbTopRow? {
    if (top == null) return null
    top.top.firstOrNull { it.filter == name }?.let { return it }
    return if (top.top.size < 10) AbTopRow(filter = name) else null
}

/** How an IRC network is doing. */
enum class AbNetState { Off, Healthy, Degraded, Disconnected }

fun networkState(n: AbNetwork): AbNetState = when {
    !n.enabled -> AbNetState.Off
    n.connected && n.healthy -> AbNetState.Healthy
    n.connected -> AbNetState.Degraded
    else -> AbNetState.Disconnected
}

fun networkStateLabel(state: AbNetState): String = when (state) {
    AbNetState.Off -> "Off"
    AbNetState.Healthy -> "Connected"
    AbNetState.Degraded -> "Connected, with channel problems"
    AbNetState.Disconnected -> "Not connected"
}

/** Channel state machine name (internal/irc/channel_state_machine.go) → plain words. */
fun channelStateLabel(ch: AbChannel): String = when (ch.state) {
    "Monitoring" -> "Watching announces"
    "Joining" -> "Joining"
    "AwaitingInvite" -> "Waiting for an invite"
    "AwaitingInviteBot" -> "Waiting for the invite bot"
    "InviteFailed" -> "Invite failed"
    "InviteFailedNoSuchNick" -> "Invite bot not found"
    "Kicked" -> "Kicked from the channel"
    "Parted" -> "Left the channel"
    "Disabled" -> "Off"
    "Error" -> "Error"
    "Idle" -> "Idle"
    "" -> when {
        !ch.enabled -> "Off"
        ch.monitoring -> "Watching announces"
        else -> "Not joined"
    }
    else -> ch.state
}

/** An enabled channel that isn't watching announces, or has errors: the usual reason nothing gets grabbed. */
fun channelHasProblem(ch: AbChannel): Boolean =
    ch.enabled && !ch.detached && (ch.errors.isNotEmpty() || !(ch.monitoring || ch.state == "Monitoring"))

/** The one-line "why" for a network that isn't healthy: its own error first, then the first troubled channel. */
fun networkProblem(n: AbNetwork): String? {
    if (networkState(n).let { it == AbNetState.Off || it == AbNetState.Healthy }) return null
    n.errors.firstOrNull { it.isNotBlank() }?.let { return it }
    val ch = n.channels.firstOrNull { channelHasProblem(it) } ?: return null
    return "${ch.name}: " + (ch.errors.firstOrNull { it.isNotBlank() } ?: channelStateLabel(ch).lowercase())
}

/** Network health in one line for the Overview: "3 of 4 networks healthy". */
data class AbIrcSummary(val enabled: Int, val healthy: Int, val problems: List<Pair<String, String>>)

fun ircSummary(networks: List<AbNetwork>): AbIrcSummary {
    val on = networks.filter { it.enabled }
    return AbIrcSummary(
        enabled = on.size,
        healthy = on.count { networkState(it) == AbNetState.Healthy },
        problems = on.mapNotNull { n -> if (networkState(n) == AbNetState.Healthy) null else n.name to (networkProblem(n) ?: networkStateLabel(networkState(n))) },
    )
}

/* ================================================================ network */

suspend fun abConfig(s: ServiceConfig): AbConfig = app.client.get(s, "/api/config")

suspend fun abStats(s: ServiceConfig): AbStats = app.client.get(s, "/api/release/stats")

/** Daily buckets for the last [days] calendar days (UTC, today included). Added in Autobrr 1.84 with the dashboard widgets. */
suspend fun abActivity(s: ServiceConfig, days: Int): AbActivity = app.client.get(s, "/api/release/stats/activity?days=$days")

suspend fun abTopFilters(s: ServiceConfig, days: Int): AbTop = app.client.get(s, "/api/release/stats/filters?days=$days")

suspend fun abTopIndexers(s: ServiceConfig, days: Int): AbTop = app.client.get(s, "/api/release/stats/indexers?days=$days")

/** Newest first (Autobrr orders by id). [filter] maps to `push_status`, which matches releases with an action in that state. */
suspend fun abReleases(s: ServiceConfig, filter: AbReleaseFilter, limit: Int): AbReleasePage =
    app.client.get(s, "/api/release?limit=$limit&offset=0" + (filter.pushStatus?.let { "&push_status=${enc(it)}" } ?: ""))

suspend fun abFilters(s: ServiceConfig): List<AbFilter> = app.client.get(s, "/api/filters?sort=priority-desc")

/** PUT /api/filters/{id}/enabled {"enabled": …}: 204, nothing else about the filter changes. */
suspend fun abSetFilterEnabled(s: ServiceConfig, id: Int, enabled: Boolean) {
    app.client.send(s, "/api/filters/$id/enabled", "PUT", buildJsonObject { put("enabled", JsonPrimitive(enabled)) })
}

suspend fun abNetworks(s: ServiceConfig): List<AbNetwork> = app.client.get(s, "/api/irc")

suspend fun abFeeds(s: ServiceConfig): List<AbFeed> = app.client.get(s, "/api/feeds")

/**
 * Reconnect an IRC network. v1.90 routes this as GET; the next release moves it to POST (with API-key scopes), so try
 * POST and fall back to GET when the router answers 405.
 */
suspend fun abRestartNetwork(s: ServiceConfig, id: Long) {
    try {
        app.client.send(s, "/api/irc/network/$id/restart", "POST")
    } catch (e: ServiceException) {
        if (e.status != 405) throw e
        app.client.text(s, "/api/irc/network/$id/restart")
    }
}
