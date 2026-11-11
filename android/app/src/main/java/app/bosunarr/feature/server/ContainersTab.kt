package app.bosunarr.feature.server

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.bosunarr.data.ServiceConfig
import app.bosunarr.feature.services.CardList
import app.bosunarr.feature.services.CardLoad
import app.bosunarr.feature.services.HubRefreshBox
import app.bosunarr.feature.services.InfoLine
import app.bosunarr.feature.services.SectionCard
import app.bosunarr.feature.services.SkeletonRows
import app.bosunarr.feature.services.StatRow
import app.bosunarr.feature.services.StatTile
import app.bosunarr.feature.services.StatusDot
import app.bosunarr.feature.services.okColor
import app.bosunarr.feature.services.rememberActionRunner
import app.bosunarr.feature.services.rememberHubLoader
import app.bosunarr.feature.services.rememberRefreshHub
import app.bosunarr.feature.services.warnColor
import app.bosunarr.net.userMessage
import app.bosunarr.ui.common.ConfirmDialog
import app.bosunarr.ui.common.Tag
import app.bosunarr.ui.common.relative
import kotlinx.coroutines.CancellationException

/** What the Containers tab shows: either the list or why Docker can't be used. */
private data class ContainersView(val access: DockerAccess, val containers: List<Container>)

@Composable
private fun stateColor(c: Container): Color = when {
    c.health == "unhealthy" -> MaterialTheme.colorScheme.error
    c.running && c.health == "health: starting" -> warnColor()
    c.running -> okColor()
    c.state == "restarting" || c.state == "paused" -> warnColor()
    c.state == "exited" && (c.exitCode ?: 0) != 0 -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.outline
}

private fun Container.stateLabel(): String = when {
    health == "unhealthy" -> "Unhealthy"
    running -> "Running"
    state == "exited" -> exitCode?.let { "Exited ($it)" } ?: "Exited"
    else -> state.replaceFirstChar { it.uppercase() }
}

