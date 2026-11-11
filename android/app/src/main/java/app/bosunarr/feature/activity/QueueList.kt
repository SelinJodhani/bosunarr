package app.bosunarr.feature.activity

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.bosunarr.app
import app.bosunarr.feature.activity.queue.ArrQueue
import app.bosunarr.feature.activity.queue.QueueEntry
import app.bosunarr.feature.activity.queue.QueueHealth
import app.bosunarr.net.userMessage
import app.bosunarr.ui.common.LocalSnackbar
import app.bosunarr.ui.common.Poster
import app.bosunarr.ui.common.Tag
import app.bosunarr.ui.common.bytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Removes queue items optimistically (hidden at once, back with a snackbar if the arr refuses). */
@Stable
class QueueController internal constructor(private val snackbar: SnackbarHostState, private val onChanged: () -> Unit) {
    private val hidden = mutableStateMapOf<String, Long>()

    fun apply(list: List<QueueEntry>): List<QueueEntry> {
        if (hidden.isEmpty()) return list
        val now = System.currentTimeMillis()
        return list.filter { e -> hidden[e.key]?.let { now - it > 20_000 } ?: true }
    }

    fun prune(list: List<QueueEntry>) {
        if (hidden.isEmpty()) return
        val now = System.currentTimeMillis()
        val keys = list.mapTo(HashSet()) { it.key }
        hidden.entries.filter { (k, at) -> k !in keys || now - at > 20_000 }.map { it.key }.forEach { hidden.remove(it) }
    }

    fun remove(e: QueueEntry, removeFromClient: Boolean, blocklist: Boolean) {
        val s = app.service(e.serviceId) ?: return
        hidden[e.key] = System.currentTimeMillis()
        app.scope.launch(Dispatchers.Main) {
            val msg = try {
                ArrQueue.remove(s, e.queueId, removeFromClient, blocklist)
                "Removed ${e.title}" + if (blocklist) " and blocklisted the release" else ""
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                hidden.remove(e.key)
                "Couldn't remove ${e.title} — ${ex.userMessage()}"
            }
            onChanged()
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(msg)
        }
    }
}

@Composable
fun rememberQueueController(onChanged: () -> Unit): QueueController {
    val snackbar = LocalSnackbar.current
    val latest = rememberUpdatedState(onChanged)
    return remember(snackbar) { QueueController(snackbar) { latest.value() } }
}

fun LazyListScope.queueRows(
    list: List<QueueEntry>,
    expanded: Map<String, Boolean>,
    onToggleExpanded: (QueueEntry) -> Unit,
    onOpen: (QueueEntry) -> Unit,
    onRemove: (QueueEntry) -> Unit,
) {
    items(list, key = { it.key }, contentType = { "queue" }) { e ->
        QueueRow(
            e = e,
            expanded = expanded[e.key] == true,
            onToggleExpanded = { onToggleExpanded(e) },
            onOpen = { onOpen(e) },
            onRemove = { onRemove(e) },
            modifier = Modifier.animateItem(),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QueueRow(
    e: QueueEntry,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    val service = remember(e.serviceId) { app.service(e.serviceId) }
    val stateColor = when {
        e.health != QueueHealth.Ok -> healthColor(e.health)
        e.state in setOf("importing", "importPending") -> cs.secondary
        e.state in setOf("paused", "queued", "delay") -> cs.outline
        else -> cs.primary
    }
    Row(
        modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { if (e.libraryId != null) onOpen() else if (e.messages.isNotEmpty()) onToggleExpanded() },
                onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); menu = true },
                onLongClickLabel = "Actions",
            )
            .padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Poster(service, e.poster, width = 44.dp, title = e.title)
        Column(Modifier.weight(1f).padding(start = 12.dp).animateContentSize()) {
            Text(e.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (e.subtitle != null) Text(e.subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            SmoothProgress(e.progress, stateColor)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    meta(
                        e.stateLabel,
                        if (e.state == "downloading" && e.sizeLeft > 0) "${bytes(e.sizeLeft)} left" else bytes(e.size).takeIf { e.size > 0 },
                        e.timeLeft.takeIf { e.state == "downloading" && e.sizeLeft > 0 },
                    ),
                    style = MaterialTheme.typography.labelMedium.tnum(),
                    fontWeight = FontWeight.Medium,
                    color = stateColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            val details = meta(e.quality, e.client ?: e.protocol?.replaceFirstChar { it.uppercase() }, e.serviceName)
            if (details.isNotEmpty()) Text(details, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (e.messages.isNotEmpty()) Messages(e, expanded, onToggleExpanded)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Actions for ${e.title}") }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                if (e.libraryId != null) DropdownMenuItem(
                    text = { Text(if (e.isEpisode) "Open series" else "Open movie") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null) },
                    onClick = { menu = false; onOpen() },
                )
                if (e.messages.isNotEmpty()) DropdownMenuItem(
                    text = { Text(if (expanded) "Hide messages" else "Show messages") },
                    leadingIcon = { Icon(Icons.Rounded.Info, null) },
                    onClick = { menu = false; onToggleExpanded() },
                )
                DropdownMenuItem(
                    text = { Text("Remove from queue…") },
                    leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                    onClick = { menu = false; onRemove() },
                )
            }
        }
    }
}

@Composable
private fun Messages(e: QueueEntry, expanded: Boolean, onToggle: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val color = if (e.health == QueueHealth.Ok) cs.onSurfaceVariant else healthColor(e.health)
    val icon = when (e.health) {
        QueueHealth.Error -> Icons.Rounded.Error
        QueueHealth.Warning -> Icons.Rounded.Warning
        QueueHealth.Ok -> Icons.Rounded.Info
    }
    Column(
        Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = if (expanded) "Hide messages" else "Show messages", onClick = onToggle)
            .padding(vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(icon, null, tint = color, modifier = Modifier.padding(top = 1.dp, end = 6.dp).size(16.dp))
            Text(
                e.messages.first(),
                style = MaterialTheme.typography.bodySmall,
                color = color,
                maxLines = if (expanded) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (e.messages.size > 1 || !expanded) Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
        if (expanded) e.messages.drop(1).forEach { m ->
            Text(m, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.padding(start = 22.dp, top = 4.dp))
        }
    }
}

@Composable
fun RemoveQueueDialog(e: QueueEntry, onConfirm: (removeFromClient: Boolean, blocklist: Boolean) -> Unit, onDismiss: () -> Unit) {
    var fromClient by rememberSaveable(e.key) { mutableStateOf(true) }
    var blocklist by rememberSaveable(e.key) { mutableStateOf(e.health == QueueHealth.Error) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove from queue?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(meta(e.title, e.subtitle.takeIf { e.isEpisode }), maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                CheckRow(fromClient, { fromClient = it }, "Remove from download client", if (fromClient) "Deletes the download and its files" else "Leaves the download in ${e.client ?: "the client"}")
                CheckRow(blocklist, { blocklist = it }, "Blocklist release", "${e.serviceName} won't grab this release again")
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(fromClient, blocklist); onDismiss() },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
            ) { Text("Remove") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Expanded-message state for queue rows, kept across polls. */
@Composable
fun rememberExpandedState() = remember { mutableStateMapOf<String, Boolean>() }
