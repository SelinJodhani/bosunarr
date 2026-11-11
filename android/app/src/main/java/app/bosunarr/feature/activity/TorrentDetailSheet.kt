package app.bosunarr.feature.activity

import android.content.ClipData
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.bosunarr.app
import app.bosunarr.feature.activity.qbit.FILE_PRIORITIES
import app.bosunarr.feature.activity.qbit.Qbit
import app.bosunarr.feature.activity.qbit.Torrent
import app.bosunarr.feature.activity.qbit.TorrentDetails
import app.bosunarr.feature.activity.qbit.TorrentFile
import app.bosunarr.feature.activity.qbit.TorrentGroup
import app.bosunarr.feature.activity.qbit.TorrentTracker
import app.bosunarr.feature.activity.qbit.categoryLabel
import app.bosunarr.feature.activity.qbit.filePriorityLabel
import app.bosunarr.net.userMessage
import app.bosunarr.ui.common.LoadingBox
import app.bosunarr.ui.common.Tag
import app.bosunarr.ui.common.bytes
import app.bosunarr.ui.common.dateTime
import app.bosunarr.ui.common.eta
import app.bosunarr.ui.common.speed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

private enum class DetailTab(val label: String) { Info("Info"), Trackers("Trackers"), Files("Files") }

/**
 * Details of one torrent. [torrent] comes from the live list, so its numbers keep moving while the sheet is open;
 * properties, trackers and files are polled here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TorrentDetailSheet(
    torrent: Torrent,
    categories: List<String>,
    controller: TorrentController,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    var tab by rememberSaveable { mutableStateOf(DetailTab.Info) }
    var details by remember(torrent.key) { mutableStateOf<TorrentDetails?>(null) }
    var detailError by remember(torrent.key) { mutableStateOf<String?>(null) }
    var refreshTick by remember { mutableStateOf(0) }
    var confirmDelete by remember { mutableStateOf(false) }
    var pickCategory by remember { mutableStateOf(false) }
    // File priorities set here but not yet confirmed by a refresh.
    val prioOverrides = remember(torrent.key) { mutableStateMapOf<Int, Int>() }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(torrent.key, refreshTick) {
        val s = app.service(torrent.serviceId) ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                try {
                    val d = Qbit.details(s, torrent.hash)
                    details = d
                    detailError = null
                    d.files.forEach { f -> if (prioOverrides[f.index] == f.priority) prioOverrides.remove(f.index) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    detailError = e.userMessage()
                }
                delay(3_000)
            }
        }
    }

    fun closeThen(after: () -> Unit) {
        scope.launch { sheet.hide() }.invokeOnCompletion { onDismiss(); after() }
    }

    fun copy(label: String, text: String) {
        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, text))) }
        // Android 13+ shows its own clipboard confirmation.
        if (android.os.Build.VERSION.SDK_INT < 33) controller.show("$label copied")
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        contentWindowInsets = { WindowInsets(0) },
    ) {
        val nav = WindowInsets.navigationBars.asPaddingValues()
        LazyColumn(contentPadding = PaddingValues(bottom = nav.calculateBottomPadding() + 16.dp)) {
            item("header") { Header(torrent) }
            item("actions") {
                Actions(
                    torrent,
                    busy = controller.isBusy(torrent),
                    onToggle = { if (torrent.isStopped || torrent.group == TorrentGroup.Errored) controller.start(listOf(torrent)) else controller.stop(listOf(torrent)) },
                    onRecheck = { controller.recheck(listOf(torrent)) },
                    onReannounce = { controller.reannounce(listOf(torrent)) },
                    onCategory = { pickCategory = true },
                    onDelete = { confirmDelete = true },
                )
            }
            item("tabs") {
                SecondaryTabRow(selectedTabIndex = tab.ordinal, containerColor = Color.Transparent) {
                    DetailTab.entries.forEach { t ->
                        val n = when (t) {
                            DetailTab.Info -> null
                            DetailTab.Trackers -> details?.trackers?.count { !it.isPseudo }
                            DetailTab.Files -> details?.files?.size
                        }
                        Tab(selected = tab == t, onClick = { tab = t }, text = { Text(if (n != null) "${t.label} ($n)" else t.label, maxLines = 1) })
                    }
                }
            }
            val d = details
            if (d == null) {
                item("loading") {
                    if (detailError != null) Text(detailError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(24.dp))
                    else LoadingBox()
                }
            } else when (tab) {
                DetailTab.Info -> {
                    if (detailError != null) item("err") { DetailError(detailError!!) }
                    infoItems(torrent, d, ::copy)
                }
                DetailTab.Trackers -> {
                    if (d.trackers.isEmpty()) item("none") { EmptyNote("No trackers") }
                    itemsIndexed(d.trackers, key = { i, tr -> "tr:$i:${tr.url}" }) { _, tr -> TrackerRow(tr, onCopy = { copy("Tracker URL", tr.url) }) }
                }
                DetailTab.Files -> {
                    if (d.files.isEmpty()) item("none") { EmptyNote(if (torrent.state.contains("meta", true)) "Waiting for metadata" else "No files") }
                    items(d.files, key = { "f:" + it.index }) { f ->
                        val prio = prioOverrides[f.index] ?: f.priority
                        FileRow(f, prio) { p ->
                            val s = app.service(torrent.serviceId) ?: return@FileRow
                            prioOverrides[f.index] = p
                            scope.launch {
                                try {
                                    Qbit.setFilePriority(s, torrent.hash, listOf(f.index), p)
                                    refreshTick++
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    prioOverrides.remove(f.index)
                                    controller.show("Couldn't change priority — ${e.userMessage()}")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        DeleteTorrentsDialog(
            listOf(torrent),
            // Slide the sheet away first, then let the row disappear from the list.
            onConfirm = { files -> val t = torrent; closeThen { controller.delete(listOf(t), files) } },
            onDismiss = { confirmDelete = false },
        )
    }
    if (pickCategory) {
        CategoryDialog(categories, torrent.category, onPick = { controller.setCategory(listOf(torrent), it) }, onDismiss = { pickCategory = false })
    }
}

@Composable
private fun Header(t: Torrent) {
    val color = groupColor(t.group)
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 8.dp)) {
        Text(t.name, style = MaterialTheme.typography.titleMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Tag(t.stateLabel, container = color.copy(alpha = 0.16f), content = if (t.group == TorrentGroup.Paused || t.group == TorrentGroup.Completed) MaterialTheme.colorScheme.onSurfaceVariant else color)
            if (t.category.isNotEmpty()) Tag(categoryLabel(t.category))
            Text(t.serviceName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(14.dp))
        SmoothProgress(t.progress.toFloat(), color, height = 6)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                meta(pct(t.progress), if (t.isDone) bytes(t.size) else "${bytes((t.size * t.progress).toLong())} of ${bytes(t.size)}", if (!t.isDone && !t.isStopped && t.eta in 1 until 8_640_000) "ETA ${eta(t.eta)}" else null),
                style = MaterialTheme.typography.bodyMedium.tnum(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            SpeedLabel(true, t.dlspeed, style = MaterialTheme.typography.labelLarge)
            SpeedLabel(false, t.upspeed, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun Actions(
    t: Torrent,
    busy: Boolean,
    onToggle: () -> Unit,
    onRecheck: () -> Unit,
    onReannounce: () -> Unit,
    onCategory: () -> Unit,
    onDelete: () -> Unit,
) {
    val resume = t.isStopped || t.group == TorrentGroup.Errored
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        ActionButton(
            if (resume) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
            when {
                resume && t.isDone -> "Seed"
                resume -> "Resume"
                t.isDone -> "Stop"
                else -> "Pause"
            },
            enabled = !busy,
            onClick = onToggle,
        )
        ActionButton(Icons.Rounded.Refresh, "Recheck", onClick = onRecheck)
        ActionButton(Icons.Rounded.Campaign, "Reannounce", onClick = onReannounce, enabled = !t.isStopped)
        ActionButton(Icons.AutoMirrored.Rounded.Label, "Category", onClick = onCategory)
        ActionButton(Icons.Rounded.Delete, "Delete", onClick = onDelete, destructive = true)
    }
}

@Composable
private fun ActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true, destructive: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(min = 64.dp)) {
        FilledTonalIconButton(
            onClick = onClick,
            enabled = enabled,
            shape = CircleShape,
            modifier = Modifier.size(52.dp),
            colors = if (destructive) IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)
            else IconButtonDefaults.filledTonalIconButtonColors(),
        ) { Icon(icon, contentDescription = null) }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, textAlign = TextAlign.Center)
    }
}

@Composable
private fun DetailError(message: String) {
    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
}

@Composable
private fun EmptyNote(text: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun epoch(s: Long?): String? = if (s == null || s <= 0 || s == 4294967295L) null else dateTime(Instant.ofEpochSecond(s).toString())

private fun androidx.compose.foundation.lazy.LazyListScope.infoItems(t: Torrent, d: TorrentDetails, copy: (String, String) -> Unit) {
    val p = d.properties
    val rows = buildList<Triple<String, String, Boolean>> {
        fun row(label: String, value: String?, copyable: Boolean = false) { if (!value.isNullOrBlank()) add(Triple(label, value, copyable)) }
        row("Size", bytes(t.size))
        row("Downloaded", bytes(p?.totalDownloaded ?: t.downloaded) + (p?.totalWasted?.takeIf { it > 0 }?.let { " (${bytes(it)} wasted)" } ?: ""))
        row("Uploaded", bytes(p?.totalUploaded ?: t.uploaded))
        row("Ratio", ratio(p?.shareRatio ?: t.ratio))
        row("Seeds", "${t.seeds} connected (${t.seedsTotal} in swarm)")
        row("Peers", "${t.peers} connected (${t.peersTotal} in swarm)")
        if (t.availability >= 0 && !t.isDone) row("Availability", String.format(java.util.Locale.US, "%.2f", t.availability))
        p?.let { row("Average speed", "↓ ${speed(it.dlSpeedAvg)}  ↑ ${speed(it.upSpeedAvg)}") }
        p?.connections?.takeIf { it > 0 }?.let { row("Connections", if (p.connectionsLimit > 0) "$it of ${p.connectionsLimit}" else "$it") }
        row("Added", epoch(p?.additionDate ?: t.addedOn))
        row("Completed", epoch(p?.completionDate ?: t.completedOn))
        row("Time active", if ((p?.timeElapsed ?: t.timeActive) > 0) eta(p?.timeElapsed ?: t.timeActive) else null)
        row("Seeding time", if ((p?.seedingTime ?: t.seedingTime) > 0) eta(p?.seedingTime ?: t.seedingTime) else null)
        row("Last seen complete", epoch(p?.lastSeen))
        if (p != null && p.piecesNum > 0) row("Pieces", "${p.piecesHave} of ${p.piecesNum} × ${bytes(p.pieceSize)}")
        if (p != null && p.reannounce > 0 && !t.isStopped) row("Next announce", eta(p.reannounce))
        row("Save path", p?.savePath?.takeIf { it.isNotBlank() } ?: t.savePath, true)
        row("Tags", t.tags.joinToString(", "))
        row("Created", epoch(p?.creationDate))
        row("Created by", p?.createdBy)
        row("Comment", p?.comment, true)
        row("Info hash", t.hash, true)
        row("Magnet link", t.magnetUri.takeIf { it.isNotBlank() }?.let { "Tap to copy" }, true)
    }
    items(rows, key = { "info:" + it.first }) { (label, value, copyable) ->
        val copyValue = if (label == "Magnet link") t.magnetUri else value
        InfoRow(label, value, if (copyable) ({ copy(label, copyValue) }) else null)
    }
}

@Composable
private fun InfoRow(label: String, value: String, onCopy: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onCopy != null) Modifier.clickable(onClickLabel = "Copy", onClick = onCopy) else Modifier)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.38f))
        Text(value, style = MaterialTheme.typography.bodyMedium.tnum(), modifier = Modifier.weight(0.62f), maxLines = 6, overflow = TextOverflow.Ellipsis)
        if (onCopy != null) Icon(Icons.Rounded.ContentCopy, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp).size(16.dp))
    }
}

@Composable
private fun TrackerRow(tr: TorrentTracker, onCopy: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val statusColor = when (tr.status) {
        2 -> cs.tertiary
        4 -> cs.error
        3 -> cs.primary
        else -> cs.onSurfaceVariant
    }
    Column(Modifier.fillMaxWidth().clickable(onClickLabel = "Copy URL", onClick = onCopy).padding(horizontal = 24.dp, vertical = 10.dp)) {
        Text(
            if (tr.isPseudo) tr.url.removePrefix("** [").removeSuffix("] **") else tr.url,
            style = MaterialTheme.typography.bodyMedium,
            color = if (tr.isPseudo) cs.onSurfaceVariant else cs.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            meta(
                if (tr.isPseudo && tr.status == 0) null else tr.statusLabel,
                tr.seeds.takeIf { it >= 0 }?.let { "$it seeds" },
                tr.leeches.takeIf { it >= 0 }?.let { "$it leechers" },
                tr.peers.takeIf { it >= 0 }?.let { "$it peers" },
                tr.downloaded.takeIf { it >= 0 }?.let { "$it downloads" },
            ),
            style = MaterialTheme.typography.labelMedium.tnum(),
            color = statusColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (tr.msg.isNotBlank()) Text(tr.msg, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
    HorizontalDivider(Modifier.padding(horizontal = 24.dp), color = cs.outlineVariant.copy(alpha = 0.5f))
}

@Composable
private fun FileRow(f: TorrentFile, priority: Int, onPriority: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    val skipped = priority == 0
    val slash = f.name.lastIndexOf('/')
    val file = if (slash >= 0) f.name.substring(slash + 1) else f.name
    val dir = if (slash >= 0) f.name.substring(0, slash) else null
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 24.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(file, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = if (skipped) cs.onSurfaceVariant else cs.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (dir != null) Text(dir, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            SmoothProgress(f.progress.toFloat(), if (skipped) cs.outline else if (f.progress >= 1) cs.tertiary else cs.primary, height = 3)
            Spacer(Modifier.height(4.dp))
            Text(meta(pct(f.progress), bytes(f.size)), style = MaterialTheme.typography.labelSmall.tnum(), color = cs.onSurfaceVariant)
        }
        Box {
            TextButton(onClick = { menu = true }) { Text(filePriorityLabel(priority), color = if (skipped) cs.onSurfaceVariant else cs.primary) }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                FILE_PRIORITIES.forEach { (value, label) ->
                    DropdownMenuItem(
                        text = { Text(if (value == 0) "Don't download" else label) },
                        onClick = { menu = false; if (value != priority) onPriority(value) },
                        trailingIcon = { if (value == priority) Icon(Icons.Rounded.Check, null, tint = cs.primary) },
                    )
                }
            }
        }
    }
}
