package app.bosunarr.feature.autobrr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Leaderboard
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.RssFeed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import app.bosunarr.data.ServiceConfig
import app.bosunarr.feature.services.CardEmpty
import app.bosunarr.feature.services.CardList
import app.bosunarr.feature.services.CardLoad
import app.bosunarr.feature.services.HealthRow
import app.bosunarr.feature.services.HubRefreshBox
import app.bosunarr.feature.services.RefreshHub
import app.bosunarr.feature.services.SectionCard
import app.bosunarr.feature.services.ServiceFrame
import app.bosunarr.feature.services.SkeletonBox
import app.bosunarr.feature.services.SkeletonRows
import app.bosunarr.feature.services.StatRow
import app.bosunarr.feature.services.StatTile
import app.bosunarr.feature.services.StatusDot
import app.bosunarr.feature.services.TabbedBody
import app.bosunarr.feature.services.okColor
import app.bosunarr.feature.services.rememberActionRunner
import app.bosunarr.feature.services.rememberHubLoader
import app.bosunarr.feature.services.rememberRefreshHub
import app.bosunarr.feature.services.warnColor
import app.bosunarr.net.ServiceException
import app.bosunarr.ui.common.ConfirmDialog
import app.bosunarr.ui.common.Tag
import app.bosunarr.ui.common.bytes
import app.bosunarr.ui.common.plural
import app.bosunarr.ui.common.relative
import app.bosunarr.ui.common.toggleRow
import java.text.NumberFormat

/** Overview periods in Autobrr's `days` (calendar days in UTC, today included); 0 = all time. */
private val PERIODS = listOf(1 to "Today", 7 to "7 days", 30 to "30 days", 0 to "All time")

@Composable
fun AutobrrServiceScreen(s: ServiceConfig, nav: NavController) {
    val hub = rememberRefreshHub()
    val config = rememberHubLoader(hub, "svc:ab:${s.id}:config", refreshMs = 10 * 60_000) { abConfig(s) }
    val version = config.value?.version?.removePrefix("v")?.takeIf { it.isNotBlank() }
    ServiceFrame(s, nav, subtitle = listOfNotNull(s.serviceType?.label, version?.let { if (it == "dev") it else "v$it" }).joinToString(" · ")) { p ->
        HubRefreshBox(hub, Modifier.padding(p)) {
            TabbedBody(listOf("Overview", "Releases", "Filters", "IRC")) { tab ->
                when (tab) {
                    0 -> OverviewTab(s, hub)
                    1 -> ReleasesTab(s, hub)
                    2 -> FiltersTab(s, hub)
                    else -> IrcTab(s, hub)
                }
            }
        }
    }
}

private fun count(n: Long): String = NumberFormat.getIntegerInstance().format(n)

@Composable
private fun divider() = HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

/* ================================================================ overview */

