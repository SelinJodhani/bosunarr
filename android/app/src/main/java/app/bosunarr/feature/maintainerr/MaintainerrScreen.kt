package app.bosunarr.feature.maintainerr

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.AutoDelete
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import app.bosunarr.ui.common.Poster
import app.bosunarr.ui.common.Tag
import app.bosunarr.ui.common.bytes
import app.bosunarr.ui.common.parseInstant
import app.bosunarr.ui.common.plural

@Composable
fun MaintainerrServiceScreen(s: ServiceConfig, nav: NavController) {
    val hub = rememberRefreshHub()
    val version = rememberHubLoader(hub, "svc:mt:${s.id}:version", refreshMs = 10 * 60_000) { mtVersion(s) }
    val v = version.value?.version?.takeIf { version.value?.status == 1 }
    ServiceFrame(s, nav, subtitle = listOfNotNull(s.serviceType?.label, v?.let { "v$it" }, "update available".takeIf { version.value?.updateAvailable == true }).joinToString(" · ")) { p ->
        HubRefreshBox(hub, Modifier.padding(p)) {
            TabbedBody(listOf("Leaving soon", "Rules", "Kept")) { tab ->
                when (tab) {
                    0 -> LeavingTab(s, hub)
                    1 -> RulesTab(s, hub)
                    else -> KeptTab(s, hub)
                }
            }
        }
    }
}