@Composable
internal fun ContainersTab(s: ServiceConfig, session: ServerSession) {
    val hub = rememberRefreshHub()
    val runner = rememberActionRunner()
    val loader = rememberHubLoader(hub, "svc:server:${s.id}:containers", refreshMs = 10_000, staleMs = 5_000) {
        when (val a = session.dockerAccess(s)) {
            is DockerAccess.Ok -> ContainersView(a, session.containers(s))
            else -> ContainersView(a, emptyList())
        }
    }
    // `docker stats --no-stream` takes ~2 s: separate, slower poll, merged in by id.
    val stats = rememberHubLoader(hub, "svc:server:${s.id}:stats", refreshMs = 15_000, staleMs = 10_000) {
        if (session.dockerAccess(s) is DockerAccess.Ok) session.stats(s) else emptyMap()
    }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }

    HubRefreshBox(hub) {
        CardList {
            item("summary") {
                SectionCard("Docker", icon = Icons.Rounded.ViewInAr) {
                    HostKeyBanner(session)
                    CardLoad(loader, placeholder = { SkeletonRows(1, rowHeight = 68.dp) }) { v ->
                        when (val a = v.access) {
                            is DockerAccess.Ok -> {
                                val running = v.containers.count { it.running }
                                val unhealthy = v.containers.count { it.health == "unhealthy" }
                                StatRow {
                                    StatTile("Running", running.toString(), Modifier.weight(1f), tint = okColor())
                                    StatTile("Stopped", (v.containers.size - running).toString(), Modifier.weight(1f))
                                    if (unhealthy > 0) StatTile("Unhealthy", unhealthy.toString(), Modifier.weight(1f), tint = MaterialTheme.colorScheme.error)
                                }
                                Text(
                                    listOfNotNull(a.version?.let { "Docker $it" }, if (a.prefix.startsWith("sudo")) "via sudo" else null).joinToString(" · "),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                )
                            }
                            DockerAccess.Missing -> DockerProblem(
                                "Docker isn't installed",
                                "The docker command wasn't found for ${s.username}. If Docker runs here, check that it's on the PATH (e.g. /usr/bin or /snap/bin).",
                            )
                            is DockerAccess.Denied -> DockerProblem(
                                "${s.username} can't use Docker",
                                "This user isn't in the docker group (groups: ${a.groups.ifBlank { "none" }}). On the server run:",
                                command = "sudo usermod -aG docker ${s.username}",
                                after = "then reconnect (the group applies to new logins). Or allow passwordless sudo for docker.",
                            )
                            is DockerAccess.DaemonDown -> DockerProblem("Docker isn't answering", a.detail.take(300))
                        }
                    }
                }
            }
            val v = loader.value
            if (v != null && v.access is DockerAccess.Ok) {
                val groups = v.containers.groupBy { it.project }
                    .toList()
                    .sortedWith(compareBy({ it.first == null }, { it.first?.lowercase() }))
                if (v.containers.isEmpty()) item("empty") {
                    SectionCard("Containers", icon = Icons.Rounded.Layers) { Text("No containers", Modifier.padding(16.dp)) }
                }
                groups.forEach { (project, list) ->
                    item("p:${project ?: "-"}") {
                        val up = list.count { it.running }
                        SectionCard(
                            project ?: "Standalone",
                            icon = Icons.Rounded.Layers,
                            trailing = { Text("$up/${list.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp)) },
                        ) {
                            list.sortedWith(compareBy({ !it.running }, { it.name })).forEach { c ->
                                ContainerRow(c, stats.value?.get(c.id) ?: stats.value?.get("name:${c.name}")) { selected = c.id }
                            }
                        }
                    }
                }
            }
        }
    }

    val c = loader.value?.containers?.firstOrNull { it.id == selected }
    if (c != null) {
        ContainerSheet(
            s, session, c,
            stats = stats.value?.get(c.id) ?: stats.value?.get("name:${c.name}"),
            busy = { runner.isBusy("${c.id}:$it") },
            onAction = { action ->
                runner.run("${c.id}:${action.verb}", success = "${c.name} ${action.done}", after = { loader.refresh(); stats.refresh() }) {
                    session.action(s, c.id, action); null
                }
            },
            onDismiss = { selected = null },
        )
    }
}

@Composable
private fun DockerProblem(title: String, text: String, command: String? = null, after: String? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
        Text(text, style = MaterialTheme.typography.bodyMedium)
        if (command != null) CodeBox(command)
        if (after != null) Text(after, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ContainerRow(c: Container, stats: ContainerStats?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(stateColor(c), size = 10.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(c.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(c.status, c.image.substringAfterLast('/')).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (stats != null && c.running) {
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(stats.cpu, style = MaterialTheme.typography.labelMedium)
                Text(stats.mem.substringBefore(" /"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContainerSheet(
    s: ServiceConfig,
    session: ServerSession,
    c: Container,
    stats: ContainerStats?,
    busy: (String) -> Boolean,
    onAction: (ContainerAction) -> Unit,
    onDismiss: () -> Unit,
) {
    var confirm by remember { mutableStateOf<ContainerAction?>(null) }
    var logs by remember { mutableStateOf(false) }
    var details by remember(c.id) { mutableStateOf<ContainerDetails?>(null) }
    LaunchedEffect(c.id, c.state) {
        details = runCatching { session.inspect(s, c.id) }.getOrNull()
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(stateColor(c), size = 12.dp)
                Spacer(Modifier.width(10.dp))
                Text(c.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Tag(c.stateLabel())
            }
            Text(
                c.status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!c.running) {
                    FilledTonalButton(onClick = { onAction(ContainerAction.Start) }, enabled = !busy("start"), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Start", maxLines = 1)
                    }
                } else {
                    OutlinedButton(onClick = { confirm = ContainerAction.Stop }, enabled = !busy("stop"), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Stop", maxLines = 1)
                    }
                }
                OutlinedButton(onClick = { confirm = ContainerAction.Restart }, enabled = !busy("restart"), modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.RestartAlt, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Restart", maxLines = 1)
                }
                OutlinedButton(onClick = { logs = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.AutoMirrored.Rounded.Article, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Logs", maxLines = 1)
                }
            }
            Spacer(Modifier.height(8.dp))
            if (stats != null && c.running) {
                StatRow {
                    StatTile("CPU", stats.cpu.ifBlank { "—" }, Modifier.weight(1f))
                    StatTile("Memory", stats.mem.substringBefore(" /").ifBlank { "—" }, Modifier.weight(1f))
                }
                InfoLine("Memory limit", stats.mem.substringAfter("/ ", "—"))
                InfoLine("Network I/O", stats.net.ifBlank { "—" })
                InfoLine("Disk I/O", stats.block.ifBlank { "—" })
                InfoLine("Processes", stats.pids.ifBlank { "—" })
            }
            InfoLine("Image", c.image)
            c.project?.let { InfoLine("Compose", listOfNotNull(it, c.service).joinToString(" / ")) }
            if (c.ports.isNotBlank()) InfoLine("Ports", c.ports.split(", ").distinct().joinToString("\n"))
            InfoLine("ID", c.shortId)
            details?.let { d ->
                d.health?.let { InfoLine("Health", it) }
                d.startedAt?.let { if (c.running) InfoLine("Started", relative(it).ifEmpty { it }) }
                d.finishedAt?.let { if (!c.running) InfoLine("Stopped", relative(it).ifEmpty { it }) }
                d.restartPolicy?.let { p -> InfoLine("Restart policy", p + (d.restartCount?.takeIf { it > 0 }?.let { " · restarted $it×" } ?: "")) }
                if (d.networks.isNotEmpty()) InfoLine("Networks", d.networks.joinToString(", "))
                if (d.mounts.isNotEmpty()) InfoLine("Mounts", d.mounts.joinToString("\n"))
            }
            if (c.created.isNotBlank()) InfoLine("Created", c.created.substringBefore(" +").substringBefore(" -"))
        }
    }
    confirm?.let { a ->
        ConfirmDialog(
            title = "${a.label} ${c.name}?",
            text = when (a) {
                ContainerAction.Stop -> "The container stops until you start it again (or its restart policy brings it back on reboot)."
                ContainerAction.Restart -> "The container stops and starts again; whatever it serves is briefly unavailable."
                ContainerAction.Start -> null
            },
            confirm = a.label,
            destructive = a == ContainerAction.Stop,
            onConfirm = { confirm = null; onAction(a) },
            onDismiss = { confirm = null },
        )
    }
    if (logs) LogsDialog(s, session, c, onDismiss = { logs = false })
}

/** Full-screen log viewer: last 200 lines, or live with "Follow". */
@Composable
private fun LogsDialog(s: ServiceConfig, session: ServerSession, c: Container, onDismiss: () -> Unit) {
    val lines = remember { mutableStateListOf<String>() }
    var follow by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableStateOf(0) }
    val list = rememberLazyListState()

    LaunchedEffect(follow, reload) {
        error = null
        loading = true
        try {
            if (!follow) {
                val text = session.logs(s, c.id)
                lines.clear()
                lines.addAll(text.trimEnd().lines().takeLast(5000))
                loading = false
            } else {
                lines.clear()
                loading = false
                session.followLogs(s, c.id).collect { l ->
                    lines.add(l)
                    if (lines.size > 3000) lines.removeRange(0, lines.size - 3000)
                }
                error = "Log stream ended"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loading = false
            error = e.userMessage()
        }
    }
    // Stick to the bottom as lines arrive (unless the user scrolled up).
    LaunchedEffect(lines.size) {
        if (lines.isEmpty()) return@LaunchedEffect
        val last = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (last < 0 || last >= lines.size - 4 || !list.canScrollForward) list.scrollToItem(lines.size - 1)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Close") }
                    Column(Modifier.weight(1f)) {
                        Text(c.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (follow) "Live" else "Last 200 lines", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!follow) IconButton(onClick = { reload++ }) { Icon(Icons.Rounded.RestartAlt, "Reload") }
                    Text("Follow", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 8.dp))
                    Switch(checked = follow, onCheckedChange = { follow = it })
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (loading && lines.isEmpty()) SkeletonRows(6, rowHeight = 14.dp)
                    SelectionContainer {
                        LazyColumn(state = list, modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                            itemsIndexed(lines) { _, l ->
                                Text(l, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 1.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
