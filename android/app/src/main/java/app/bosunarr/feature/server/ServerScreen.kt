package app.bosunarr.feature.server

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import app.bosunarr.app
import app.bosunarr.data.ServiceConfig
import app.bosunarr.feature.services.CardList
import app.bosunarr.feature.services.CardLoad
import app.bosunarr.feature.services.DiskRow
import app.bosunarr.feature.services.HubRefreshBox
import app.bosunarr.feature.services.InfoLine
import app.bosunarr.feature.services.RefreshHub
import app.bosunarr.feature.services.SectionCard
import app.bosunarr.feature.services.ServiceFrame
import app.bosunarr.feature.services.SkeletonRows
import app.bosunarr.feature.services.StatRow
import app.bosunarr.feature.services.StatTile
import app.bosunarr.feature.services.TabbedBody
import app.bosunarr.feature.services.rememberHubLoader
import app.bosunarr.feature.services.rememberRefreshHub
import app.bosunarr.feature.services.warnColor
import app.bosunarr.ui.common.bytes
import kotlinx.coroutines.launch
import java.util.Locale

/** Holds the screen's SSH session across recompositions and rotation; closes it when the screen goes away. */
class ServerViewModel : ViewModel() {
    val session = ServerSession()
    override fun onCleared() = session.close()
}

/** A Server (SSH) service: host health, Docker containers, saved commands. */
@Composable
fun ServerServiceScreen(s: ServiceConfig, nav: NavController) {
    val vm: ServerViewModel = viewModel(key = "server:${s.id}")
    val session = vm.session
    // Don't hold the connection open while the app is in the background; the next poll reconnects.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, session) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) session.close() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val hub = rememberRefreshHub()
    val target = SshTarget.parse(s.url)
    ServiceFrame(s, nav, subtitle = listOfNotNull(s.username?.let { "$it@" + (target?.display ?: "") } ?: target?.display, "SSH").joinToString(" · ")) { p ->
        TabbedBody(listOf("Overview", "Containers", "Commands"), Modifier.padding(p)) { tab ->
            when (tab) {
                0 -> OverviewTab(s, session, hub)
                1 -> ContainersTab(s, session)
                else -> CommandsTab(s, session)
            }
        }
    }
    HostKeyPrompt(s, session, onTrusted = { hub.refreshAll() })
}

/** When a poll is refused over the host key, ask once (per problem) and pin the key on "trust". */
@Composable
private fun HostKeyPrompt(s: ServiceConfig, session: ServerSession, onTrusted: () -> Unit) {
    val problem by session.hostKeyProblem
    var dismissed by remember { mutableStateOf<HostKeyPin?>(null) }
    val p = problem ?: return
    val presented = (p as? HostKeyUnknown)?.presented ?: (p as? HostKeyMismatch)?.presented ?: return
    if (dismissed == presented) return
    HostKeyDialog(
        p,
        onTrust = { pin ->
            dismissed = pin
            session.hostKeyProblem.value = null
            app.scope.launch {
                val current = app.service(s.id) ?: s
                app.saveService(current.copy(sshHostKey = pin.encode()))
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onTrusted() }
            }
        },
        onDismiss = { dismissed = presented },
    )
}

/** Inline explanation + action for a refused host key, inside a card. */
@Composable
internal fun HostKeyBanner(session: ServerSession) {
    val p = session.hostKeyProblem.value ?: return
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.GppMaybe, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(p.message.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
    }
}

/* ================================================================ overview */

internal fun uptimeText(sec: Long): String {
    val d = sec / 86_400
    val h = (sec % 86_400) / 3600
    val m = (sec % 3600) / 60
    return when {
        d > 0 -> "${d}d ${h}h"
        h > 0 -> "${h}h ${m}m"
        else -> "${m}m"
    }
}

private fun pctText(f: Double?) = if (f == null) "—" else String.format(Locale.US, if (f < 0.1) "%.1f%%" else "%.0f%%", f * 100)

@Composable
private fun levelColor(f: Double?): Color = when {
    f == null -> MaterialTheme.colorScheme.primary
    f >= 0.9 -> MaterialTheme.colorScheme.error
    f >= 0.75 -> warnColor()
    else -> MaterialTheme.colorScheme.primary
}

/** Label, value on the right, and a usage bar. */
@Composable
internal fun MeterRow(label: String, value: String, fraction: Double?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        if (fraction != null) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { fraction.toFloat().coerceIn(0f, 1f) },
                color = levelColor(fraction),
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                drawStopIndicator = {},
            )
        }
    }
}