@Composable
private fun OverviewTab(s: ServiceConfig, hub: RefreshHub) {
    var days by rememberSaveable { mutableIntStateOf(7) }
    val totals = rememberHubLoader(hub, "svc:ab:${s.id}:totals:$days", refreshMs = 60_000) {
        if (days == 0) abStats(s).totals()
        else try {
            abActivity(s, days).totals()
        } catch (e: ServiceException) {
            // Per-period stats came with Autobrr 1.84; older versions only have the all-time counts.
            if (e.status == 404) throw ServiceException("This Autobrr is too old for per-period numbers (1.84 or newer). All time still works.", 404)
            throw e
        }
    }
    val networks = rememberHubLoader(hub, "svc:ab:${s.id}:irc", refreshMs = 30_000) { abNetworks(s) }
    val top = rememberHubLoader(hub, "svc:ab:${s.id}:topidx:$days", refreshMs = 5 * 60_000) { abTopIndexers(s, days) }
    CardList {
        item("periods") {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PERIODS.forEach { (d, label) -> FilterChip(selected = days == d, onClick = { days = d }, label = { Text(label) }) }
            }
        }
        item("stats") {
            CardLoad(totals, placeholder = { StatRow { repeat(4) { SkeletonBox(Modifier.weight(1f).height(64.dp), RoundedCornerShape(16.dp)) } } }) { t ->
                StatRow {
                    StatTile("Matched", count(t.matched), Modifier.weight(1f))
                    StatTile("Grabbed", count(t.approved), Modifier.weight(1f), tint = if (t.approved > 0) okColor() else MaterialTheme.colorScheme.onSurface)
                    StatTile("Rejected", count(t.rejected), Modifier.weight(1f), tint = if (t.rejected > 0) warnColor() else MaterialTheme.colorScheme.onSurface)
                    StatTile("Errors", count(t.errors), Modifier.weight(1f), tint = if (t.errors > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        item("irc") {
            SectionCard("Announce channels", icon = Icons.Rounded.Forum) {
                CardLoad(networks, placeholder = { SkeletonRows(2, rowHeight = 36.dp) }) { list ->
                    val sum = ircSummary(list)
                    when {
                        list.isEmpty() -> CardEmpty("No IRC networks: Autobrr only grabs from RSS feeds")
                        sum.enabled == 0 -> CardEmpty("Every IRC network is off")
                        sum.problems.isEmpty() -> Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(okColor())
                            Spacer(Modifier.width(10.dp))
                            Text(if (sum.enabled == 1) "Connected and watching announces" else "All ${sum.enabled} networks connected and watching announces", style = MaterialTheme.typography.bodyMedium)
                        }
                        else -> {
                            Text(
                                "${sum.healthy} of ${plural(sum.enabled, "network")} healthy",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                            sum.problems.forEach { (name, why) -> HealthRow("error", why, source = name) }
                        }
                    }
                }
            }
        }
        item("top") {
            SectionCard("Top indexers", icon = Icons.Rounded.Leaderboard) {
                CardLoad(top, placeholder = { SkeletonRows(3, rowHeight = 36.dp) }) { t ->
                    if (t.top.isEmpty()) CardEmpty("Nothing matched in this period")
                    t.top.forEachIndexed { i, row ->
                        if (i > 0) divider()
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(row.indexer, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(
                                "${count(row.matched)} matched · ${count(row.approved)} grabbed",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/* ================================================================ releases */

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReleasesTab(s: ServiceConfig, hub: RefreshHub) {
    var filter by rememberSaveable { mutableStateOf(AbReleaseFilter.All) }
    var limit by rememberSaveable { mutableIntStateOf(30) }
    val releases = rememberHubLoader(hub, "svc:ab:${s.id}:releases:${filter.name}:$limit", refreshMs = 60_000) { abReleases(s, filter, limit) }
    CardList {
        item("filters") {
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AbReleaseFilter.entries.forEach { f -> FilterChip(selected = filter == f, onClick = { filter = f; limit = 30 }, label = { Text(f.label) }) }
            }
        }
        item("releases") {
            SectionCard("Matched releases", icon = Icons.Rounded.History) {
                CardLoad(releases, placeholder = { SkeletonRows(6, rowHeight = 56.dp) }) { page ->
                    if (page.data.isEmpty()) CardEmpty(if (filter == AbReleaseFilter.All) "Nothing matched a filter yet" else "None")
                    page.data.forEachIndexed { i, r ->
                        if (i > 0) divider()
                        ReleaseRow(r)
                    }
                    if (page.data.size < page.count) {
                        OutlinedButton(onClick = { limit += 30 }, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            Text("Show more (${count(page.count - page.data.size)} older)")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun outcomeColors(o: AbOutcome): Pair<Color, Color> {
    val cs = MaterialTheme.colorScheme
    return when (o) {
        AbOutcome.Approved -> okColor().copy(alpha = 0.16f) to okColor()
        AbOutcome.Rejected -> warnColor().copy(alpha = 0.16f) to warnColor()
        AbOutcome.Error -> cs.errorContainer to cs.onErrorContainer
        AbOutcome.Pending, AbOutcome.NoAction -> cs.secondaryContainer to cs.onSecondaryContainer
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReleaseRow(r: AbRelease) {
    val outcome = releaseOutcome(r)
    val (container, content) = outcomeColors(outcome)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                listOfNotNull(r.indexer.name.ifBlank { r.indexer.identifier }.ifBlank { null }, r.filter.ifBlank { null }).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(abInstant(r.timestamp)?.let { relative(it) } ?: "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(r.name.ifBlank { r.title }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Tag(outcome.label, container = container, content = content)
            if (r.size > 0) Tag(bytes(r.size))
            if (outcome == AbOutcome.Approved) {
                r.actions.filter { it.status == "PUSH_APPROVED" }.map { it.action.ifBlank { actionTypeLabel(it.type) } }.distinct().take(3).forEach { Tag(it) }
            }
        }
        if (outcome == AbOutcome.Rejected || outcome == AbOutcome.Error) {
            rejectionReasons(r).takeIf { it.isNotEmpty() }?.let { reasons ->
                Text(
                    reasons.joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (outcome == AbOutcome.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/* ================================================================ filters */

@Composable
private fun FiltersTab(s: ServiceConfig, hub: RefreshHub) {
    val filters = rememberHubLoader(hub, "svc:ab:${s.id}:filters", refreshMs = 2 * 60_000) { abFilters(s) }
    // Per-filter counts are a best effort: older Autobrr (before 1.84) has no stats endpoint, and the list is fine without.
    val counts = rememberHubLoader(hub, "svc:ab:${s.id}:topfilters", refreshMs = 5 * 60_000) { runCatching { abTopFilters(s, 7) }.getOrNull() }
    val runner = rememberActionRunner()
    var toggling by remember { mutableStateOf<AbFilter?>(null) }
    CardList {
        item("help") {
            Text(
                "A filter decides which announced releases Autobrr grabs. Counts are for the last 7 days.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        item("list") {
            SectionCard("Filters", icon = Icons.Rounded.FilterAlt) {
                CardLoad(filters, placeholder = { SkeletonRows(5, rowHeight = 56.dp) }) { list ->
                    if (list.isEmpty()) CardEmpty("No filters yet: Autobrr won't grab anything")
                    list.forEachIndexed { i, f ->
                        if (i > 0) divider()
                        FilterRow(f, filterCount(counts.value, f.name), busy = runner.isBusy("ab:${s.id}:filter:${f.id}")) { toggling = f }
                    }
                }
            }
        }
    }
    toggling?.let { f ->
        val on = !f.enabled
        ConfirmDialog(
            title = if (on) "Turn on ${f.name}?" else "Turn off ${f.name}?",
            text = if (on) "Autobrr starts grabbing releases that match this filter."
            else "Autobrr stops grabbing releases that match this filter until you turn it back on.",
            confirm = if (on) "Turn on" else "Turn off",
            onConfirm = {
                runner.run("ab:${s.id}:filter:${f.id}", after = { filters.refresh() }) {
                    try {
                        abSetFilterEnabled(s, f.id, on)
                    } catch (e: ServiceException) {
                        // Reading worked, so a refusal here is the key's scope (newer Autobrr has read-only keys).
                        if (e.status == 401) throw ServiceException("${s.name} didn't allow it: the API key needs write access to filters", 401)
                        throw e
                    }
                    if (on) "${f.name} is on" else "${f.name} is off"
                }
            },
            onDismiss = { toggling = null },
        )
    }
}

@Composable
private fun FilterRow(f: AbFilter, stat: AbTopRow?, busy: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleRow(f.enabled, enabled = !busy) { onToggle() }.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(f.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    stat?.let { "${count(it.matched)} matched · ${count(it.approved)} grabbed" },
                    when {
                        f.actionsCount == 0 -> "no actions"
                        f.actionsEnabled == f.actionsCount -> plural(f.actionsCount, "action")
                        else -> "${f.actionsEnabled} of ${plural(f.actionsCount, "action")} on"
                    },
                    f.indexers.size.takeIf { it > 0 }?.let { plural(it, "indexer") },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // An enabled filter with no enabled action matches releases and then sends them nowhere.
            if (f.enabled && f.actionsEnabled == 0) {
                Text("Matches go nowhere: no action is on", style = MaterialTheme.typography.bodySmall, color = warnColor())
            }
        }
        if (busy) CircularProgressIndicator(Modifier.padding(start = 12.dp).size(20.dp), strokeWidth = 2.dp)
        else Switch(checked = f.enabled, onCheckedChange = null, modifier = Modifier.padding(start = 12.dp))
    }
}

/* ================================================================ IRC + feeds */

@Composable
private fun IrcTab(s: ServiceConfig, hub: RefreshHub) {
    val networks = rememberHubLoader(hub, "svc:ab:${s.id}:irc", refreshMs = 30_000) { abNetworks(s) }
    val feeds = rememberHubLoader(hub, "svc:ab:${s.id}:feeds", refreshMs = 60_000) { abFeeds(s) }
    val runner = rememberActionRunner()
    var restarting by remember { mutableStateOf<AbNetwork?>(null) }
    CardList {
        item("networks") {
            SectionCard("IRC networks", icon = Icons.Rounded.Forum) {
                CardLoad(networks, placeholder = { SkeletonRows(3, rowHeight = 64.dp) }) { list ->
                    if (list.isEmpty()) CardEmpty("No IRC networks")
                    list.sortedWith(compareBy({ !it.enabled }, { it.name.lowercase() })).forEachIndexed { i, n ->
                        if (i > 0) divider()
                        NetworkRow(n, busy = runner.isBusy("ab:${s.id}:restart:${n.id}")) { restarting = n }
                    }
                }
            }
        }
        val feedList = feeds.value.orEmpty()
        if (feedList.isNotEmpty() || feeds.state.error != null) item("feeds") {
            SectionCard("RSS and Torznab feeds", icon = Icons.Rounded.RssFeed) {
                CardLoad(feeds, placeholder = { SkeletonRows(2, rowHeight = 44.dp) }) { list ->
                    list.sortedWith(compareBy({ !it.enabled }, { it.name.lowercase() })).forEachIndexed { i, f ->
                        if (i > 0) divider()
                        FeedRow(f)
                    }
                }
            }
        }
    }
    restarting?.let { n ->
        ConfirmDialog(
            title = "Reconnect ${n.name}?",
            text = "Autobrr disconnects from ${n.server} and joins its channels again. Announces made while it reconnects are missed.",
            confirm = "Reconnect",
            onConfirm = {
                runner.run("ab:${s.id}:restart:${n.id}", after = { networks.refresh() }) {
                    try {
                        abRestartNetwork(s, n.id)
                    } catch (e: ServiceException) {
                        if (e.status == 401) throw ServiceException("${s.name} didn't allow it: the API key can't control IRC", 401)
                        throw e
                    }
                    "Reconnecting ${n.name}"
                }
            },
            onDismiss = { restarting = null },
        )
    }
}

@Composable
private fun stateColor(state: AbNetState): Color = when (state) {
    AbNetState.Healthy -> okColor()
    AbNetState.Degraded -> warnColor()
    AbNetState.Disconnected -> MaterialTheme.colorScheme.error
    AbNetState.Off -> MaterialTheme.colorScheme.outline
}

@Composable
private fun NetworkRow(n: AbNetwork, busy: Boolean, onRestart: () -> Unit) {
    val state = networkState(n)
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(stateColor(state))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(n.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        networkStateLabel(state) + (abInstant(n.connectedSince)?.takeIf { state != AbNetState.Off && n.connected }?.let { since ->
                            relative(since).let { r -> if (r.endsWith(" ago")) " for " + r.removeSuffix(" ago") else " $r" }
                        } ?: ""),
                        n.currentNick.ifBlank { n.nick }.ifBlank { null },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (n.enabled) {
                if (busy) CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp)
                else IconButton(onClick = onRestart) { Icon(Icons.Rounded.RestartAlt, "Reconnect ${n.name}") }
            }
        }
        n.errors.filter { it.isNotBlank() }.take(2).forEach {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 18.dp, end = 12.dp, top = 2.dp))
        }
        if (n.enabled) n.channels.forEach { ch -> ChannelLine(ch) }
    }
}

@Composable
private fun ChannelLine(ch: AbChannel) {
    val problem = channelHasProblem(ch)
    val last = abInstant(ch.lastAnnounce)
    Column(Modifier.padding(start = 18.dp, end = 12.dp, top = 4.dp)) {
        Text(
            listOfNotNull(
                ch.name,
                channelStateLabel(ch),
                if (ch.enabled && !problem) (last?.let { "last announce ${relative(it)}" } ?: "no announces yet") else null,
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = if (problem) warnColor() else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        ch.errors.firstOrNull { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun FeedRow(f: AbFeed) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusDot(if (f.enabled) okColor() else MaterialTheme.colorScheme.outline)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(f.name.ifBlank { f.indexer.name }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (!f.enabled) "Off" else listOfNotNull(
                    abInstant(f.lastRun)?.let { "Checked ${relative(it)}" } ?: "Not checked yet",
                    abInstant(f.nextRun)?.let { "next ${relative(it)}" },
                    f.type.takeIf { it.isNotBlank() }?.let { it.lowercase().replaceFirstChar { c -> c.uppercase() } },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
