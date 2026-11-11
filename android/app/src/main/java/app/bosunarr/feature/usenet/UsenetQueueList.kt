package app.bosunarr.feature.usenet

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.VerticalAlignTop
import androidx.compose.material.icons.rounded.LowPriority
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.bosunarr.app
import app.bosunarr.data.ServiceConfig
import app.bosunarr.feature.services.InfoLine
import app.bosunarr.feature.services.InlineError
import app.bosunarr.feature.services.warnColor
import app.bosunarr.net.ServiceException
import app.bosunarr.net.userMessage
import app.bosunarr.ui.common.ErrorBox
import app.bosunarr.ui.common.Loader
import app.bosunarr.ui.common.LoadingBox
import app.bosunarr.ui.common.MessageBox
import app.bosunarr.ui.common.Tag
import app.bosunarr.ui.common.bytes
import app.bosunarr.ui.common.eta
import app.bosunarr.ui.common.expandTopBarFirst
import app.bosunarr.ui.common.rememberLoader
import java.util.Locale

/** Queues of one or more Usenet clients, plus per-client errors (one client failing doesn't hide the others). */
@Immutable
data class UsenetQueueData(val clients: List<UsenetSnapshot>, val errors: List<String>) {
    val items: List<NzbItem> = clients.flatMap { it.items }
}

/** Load every Usenet client in [services] at once. Throws only when all of them failed. */
suspend fun loadUsenetQueue(services: List<ServiceConfig>): UsenetQueueData {
    val r = UsenetApi.queues(services)
    val ok = r.mapNotNull { it.second.getOrNull() }
    if (r.isNotEmpty() && ok.isEmpty()) throw r.first().second.exceptionOrNull() ?: ServiceException("Usenet clients are unreachable")
    val errors = r.mapNotNull { (s, x) -> x.exceptionOrNull()?.let { "${s.name}: ${it.userMessage()}" } } +
        ok.mapNotNull { snap -> snap.error?.let { "${snap.serviceName}: $it" } }
    return UsenetQueueData(ok, errors)
}

/** Polling loader for the queues of [services] (every 2.5 s while visible). */
@Composable
fun rememberUsenetQueue(services: List<ServiceConfig>, refreshMs: Long = 2_500): Loader<UsenetQueueData> {
    val ids = services.filter { isUsenet(it.serviceType) }.joinToString(",") { it.id }
    return rememberLoader("usenet:queue:$ids", refreshMs) { loadUsenetQueue(services) }
}

/**
 * Self-contained Usenet queue for every client in [services]: polling, pull to refresh, optimistic actions, item
 * sheet, multi-select with a batch bar. Drop it into a tab as is (the Activity "Usenet" tab).
 */
@Composable
fun UsenetQueueList(
    services: List<ServiceConfig>,
    modifier: Modifier = Modifier,
    showClient: Boolean = services.count { isUsenet(it.serviceType) } > 1,
    contentPadding: PaddingValues = PaddingValues(bottom = 96.dp),
) {
    val loader = rememberUsenetQueue(services)
    val controller = rememberUsenetController { loader.refresh() }
    UsenetQueueContent(loader, controller, modifier, showClient = showClient, contentPadding = contentPadding)
}

