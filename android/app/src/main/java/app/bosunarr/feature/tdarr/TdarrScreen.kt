package app.bosunarr.feature.tdarr

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import app.bosunarr.data.ServiceConfig
import app.bosunarr.feature.services.CardEmpty
import app.bosunarr.feature.services.CardList
import app.bosunarr.feature.services.CardLoad
import app.bosunarr.feature.services.HubRefreshBox
import app.bosunarr.feature.services.InfoLine
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
import app.bosunarr.ui.common.Loader
import app.bosunarr.ui.common.Tag
import app.bosunarr.ui.common.bytes
import app.bosunarr.ui.common.eta
import app.bosunarr.ui.common.plural
import java.util.Locale
import kotlin.math.abs

/** Live worker progress refreshes this often while the screen is open. */
private const val LIVE_MS = 5_000L

/** "45% · 125 fps · 1m 30s left": the parts Tdarr knows. */
internal fun workerProgressLine(w: TdWorker): String = listOfNotNull(
    w.percent?.let { "${it.toInt().coerceIn(0, 100)}%" },
    w.fps?.takeIf { it > 0 }?.let { "${it.toInt()} fps" },
    w.etaSeconds?.takeIf { it > 0 }?.let { "${eta(it)} left" },
).joinToString(" · ")

/** "4.5 GB → about 4.2 GB": the source file and what it is becoming (the output so far, or Tdarr's estimate). */
internal fun workerSizeLine(w: TdWorker): String? {
    val from = w.originalBytes ?: return null
    return when {
        w.estimatedBytes != null -> "${bytes(from)} → about ${bytes(w.estimatedBytes)}"
        w.outputBytes != null -> "${bytes(from)} → ${bytes(w.outputBytes)} so far"
        else -> bytes(from)
    }
}

/** "Transcode CPU 2 · GPU 1 · Health check CPU 1": the node's non-zero worker limits. */
internal fun limitsLine(limits: Map<String, Int>): String? {
    fun part(job: String, prefix: String): String? {
        val hw = listOf("cpu" to "CPU", "gpu" to "GPU").mapNotNull { (k, label) -> limits["$prefix$k"]?.takeIf { it > 0 }?.let { "$label $it" } }
        return if (hw.isEmpty()) null else "$job ${hw.joinToString(" · ")}"
    }
    return listOfNotNull(part("Transcode", "transcode"), part("Health check", "healthcheck")).joinToString(" · ").ifEmpty { null }
}

private fun gb(v: Double) = String.format(Locale.getDefault(), "%.1f", v)

@Composable
fun TdarrServiceScreen(s: ServiceConfig, nav: NavController) {
    val hub = rememberRefreshHub()
    val status = rememberHubLoader(hub, "svc:td:${s.id}:status", refreshMs = 10 * 60_000) { tdStatus(s) }
    val version = status.value?.version
    ServiceFrame(s, nav, subtitle = listOfNotNull(s.serviceType?.label, version?.let { "v$it" }).joinToString(" · ")) { p ->
        HubRefreshBox(hub, Modifier.padding(p)) {
            TabbedBody(listOf("Overview", "Nodes", "Libraries", "Queue")) { tab ->
                when (tab) {
                    0 -> OverviewTab(s, hub, status)
                    1 -> NodesTab(s, hub)
                    2 -> LibrariesTab(s, hub)
                    else -> QueueTab(s, hub)
                }
            }
        }
    }
}

/* ================================================================ overview */

