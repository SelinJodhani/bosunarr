package app.bosunarr.feature.cleanuparr

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.NotificationImportant
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import app.bosunarr.feature.services.HubRefreshBox
import app.bosunarr.feature.services.RefreshHub
import app.bosunarr.feature.services.SectionCard
import app.bosunarr.feature.services.ServiceFrame
import app.bosunarr.feature.services.SkeletonBox
import app.bosunarr.feature.services.SkeletonRows
import app.bosunarr.feature.services.StatRow
import app.bosunarr.feature.services.StatTile
import app.bosunarr.feature.services.TabbedBody
import app.bosunarr.feature.services.rememberActionRunner
import app.bosunarr.feature.services.rememberHubLoader
import app.bosunarr.feature.services.rememberRefreshHub
import app.bosunarr.feature.services.warnColor
import app.bosunarr.ui.common.ConfirmDialog
import app.bosunarr.ui.common.Tag
import app.bosunarr.ui.common.parseInstant
import app.bosunarr.ui.common.plural
import app.bosunarr.ui.common.relative

private val PERIODS = listOf(24 to "24 hours", 168 to "7 days", 720 to "30 days")

@Composable
fun CleanuparrServiceScreen(s: ServiceConfig, nav: NavController) {
    val hub = rememberRefreshHub()
    val status = rememberHubLoader(hub, "svc:cl:${s.id}:status", refreshMs = 10 * 60_000) { clStatus(s) }
    val version = status.value?.application?.version?.removeSuffix(".0")
    ServiceFrame(s, nav, subtitle = listOfNotNull(s.serviceType?.label, version?.let { "v$it" }).joinToString(" · ")) { p ->
        HubRefreshBox(hub, Modifier.padding(p)) {
            TabbedBody(listOf("Overview", "Activity", "Strikes")) { tab ->
                when (tab) {
                    0 -> OverviewTab(s, hub)
                    1 -> ActivityTab(s, hub)
                    else -> StrikesTab(s, hub)
                }
            }
        }
    }
}

/* ================================================================ overview */