/**
 * The queue body with a caller-owned [loader] and [controller], so a screen can put its own [header] items (speed
 * card, buttons) above the jobs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsenetQueueContent(
    loader: Loader<UsenetQueueData>,
    controller: UsenetController,
    modifier: Modifier = Modifier,
    showClient: Boolean = false,
    listState: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(bottom = 96.dp),
    emptyHint: String = "Nothing is downloading. Grabs from Sonarr and Radarr, and NZBs you add, show up here.",
    header: LazyListScope.(UsenetQueueData) -> Unit = {},
) {
    val st = loader.state
    var open by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(st.value) { st.value?.let { controller.prune(it.items) } }
    Box(modifier.fillMaxSize()) {
        PullToRefreshBox(isRefreshing = st.userRefreshing && st.value != null, onRefresh = { loader.pullRefresh() }, modifier = Modifier.fillMaxSize()) {
            val data = st.value
            when {
                data != null -> {
                    val items = controller.apply(data.items)
                    LazyColumn(Modifier.fillMaxSize().expandTopBarFirst(), state = listState, contentPadding = contentPadding) {
                        header(data)
                        (listOfNotNull(st.error) + data.errors).distinct().forEach { e -> item("err:$e") { InlineError(e, compact = true) } }
                        if (items.isEmpty()) item("empty") { MessageBox(Icons.Rounded.Inbox, "Queue is empty", hint = emptyHint) }
                        usenetQueueRows(items, controller, showClient) { open = it.key }
                    }
                }
                st.error != null -> LazyColumn(Modifier.fillMaxSize()) { item { ErrorBox(st.error, onRetry = { loader.refresh() }) } }
                else -> LoadingBox()
            }
        }
        val all = st.value?.items?.let { controller.apply(it) }.orEmpty()
        UsenetSelectionBar(controller, all, Modifier.align(Alignment.BottomCenter).padding(16.dp).navigationBarsPadding())
    }
    val item = open?.let { k -> st.value?.items?.firstOrNull { it.key == k } }
    if (item != null) NzbItemSheet(item, controller, onDismiss = { open = null })
}

/** Job rows for a LazyColumn. Keys are `serviceId:id`, so polling never resets scroll. */
fun LazyListScope.usenetQueueRows(
    list: List<NzbItem>,
    controller: UsenetController,
    showClient: Boolean,
    onOpen: (NzbItem) -> Unit,
) {
    items(list, key = { it.key }, contentType = { "nzb" }) { t ->
        UsenetQueueRow(
            item = t,
            selected = t.key in controller.selection,
            selecting = controller.selecting,
            showClient = showClient,
            onClick = { if (controller.selecting) controller.toggle(t) else onOpen(t) },
            onLongClick = { controller.toggle(t) },
            modifier = Modifier.animateItem(),
        )
    }
}

internal fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")

private fun pct(p: Float): String = String.format(Locale.US, "%.1f%%", kotlin.math.floor(p * 1000.0) / 10)

