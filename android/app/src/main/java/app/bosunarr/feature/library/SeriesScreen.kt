package app.bosunarr.feature.library

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import app.bosunarr.app
import app.bosunarr.data.ServiceConfig
import app.bosunarr.ui.common.ErrorBox
import app.bosunarr.ui.common.LoadingBox
import app.bosunarr.ui.common.LocalSnackbar
import app.bosunarr.ui.common.SectionTitle
import app.bosunarr.ui.common.Tag
import app.bosunarr.ui.common.back
import app.bosunarr.ui.common.bytes
import app.bosunarr.ui.common.date
import app.bosunarr.ui.common.dateTime
import app.bosunarr.ui.common.parseInstant
import app.bosunarr.ui.common.plural
import app.bosunarr.ui.common.relative
import app.bosunarr.ui.common.rememberLoader
import app.bosunarr.ui.common.runtime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.Locale

/** Pop [entry] only if it's still on top (an async action may finish after the user left). */
fun NavController.backFrom(entry: NavBackStackEntry) {
    if (currentBackStackEntry?.id == entry.id) back()
}

private fun pad(n: Int) = n.toString().padStart(2, '0')

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SeriesScreen(nav: NavController, entry: NavBackStackEntry, service: ServiceConfig, id: Int) {
    val key = libKey(service.id, LibKind.Series, id)
    val loader = rememberLoader("series:${service.id}:$id") { LibraryApi.series(service, id) }
    val state = loader.state
    val d = state.value
    val cached = remember(key) { LibraryStore.items[key] }
    val snackbar = LocalSnackbar.current
    val list = rememberLazyListState()
    val busy = remember { mutableStateMapOf<String, Boolean>() }
    var expanded by rememberSaveable { mutableStateOf(setOf<Int>()) }
    var openEpisodes by rememberSaveable { mutableStateOf(setOf<Int>()) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    val x = d?.series
    val title = x?.title ?: cached?.title ?: ""

    fun busyAction(tag: String, success: String?, block: suspend () -> Unit) {
        if (busy[tag] == true) return
        busy[tag] = true
        runAction(snackbar, success, onDone = { busy.remove(tag) }, block = block)
    }

    fun toggleSeries() {
        val s = x ?: return
        val to = !s.monitored
        loader.mutate { it.copy(series = it.series.copy(monitored = to)) }
        LibraryStore.setMonitored(key, to)
        runAction(snackbar, null, onError = {
            loader.mutate { it.copy(series = it.series.copy(monitored = !to)) }
            LibraryStore.setMonitored(key, !to)
        }) { LibraryApi.setSeriesMonitored(service, id, to) }
    }

    fun toggleSeason(n: Int, to: Boolean) {
        val before = loader.value ?: return
        val oldEpisodes = before.episodes.filter { it.seasonNumber == n }.associate { it.id to it.monitored }
        loader.mutate { v ->
            v.copy(
                series = v.series.copy(seasons = v.series.seasons.map { if (it.seasonNumber == n) it.copy(monitored = to) else it }),
                episodes = v.episodes.map { if (it.seasonNumber == n) it.copy(monitored = to) else it },
            )
        }
        runAction(snackbar, null, onError = {
            loader.mutate { v ->
                v.copy(
                    series = v.series.copy(seasons = v.series.seasons.map { if (it.seasonNumber == n) it.copy(monitored = !to) else it }),
                    episodes = v.episodes.map { e -> oldEpisodes[e.id]?.let { e.copy(monitored = it) } ?: e },
                )
            }
        }) {
            LibraryApi.setSeasonMonitored(service, id, n, to)
            loader.refresh() // statistics change with monitoring
        }
    }

    fun toggleEpisode(e: ArrEpisode) {
        val to = !e.monitored
        loader.mutate { v -> v.copy(episodes = v.episodes.map { if (it.id == e.id) it.copy(monitored = to) else it }) }
        runAction(snackbar, null, onError = {
            loader.mutate { v -> v.copy(episodes = v.episodes.map { if (it.id == e.id) it.copy(monitored = !to) else it }) }
        }) { LibraryApi.setEpisodesMonitored(service, listOf(e.id), to) }
    }

    DetailScaffold(title, list, onBack = { nav.back() }) { p ->
        val pull = rememberPullToRefreshState()
        val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        PullToRefreshBox(
            isRefreshing = state.loading && d != null,
            onRefresh = { loader.refresh() },
            state = pull,
            modifier = Modifier.fillMaxSize(),
            indicator = {
                PullToRefreshDefaults.Indicator(state = pull, isRefreshing = state.loading && d != null, modifier = Modifier.align(Alignment.TopCenter).padding(top = top + 64.dp))
            },
        ) {
            LazyColumn(state = list, contentPadding = PaddingValues(bottom = p.calculateBottomPadding() + 32.dp), modifier = Modifier.fillMaxSize()) {
                item("header") {
                    DetailHeader(
                        service,
                        fanart = x?.let { arrImage(service, it.images, "fanart", ownerId = it.id) },
                        poster = x?.let { arrImage(service, it.images, "poster", 500, it.id) } ?: cached?.poster,
                        title = title,
                        lines = listOf(
                            listOfNotNull((x?.year ?: cached?.year)?.takeIf { it > 0 }?.toString(), x?.network ?: cached?.network).joinToString(" · "),
                            x?.let { s -> listOfNotNull(seriesStatusLabel(s.status), runtime(s.runtime).takeIf { it.isNotEmpty() }).joinToString(" · ") }.orEmpty(),
                        ),
                    )
                }
                when {
                    d == null && state.error != null -> item("error") { ErrorBox(state.error, onRetry = { loader.refresh() }) }
                    d == null || x == null -> item("loading") { LoadingBox() }
                    else -> {
                        item("actions") {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 16.dp)) {
                                DetailAction(if (x.monitored) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, if (x.monitored) "Monitored" else "Unmonitored", ::toggleSeries, selected = x.monitored)
                                DetailAction(Icons.Rounded.Search, "Search", { busyAction("search", "Searching for monitored episodes") { LibraryApi.searchSeries(service, id) } }, busy = busy["search"] == true)
                                DetailAction(Icons.Rounded.Refresh, "Refresh", {
                                    busyAction("refresh", "Refreshing and rescanning") {
                                        LibraryApi.refreshSeries(service, id)
                                        app.scope.launch(Dispatchers.Main) { delay(5000); loader.refresh() }
                                    }
                                }, busy = busy["refresh"] == true)
                                DetailAction(Icons.Rounded.Delete, "Delete", { confirmDelete = true }, busy = busy["delete"] == true, destructive = true)
                            }
                        }
                        item("facts") {
                            val st = x.statistics
                            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (st != null && st.episodeCount > 0) {
                                    val done = st.episodeFileCount >= st.episodeCount
                                    Tag(
                                        "${st.episodeFileCount}/${st.episodeCount} episodes",
                                        container = if (done) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer,
                                        content = if (done) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onTertiaryContainer,
                                    )
                                }
                                if ((st?.sizeOnDisk ?: 0) > 0) Tag(bytes(st?.sizeOnDisk))
                                d.profile?.let { Tag(it) }
                                x.seriesType?.takeIf { it != "standard" && it.isNotBlank() }?.let { Tag(it.replaceFirstChar { c -> c.titlecase() }) }
                                x.ratings?.best?.let { Tag("★ ${String.format(Locale.getDefault(), "%.1f", it)}") }
                                x.certification?.takeIf { it.isNotBlank() }?.let { Tag(it) }
                            }
                        }
                        if (!x.overview.isNullOrBlank()) item("overview") { ExpandableText(x.overview, Modifier.padding(horizontal = 16.dp, vertical = 16.dp).fillMaxWidth()) }
                        if (x.genres.isNotEmpty()) item("genres") {
                            Text(
                                x.genres.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp),
                            )
                        }
                        item("info") {
                            InfoCard(
                                listOf(
                                    "Next episode" to x.nextAiring?.let { "${dateTime(it)} (${relative(it)})" },
                                    "Previous" to x.previousAiring?.let { dateTime(it) },
                                    "First aired" to x.firstAired?.let { date(it) },
                                    "Added" to x.added?.let { date(it) },
                                    "Path" to x.path,
                                    "Instance" to service.name,
                                ),
                            )
                        }
                        val seasons = x.seasons.sortedWith(compareBy<ArrSeason> { it.seasonNumber == 0 }.thenByDescending { it.seasonNumber })
                        val bySeason = d.episodes.groupBy { it.seasonNumber }
                        if (seasons.isNotEmpty()) item("seasons-title") { SectionTitle("Seasons") }
                        seasons.forEach { season ->
                            val n = season.seasonNumber
                            val eps = bySeason[n].orEmpty().sortedBy { it.episodeNumber }
                            val open = n in expanded
                            item("s$n") {
                                SeasonRow(
                                    season, eps, open,
                                    onToggleOpen = { expanded = if (open) expanded - n else expanded + n },
                                    onToggleMonitored = { toggleSeason(n, !season.monitored) },
                                    searching = busy["season-$n"] == true,
                                    onSearch = { busyAction("season-$n", "Searching ${seasonName(n).lowercase()}") { LibraryApi.searchSeason(service, id, n) } },
                                )
                            }
                            if (open) {
                                if (eps.isEmpty()) item("s$n-empty") {
                                    Text("No episodes yet", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 72.dp, top = 4.dp, bottom = 12.dp))
                                }
                                items(eps, key = { "e${it.id}" }, contentType = { "episode" }) { e ->
                                    EpisodeRow(
                                        e, d.files[e.episodeFileId],
                                        seasonMonitored = season.monitored,
                                        showOverview = e.id in openEpisodes,
                                        onClick = { openEpisodes = if (e.id in openEpisodes) openEpisodes - e.id else openEpisodes + e.id },
                                        onToggleMonitored = { toggleEpisode(e) },
                                        searching = busy["ep-${e.id}"] == true,
                                        onSearch = { busyAction("ep-${e.id}", "Searching S${pad(e.seasonNumber)}E${pad(e.episodeNumber)}") { LibraryApi.searchEpisodes(service, listOf(e.id)) } },
                                    )
                                }
                                item("s$n-end") { HorizontalDivider(Modifier.padding(start = 16.dp, top = 4.dp)) }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        DeleteDialog(title, "series", onConfirm = { files ->
            busyAction("delete", "Deleted $title") {
                LibraryApi.deleteSeries(service, id, files)
                LibraryStore.removed(key)
                nav.backFrom(entry)
            }
        }, onDismiss = { confirmDelete = false })
    }
}

private fun seriesStatusLabel(s: String?): String? = when (s?.lowercase()) {
    "continuing" -> "Continuing"
    "ended" -> "Ended"
    "upcoming" -> "Upcoming"
    "deleted" -> "Removed from TVDB"
    null, "" -> null
    else -> s.replaceFirstChar { it.titlecase() }
}

private fun seasonName(n: Int) = if (n == 0) "Specials" else "Season $n"

@Composable
private fun SeasonRow(
    season: ArrSeason,
    episodes: List<ArrEpisode>,
    open: Boolean,
    onToggleOpen: () -> Unit,
    onToggleMonitored: () -> Unit,
    searching: Boolean,
    onSearch: () -> Unit,
) {
    val st = season.statistics
    val now = Instant.now()
    val have = st?.episodeFileCount ?: episodes.count { it.hasFile }
    val total = st?.episodeCount ?: episodes.count { it.hasFile || (parseInstant(it.airDateUtc)?.isBefore(now) == true && it.monitored) }
    val all = st?.totalEpisodeCount?.takeIf { it > 0 } ?: episodes.size
    val size = st?.sizeOnDisk ?: 0
    val rotation by animateFloatAsState(if (open) 180f else 0f, label = "chevron")
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggleOpen).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            val complete = total > 0 && have >= total
            CircularProgressIndicator(
                progress = { if (total > 0) (have.toFloat() / total).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.size(36.dp),
                strokeWidth = 3.dp,
                color = if (season.monitored) cs.primary else cs.outline,
                trackColor = cs.surfaceContainerHighest,
                gapSize = 0.dp,
            )
            if (complete) Icon(Icons.Rounded.Check, null, Modifier.size(18.dp), tint = cs.primary)
            else Text(if (total > 0) "$have" else "–", style = MaterialTheme.typography.labelMedium)
        }
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(seasonName(season.seasonNumber), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    if (all == 0) "No episodes yet" else if (total > 0) "$have of $total episodes" else plural(all, "episode"),
                    if (size > 0) bytes(size) else null,
                    if (!season.monitored) "Unmonitored" else null,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onToggleMonitored) {
            Icon(if (season.monitored) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, if (season.monitored) "Unmonitor season" else "Monitor season", tint = if (season.monitored) cs.primary else cs.onSurfaceVariant)
        }
        IconButton(onClick = onSearch, enabled = !searching) {
            if (searching) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.Search, "Search season")
        }
        Icon(Icons.Rounded.ExpandMore, if (open) "Collapse" else "Expand", Modifier.padding(end = 8.dp).rotate(rotation), tint = cs.onSurfaceVariant)
    }
}