private val Divider: @Composable () -> Unit = {
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

@Composable
private fun daysColor(days: Int?, deletes: Boolean): Color = when {
    days == null -> MaterialTheme.colorScheme.onSurfaceVariant
    days <= 3 && deletes -> MaterialTheme.colorScheme.error
    days <= 7 -> warnColor()
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/* ================================================================ leaving soon */

private const val PER_COLLECTION = 50

@Composable
private fun LeavingTab(s: ServiceConfig, hub: RefreshHub) {
    val leaving = rememberHubLoader(hub, "svc:mt:${s.id}:leaving", refreshMs = 5 * 60_000) { loadLeaving(s, PER_COLLECTION) }
    val runner = rememberActionRunner()
    var picked by remember { mutableStateOf<Pair<MtLeavingGroup, MtLeavingItem>?>(null) }
    CardList {
        item("summary") {
            CardLoad(leaving, placeholder = { StatRow { repeat(3) { SkeletonBox(Modifier.weight(1f).height(64.dp), RoundedCornerShape(16.dp)) } } }) { groups ->
                val total = groups.sumOf { it.total }
                val week = groups.sumOf { g -> g.items.count { (it.daysLeft ?: Int.MAX_VALUE) <= 7 } }
                val size = groups.mapNotNull { it.collection.totalSizeBytes }.takeIf { it.isNotEmpty() }?.sum()
                StatRow {
                    StatTile("Leaving", total.toString(), Modifier.weight(1f))
                    StatTile("Within 7 days", week.toString(), Modifier.weight(1f), tint = if (week > 0) warnColor() else MaterialTheme.colorScheme.onSurface)
                    StatTile("Size", size?.let { bytes(it) } ?: "–", Modifier.weight(1f))
                }
            }
        }
        val groups = leaving.value.orEmpty()
        if (leaving.value != null && groups.isEmpty()) item("empty") {
            SectionCard("Nothing is leaving", icon = Icons.Rounded.AutoDelete) {
                CardEmpty("No active collection with a timer has anything in it. Items appear here once a rule collects them.")
            }
        }
        groups.forEach { g ->
            item("c:${g.collection.id}") {
                SectionCard(g.collection.title, icon = Icons.Rounded.AutoDelete) {
                    Text(
                        listOfNotNull(timerText(g.collection), g.rule?.name?.takeIf { it != g.collection.title }?.let { "rule “$it”" }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
                    )
                    if (g.items.isEmpty()) CardEmpty("Maintainerr couldn't read these items from your media server")
                    g.items.forEachIndexed { i, item ->
                        if (i > 0) Divider()
                        LeavingRow(s, g, item, busy = runner.isBusy("mt:${s.id}:item:${g.collection.id}:${item.media.mediaServerId}")) { picked = g to item }
                    }
                    if (g.total > g.items.size) {
                        Text(
                            "Showing the ${g.items.size} soonest of ${g.total}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
    picked?.let { (g, item) ->
        ItemDialog(
            g, item,
            onKeep = { everywhere ->
                val target = if (everywhere || g.rule == null) null else g.collection.id
                runner.run("mt:${s.id}:item:${g.collection.id}:${item.media.mediaServerId}", after = { leaving.refresh() }) {
                    mtKeep(s, item.media.mediaServerId, target)
                    "Kept: ${item.title}"
                }
            },
            onPostpone = { days ->
                runner.run("mt:${s.id}:item:${g.collection.id}:${item.media.mediaServerId}", after = { leaving.refresh() }) {
                    val r = mtPostpone(s, g.collection.id, item.media.mediaServerId, days)
                    val left = parseInstant(r.deletionDate)?.let { daysUntil(it) }
                    if (left != null) "Postponed: ${daysLeftText(left).lowercase()}" else "Postponed"
                }
            },
            onDismiss = { picked = null },
        )
    }
}

@Composable
private fun LeavingRow(s: ServiceConfig, g: MtLeavingGroup, item: MtLeavingItem, busy: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = !busy, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Poster(s, item.poster, width = 44.dp, title = item.title)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(item.daysLeft?.let { daysLeftText(it) }, item.media.sizeBytes?.takeIf { it > 0 }?.let { bytes(it) }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = daysColor(item.daysLeft, deletesFiles(g.collection.arrAction)),
            )
            if (item.media.isManual == true) Tag("Added by hand", modifier = Modifier.padding(top = 4.dp))
        }
        if (busy) CircularProgressIndicator(Modifier.padding(start = 8.dp).size(20.dp), strokeWidth = 2.dp)
    }
}

/** What to do with one item: keep it (exclude) or postpone it. The dialog spells out each effect before anything runs. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemDialog(g: MtLeavingGroup, item: MtLeavingItem, onKeep: (everywhere: Boolean) -> Unit, onPostpone: (days: Int) -> Unit, onDismiss: () -> Unit) {
    var everywhere by remember { mutableStateOf(g.rule == null) }
    var days by remember { mutableIntStateOf(14) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.title, maxLines = 3, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "In “${g.collection.title}”: " + timerText(g.collection).replaceFirstChar { it.lowercase() } +
                        (item.daysLeft?.let { " (${daysLeftText(it).lowercase()})" } ?: "") + ".",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("Keep it", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (everywhere) "Excludes it from every rule and takes it out of every collection. Undo it on the Kept tab."
                    else "Excludes it from this rule and takes it out of this collection. Undo it on the Kept tab.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (g.rule != null) Row(Modifier.fillMaxWidth().clickable { everywhere = !everywhere }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(everywhere, { everywhere = it })
                    Text("From every rule", style = MaterialTheme.typography.bodyMedium)
                }
                Button(onClick = { onKeep(everywhere); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Shield, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Keep it")
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("Postpone", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Pushes its date out; if it's already due, counted from today. It stays in the collection.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(7, 14, 30, 90).forEach { d -> FilterChip(selected = days == d, onClick = { days = d }, label = { Text(plural(d, "day")) }) }
                }
                OutlinedButton(onClick = { onPostpone(days); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Postpone ${plural(days, "day")}")
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/* ================================================================ rules */

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RulesTab(s: ServiceConfig, hub: RefreshHub) {
    val rules = rememberHubLoader(hub, "svc:mt:${s.id}:rules", refreshMs = 15_000) { loadRules(s) }
    val runner = rememberActionRunner()
    var confirmAll by remember { mutableStateOf(false) }
    var confirmOne by remember { mutableStateOf<MtRuleRow?>(null) }
    CardList {
        item("status") {
            val st = rules.value?.status
            val running = st?.processingQueue == true || st?.executingRuleGroupId != null
            val queued = st?.pendingRuleGroupIds?.size ?: 0
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        st == null -> "Rules run on Maintainerr's schedule. Running them updates the collections; nothing is deleted until an item's time is up."
                        running -> "Rules are running" + (if (queued > 0) " · ${queued} queued" else "")
                        else -> "Rules aren't running. Running them updates the collections; nothing is deleted until an item's time is up."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                if (runner.isBusy("mt:${s.id}:runall")) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else OutlinedButton(onClick = { confirmAll = true }, enabled = !running && rules.value != null) { Text("Run all") }
            }
        }
        item("list") {
            SectionCard("Rules and collections", icon = Icons.Rounded.Checklist) {
                CardLoad(rules, placeholder = { SkeletonRows(4, rowHeight = 56.dp) }) { o ->
                    if (o.rows.isEmpty()) CardEmpty("No rules yet. Create them in Maintainerr's web UI.")
                    o.rows.forEachIndexed { i, row ->
                        if (i > 0) Divider()
                        val id = row.rule?.id
                        val executing = id != null && (o.status?.executingRuleGroupId == id || id in o.status?.pendingRuleGroupIds.orEmpty())
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(row.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                row.collection?.let { c ->
                                    Text(
                                        listOfNotNull(
                                            plural(c.mediaCount, "item"),
                                            c.totalSizeBytes?.takeIf { it > 0 }?.let { bytes(it) },
                                            timerText(c),
                                        ).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (c.handledMediaAmount > 0) Text(
                                        "Handled so far: " + plural(c.handledMediaAmount, "item") + (c.handledMediaSizeBytes.takeIf { it > 0 }?.let { ", ${bytes(it)} freed" } ?: ""),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (row.active) Tag("Active") else Tag("Off", container = MaterialTheme.colorScheme.surfaceContainerHighest, content = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (row.rule == null) Tag("Manual collection")
                                    if (row.rule != null && !row.rule.useRules) Tag("No rules")
                                    row.rule?.rules?.size?.takeIf { it > 0 }?.let { Tag(plural(it, "rule")) }
                                    if (executing) Tag("Running", container = MaterialTheme.colorScheme.primaryContainer, content = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            }
                            if (row.canRun) {
                                if (runner.isBusy("mt:${s.id}:run:$id") || executing) CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp)
                                else IconButton(onClick = { confirmOne = row }) { Icon(Icons.Rounded.PlayArrow, "Run ${row.title} now") }
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmAll) ConfirmDialog(
        title = "Run every active rule?",
        text = "Maintainerr re-checks your library and updates every collection now. Items it adds start their timers today; nothing is deleted by this.",
        confirm = "Run all",
        onConfirm = {
            runner.run("mt:${s.id}:runall", after = { rules.refresh() }) { mtRunAllRules(s); "Rules started" }
        },
        onDismiss = { confirmAll = false },
    )
    confirmOne?.let { row ->
        ConfirmDialog(
            title = "Run “${row.title}”?",
            text = "Maintainerr re-checks your library against this rule and updates its collection now. Nothing is deleted by this.",
            confirm = "Run",
            onConfirm = {
                row.rule?.id?.let { id ->
                    runner.run("mt:${s.id}:run:$id", after = { rules.refresh() }) { mtRunRule(s, id); "Running “${row.title}”" }
                }
            },
            onDismiss = { confirmOne = null },
        )
    }
}

/* ================================================================ kept (exclusions) */

@Composable
private fun KeptTab(s: ServiceConfig, hub: RefreshHub) {
    val kept = rememberHubLoader(hub, "svc:mt:${s.id}:kept", refreshMs = 5 * 60_000) { loadKept(s) }
    val runner = rememberActionRunner()
    var undoing by remember { mutableStateOf<MtKept?>(null) }
    CardList {
        item("help") {
            Text(
                "Items you've kept are excluded from rules, so Maintainerr won't collect or delete them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        item("list") {
            SectionCard("Kept", icon = Icons.Rounded.Shield) {
                CardLoad(kept, placeholder = { SkeletonRows(4, rowHeight = 48.dp) }) { list ->
                    if (list.isEmpty()) CardEmpty("Nothing kept yet")
                    list.forEachIndexed { i, k ->
                        if (i > 0) Divider()
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(k.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(k.from, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (runner.isBusy("mt:${s.id}:unkeep:${k.exclusion.id}")) CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp)
                            else TextButton(onClick = { undoing = k }) { Text("Undo") }
                        }
                    }
                }
            }
        }
    }
    undoing?.let { k ->
        ConfirmDialog(
            title = "Stop keeping this?",
            text = "${k.title}\n\nIt's no longer excluded" + (if (k.collectionId == null) " from any rule" else " from this rule") +
                ". If it still matches, the next rule run collects it again and its timer starts over.",
            confirm = "Stop keeping",
            onConfirm = {
                runner.run("mt:${s.id}:unkeep:${k.exclusion.id}", after = { kept.refresh() }) {
                    mtUnkeep(s, k.exclusion.parent ?: k.exclusion.mediaServerId, k.collectionId)
                    "No longer kept"
                }
            },
            onDismiss = { undoing = null },
        )
    }
}