@Composable
internal fun stateColor(s: NzbState): Color = when (s) {
    NzbState.Downloading -> MaterialTheme.colorScheme.primary
    NzbState.Queued -> MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
    NzbState.Paused -> MaterialTheme.colorScheme.outline
    NzbState.Fetching -> MaterialTheme.colorScheme.tertiary
    NzbState.Processing -> MaterialTheme.colorScheme.secondary
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UsenetQueueRow(
    item: NzbItem,
    selected: Boolean,
    selecting: Boolean,
    showClient: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val cs = MaterialTheme.colorScheme
    val color = stateColor(item.state)
    Row(
        modifier
            .fillMaxWidth()
            .background(if (selected) cs.secondaryContainer.copy(alpha = 0.6f) else cs.surface)
            .combinedClickable(
                onClick = onClick,
                onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onLongClick() },
                onLongClickLabel = "Select",
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            Icon(
                if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                contentDescription = if (selected) "Selected" else "Not selected",
                tint = if (selected) cs.primary else cs.onSurfaceVariant,
                modifier = Modifier.padding(end = 12.dp).size(22.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(item.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (item.priority != NzbPriority.Normal) {
                    Spacer(Modifier.width(8.dp))
                    PriorityTag(item.priority)
                }
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { item.progress },
                color = color,
                trackColor = cs.surfaceContainerHighest,
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                drawStopIndicator = {},
            )
            Spacer(Modifier.height(6.dp))
            Text(
                listOfNotNull(
                    item.stateLabel,
                    if (item.state == NzbState.Processing) null else pct(item.progress),
                    item.eta?.takeIf { item.state == NzbState.Downloading || item.state == NzbState.Queued }?.let { "ETA ${eta(it)}" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium.tabular(),
                fontWeight = FontWeight.Medium,
                color = if (item.state == NzbState.Paused) cs.onSurfaceVariant else color.copy(alpha = 1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                listOfNotNull(
                    if (item.left <= 0 || item.state == NzbState.Processing) bytes(item.size) else "${bytes((item.size - item.left).coerceAtLeast(0))} of ${bytes(item.size)}",
                    item.category.takeIf { it.isNotEmpty() },
                    if (showClient) item.serviceName else null,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall.tabular(),
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.labels.isNotEmpty()) {
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item.labels.take(3).forEach { l ->
                        Tag(l.lowercase().replaceFirstChar { it.uppercase() }, container = warnColor().copy(alpha = 0.16f), content = warnColor())
                    }
                }
            }
        }
    }
}

@Composable
private fun PriorityTag(p: NzbPriority) {
    val cs = MaterialTheme.colorScheme
    val high = p == NzbPriority.Force || p == NzbPriority.VeryHigh || p == NzbPriority.High
    Tag(p.label, container = if (high) cs.tertiaryContainer else cs.surfaceContainerHighest, content = if (high) cs.onTertiaryContainer else cs.onSurfaceVariant)
}

/** Floating batch-action bar shown while jobs are selected (Back clears the selection). */
@Composable
fun UsenetSelectionBar(controller: UsenetController, all: List<NzbItem>, modifier: Modifier = Modifier) {
    BackHandler(enabled = controller.selecting) { controller.clearSelection() }
    AnimatedVisibility(
        visible = controller.selecting,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier,
    ) {
        val selected = remember(all, controller.selection) { all.filter { it.key in controller.selection } }
        var menu by remember { mutableStateOf(false) }
        var confirmDelete by remember { mutableStateOf(false) }
        var pickPriority by remember { mutableStateOf(false) }
        var pickCategory by remember { mutableStateOf(false) }
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(28.dp),
            shadowElevation = 6.dp,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                IconButton(onClick = { controller.clearSelection() }) { Icon(Icons.Rounded.Close, "Clear selection") }
                Text("${selected.size} selected", style = MaterialTheme.typography.titleSmall.tabular(), modifier = Modifier.weight(1f), maxLines = 1)
                val pausable = selected.filter { it.state != NzbState.Paused && it.state != NzbState.Processing }
                val resumable = selected.filter { it.state == NzbState.Paused }
                IconButton(onClick = { controller.pause(pausable); controller.clearSelection() }, enabled = pausable.isNotEmpty()) { Icon(Icons.Rounded.Pause, "Pause selected") }
                IconButton(onClick = { controller.resume(resumable); controller.clearSelection() }, enabled = resumable.isNotEmpty()) { Icon(Icons.Rounded.PlayArrow, "Resume selected") }
                IconButton(onClick = { confirmDelete = true }, enabled = selected.isNotEmpty()) { Icon(Icons.Rounded.Delete, "Delete selected") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More actions") }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Select all") }, onClick = { controller.selection = all.map { it.key }.toSet(); menu = false })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Move to top") }, onClick = { controller.moveTop(selected); controller.clearSelection(); menu = false })
                        // Priorities and categories belong to one client, so only offer these when the selection is on one.
                        if (selected.map { it.serviceId }.distinct().size == 1) {
                            DropdownMenuItem(text = { Text("Set priority…") }, onClick = { pickPriority = true; menu = false })
                            DropdownMenuItem(text = { Text("Set category…") }, onClick = { pickCategory = true; menu = false })
                        }
                    }
                }
            }
        }
        if (confirmDelete && selected.isNotEmpty()) {
            DeleteJobsDialog(selected, onConfirm = { purge -> controller.delete(selected, purge); controller.clearSelection() }, onDismiss = { confirmDelete = false })
        }
        if (pickPriority && selected.isNotEmpty()) {
            PriorityDialog(
                selected.first().type, selected.map { it.priority }.distinct().singleOrNull(),
                onPick = { p -> controller.setPriority(selected, p); controller.clearSelection() },
                onDismiss = { pickPriority = false },
            )
        }
        val svc = selected.firstOrNull()?.let { app.service(it.serviceId) }
        if (pickCategory && svc != null) {
            UsenetCategoryDialog(
                svc, selected.map { it.category }.distinct().singleOrNull(),
                onPick = { c -> controller.setCategory(selected, c); controller.clearSelection() },
                onDismiss = { pickCategory = false },
            )
        }
    }
}

