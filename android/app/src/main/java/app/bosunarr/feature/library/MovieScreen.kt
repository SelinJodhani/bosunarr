package app.bosunarr.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.ManageSearch
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import app.bosunarr.app
import app.bosunarr.data.ServiceConfig
import app.bosunarr.nav.ReleaseSearchRoute
import app.bosunarr.ui.common.ErrorBox
import app.bosunarr.ui.common.LoadingBox
import app.bosunarr.ui.common.LocalSnackbar
import app.bosunarr.ui.common.SectionTitle
import app.bosunarr.ui.common.Tag
import app.bosunarr.ui.common.back
import app.bosunarr.ui.common.bytes
import app.bosunarr.ui.common.date
import app.bosunarr.ui.common.parseInstant
import app.bosunarr.ui.common.rememberLoader
import app.bosunarr.ui.common.runtime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MovieScreen(nav: NavController, entry: NavBackStackEntry, service: ServiceConfig, id: Int) {
    val key = libKey(service.id, LibKind.Movie, id)
    val loader = rememberLoader("movie:${service.id}:$id") { LibraryApi.movie(service, id) }
    val state = loader.state
    val d = state.value
    val cached = remember(key) { LibraryStore.items[key] }
    val snackbar = LocalSnackbar.current
    val list = rememberLazyListState()
    val busy = remember { mutableStateMapOf<String, Boolean>() }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    val m = d?.movie
    val title = m?.title ?: cached?.title ?: ""

    fun busyAction(tag: String, success: String?, block: suspend () -> Unit) {
        if (busy[tag] == true) return
        busy[tag] = true
        runAction(snackbar, success, onDone = { busy.remove(tag) }, block = block)
    }

    fun toggleMonitored() {
        val cur = m ?: return
        val to = !cur.monitored
        loader.mutate { it.copy(movie = it.movie.copy(monitored = to)) }
        LibraryStore.setMonitored(key, to)
        runAction(snackbar, null, onError = {
            loader.mutate { it.copy(movie = it.movie.copy(monitored = !to)) }
            LibraryStore.setMonitored(key, !to)
        }) { LibraryApi.setMovieMonitored(service, id, to) }
    }

    fun pickRelease() {
        val year = (m?.year ?: cached?.year)?.takeIf { it > 0 }?.toString()
        nav.navigate(ReleaseSearchRoute(service.id, title, year, movieId = id))
    }

    val uri = LocalUriHandler.current
    DetailScaffold(
        title, list, onBack = { nav.back() },
        menu = if (m == null) null else { close ->
            DropdownMenuItem(
                text = { Text("Interactive search") },
                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.ManageSearch, null) },
                onClick = { close(); pickRelease() },
            )
            m.titleSlug?.takeIf { it.isNotBlank() }?.let { slug ->
                DropdownMenuItem(
                    text = { Text("Open in ${service.name}") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null) },
                    onClick = { close(); runCatching { uri.openUri("${app.client.baseUrl(service).trimEnd('/')}/movie/$slug") } },
                )
            }
            DropdownMenuItem(
                text = { Text("Delete movie…", color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                enabled = busy["delete"] != true,
                onClick = { close(); confirmDelete = true },
            )
        },
    ) { p ->
        val pull = rememberPullToRefreshState()
        val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        // The spinner is for the user's pull only; refetches after an action happen quietly.
        val refreshing = state.userRefreshing && d != null
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { loader.pullRefresh() },
            state = pull,
            modifier = Modifier.fillMaxSize(),
            indicator = {
                PullToRefreshDefaults.Indicator(state = pull, isRefreshing = refreshing, modifier = Modifier.align(Alignment.TopCenter).padding(top = top + 64.dp))
            },
        ) {
            LazyColumn(state = list, contentPadding = PaddingValues(bottom = p.calculateBottomPadding() + 32.dp), modifier = Modifier.fillMaxSize()) {
                item("header") {
                    DetailHeader(
                        service,
                        fanart = m?.let { arrImage(service, it.images, "fanart", ownerId = it.id) },
                        poster = m?.let { arrImage(service, it.images, "poster", 500, it.id) } ?: cached?.poster,
                        title = title,
                        lines = listOf(
                            listOfNotNull((m?.year ?: cached?.year)?.takeIf { it > 0 }?.toString(), m?.certification?.takeIf { it.isNotBlank() }, m?.let { runtime(it.runtime) }).filter { it.isNotEmpty() }.joinToString(" · "),
                            m?.studio ?: cached?.network ?: "",
                        ),
                    )
                }
                when {
                    d == null && state.error != null -> item("error") { ErrorBox(state.error, Modifier.fillParentMaxSize(), onRetry = { loader.refresh() }) }
                    d == null || m == null -> item("loading") { LoadingBox() }
                    else -> {
                        item("actions") {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 16.dp)) {
                                DetailAction(if (m.monitored) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, if (m.monitored) "Monitored" else "Unmonitored", ::toggleMonitored, selected = m.monitored, toggle = true)
                                DetailAction(Icons.Rounded.Search, "Search", { busyAction("search", "Searching for ${m.title}") { LibraryApi.searchMovie(service, id) } }, busy = busy["search"] == true)
                                DetailAction(Icons.Rounded.Refresh, "Refresh", {
                                    busyAction("refresh", "Refreshing and rescanning") {
                                        LibraryApi.refreshMovie(service, id)
                                        app.scope.launch(Dispatchers.Main) { delay(5000); loader.refresh() }
                                    }
                                }, busy = busy["refresh"] == true)
                                DetailAction(Icons.AutoMirrored.Rounded.ManageSearch, "Pick release", ::pickRelease)
                            }
                        }
                        item("facts") {
                            val cs = MaterialTheme.colorScheme
                            val status = movieStatus(m)
                            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                when (status) {
                                    LibStatus.Downloaded -> Tag(status.label, container = cs.primaryContainer, content = cs.onPrimaryContainer)
                                    LibStatus.Missing -> Tag(status.label, container = cs.errorContainer, content = cs.onErrorContainer)
                                    else -> Tag(status.label)
                                }
                                m.movieFile?.quality?.quality?.name?.let { Tag(it) }
                                if (m.diskSize > 0) Tag(bytes(m.diskSize))
                                d.profile?.let { Tag(it) }
                                m.ratings?.best?.let { r -> Tag("★ ${String.format(Locale.getDefault(), "%.1f", r)}${m.ratings.source?.let { " $it" } ?: ""}") }
                            }
                        }
                        if (!m.overview.isNullOrBlank()) item("overview") { ExpandableText(m.overview, Modifier.padding(horizontal = 16.dp, vertical = 16.dp).fillMaxWidth()) }
                        if (m.genres.isNotEmpty()) item("genres") {
                            Text(
                                m.genres.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp),
                            )
                        }
                        item("info") {
                            InfoCard(
                                listOf(
                                    "Status" to releaseStatusLabel(m.status),
                                    "In cinemas" to m.inCinemas?.let { releaseDate(it) },
                                    "Digital" to m.digitalRelease?.let { releaseDate(it) },
                                    "Physical" to m.physicalRelease?.let { releaseDate(it) },
                                    "Available from" to releaseStatusLabel(m.minimumAvailability),
                                    "Added" to m.added?.let { date(it) },
                                    "Path" to m.path,
                                    "Instance" to service.name,
                                ),
                            )
                        }
                        m.movieFile?.let { f ->
                            item("file-title") { SectionTitle("File", Modifier.padding(top = 8.dp)) }
                            item("file") {
                                val mi = f.mediaInfo
                                val channels = mi?.audioChannels?.takeIf { it > 0 }?.let { String.format(Locale.US, "%.1f", it) }
                                InfoCard(
                                    listOf(
                                        "Quality" to f.quality?.quality?.name,
                                        "Size" to f.size.takeIf { it > 0 }?.let { bytes(it) },
                                        "Video" to listOfNotNull(mi?.resolution, mi?.videoCodec, mi?.videoDynamicRangeType?.takeIf { it.isNotBlank() }).filter { it.isNotBlank() }.joinToString(" · "),
                                        "Audio" to listOfNotNull(mi?.audioCodec, channels).filter { it.isNotBlank() }.joinToString(" "),
                                        "Languages" to f.languages.mapNotNull { it.name }.joinToString(", ").ifBlank { mi?.audioLanguages },
                                        "Subtitles" to mi?.subtitles,
                                        "Release group" to f.releaseGroup,
                                        "Imported" to f.dateAdded?.let { date(it) },
                                        "File" to (f.relativePath ?: f.path),
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        DeleteDialog(title, "movie", onConfirm = { files ->
            busyAction("delete", "Deleted $title") {
                LibraryApi.deleteMovie(service, id, files)
                LibraryStore.removed(key)
                nav.backFrom(entry)
            }
        }, onDismiss = { confirmDelete = false })
    }
}

/** Release dates are calendar dates stored as UTC midnight: show the date part as-is, with "(in 3 days)" when ahead. */
fun releaseDate(iso: String): String {
    val day = runCatching { java.time.LocalDate.parse(iso.take(10)) }.getOrNull() ?: return date(iso)
    val text = day.format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM))
    val ahead = parseInstant(iso)?.isAfter(Instant.now()) == true
    return if (ahead) {
        val days = java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.now(), day)
        "$text (${when { days <= 0L -> "today"; days == 1L -> "tomorrow"; else -> "in $days days" }})"
    } else text
}

private fun releaseStatusLabel(s: String?): String? = when (s) {
    null, "" -> null
    "tba" -> "TBA"
    "announced" -> "Announced"
    "inCinemas" -> "In cinemas"
    "released" -> "Released"
    "deleted" -> "Removed from TMDb"
    else -> s.replaceFirstChar { it.titlecase() }
}