@Composable
private fun OverviewTab(s: ServiceConfig, hub: RefreshHub, status: Loader<TdStatus>) {
    val stats = rememberHubLoader(hub, "svc:td:${s.id}:stats", refreshMs = 30_000) { tdStats(s) }
    val nodes = rememberHubLoader(hub, "svc:td:${s.id}:nodes", refreshMs = LIVE_MS, staleMs = LIVE_MS) { tdNodes(s) }
    CardList {
        item("stats") {
            CardLoad(stats, placeholder = { StatRow { repeat(3) { SkeletonBox(Modifier.weight(1f).height(64.dp), RoundedCornerShape(16.dp)) } } }) { st ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatRow {
                        StatTile("Files", String.format(Locale.getDefault(), "%,d", st.files), Modifier.weight(1f))
                        StatTile("Transcodes", String.format(Locale.getDefault(), "%,d", st.transcodes), Modifier.weight(1f))
                        StatTile("Health checks", String.format(Locale.getDefault(), "%,d", st.healthChecks), Modifier.weight(1f))
                    }
                    StatRow {
                        StatTile("In queue", String.format(Locale.getDefault(), "%,d", st.queue), Modifier.weight(1f))
                        StatTile("Errors", String.format(Locale.getDefault(), "%,d", st.errors), Modifier.weight(1f), tint = if (st.errors > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                        StatTile(if (st.savedGb < 0) "Size grew" else "Space saved", bytes(gbToBytes(abs(st.savedGb))), Modifier.weight(1f))
                    }
                    Text(
                        listOfNotNull(
                            "${plural(st.transcodeQueue, "file")} to transcode",
                            "${plural(st.healthQueue, "file")} to health-check",
                            st.hold.takeIf { it > 0 }?.let { "$it on hold" },
                            st.transcodeErrors.takeIf { it > 0 }?.let { plural(it, "transcode error") },
                            st.healthErrors.takeIf { it > 0 }?.let { plural(it, "failed health check") },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
            }
        }
        item("working") {
            val busy = nodes.value.orEmpty().flatMap { n -> n.busy.map { n to it } }
            SectionCard("Working now", icon = Icons.Rounded.Speed, trailing = {
                if (busy.isNotEmpty()) Tag(busy.size.toString(), modifier = Modifier.padding(end = 8.dp), container = MaterialTheme.colorScheme.primary, content = MaterialTheme.colorScheme.onPrimary)
            }) {
                CardLoad(nodes, placeholder = { SkeletonRows(2, rowHeight = 56.dp) }) { list ->
                    when {
                        list.isEmpty() -> CardEmpty("No nodes are connected")
                        busy.isEmpty() -> CardEmpty(if (list.all { it.paused }) "Every node is paused" else "All workers are idle")
                    }
                    busy.forEachIndexed { i, (node, w) ->
                        if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        WorkerRow(w, node = node.name.takeIf { list.size > 1 })
                    }
                }
            }
        }
        item("server") {
            SectionCard("Server", icon = Icons.Rounded.Dns) {
                CardLoad(status, placeholder = { SkeletonRows(2, rowHeight = 20.dp) }) { st ->
                    st.version?.let { InfoLine("Version", it) }
                    st.os?.let { InfoLine("System", it.replaceFirstChar { c -> c.titlecase(Locale.getDefault()) }) }
                    st.uptimeSeconds?.let { InfoLine("Running for", eta(it)) }
                    if (!st.healthy && st.status != null) InfoLine("Status", st.status)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkerRow(w: TdWorker, node: String? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        val file = w.file?.substringAfterLast('/')?.substringAfterLast('\\')
        Text(file ?: "Waiting for a file", style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(node, w.status, w.step?.let { "step $it" }).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        w.percent?.let { pct ->
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { (pct / 100).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                drawStopIndicator = {},
            )
        }
        FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val gpu = w.type.endsWith("gpu", ignoreCase = true)
            Tag(
                workerTypeLabel(w.type),
                container = if (gpu) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                content = if (gpu) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
            )
            workerProgressLine(w).takeIf { it.isNotEmpty() }?.let { Tag(it) }
            workerSizeLine(w)?.let { Tag(it) }
        }
    }
}

/* ================================================================ nodes */

/** A pause/resume the user is confirming: one node, or all of them (null). */
private data class PauseAsk(val node: TdNode?, val pause: Boolean)

@Composable
private fun NodesTab(s: ServiceConfig, hub: RefreshHub) {
    val nodes = rememberHubLoader(hub, "svc:td:${s.id}:nodes", refreshMs = LIVE_MS, staleMs = LIVE_MS) { tdNodes(s) }
    val pausedAll = rememberHubLoader(hub, "svc:td:${s.id}:pausedall", refreshMs = 30_000) { tdPausedAll(s) }
    val runner = rememberActionRunner()
    var ask by remember { mutableStateOf<PauseAsk?>(null) }
    CardList {
        item("all") {
            val all = pausedAll.value
            SectionCard("All nodes", icon = Icons.Rounded.Dns, trailing = {
                if (all != null) {
                    if (runner.isBusy("td:${s.id}:pauseall")) CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp)
                    else TextButton(onClick = { ask = PauseAsk(null, !all) }) { Text(if (all) "Resume all" else "Pause all") }
                }
            }) {
                CardLoad(pausedAll, placeholder = { SkeletonRows(1, rowHeight = 20.dp) }) { paused ->
                    Text(
                        if (paused) "Paused in Tdarr: no node starts new work until you resume."
                        else "Running. Pausing stops every node from starting new work; files already in progress finish.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (paused) warnColor() else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }
        val list = nodes.value
        if (list == null) item("load") {
            SectionCard("Nodes", icon = Icons.Rounded.Memory) { CardLoad(nodes, placeholder = { SkeletonRows(3, rowHeight = 56.dp) }) {} }
        } else if (list.isEmpty()) item("none") {
            SectionCard("Nodes", icon = Icons.Rounded.Memory) { CardEmpty("No nodes are connected to this server") }
        } else list.forEach { node ->
            item("node:${node.id}") { NodeCard(node, busy = runner.isBusy("td:${s.id}:pause:${node.id}"), onToggle = { ask = PauseAsk(node, !node.paused) }) }
        }
    }
    ask?.let { a ->
        val node = a.node
        ConfirmDialog(
            title = when {
                node == null && a.pause -> "Pause all nodes?"
                node == null -> "Resume all nodes?"
                a.pause -> "Pause ${node.name}?"
                else -> "Resume ${node.name}?"
            },
            text = if (a.pause) "Workers finish the files they're on, then stop taking new ones until you resume." else "Workers start taking files from the queue again.",
            confirm = if (a.pause) "Pause" else "Resume",
            onConfirm = {
                if (node == null) runner.run("td:${s.id}:pauseall", after = { pausedAll.refresh(); nodes.refresh() }) {
                    tdSetPausedAll(s, a.pause)
                    if (a.pause) "All nodes paused" else "All nodes resumed"
                } else runner.run("td:${s.id}:pause:${node.id}", after = { nodes.refresh() }) {
                    tdSetNodePaused(s, node.id, a.pause)
                    if (a.pause) "${node.name} paused" else "${node.name} resumed"
                }
            },
            onDismiss = { ask = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NodeCard(node: TdNode, busy: Boolean, onToggle: () -> Unit) {
    SectionCard(node.name, icon = Icons.Rounded.Memory, trailing = {
        if (busy) CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp)
        else TextButton(onClick = onToggle) { Text(if (node.paused) "Resume" else "Pause") }
    }) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (node.paused) Tag("Paused", container = MaterialTheme.colorScheme.errorContainer, content = MaterialTheme.colorScheme.onErrorContainer)
                node.cpuPercent?.let { Tag("CPU ${it.toInt()}%") }
                if (node.memUsedGb != null && node.memTotalGb != null) Tag("RAM ${gb(node.memUsedGb)} of ${gb(node.memTotalGb)} GB")
            }
            val details = listOfNotNull(limitsLine(node.limits), node.address).joinToString(" · ")
            if (details.isNotEmpty()) Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val busyWorkers = node.busy
        if (busyWorkers.isEmpty()) CardEmpty(if (node.paused) "Paused, nothing in progress" else "Idle")
        busyWorkers.forEachIndexed { i, w ->
            HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = if (i == 0) 6.dp else 0.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            WorkerRow(w)
        }
    }
}

/* ================================================================ libraries */

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibrariesTab(s: ServiceConfig, hub: RefreshHub) {
    val libs = rememberHubLoader(hub, "svc:td:${s.id}:libraries", refreshMs = 60_000) { tdLibraries(s) }
    CardList {
        item("libs") {
            SectionCard("Libraries", icon = Icons.Rounded.VideoLibrary) {
                CardLoad(libs, placeholder = { SkeletonRows(3, rowHeight = 64.dp) }) { list ->
                    if (list.isEmpty()) CardEmpty("No libraries set up in Tdarr")
                    list.forEachIndexed { i, lib ->
                        if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(lib.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (!lib.loaded) Text("Counts unavailable", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else LibraryCounts(lib)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryCounts(lib: TdLibrary) {
    Text(
        listOfNotNull(plural(lib.files ?: 0, "file"), lib.savedGb?.takeIf { it != 0.0 }?.let(::savedLabel)).joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (lib.queued > 0) Tag("${lib.queued} queued", container = MaterialTheme.colorScheme.primaryContainer, content = MaterialTheme.colorScheme.onPrimaryContainer)
        if (lib.transcoded > 0) Tag("${lib.transcoded} transcoded")
        if (lib.notRequired > 0) Tag("${lib.notRequired} not required")
        if (lib.errors > 0) Tag(plural(lib.errors, "error"), container = MaterialTheme.colorScheme.errorContainer, content = MaterialTheme.colorScheme.onErrorContainer)
    }
    if (lib.codecs.isNotEmpty()) {
        Text(
            lib.codecs.take(4).joinToString(" · ") { (c, n) -> "${c.uppercase(Locale.ROOT)} $n" },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/* ================================================================ queue + errors */

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QueueTab(s: ServiceConfig, hub: RefreshHub) {
    var table by rememberSaveable { mutableStateOf(TdTable.TranscodeQueue) }
    var limit by rememberSaveable { mutableIntStateOf(30) }
    val files = rememberHubLoader(hub, "svc:td:${s.id}:files:${table.name}:$limit", refreshMs = 30_000) { tdFiles(s, table, limit) }
    CardList {
        item("tables") {
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TdTable.entries.forEach { t -> FilterChip(selected = table == t, onClick = { table = t; limit = 30 }, label = { Text(t.label) }) }
            }
        }
        item("files") {
            val errors = table == TdTable.TranscodeErrors || table == TdTable.HealthErrors
            SectionCard(table.label, icon = Icons.Rounded.Movie, trailing = {
                files.value?.total?.takeIf { it > 0 }?.let { Tag(String.format(Locale.getDefault(), "%,d", it), modifier = Modifier.padding(end = 8.dp)) }
            }) {
                CardLoad(files, placeholder = { SkeletonRows(6, rowHeight = 52.dp) }) { page ->
                    if (page.items.isEmpty()) CardEmpty(table.empty)
                    page.items.forEachIndexed { i, f ->
                        if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(f.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            f.folder?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (errors) {
                                    val what = if (table == TdTable.TranscodeErrors) f.transcode else f.health
                                    what?.let { Tag(it, container = MaterialTheme.colorScheme.errorContainer, content = MaterialTheme.colorScheme.onErrorContainer) }
                                }
                                f.codec?.let { Tag(it.uppercase(Locale.ROOT)) }
                                f.resolution?.let { Tag(it) }
                                f.container?.let { Tag(it) }
                                f.sizeBytes?.let { Tag(bytes(it)) }
                            }
                        }
                    }
                    if (page.items.size < page.total) {
                        OutlinedButton(onClick = { limit += 30 }, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            Text("Show more (${String.format(Locale.getDefault(), "%,d", page.total - page.items.size)} left)")
                        }
                    }
                }
            }
        }
    }
}
