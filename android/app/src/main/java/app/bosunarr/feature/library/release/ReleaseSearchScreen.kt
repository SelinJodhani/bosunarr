package app.bosunarr.feature.library.release

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Newspaper
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import app.bosunarr.app
import app.bosunarr.data.ServiceConfig
import app.bosunarr.feature.library.horizontalFadingEdges
import app.bosunarr.feature.library.runAction
import app.bosunarr.net.userMessage
import app.bosunarr.nav.ReleaseSearchRoute
import app.bosunarr.ui.art.SeaScene
import app.bosunarr.ui.common.AppScaffold
import app.bosunarr.ui.common.ErrorBox
import app.bosunarr.ui.common.FullScreenScroll
import app.bosunarr.ui.common.LocalSnackbar
import app.bosunarr.ui.common.MessageBox
import app.bosunarr.ui.common.Tag
import app.bosunarr.ui.common.back
import app.bosunarr.ui.common.bytes
import app.bosunarr.ui.common.plural
import app.bosunarr.ui.common.safeOpen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/* ---------------------------------------------------------------- state */

/** Survives rotation and process-less navigation so a minute-long search isn't restarted. */
class ReleaseSearchViewModel : ViewModel() {
    sealed interface Phase {
        data object Idle : Phase
        data class Searching(val since: Long) : Phase
        data class Done(val releases: List<Release>) : Phase
        data class Failed(val message: String) : Phase
        data object Cancelled : Phase
    }

    var phase by mutableStateOf<Phase>(Phase.Idle)
        private set
    /** Release keys being grabbed / sent. */
    val grabbing = mutableStateMapOf<String, Boolean>()
    val grabbed = mutableStateMapOf<String, Boolean>()
    private var job: Job? = null

    fun search(s: ServiceConfig, t: ReleaseTarget) {
        job?.cancel()
        phase = Phase.Searching(System.currentTimeMillis())
        job = viewModelScope.launch {
            phase = try {
                Phase.Done(ReleaseApi.search(s, t))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Phase.Failed(e.userMessage())
            }
        }
    }

    /** Stops waiting (the HTTP call is cancelled; the arr may still finish its indexer queries on its own). */
    fun cancel() {
        job?.cancel()
        job = null
        phase = Phase.Cancelled
    }
}

enum class ReleaseSort(val label: String, val descendingByDefault: Boolean) {
    Best("Best match", false),
    Score("Score", true),
    Seeders("Seeders", true),
    Size("Size", true),
    Age("Age", false),
    Quality("Quality", true),
}

private fun comparatorFor(sort: ReleaseSort): Comparator<Release> = when (sort) {
    ReleaseSort.Best -> compareBy { it.order }
    ReleaseSort.Score -> compareBy<Release> { it.customFormatScore }.thenBy { it.qualityWeight }
    ReleaseSort.Seeders -> compareBy<Release> { it.seeders ?: it.grabs ?: -1 }
    ReleaseSort.Size -> compareBy { it.size }
    ReleaseSort.Age -> compareBy { it.ageMinutes ?: Double.MAX_VALUE }
    ReleaseSort.Quality -> compareBy<Release> { it.qualityWeight }.thenBy { it.resolution }.thenBy { it.customFormatScore }
}