/** Details and actions for one queued job. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NzbItemSheet(item: NzbItem, controller: UsenetController, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var pickPriority by rememberSaveable { mutableStateOf(false) }
    var pickCategory by rememberSaveable { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Text(item.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp), maxLines = 4, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { item.progress },
                color = stateColor(item.state),
                trackColor = cs.surfaceContainerHighest,
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                drawStopIndicator = {},
            )
            Spacer(Modifier.height(8.dp))
            InfoLine("Status", listOfNotNull(item.stateLabel, if (item.state == NzbState.Processing) null else pct(item.progress)).joinToString(" · "))
            InfoLine("Size", if (item.left > 0) "${bytes(item.left)} left of ${bytes(item.size)}" else bytes(item.size))
            if (item.eta != null && item.state != NzbState.Paused) InfoLine("Time left", eta(item.eta))
            InfoLine("Category", item.category.ifEmpty { "None" })
            InfoLine("Priority", item.priority.label)
            InfoLine("Position", "#${item.index + 1} in queue")
            InfoLine("Client", item.serviceName)
            if (item.labels.isNotEmpty()) InfoLine("Labels", item.labels.joinToString(", "))
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            val colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            @Composable
            fun Action(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color = cs.onSurfaceVariant, onClick: () -> Unit) {
                ListItem(
                    headlineContent = { Text(label, color = if (tint == cs.error) cs.error else Color.Unspecified) },
                    leadingContent = { Icon(icon, null, tint = tint) },
                    colors = colors,
                    modifier = Modifier.clickable(onClick = onClick),
                )
            }
            when (item.state) {
                NzbState.Paused -> Action("Resume", Icons.Rounded.PlayArrow) { controller.resume(listOf(item)); onDismiss() }
                NzbState.Processing -> {}
                else -> Action("Pause", Icons.Rounded.Pause) { controller.pause(listOf(item)); onDismiss() }
            }
            if (item.state != NzbState.Processing) {
                if (item.index > 0) Action("Move to top", Icons.Rounded.VerticalAlignTop) { controller.moveTop(listOf(item)); onDismiss() }
                Action("Priority — ${item.priority.label}", Icons.Rounded.LowPriority) { pickPriority = true }
                Action("Category — ${item.category.ifEmpty { "None" }}", Icons.AutoMirrored.Rounded.Label) { pickCategory = true }
            }
            Action("Delete", Icons.Rounded.Delete, tint = cs.error) { confirmDelete = true }
        }
    }
    if (confirmDelete) DeleteJobsDialog(listOf(item), onConfirm = { purge -> controller.delete(listOf(item), purge); onDismiss() }, onDismiss = { confirmDelete = false })
    if (pickPriority) PriorityDialog(item.type, item.priority, onPick = { p -> controller.setPriority(listOf(item), p); onDismiss() }, onDismiss = { pickPriority = false })
    val svc = app.service(item.serviceId)
    if (pickCategory && svc != null) {
        UsenetCategoryDialog(svc, item.category, onPick = { c -> controller.setCategory(listOf(item), c); onDismiss() }, onDismiss = { pickCategory = false })
    }
}