private fun rate(bps: Double): String = if (bps < 1) "0 B/s" else bytes(bps.toLong()) + "/s"

@Composable
private fun OverviewTab(s: ServiceConfig, session: ServerSession, hub: RefreshHub) {
    // Polls every 5 s while visible over the one connection; CPU/network rates come from consecutive polls.
    val loader = rememberHubLoader(hub, "svc:server:${s.id}:overview", refreshMs = 5_000, staleMs = 5_000) { session.overview(s) }
    HubRefreshBox(hub) {
        CardList {
            item("system") {
                SectionCard("System", icon = Icons.Rounded.Dns) {
                    HostKeyBanner(session)
                    CardLoad(loader, placeholder = { SkeletonRows(3, rowHeight = 20.dp) }) { o ->
                        Text(
                            o.hostname,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        o.os?.let { InfoLine("OS", it) }
                        o.kernel?.let { InfoLine("Kernel", it) }
                        InfoLine("Uptime", uptimeText(o.uptimeSec))
                    }
                }
            }
            item("cpu") {
                SectionCard("CPU", icon = Icons.Rounded.Speed) {
                    CardLoad(loader, placeholder = { SkeletonRows(1, rowHeight = 68.dp) }) { o ->
                        StatRow {
                            StatTile("Usage", pctText(o.cpu), Modifier.weight(1f), tint = levelColor(o.cpu))
                            StatTile("Load (1 min)", o.load.getOrNull(0)?.let { String.format(Locale.US, "%.2f", it) } ?: "—", Modifier.weight(1f),
                                tint = if (o.cores != null && (o.load.getOrNull(0) ?: 0.0) > o.cores) warnColor() else MaterialTheme.colorScheme.onSurface)
                        }
                        MeterRow("CPU", pctText(o.cpu), o.cpu)
                        val loads = o.load.joinToString(" · ") { String.format(Locale.US, "%.2f", it) }
                        Text(
                            listOfNotNull("Load $loads", o.cores?.let { "$it cores" }).joinToString("  ·  "),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            item("mem") {
                SectionCard("Memory", icon = Icons.Rounded.Memory) {
                    CardLoad(loader, placeholder = { SkeletonRows(2, rowHeight = 24.dp) }) { o ->
                        val ram = if (o.memTotal > 0) o.memUsed.toDouble() / o.memTotal else null
                        MeterRow("RAM", "${bytes(o.memUsed)} of ${bytes(o.memTotal)}", ram)
                        if (o.swapTotal > 0) {
                            MeterRow("Swap", "${bytes(o.swapUsed)} of ${bytes(o.swapTotal)}", o.swapUsed.toDouble() / o.swapTotal)
                        } else Text("No swap", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    }
                }
            }
            item("disks") {
                SectionCard("Disks", icon = Icons.Rounded.Storage) {
                    CardLoad(loader, placeholder = { SkeletonRows(2, rowHeight = 24.dp) }) { o ->
                        if (o.disks.isEmpty()) Text("No disks reported", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                        o.disks.forEach { d -> DiskRow(d.mount, d.available, d.total, note = d.filesystem) }
                    }
                }
            }
            item("net") {
                SectionCard("Network", icon = Icons.Rounded.NetworkCheck) {
                    CardLoad(loader, placeholder = { SkeletonRows(2, rowHeight = 24.dp) }) { o ->
                        if (o.net.isEmpty()) Text("No interfaces reported", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                        StatRow {
                            StatTile("Down", rate(o.net.sumOf { it.rxPerSec }), Modifier.weight(1f), icon = Icons.AutoMirrored.Rounded.TrendingUp)
                            StatTile("Up", rate(o.net.sumOf { it.txPerSec }), Modifier.weight(1f))
                        }
                        o.net.take(6).forEach { n ->
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(n.iface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("↓ ${rate(n.rxPerSec)}   ↑ ${rate(n.txPerSec)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            val temps = loader.value?.temps.orEmpty()
            if (temps.isNotEmpty()) item("temps") {
                SectionCard("Temperatures", icon = Icons.Rounded.Thermostat) {
                    temps.forEach { t ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(t.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                String.format(Locale.US, "%.0f °C", t.celsius),
                                style = MaterialTheme.typography.labelLarge,
                                color = when {
                                    t.celsius >= 85 -> MaterialTheme.colorScheme.error
                                    t.celsius >= 70 -> warnColor()
                                    else -> MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