@Composable
private fun OverviewTab(s: ServiceConfig, hub: RefreshHub) {
    var hours by rememberSaveable { mutableIntStateOf(168) }
    val stats = rememberHubLoader(hub, "svc:cl:${s.id}:stats:$hours", refreshMs = 60_000) { clStats(s, hours) }
    val attention = rememberHubLoader(hub, "svc:cl:${s.id}:attention", refreshMs = 60_000) { clAttention(s) }
    val jobs = rememberHubLoader(hub, "svc:cl:${s.id}:jobs", refreshMs = 30_000) { clJobs(s) }
    val runner = rememberActionRunner()
    CardList {
        item("periods") {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PERIODS.forEach { (h, label) -> FilterChip(selected = hours == h, onClick = { hours = h }, label = { Text(label) }) }
            }
        }
        item("stats") {
            CardLoad(stats, placeholder = { StatRow { repeat(3) { SkeletonBox(Modifier.weight(1f).height(64.dp), RoundedCornerShape(16.dp)) } } }) { st ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatRow {
                        StatTile("Strikes", st.strikes.total.toString(), Modifier.weight(1f), tint = if (st.strikes.total > 0) warnColor() else MaterialTheme.colorScheme.onSurface)
                        StatTile("Removed", st.removals.total.toString(), Modifier.weight(1f), tint = if (st.removals.total > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                        StatTile("Replacements", st.searches.total.toString(), Modifier.weight(1f))
                    }
                    val why = (st.removals.byReason + st.strikes.byType).entries.sortedByDescending { it.value }
                    if (why.isNotEmpty() || st.cleaned.total > 0) {
                        Text(
                            buildList {
                                st.removals.byReason.forEach { (r, n) -> add("$n removed: ${strikeLabel(r).lowercase()}") }
                                st.strikes.byType.forEach { (t, n) -> add("${plural(n, "strike")}: ${strikeLabel(t).lowercase()}") }
                                if (st.cleaned.total > 0) add(plural(st.cleaned.total, "finished download") + " cleaned up")
                                if (st.searches.grabbed > 0) add(plural(st.searches.grabbed, "replacement") + " grabbed")
                            }.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                    }
                }
            }
        }
        val open = attention.value.orEmpty()
        if (open.isNotEmpty()) item("attention") {
            SectionCard("Needs your attention", icon = Icons.Rounded.NotificationImportant, trailing = {
                TextButton(onClick = {
                    runner.run("cl:${s.id}:resolveall", after = { attention.refresh() }) {
                        open.forEach { clResolve(s, it.id) }
                        "Marked ${plural(open.size, "item")} as resolved"
                    }
                }) { Text("Resolve all") }
            }) {
                open.forEachIndexed { i, e ->
                    if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            e.itemTitle?.let { Text(it, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                            Text(e.message, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(relative(e.timestamp).ifBlank { null }, e.downloadClientName).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = {
                            runner.run("cl:${s.id}:resolve:${e.id}", after = { attention.refresh() }) { clResolve(s, e.id); "Resolved" }
                        }) { Icon(Icons.Rounded.DoneAll, "Mark resolved") }
                    }
                }
            }
        }
        item("jobs") {
            SectionCard("Jobs", icon = Icons.Rounded.Schedule) {
                CardLoad(jobs, placeholder = { SkeletonRows(4, rowHeight = 44.dp) }) { list ->
                    if (list.isEmpty()) CardEmpty("No jobs")
                    list.forEachIndexed { i, j ->
                        if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        JobRow(j, busy = runner.isBusy("cl:${s.id}:job:${j.jobType}")) {
                            runner.run("cl:${s.id}:job:${j.jobType}", after = { jobs.refresh(); stats.refresh() }) {
                                clTriggerJob(s, j.jobType.ifBlank { j.name })
                                "${jobLabel(j.jobType.ifBlank { j.name })} started"
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun JobRow(j: ClJob, busy: Boolean, onRun: () -> Unit) {
    val scheduled = j.status.equals("Scheduled", ignoreCase = true) || j.status.equals("Running", ignoreCase = true)
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(jobLabel(j.jobType.ifBlank { j.name }), style = MaterialTheme.typography.bodyLarge)
            Text(
                if (!scheduled) "Off" else listOfNotNull(
                    parseInstant(j.previousRunTime)?.let { "Ran ${relative(it)}" },
                    parseInstant(j.nextRunTime)?.let { "next ${relative(it)}" },
                ).joinToString(" · ").ifEmpty { j.status },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (scheduled) {
            if (busy) CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp)
            else IconButton(onClick = onRun) { Icon(Icons.Rounded.PlayArrow, "Run ${jobLabel(j.jobType)} now") }
        }
    }
}

/* ================================================================ activity */

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActivityTab(s: ServiceConfig, hub: RefreshHub) {
    var filter by rememberSaveable { mutableStateOf(ClFilter.All) }
    var limit by rememberSaveable { mutableIntStateOf(30) }
    val events = rememberHubLoader(hub, "svc:cl:${s.id}:events:${filter.name}:$limit", refreshMs = 60_000) { clEvents(s, filter, 1, limit) }
    CardList {
        item("filters") {
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ClFilter.entries.forEach { f -> FilterChip(selected = filter == f, onClick = { filter = f; limit = 30 }, label = { Text(f.label) }) }
            }
        }
        item("events") {
            SectionCard("What Cleanuparr did", icon = Icons.Rounded.Gavel) {
                CardLoad(events, placeholder = { SkeletonRows(6, rowHeight = 52.dp) }) { page ->
                    if (page.items.isEmpty()) CardEmpty("Nothing yet")
                    page.items.forEachIndexed { i, e ->
                        if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        EventRow(e)
                    }
                    if (page.items.size < page.totalCount) {
                        OutlinedButton(onClick = { limit += 30 }, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            Text("Show more (${page.totalCount - page.items.size} older)")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun severityColor(severity: String): Color = when (severity) {
    "Error" -> MaterialTheme.colorScheme.error
    "Important", "Warning" -> warnColor()
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun EventRow(e: ClEvent) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                eventLabel(e.eventType) + (e.strikeCount?.takeIf { e.eventType.endsWith("Strike") }?.let { " ($it)" } ?: ""),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = severityColor(e.severity),
                modifier = Modifier.weight(1f),
            )
            if (e.isDryRun) Tag("Dry run", modifier = Modifier.padding(end = 6.dp))
            Text(relative(e.timestamp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val title = e.itemTitle?.takeIf { it.isNotBlank() }
        if (title != null) Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        else Text(e.message, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (e.grabbedItems.isNotEmpty()) {
            Text("Grabbed: " + e.grabbedItems.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/* ================================================================ strikes */

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StrikesTab(s: ServiceConfig, hub: RefreshHub) {
    val strikes = rememberHubLoader(hub, "svc:cl:${s.id}:strikes", refreshMs = 60_000) { clStrikes(s) }
    val limits = rememberHubLoader(hub, "svc:cl:${s.id}:limits", refreshMs = 10 * 60_000) { clLimits(s) }
    val runner = rememberActionRunner()
    var forgiving by remember { mutableStateOf<ClStrikeItem?>(null) }
    CardList {
        item("help") {
            Text(
                "Cleanuparr strikes a download each time it finds it stuck or failing, and removes it at the limit. Forgive one you know is fine to start it from zero.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        item("list") {
            SectionCard("Struck downloads", icon = Icons.Rounded.Gavel) {
                CardLoad(strikes, placeholder = { SkeletonRows(4, rowHeight = 60.dp) }) { list ->
                    val sorted = list.sortedWith(compareBy<ClStrikeItem>({ it.isRemoved }, { -(parseInstant(it.latestStrikeAt)?.epochSecond ?: 0) }))
                    if (sorted.isEmpty()) CardEmpty("No strikes: every download is healthy")
                    sorted.forEachIndexed { i, item ->
                        if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        val badge = badgeFor(item, limits.value.orEmpty())
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = if (item.isRemoved) 16.dp else 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(item.title.ifBlank { item.downloadId }, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    badge?.text ?: item.strikesByType.entries.joinToString(" · ") { (t, n) -> "${plural(n, "strike")} · ${strikeLabel(t)}" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (badge?.critical == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    when {
                                        item.isRemoved -> Tag("Removed", container = MaterialTheme.colorScheme.errorContainer, content = MaterialTheme.colorScheme.onErrorContainer)
                                        item.isMarkedForRemoval -> Tag("Being removed", container = MaterialTheme.colorScheme.errorContainer, content = MaterialTheme.colorScheme.onErrorContainer)
                                    }
                                    if (item.isReturning) Tag("Came back")
                                    if (item.hasDryRunStrikes) Tag("Dry run")
                                    parseInstant(item.latestStrikeAt)?.let { Tag("Last ${relative(it)}") }
                                }
                            }
                            if (!item.isRemoved) {
                                if (runner.isBusy("cl:${s.id}:forgive:${item.downloadItemId}")) CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp)
                                else TextButton(onClick = { forgiving = item }) { Text("Forgive") }
                            }
                        }
                    }
                }
            }
        }
    }
    forgiving?.let { item ->
        ConfirmDialog(
            title = "Forgive this download?",
            text = "${item.title}\n\nIts ${plural(item.totalStrikes, "strike")} are cleared. If it's still stuck, Cleanuparr starts striking it again.",
            confirm = "Forgive",
            onConfirm = {
                runner.run("cl:${s.id}:forgive:${item.downloadItemId}", after = { strikes.refresh(); StrikeBadges.invalidate() }) {
                    clForgive(s, item.downloadItemId)
                    "Strikes cleared"
                }
            },
            onDismiss = { forgiving = null },
        )
    }
}
