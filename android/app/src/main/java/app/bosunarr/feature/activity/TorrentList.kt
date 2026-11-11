package app.bosunarr.feature.activity

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.bosunarr.feature.activity.qbit.Torrent
import app.bosunarr.feature.activity.qbit.TorrentGroup
import app.bosunarr.feature.activity.qbit.categoryLabel
import app.bosunarr.ui.common.bytes
import app.bosunarr.ui.common.eta
import java.util.Locale

enum class TorrentFilter(val label: String, val test: (Torrent) -> Boolean) {
    All("All", { true }),
    Downloading("Downloading", { !it.isDone && !it.isStopped && it.group != TorrentGroup.Errored }),
    Seeding("Seeding", { it.group == TorrentGroup.Seeding }),
    Completed("Completed", { it.isDone }),
    Paused("Paused", { it.isStopped || it.group == TorrentGroup.Paused || it.group == TorrentGroup.Completed }),
    Active("Active", { it.isActive }),
    Errored("Errored", { it.group == TorrentGroup.Errored }),
}

private fun etaKey(t: Torrent) = if (t.isDone || t.eta <= 0 || t.eta >= 8_640_000) Long.MAX_VALUE else t.eta

enum class TorrentSort(val label: String, val cmp: Comparator<Torrent>) {
    Added("Recently added", compareByDescending { it.addedOn }),
    Name("Name", compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }),
    Progress("Progress", compareBy { it.progress }),
    DownSpeed("Download speed", compareByDescending { it.dlspeed }),
    UpSpeed("Upload speed", compareByDescending { it.upspeed }),
    Size("Size", compareByDescending { it.size }),
    Eta("Time left", compareBy { etaKey(it) }),
    Ratio("Ratio", compareByDescending { it.ratio }),
}

/** Search, filter, sort and multi-selection of a torrent list. Search/filter/sort survive rotation and navigation. */
@Stable
class TorrentListState internal constructor(
    queryState: MutableState<String>,
    filterState: MutableState<TorrentFilter>,
    sortState: MutableState<TorrentSort>,
) {
    var query by queryState
    var filter by filterState
    var sort by sortState
    var selection by mutableStateOf<Set<String>>(emptySet())
    val selecting: Boolean get() = selection.isNotEmpty()

    fun toggle(t: Torrent) {
        selection = if (t.key in selection) selection - t.key else selection + t.key
    }

    fun clearSelection() { selection = emptySet() }

    /** Search applies first; chip counts are taken after it so they match what tapping a chip shows. */
    fun searched(list: List<Torrent>): List<Torrent> {
        val needle = query.trim().lowercase(Locale.ROOT)
        if (needle.isEmpty()) return list
        return list.filter { it.name.lowercase(Locale.ROOT).contains(needle) || it.category.lowercase(Locale.ROOT) == needle }
    }

    fun visible(searched: List<Torrent>): List<Torrent> =
        // Ties fall back to the key so equal values (0 B/s, same size…) never swap places between polls.
        searched.filter(filter.test).sortedWith(sort.cmp.thenBy { it.key })
}

@Composable
fun rememberTorrentListState(key: String): TorrentListState {
    val q = rememberSaveable { mutableStateOf("") }
    val f = rememberSaveable { mutableStateOf(TorrentFilter.All) }
    val s = rememberSaveable { mutableStateOf(TorrentSort.Added) }
    return remember(key) { TorrentListState(q, f, s) }
}

/** Search + sort row and the status chips. [leadingChips] go before the status chips. */
@Composable
fun TorrentToolbar(
    state: TorrentListState,
    searched: List<Torrent>,
    modifier: Modifier = Modifier,
    leadingChips: @Composable RowScope.() -> Unit = {},
) {
    val counts = remember(searched) { TorrentFilter.entries.associateWith { f -> searched.count(f.test) } }
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            SearchPill(state.query, { state.query = it }, "Search torrents", Modifier.weight(1f))
            SortButton(state)
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leadingChips()
            TorrentFilter.entries.forEach { f ->
                val n = counts[f] ?: 0
                FilterChip(
                    selected = state.filter == f,
                    onClick = { state.filter = f },
                    label = {
                        Text(f.label)
                        Spacer(Modifier.width(6.dp))
                        Text(n.toString(), style = MaterialTheme.typography.labelLarge.tnum(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                )
            }
        }
    }
}

/**
 * Floating batch-action bar shown while torrents are selected (Back clears the selection). Selected torrents that
 * disappear (deleted elsewhere) drop out of the selection.
 */
@Composable
fun SelectionBar(
    state: TorrentListState,
    all: List<Torrent>,
    controller: TorrentController,
    categories: (Torrent) -> List<String>,
    modifier: Modifier = Modifier,
) {
    val selected = remember(all, state.selection) { all.filter { it.key in state.selection } }
    LaunchedEffect(selected.size, state.selection.size) {
        if (state.selection.isNotEmpty() && selected.size != state.selection.size) state.selection = selected.map { it.key }.toSet()
    }
    BackHandler(enabled = state.selecting) { state.clearSelection() }
    AnimatedVisibility(
        visible = state.selecting,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier,
    ) {
        SelectionBarContent(state, selected, all, controller, categories)
    }
}

@Composable
private fun SortButton(state: TorrentListState) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.AutoMirrored.Rounded.Sort, "Sort") }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            Text("Sort by", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            TorrentSort.entries.forEach { s ->
                DropdownMenuItem(
                    text = { Text(s.label) },
                    onClick = { state.sort = s; open = false },
                    trailingIcon = { if (state.sort == s) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary) },
                )
            }
        }
    }
}

