package app.bosunarr.feature.usenet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import app.bosunarr.app
import app.bosunarr.data.ServiceConfig
import app.bosunarr.feature.services.ServiceFrame
import app.bosunarr.feature.services.TabbedBody
import app.bosunarr.feature.services.warnColor
import app.bosunarr.ui.common.SectionTitle
import app.bosunarr.ui.common.bytes
import app.bosunarr.ui.common.eta
import app.bosunarr.ui.common.speed
import kotlinx.coroutines.delay

/** SABnzbd's own screen (opened from ServiceScreen dispatch). */
@Composable
fun SabnzbdServiceScreen(service: ServiceConfig, nav: NavController) = UsenetServiceScreen(service, nav)

/** NZBGet's own screen (opened from ServiceScreen dispatch). */
@Composable
fun NzbgetServiceScreen(service: ServiceConfig, nav: NavController) = UsenetServiceScreen(service, nav)

private val PAUSE_FOR = listOf(15 to "For 15 minutes", 60 to "For 1 hour", 180 to "For 3 hours", 720 to "For 12 hours")

/** Header with speed / time left / size left / disk free, pause-resume and speed limit; Queue and History tabs. */
@Composable
private fun UsenetServiceScreen(service: ServiceConfig, nav: NavController) {
    val services by app.services.collectAsState()
    // The latest saved config (it may have been edited since the route was opened).
    val s = services.firstOrNull { it.id == service.id } ?: service
    val loader = rememberUsenetQueue(listOf(s))
    val controller = rememberUsenetController { loader.refresh() }
    val listState = rememberLazyListState()
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var showLimit by rememberSaveable { mutableStateOf(false) }
    // Paused/resumed position shown while the change is in flight, until a poll confirms it.
    var pausedOverride by remember { mutableStateOf<Boolean?>(null) }

    val snap = loader.value?.clients?.firstOrNull()
    LaunchedEffect(snap?.server?.paused, pausedOverride) {
        val want = pausedOverride ?: return@LaunchedEffect
        if (snap?.server?.paused == want) pausedOverride = null
        else { delay(10_000); pausedOverride = null }
    }

    fun setPaused(paused: Boolean, minutes: Int? = null) {
        pausedOverride = paused
        val label = when {
            !paused -> "Downloads resumed"
            minutes != null -> "Paused — resumes in ${eta(minutes * 60L)}"
            else -> "Downloads paused"
        }
        controller.run("pause-all:${s.id}", label) {
            try {
                when {
                    !paused -> UsenetApi.resumeAll(s)
                    minutes != null -> UsenetApi.pauseFor(s, minutes)
                    else -> UsenetApi.pauseAll(s)
                }
            } catch (e: Exception) {
                pausedOverride = null
                throw e
            }
        }
    }

    ServiceFrame(
        s, nav,
        subtitle = listOfNotNull(s.serviceType?.label, snap?.server?.version?.let { "v$it" }).joinToString(" · "),
        floatingActionButton = {
            val atTop by remember { derivedStateOf { listState.firstVisibleItemIndex < 3 } }
            if (!controller.selecting) ExtendedFloatingActionButton(
                text = { Text("Add NZB") },
                icon = { Icon(Icons.Rounded.Add, null) },
                onClick = { showAdd = true },
                expanded = atTop,
            )
        },
    ) { p ->
        TabbedBody(listOf("Queue", "History"), Modifier.padding(p)) { tab ->
            when (tab) {
                0 -> UsenetQueueContent(loader, controller, listState = listState) { data ->
                    val c = data.clients.firstOrNull()
                    if (c != null) {
                        item("status") {
                            StatusCard(
                                server = c.server,
                                paused = pausedOverride ?: c.server.paused,
                                onPause = { min -> setPaused(true, min) },
                                onResume = { setPaused(false) },
                                onLimit = { showLimit = true },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        item("queue-title") { SectionTitle(if (c.items.isEmpty()) "Queue" else "Queue · ${c.items.size}") }
                    }
                }
                else -> UsenetHistoryList(listOf(s), showClient = false)
            }
        }
    }

    if (showAdd) {
        AddNzbSheet(
            clients = listOf(s),
            onDismiss = { showAdd = false },
            onAdded = { msg -> controller.toast(msg); loader.refresh() },
            preferredClient = s.id,
        )
    }
    if (showLimit) {
        SpeedLimitDialog(
            current = snap?.server?.limit ?: 0,
            onSet = { bps ->
                controller.run("limit:${s.id}", if (bps > 0) "Speed limited to ${speed(bps)}" else "Speed limit removed") { UsenetApi.setSpeedLimit(s, bps) }
            },
            onDismiss = { showLimit = false },
        )
    }
}

@Composable
private fun StatusCard(
    server: NzbServer,
    paused: Boolean,
    onPause: (minutes: Int?) -> Unit,
    onResume: () -> Unit,
    onLimit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surfaceContainer)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.ArrowDownward, null, tint = cs.primary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Download", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(speed(server.down), style = MaterialTheme.typography.headlineSmall.tabular(), fontWeight = FontWeight.Medium, maxLines = 1)
                    Text(
                        if (server.limit > 0) "Limit ${speed(server.limit)}" else "No limit",
                        style = MaterialTheme.typography.bodySmall.tabular(),
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text("Time left", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant, modifier = Modifier.height(18.dp))
                    Spacer(Modifier.height(2.dp))
                    Text(
                        when {
                            paused -> "Paused"
                            server.left <= 0 -> "—"
                            else -> server.timeLeft?.let { eta(it) } ?: "∞"
                        },
                        style = MaterialTheme.typography.headlineSmall.tabular(),
                        fontWeight = FontWeight.Medium,
                        color = if (paused) warnColor() else cs.onSurface,
                        maxLines = 1,
                    )
                    Text("${bytes(server.left)} left", style = MaterialTheme.typography.bodySmall.tabular(), color = cs.onSurfaceVariant, maxLines = 1)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 14.dp), color = cs.outlineVariant.copy(alpha = 0.6f))
            val stats = listOfNotNull(
                "Disk free" to (server.freeDisk?.let { bytes(it) } ?: "—"),
                server.today?.let { "Today" to bytes(it) },
            )
            Row(Modifier.fillMaxWidth()) {
                stats.forEach { (label, value) ->
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                        Text(value, style = MaterialTheme.typography.bodyMedium.tabular(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (stats.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (paused) {
                    FilledTonalButton(onClick = onResume, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Resume", maxLines = 1)
                    }
                } else {
                    var menu by remember { mutableStateOf(false) }
                    Box(Modifier.weight(1f)) {
                        OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.Pause, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Pause", maxLines = 1)
                            Icon(Icons.Rounded.ArrowDropDown, null, Modifier.size(18.dp))
                        }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Pause") }, onClick = { menu = false; onPause(null) })
                            PAUSE_FOR.forEach { (min, label) ->
                                DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; onPause(min) })
                            }
                        }
                    }
                }
                OutlinedButton(onClick = onLimit, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Speed, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Speed limit", maxLines = 1)
                }
            }
        }
    }
}