@Composable
private fun EpisodeRow(
    e: ArrEpisode,
    file: ArrEpisodeFile?,
    seasonMonitored: Boolean,
    showOverview: Boolean,
    onClick: () -> Unit,
    onToggleMonitored: () -> Unit,
    searching: Boolean,
    onSearch: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val air = parseInstant(e.airDateUtc)
    val aired = air != null && air.isBefore(Instant.now())
    val (stateText, stateColor) = when {
        e.hasFile -> (listOfNotNull(file?.quality?.quality?.name, file?.size?.takeIf { it > 0 }?.let { bytes(it) }).joinToString(" · ").ifEmpty { "Downloaded" }) to cs.primary
        !aired -> "Not aired" to cs.onSurfaceVariant
        e.monitored && seasonMonitored -> "Missing" to cs.error
        else -> "Not downloaded" to cs.onSurfaceVariant
    }
    val airText = when {
        air == null -> "TBA"
        aired -> date(e.airDateUtc)
        else -> "${date(e.airDateUtc)} (${relative(air)})"
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = if (e.hasFile) cs.primaryContainer else cs.surfaceContainerHigh,
            contentColor = if (e.hasFile) cs.onPrimaryContainer else cs.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp).widthIn(min = 40.dp),
        ) {
            Text("${e.episodeNumber}", style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp))
        }
        Column(Modifier.weight(1f).padding(start = 16.dp, top = 2.dp)) {
            Text(e.title?.takeIf { it.isNotBlank() } ?: "Episode ${e.episodeNumber}", style = MaterialTheme.typography.bodyLarge, maxLines = if (showOverview) 4 else 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(airText, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1)
                Text("  ·  ", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                Text(stateText, style = MaterialTheme.typography.bodySmall, color = stateColor, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            }
            if (showOverview) {
                Spacer(Modifier.height(4.dp))
                Text(e.overview?.takeIf { it.isNotBlank() } ?: "No description.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
        IconButton(onClick = onToggleMonitored) {
            Icon(if (e.monitored) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, if (e.monitored) "Unmonitor episode" else "Monitor episode", tint = if (e.monitored) cs.primary else cs.onSurfaceVariant)
        }
        IconButton(onClick = onSearch, enabled = !searching) {
            if (searching) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.Search, "Search episode")
        }
    }
}