@Composable
private fun SelectionBarContent(
    state: TorrentListState,
    selected: List<Torrent>,
    all: List<Torrent>,
    controller: TorrentController,
    categories: (Torrent) -> List<String>,
) {
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var pickCategory by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(28.dp),
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            IconButton(onClick = { state.clearSelection() }) { Icon(Icons.Rounded.Close, "Clear selection") }
            Text("${selected.size} selected", style = MaterialTheme.typography.titleSmall.tnum(), modifier = Modifier.weight(1f), maxLines = 1)
            val canStart = selected.any { it.isStopped || it.group == TorrentGroup.Errored }
            val canStop = selected.any { !it.isStopped }
            IconButton(onClick = { controller.stop(selected.filter { !it.isStopped }); state.clearSelection() }, enabled = canStop) { Icon(Icons.Rounded.Pause, "Pause selected") }
            IconButton(onClick = { controller.start(selected.filter { it.isStopped || it.group == TorrentGroup.Errored }); state.clearSelection() }, enabled = canStart) { Icon(Icons.Rounded.PlayArrow, "Resume selected") }
            IconButton(onClick = { confirmDelete = true }, enabled = selected.isNotEmpty()) { Icon(Icons.Rounded.Delete, "Delete selected") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More actions") }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Select all") }, onClick = { state.selection = all.map { it.key }.toSet(); menu = false })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Force recheck") }, onClick = { controller.recheck(selected); state.clearSelection(); menu = false })
                    DropdownMenuItem(text = { Text("Reannounce") }, onClick = { controller.reannounce(selected); state.clearSelection(); menu = false })
                    // Categories belong to one client, so only offer this when the selection is on one.
                    if (selected.map { it.serviceId }.distinct().size == 1) {
                        DropdownMenuItem(text = { Text("Set category…") }, onClick = { pickCategory = true; menu = false })
                    }
                }
            }
        }
    }
    if (confirmDelete && selected.isNotEmpty()) {
        DeleteTorrentsDialog(selected, onConfirm = { files -> controller.delete(selected, files); state.clearSelection() }, onDismiss = { confirmDelete = false })
    }
    if (pickCategory && selected.isNotEmpty()) {
        val common = selected.map { it.category }.distinct().singleOrNull()
        CategoryDialog(categories(selected.first()), common, onPick = { c -> controller.setCategory(selected, c); state.clearSelection() }, onDismiss = { pickCategory = false })
    }
}

/** Torrent rows for a LazyColumn. Keys are `serviceId:hash`, so polling never resets scroll or animations. */
fun LazyListScope.torrentRows(
    list: List<Torrent>,
    state: TorrentListState,
    showClient: Boolean,
    onOpen: (Torrent) -> Unit,
) {
    items(list, key = { it.key }, contentType = { "torrent" }) { t ->
        TorrentRow(
            t = t,
            selected = t.key in state.selection,
            selecting = state.selecting,
            showClient = showClient,
            onClick = { if (state.selecting) state.toggle(t) else onOpen(t) },
            onLongClick = { state.toggle(t) },
            modifier = Modifier.animateItem(),
        )
    }
}

fun pct(p: Double): String = String.format(Locale.US, "%.1f%%", kotlin.math.floor(p * 1000) / 10)

fun ratio(r: Double): String = if (r >= 100) String.format(Locale.US, "%.0f", r) else String.format(Locale.US, "%.2f", r)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TorrentRow(
    t: Torrent,
    selected: Boolean,
    selecting: Boolean,
    showClient: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val cs = MaterialTheme.colorScheme
    val color = groupColor(t.group)
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
            Text(t.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            SmoothProgress(t.progress.toFloat(), color)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    meta(t.stateLabel, if (t.isDone) null else pct(t.progress)),
                    style = MaterialTheme.typography.labelMedium.tnum(),
                    fontWeight = FontWeight.Medium,
                    color = if (t.group == TorrentGroup.Paused || t.group == TorrentGroup.Completed) cs.onSurfaceVariant else color,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                val moving = t.group in setOf(TorrentGroup.Downloading, TorrentGroup.Stalled, TorrentGroup.Seeding) || t.isActive
                if (moving) {
                    if (!t.isDone || t.dlspeed > 0) SpeedLabel(true, t.dlspeed, style = MaterialTheme.typography.labelMedium, color = cs.onSurface)
                    SpeedLabel(false, t.upspeed, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelMedium, color = cs.onSurface)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                meta(
                    if (t.isDone) bytes(t.size) else "${bytes((t.size * t.progress).toLong())} of ${bytes(t.size)}",
                    if (!t.isDone && !t.isStopped && t.eta in 1 until 8_640_000) "ETA ${eta(t.eta)}" else null,
                    if (t.isDone || t.uploaded > 0) "Ratio ${ratio(t.ratio)}" else null,
                    t.category.takeIf { it.isNotEmpty() }?.let { categoryLabel(it) },
                    if (showClient) t.serviceName else null,
                ),
                style = MaterialTheme.typography.bodySmall.tnum(),
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