/* ---------------------------------------------------------------- screen */

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReleaseSearchScreen(nav: NavController, service: ServiceConfig, route: ReleaseSearchRoute) {
    val target = remember(route) { ReleaseTarget(route.movieId, route.seriesId, route.episodeId, route.seasonNumber) }
    val vm: ReleaseSearchViewModel = viewModel(key = "releases:${service.id}:$target")
    LaunchedEffect(vm) { if (vm.phase == ReleaseSearchViewModel.Phase.Idle) vm.search(service, target) }
    val phase = vm.phase
    val snackbar = LocalSnackbar.current

    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(ReleaseSort.Best) }
    var descending by rememberSaveable { mutableStateOf(ReleaseSort.Best.descendingByDefault) }
    var qualities by rememberSaveable { mutableStateOf(setOf<String>()) }
    var protocol by rememberSaveable { mutableStateOf<String?>(null) }
    var hideRejected by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }
    var confirm by remember { mutableStateOf<Release?>(null) }

    fun grab(r: Release) {
        if (vm.grabbing[r.key] == true) return
        vm.grabbing[r.key] = true
        runAction(snackbar, null, onDone = { vm.grabbing.remove(r.key) }) {
            val client = ReleaseApi.grab(service, target, r)
            vm.grabbed[r.key] = true
            app.scope.launch { snackbar.showSnackbar(if (client != null) "Sent to $client" else "Sent to the download client") }
        }
    }

    AppScaffold(
        route.title.ifBlank { "Interactive search" },
        subtitle = listOfNotNull(route.subtitle?.takeIf { it.isNotBlank() }, "Interactive search").joinToString(" · "),
        onBack = { nav.back() },
        actions = {
            if (phase !is ReleaseSearchViewModel.Phase.Searching) {
                IconButton(onClick = { vm.search(service, target) }) { Icon(Icons.Rounded.Refresh, "Search again") }
            }
        },
    ) { p ->
        Box(Modifier.padding(p).fillMaxSize()) {
            when (phase) {
                ReleaseSearchViewModel.Phase.Idle, is ReleaseSearchViewModel.Phase.Searching -> {
                    val since = (phase as? ReleaseSearchViewModel.Phase.Searching)?.since ?: System.currentTimeMillis()
                    SearchingState(service, since, onCancel = { vm.cancel() })
                }
                is ReleaseSearchViewModel.Phase.Failed -> FullScreenScroll { m -> ErrorBox(phase.message, m, onRetry = { vm.search(service, target) }) }
                ReleaseSearchViewModel.Phase.Cancelled -> FullScreenScroll { m ->
                    MessageBox(
                        SeaScene.Spyglass, "Search cancelled", m,
                        hint = "${service.name} may still finish searching its indexers on its own.",
                        action = "Search again", onAction = { vm.search(service, target) },
                    )
                }
                is ReleaseSearchViewModel.Phase.Done -> {
                    val all = phase.releases
                    if (all.isEmpty()) {
                        FullScreenScroll { m ->
                            MessageBox(
                                SeaScene.Spyglass, "No releases found", m,
                                hint = "None of ${service.name}'s indexers had anything. Check the indexers in ${service.name} or Prowlarr, or try again later.",
                                action = "Search again", onAction = { vm.search(service, target) },
                            )
                        }
                    } else {
                        val shown = remember(all, query, sort, descending, qualities, protocol, hideRejected) {
                            val tokens = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
                            val cmp = comparatorFor(sort).let { if (descending) it.reversed() else it }
                            all.asSequence()
                                .filter { r -> tokens.all { t -> r.title.lowercase().contains(t) } }
                                .filter { qualities.isEmpty() || (it.quality ?: "Unknown") in qualities }
                                .filter { protocol == null || it.protocol == protocol }
                                .filter { !hideRejected || !it.rejected }
                                // Rejected releases always sink to the bottom; the chosen order applies within each group.
                                .sortedWith(compareBy<Release> { it.rejected }.then(cmp))
                                .toList()
                        }
                        Column(Modifier.fillMaxSize()) {
                            ReleaseFilters(
                                all, query, { query = it },
                                sort, descending,
                                onSort = { s -> if (s == sort) descending = !descending else { sort = s; descending = s.descendingByDefault } },
                                qualities, { qualities = it },
                                protocol, { protocol = it },
                                hideRejected, { hideRejected = it },
                            )
                            val rejectedCount = all.count { it.rejected }
                            Text(
                                (if (shown.size == all.size) plural(all.size, "release") else "Showing ${shown.size} of ${all.size}") +
                                    (if (rejectedCount > 0) " · $rejectedCount rejected" else ""),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            )
                            if (shown.isEmpty()) {
                                MessageBox(
                                    Icons.Rounded.Search, "No releases match", hint = "Clear the search or filters to see all ${all.size}.",
                                    action = "Clear filters", onAction = { query = ""; qualities = emptySet(); protocol = null; hideRejected = false },
                                )
                            } else {
                                val list = rememberLazyListState()
                                LazyColumn(state = list, contentPadding = PaddingValues(bottom = 96.dp), modifier = Modifier.fillMaxSize()) {
                                    items(shown, key = { it.key }) { r ->
                                        ReleaseRow(
                                            r,
                                            open = r.key in expanded,
                                            onToggle = { expanded = if (r.key in expanded) expanded - r.key else expanded + r.key },
                                            grabbing = vm.grabbing[r.key] == true,
                                            grabbed = vm.grabbed[r.key] == true,
                                            onGrab = { if (r.rejected) confirm = r else grab(r) },
                                            modifier = Modifier.animateItem(),
                                        )
                                        HorizontalDivider(Modifier.padding(start = 16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    confirm?.let { r ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            icon = { Icon(Icons.Rounded.Block, null) },
            title = { Text("Grab anyway?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${service.name} rejected this release:")
                    r.rejections.ifEmpty { listOf("No reason given") }.forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                    Text("It will be sent to the download client regardless. ${service.name} may still refuse to import it afterwards (e.g. if it's not an upgrade).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { Button(onClick = { confirm = null; grab(r) }) { Text("Grab anyway") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SearchingState(service: ServiceConfig, since: Long, onCancel: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(since) {
        while (true) { now = System.currentTimeMillis(); delay(1000) }
    }
    val secs = ((now - since) / 1000).coerceAtLeast(0)
    Column(Modifier.fillMaxSize()) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        FullScreenScroll { m ->
            MessageBox(
                SeaScene.Spyglass, "Searching indexers…", m,
                hint = "${service.name} is asking every indexer — this can take a minute." + if (secs >= 3) "\n${secs}s" else "",
                action = "Cancel", onAction = onCancel,
            )
        }
    }
}

/* ---------------------------------------------------------------- filters */

@Composable
private fun ReleaseFilters(
    all: List<Release>,
    query: String,
    onQuery: (String) -> Unit,
    sort: ReleaseSort,
    descending: Boolean,
    onSort: (ReleaseSort) -> Unit,
    qualities: Set<String>,
    onQualities: (Set<String>) -> Unit,
    protocol: String?,
    onProtocol: (String?) -> Unit,
    hideRejected: Boolean,
    onHideRejected: (Boolean) -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    TextField(
        value = query,
        onValueChange = onQuery,
        placeholder = { Text("Filter release titles") },
        leadingIcon = { Icon(Icons.Rounded.Search, null) },
        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Rounded.Close, "Clear filter") } },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
    val scroll = rememberScrollState()
    Row(
        Modifier.fillMaxWidth().horizontalFadingEdges(scroll).horizontalScroll(scroll).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Sort: pick a key; picking the current one again flips the direction.
        Box {
            var open by remember { mutableStateOf(false) }
            FilterChip(
                selected = sort != ReleaseSort.Best,
                onClick = { open = true },
                label = { Text(if (sort == ReleaseSort.Best) "Sort" else sort.label) },
                leadingIcon = if (sort != ReleaseSort.Best) ({ Icon(if (descending) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward, null, Modifier.size(FilterChipDefaults.IconSize)) }) else null,
                trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, null, Modifier.size(FilterChipDefaults.IconSize)) },
            )
            DropdownMenu(open, onDismissRequest = { open = false }) {
                ReleaseSort.entries.forEach { s ->
                    DropdownMenuItem(
                        text = { Text(s.label) },
                        leadingIcon = {
                            when {
                                s == sort && s != ReleaseSort.Best -> Icon(if (descending) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward, if (descending) "Descending" else "Ascending")
                                s == sort -> Icon(Icons.Rounded.Check, null)
                                else -> Spacer(Modifier.size(24.dp))
                            }
                        },
                        onClick = { onSort(s); if (s != sort) open = false },
                    )
                }
            }
        }
        // Quality: multi-select of the qualities present, best first.
        val qualityOptions = remember(all) {
            all.groupBy { it.quality ?: "Unknown" }.entries.sortedByDescending { e -> e.value.maxOf { it.qualityWeight } }.map { it.key to it.value.size }
        }
        if (qualityOptions.size > 1) Box {
            var open by remember { mutableStateOf(false) }
            FilterChip(
                selected = qualities.isNotEmpty(),
                onClick = { open = true },
                label = {
                    Text(
                        when (qualities.size) {
                            0 -> "Quality"
                            1 -> qualities.first()
                            else -> "${qualities.size} qualities"
                        },
                    )
                },
                trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, null, Modifier.size(FilterChipDefaults.IconSize)) },
            )
            DropdownMenu(open, onDismissRequest = { open = false }) {
                DropdownMenuItem(
                    text = { Text("Any quality") },
                    leadingIcon = { if (qualities.isEmpty()) Icon(Icons.Rounded.Check, null) else Spacer(Modifier.size(24.dp)) },
                    onClick = { onQualities(emptySet()); open = false },
                )
                qualityOptions.forEach { (q, n) ->
                    val on = q in qualities
                    DropdownMenuItem(
                        text = { Text("$q  ($n)") },
                        leadingIcon = { if (on) Icon(Icons.Rounded.Check, null) else Spacer(Modifier.size(24.dp)) },
                        onClick = { onQualities(if (on) qualities - q else qualities + q) },
                    )
                }
            }
        }
        // Protocol: only when both kinds came back.
        val protocols = remember(all) { all.map { it.protocol }.filter { it != "unknown" }.distinct().sorted() }
        if (protocols.size > 1) protocols.forEach { pr ->
            FilterChip(
                selected = protocol == pr,
                onClick = { onProtocol(if (protocol == pr) null else pr) },
                label = { Text(if (pr == "torrent") "Torrent" else "Usenet") },
                leadingIcon = { Icon(protocolIcon(pr), null, Modifier.size(FilterChipDefaults.IconSize)) },
            )
        }
        if (all.any { it.rejected }) FilterChip(
            selected = hideRejected,
            onClick = { onHideRejected(!hideRejected) },
            label = { Text("Hide rejected") },
            leadingIcon = if (hideRejected) ({ Icon(Icons.Rounded.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }) else null,
        )
    }
}

private fun protocolIcon(p: String) = if (p == "usenet") Icons.Rounded.Newspaper else Icons.Rounded.SwapVert

/* ---------------------------------------------------------------- row */

/** "45 min", "6 h", "3 d", "4 mo", "2 y" */
private fun age(minutes: Double?): String? {
    val m = minutes ?: return null
    return when {
        m < 60 -> "${m.toInt().coerceAtLeast(1)} min"
        m < 60 * 24 -> "${(m / 60).toInt()} h"
        m < 60 * 24 * 60 -> "${(m / 1440).toInt()} d"
        m < 60 * 24 * 365 -> "${(m / (1440 * 30)).toInt()} mo"
        else -> "${(m / (1440 * 365)).toInt()} y"
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReleaseRow(
    r: Release,
    open: Boolean,
    onToggle: () -> Unit,
    grabbing: Boolean,
    grabbed: Boolean,
    onGrab: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val dim = if (r.rejected) 0.6f else 1f
    val uri = LocalUriHandler.current
    Column(modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).alpha(dim), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    r.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                    maxLines = if (open) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Tag(r.quality ?: "Unknown", container = cs.primaryContainer, content = cs.onPrimaryContainer)
                    if (r.customFormatScore != 0 || r.customFormats.isNotEmpty()) {
                        val positive = r.customFormatScore > 0
                        Tag(
                            (if (positive) "+" else "") + r.customFormatScore,
                            container = when {
                                r.customFormatScore > 0 -> cs.tertiaryContainer
                                r.customFormatScore < 0 -> cs.errorContainer
                                else -> cs.surfaceContainerHighest
                            },
                            content = when {
                                r.customFormatScore > 0 -> cs.onTertiaryContainer
                                r.customFormatScore < 0 -> cs.onErrorContainer
                                else -> cs.onSurfaceVariant
                            },
                        )
                    }
                    r.episodeLabel?.let { Tag(it, container = cs.surfaceContainerHighest, content = cs.onSurfaceVariant) }
                    val langs = r.languages
                    if (langs.isNotEmpty()) Tag(
                        if (langs.size <= 2 || open) langs.joinToString(", ") else "${langs.take(2).joinToString(", ")} +${langs.size - 2}",
                        container = cs.surfaceContainerHighest, content = cs.onSurfaceVariant,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(protocolIcon(r.protocol), if (r.isUsenet) "Usenet" else "Torrent", Modifier.size(14.dp), tint = cs.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        listOfNotNull(
                            r.size.takeIf { it > 0 }?.let { bytes(it) },
                            age(r.ageMinutes),
                            r.indexer.takeIf { it.isNotBlank() },
                        ).joinToString("  ·  "),
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    when {
                        r.isTorrent && r.seeders != null -> {
                            Text("  ·  ", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                            Text(
                                "▲${r.seeders} ▼${r.leechers ?: 0}",
                                style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium,
                                color = if (r.seeders == 0) cs.error else cs.primary, maxLines = 1,
                            )
                        }
                        r.grabs != null -> Text("  ·  ${plural(r.grabs, "grab")}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.width(4.dp))
            val label = if (r.rejected) "Grab rejected release" else "Grab release"
            FilledTonalIconButton(
                onClick = onGrab,
                enabled = !grabbing && !grabbed,
                colors = if (r.rejected) IconButtonDefaults.filledTonalIconButtonColors(containerColor = cs.surfaceContainerHighest, contentColor = cs.onSurfaceVariant)
                else IconButtonDefaults.filledTonalIconButtonColors(),
            ) {
                when {
                    grabbing -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    grabbed -> Icon(Icons.Rounded.Check, "Sent", tint = cs.primary)
                    else -> Icon(Icons.Rounded.Download, label)
                }
            }
        }
        if (r.rejected) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Top) {
                Icon(if (r.temporarilyRejected) Icons.Rounded.Schedule else Icons.Rounded.Block, null, Modifier.padding(top = 1.dp).size(16.dp), tint = cs.error)
                Spacer(Modifier.width(6.dp))
                val prefix = if (r.temporarilyRejected) "Temporarily rejected" else "Rejected"
                val first = r.rejections.firstOrNull()
                Text(
                    when {
                        open || first == null -> prefix
                        r.rejections.size == 1 -> "$prefix: $first"
                        else -> "$prefix: $first  (+${r.rejections.size - 1} more)"
                    },
                    style = MaterialTheme.typography.bodySmall, color = cs.error, fontWeight = FontWeight.Medium,
                    maxLines = if (open) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(top = 6.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (r.rejected) r.rejections.forEach {
                    Text("•  $it", style = MaterialTheme.typography.bodySmall, color = cs.error, modifier = Modifier.padding(start = 22.dp))
                }
                if (r.customFormats.isNotEmpty()) Text(
                    "Custom formats: " + r.customFormats.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                )
                listOfNotNull(
                    r.releaseGroup?.let { "Group: $it" },
                    r.indexerFlags.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.removePrefix("g_").removePrefix("G_") }?.let { "Flags: $it" },
                ).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
                r.infoUrl?.let { url ->
                    TextButton(onClick = { uri.safeOpen(url) }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Open on ${r.indexer.ifBlank { "indexer" }}")
                    }
                }
            }
        }
    }
}
