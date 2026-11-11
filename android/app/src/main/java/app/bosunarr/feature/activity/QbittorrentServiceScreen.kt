package app.bosunarr.feature.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import app.bosunarr.app
import app.bosunarr.data.ServiceConfig
import app.bosunarr.feature.activity.qbit.Qbit
import app.bosunarr.feature.activity.qbit.QbitServer
import app.bosunarr.feature.activity.qbit.Torrent
import app.bosunarr.feature.activity.qbit.categoryLabel
import app.bosunarr.feature.library.horizontalFadingEdges
import app.bosunarr.nav.ServiceEditRoute
import app.bosunarr.net.userMessage
import app.bosunarr.ui.common.AppScaffold
import app.bosunarr.ui.common.SectionTitle
import app.bosunarr.ui.common.back
import app.bosunarr.ui.common.bytes
import app.bosunarr.ui.common.rememberLoader
import app.bosunarr.ui.common.speed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Marks "no category" in the category filter. */
private const val UNCATEGORIZED = "\u0000none"

/** qBittorrent's own screen (opened from ServiceScreen dispatch). */
@Composable
fun QbittorrentServiceScreen(service: ServiceConfig, nav: NavController) {
    val services by app.services.collectAsState()
    // The latest saved config (it may have been edited since the route was opened).
    val s = services.firstOrNull { it.id == service.id } ?: service
    val loader = rememberLoader("qbit:${service.id}", refreshMs = 2_500) {
        val snap = Qbit.snapshot(s)
        TorrentData(listOf(snap), listOfNotNull(snap.error?.let { ServiceError(snap.serviceName, it) }))
    }
    val controller = rememberTorrentController { loader.refresh() }
    val listState = rememberLazyListState()
    val torrentState = rememberTorrentListState("qbit:${service.id}")
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var showAdd by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Alt-speed switch position while the change is in flight.
    var altPending by remember { mutableStateOf<Boolean?>(null) }

    val data = loader.value
    val snap = data?.clients?.firstOrNull()

    var altToken by remember { mutableStateOf(0) }
    fun setAlt(enabled: Boolean) {
        if (altPending != null) return
        altPending = enabled
        val token = ++altToken
        scope.launch {
            try {
                Qbit.setAltSpeed(s, enabled)
                controller.show(if (enabled) "Alternative speed limits on" else "Alternative speed limits off")
                loader.refresh()
                delay(8_000) // give up waiting for the poll to confirm
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                controller.show("Couldn't switch speed limits — ${e.userMessage()}")
            } finally {
                if (altToken == token) altPending = null
            }
        }
    }
    // Keep the switch where the user put it until a poll reports the new mode.
    LaunchedEffect(snap?.server?.altSpeed) {
        if (altPending != null && snap?.server?.altSpeed == altPending) altPending = null
    }

    AppScaffold(
        title = s.name,
        subtitle = snap?.let { connectionLabel(it.server.connection) },
        onBack = { nav.back() },
        actions = {
            var open by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                DropdownMenu(open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(text = { Text("Edit service") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { open = false; nav.navigate(ServiceEditRoute(id = s.id)) })
                }
            }
        },
        floatingActionButton = {
            val atTop by remember { derivedStateOf { listState.firstVisibleItemIndex < 3 } }
            if (!torrentState.selecting) ExtendedFloatingActionButton(
                text = { Text("Add torrent") },
                icon = { Icon(Icons.Rounded.Add, null) },
                onClick = { showAdd = true },
                expanded = atTop,
            )
        },
    ) { p ->
        PolledContent(loader, Modifier.padding(p)) { d ->
            val c = d.clients.firstOrNull()
            val torrents = d.torrents
            val counts = remember(torrents) { torrents.groupingBy { it.category.ifEmpty { UNCATEGORIZED } }.eachCount() }
            val cats = remember(c?.categories, counts, category) {
                (c?.categories.orEmpty() + counts.keys.filter { it != UNCATEGORIZED } + listOfNotNull(category?.takeIf { it != UNCATEGORIZED }))
                    .distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
            }
            val catFilter: ((Torrent) -> Boolean)? = category?.let { cat -> { t -> if (cat == UNCATEGORIZED) t.category.isEmpty() else t.category == cat } }
            TorrentListBody(
                data = d,
                controller = controller,
                state = torrentState,
                listState = listState,
                showClient = false,
                error = errorText(d.errors) ?: loader.state.error,
                onAdd = { showAdd = true },
                onRetry = { loader.refresh() },
                filter = catFilter,
                onResetFilter = { category = null },
                header = {
                    if (c != null) {
                        item("transfer") { TransferCard(c.server, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
                        item("alt") {
                            AltSpeedCard(
                                on = altPending ?: c.server.altSpeed,
                                busy = altPending != null,
                                server = c.server,
                                onChange = ::setAlt,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                        item("bulk") {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(onClick = { controller.startAll(torrents) }, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Resume all", maxLines = 1)
                                }
                                OutlinedButton(onClick = { controller.stopAll(torrents) }, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Rounded.Pause, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Pause all", maxLines = 1)
                                }
                            }
                        }
                        if (cats.isNotEmpty() || counts.containsKey(UNCATEGORIZED)) {
                            item("cats-title") { SectionTitle("Categories") }
                            item("cats") {
                                val catScroll = rememberScrollState()
                                Row(
                                    Modifier.fillMaxWidth().horizontalFadingEdges(catScroll).horizontalScroll(catScroll).padding(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    CountChip("All", torrents.size, category == null) { category = null }
                                    cats.forEach { cat -> CountChip(categoryLabel(cat), counts[cat] ?: 0, category == cat) { category = if (category == cat) null else cat } }
                                    if (counts.containsKey(UNCATEGORIZED) || category == UNCATEGORIZED) {
                                        CountChip("Uncategorized", counts[UNCATEGORIZED] ?: 0, category == UNCATEGORIZED) { category = if (category == UNCATEGORIZED) null else UNCATEGORIZED }
                                    }
                                }
                            }
                        }
                        item("torrents-title") { SectionTitle("Torrents") }
                    }
                },
            )
        }
    }

    if (showAdd) {
        AddTorrentSheet(
            clients = listOf(s),
            initial = null,
            onDismiss = { showAdd = false },
            onAdded = { msg -> controller.show(msg); loader.refresh() },
            preferredClient = s.id,
        )
    }
}

private fun connectionLabel(c: String) = when (c) {
    "connected" -> "Connected"
    "firewalled" -> "Firewalled"
    "disconnected" -> "Disconnected"
    else -> null
}

@Composable
private fun CountChip(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(6.dp))
            Text(count.toString(), style = MaterialTheme.typography.labelLarge.tnum(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
    )
}

@Composable
private fun TransferCard(sv: QbitServer, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surfaceContainer)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth()) {
                BigSpeed(true, sv.down, sv.downLimit, Modifier.weight(1f))
                BigSpeed(false, sv.up, sv.upLimit, Modifier.weight(1f))
            }
            HorizontalDivider(Modifier.padding(vertical = 14.dp), color = cs.outlineVariant.copy(alpha = 0.6f))
            val stats = listOf(
                "Session" to "↓ ${bytes(sv.sessionDown)}  ↑ ${bytes(sv.sessionUp)}",
                "All-time" to "↓ ${bytes(sv.allTimeDown)}  ↑ ${bytes(sv.allTimeUp)}",
                "Ratio" to (sv.ratio?.let { ratio(it) } ?: "—"),
                "Free space" to (sv.freeSpace?.let { bytes(it) } ?: "—"),
                "DHT nodes" to sv.dhtNodes.toString(),
                "Peers" to sv.peers.toString(),
            )
            stats.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    pair.forEach { (label, value) ->
                        Column(Modifier.weight(1f)) {
                            Text(label, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                            Text(value, style = MaterialTheme.typography.bodyMedium.tnum(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                val (color, label) = when (sv.connection) {
                    "connected" -> cs.tertiary to "Connected"
                    "firewalled" -> Color(0xFFE0A030) to "Firewalled — incoming connections are blocked"
                    "disconnected" -> cs.error to "Disconnected"
                    else -> cs.outline to "Connection status unknown"
                }
                Box(Modifier.size(8.dp).background(color, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun BigSpeed(down: Boolean, bps: Long, limit: Long, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (down) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward, null, tint = if (down) cs.primary else cs.tertiary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(if (down) "Download" else "Upload", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
        }
        Spacer(Modifier.height(2.dp))
        Text(speed(bps), style = MaterialTheme.typography.headlineSmall.tnum(), fontWeight = FontWeight.Medium, maxLines = 1)
        Text(if (limit > 0) "Limit ${speed(limit)}" else "No limit", style = MaterialTheme.typography.bodySmall.tnum(), color = cs.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun AltSpeedCard(on: Boolean, busy: Boolean, server: QbitServer, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = if (on) cs.secondaryContainer else cs.surfaceContainer),
    ) {
        Row(
            Modifier.fillMaxWidth().toggleable(on, enabled = !busy, role = Role.Switch, onValueChange = onChange).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Speed, null, tint = if (on) cs.onSecondaryContainer else cs.onSurfaceVariant)
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text("Alternative speed limits", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (on) "On — ↓ ${if (server.downLimit > 0) speed(server.downLimit) else "∞"}  ↑ ${if (server.upLimit > 0) speed(server.upLimit) else "∞"}" else "Off",
                    style = MaterialTheme.typography.bodySmall.tnum(),
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(checked = on, onCheckedChange = null, enabled = !busy)
        }
    }
}
