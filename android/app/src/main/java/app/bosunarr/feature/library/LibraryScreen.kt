package app.bosunarr.feature.library

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import app.bosunarr.app
import app.bosunarr.data.ServiceConfig
import app.bosunarr.data.ServiceType
import app.bosunarr.nav.LibraryAddRoute
import app.bosunarr.nav.MovieRoute
import app.bosunarr.nav.SeriesRoute
import app.bosunarr.nav.SettingsRoute
import app.bosunarr.ui.art.SeaScene
import app.bosunarr.ui.common.FullScreenScroll
import app.bosunarr.ui.common.AppScaffold
import app.bosunarr.ui.common.ErrorBanner
import app.bosunarr.ui.common.ErrorBox
import app.bosunarr.ui.common.LoadingBox
import app.bosunarr.ui.common.MessageBox
import app.bosunarr.ui.common.ScrollToTopOnReselect
import app.bosunarr.ui.common.rememberLoader
import java.util.Locale

/** Adult (Whisparr) titles never mix into All/Movies; they only show under their own chip. */
private enum class KindFilter(val label: String) { All("All"), Series("Series"), Movies("Movies"), Adult("Adult") }
private enum class StatusFilter(val label: String) { Any("Any"), Monitored("Monitored"), Missing("Missing"), Downloaded("Downloaded"), Unmonitored("Unmonitored") }
private enum class SortKey(val label: String) { Title("Title"), Added("Recently added"), Year("Year"), Size("Size on disk") }

private fun count(n: Int) = String.format(Locale.getDefault(), "%,d", n)

@Composable
fun LibraryScreen(nav: NavController) {
    val loaded by app.state.collectAsState()
    val all by app.services.collectAsState()
    val arrs = remember(all) { all.filter { it.serviceType == ServiceType.Sonarr || it.serviceType == ServiceType.Radarr || it.serviceType == ServiceType.Whisparr } }
    when {
        loaded == null -> AppScaffold("Library") { p -> LoadingBox(Modifier.padding(p)) }
        arrs.isEmpty() -> AppScaffold("Library") { p ->
            FullScreenScroll(Modifier.padding(p)) { m ->
                MessageBox(
                    Icons.Rounded.Dns, "No Sonarr or Radarr yet", m,
                    hint = "Add a Sonarr or Radarr service to browse your series and movies here.",
                    action = "Open Settings", onAction = { nav.navigate(SettingsRoute) },
                    scene = SeaScene.Harbor,
                )
            }
        }
        else -> LibraryContent(nav, arrs)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryContent(nav: NavController, arrs: List<ServiceConfig>) {
    val key = remember(arrs) { arrs.joinToString("|") { "${it.id}@${it.url}#${it.apiKey.hashCode()}" }.hashCode() }
    val loader = rememberLoader("library:$key") { LibraryApi.library(arrs) }
    val state = loader.state

    // Something was added elsewhere: refetch (old data stays on screen meanwhile).
    val version by LibraryStore.version.collectAsState()
    var seenVersion by rememberSaveable { mutableIntStateOf(version) }
    LaunchedEffect(version) {
        if (version != seenVersion) {
            seenVersion = version
            loader.refresh()
        }
    }
    val overrides by LibraryStore.overrides.collectAsState()

    var query by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(KindFilter.All) }
    var status by rememberSaveable { mutableStateOf(StatusFilter.Any) }
    var sort by rememberSaveable { mutableStateOf(SortKey.Title) }
    var sortOpen by remember { mutableStateOf(false) }

    val hasSonarr = arrs.any { it.serviceType == ServiceType.Sonarr }
    val hasRadarr = arrs.any { it.serviceType == ServiceType.Radarr }
    val hasWhisparr = arrs.any { it.serviceType == ServiceType.Whisparr }
    val adultIds = remember(arrs) { arrs.filter { it.serviceType == ServiceType.Whisparr }.map { it.id }.toSet() }
    // With only Whisparr configured there's nothing else to show, so "All" includes it.
    val allIncludesAdult = !hasSonarr && !hasRadarr
    val byId = remember(arrs) { arrs.associateBy { it.id } }

    val data = state.value
    val loadedItems = remember(data, overrides) { data?.let { LibraryStore.apply(it.items, it.fetchedAt, overrides) } ?: emptyList() }
    // The pool the current kind draws from: adult titles only under the Adult chip.
    val everything = remember(loadedItems, kind, adultIds) {
        when {
            kind == KindFilter.Adult -> loadedItems.filter { it.serviceId in adultIds }
            allIncludesAdult -> loadedItems
            else -> loadedItems.filter { it.serviceId !in adultIds }
        }
    }
    val shown = remember(everything, query, kind, status, sort) {
        val needle = query.trim().lowercase()
        val list = everything.filter { i ->
            when (kind) {
                KindFilter.All, KindFilter.Adult -> true
                KindFilter.Series -> i.kind == LibKind.Series
                KindFilter.Movies -> i.kind == LibKind.Movie
            } &&
                when (status) {
                    StatusFilter.Any -> true
                    StatusFilter.Monitored -> i.monitored
                    StatusFilter.Unmonitored -> !i.monitored
                    StatusFilter.Missing -> i.status == LibStatus.Missing || i.status == LibStatus.Partial
                    StatusFilter.Downloaded -> i.status == LibStatus.Downloaded
                } &&
                (needle.isEmpty() || i.title.lowercase().contains(needle) || i.year?.toString() == needle)
        }
        when (sort) {
            SortKey.Title -> list
            SortKey.Added -> list.sortedByDescending { it.added.orEmpty() }
            SortKey.Year -> list.sortedByDescending { it.year ?: 0 }
            SortKey.Size -> list.sortedByDescending { it.sizeOnDisk }
        }
    }
    val narrowed = query.isNotBlank() || kind != KindFilter.All || status != StatusFilter.Any
    val subtitle = when {
        data == null -> null
        narrowed -> "${count(shown.size)} of ${count(everything.size)}"
        else -> listOfNotNull(
            if (hasSonarr) "${count(everything.count { it.kind == LibKind.Series })} series" else null,
            if (hasRadarr) everything.count { it.kind == LibKind.Movie }.let { "${count(it)} ${if (it == 1) "movie" else "movies"}" } else null,
        ).joinToString(" · ")
    }

    val grid = rememberLazyGridState()
    // Back to the top when the filters change (but not when returning from a detail screen).
    val signature = "$query|$kind|$status|$sort"
    var lastSignature by rememberSaveable { mutableStateOf(signature) }
    LaunchedEffect(signature) {
        if (signature != lastSignature) {
            lastSignature = signature
            grid.scrollToItem(0)
        }
    }

    val addKind = when {
        kind == KindFilter.Adult -> "adult"
        kind == KindFilter.Movies -> "movie"
        kind == KindFilter.Series -> "series"
        hasSonarr -> "series"
        else -> "movie"
    }
    val keyboard = LocalSoftwareKeyboardController.current

    AppScaffold(
        "Library",
        subtitle = subtitle,
        actions = {
            Box {
                IconButton(onClick = { sortOpen = true }) { Icon(Icons.AutoMirrored.Rounded.Sort, "Sort") }
                DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                    SortKey.entries.forEach { k ->
                        DropdownMenuItem(
                            text = { Text(k.label) },
                            onClick = { sort = k; sortOpen = false },
                            trailingIcon = { if (k == sort) Icon(Icons.Rounded.Check, "Selected") },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            // Labelled at the top, a compact icon FAB as soon as the grid scrolls (so it covers less artwork).
            val atTop by remember { derivedStateOf { grid.firstVisibleItemIndex == 0 && grid.firstVisibleItemScrollOffset < 48 } }
            ExtendedFloatingActionButton(
                onClick = { nav.navigate(LibraryAddRoute(addKind)) },
                expanded = atTop,
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text("Add") },
            )
        },
    ) { p ->
        ScrollToTopOnReselect(grid)
        Column(Modifier.padding(p).fillMaxSize()) {
            TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search library") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear search") }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            val chipScroll = rememberScrollState()
            Row(
                Modifier.fillMaxWidth().horizontalFadingEdges(chipScroll).horizontalScroll(chipScroll).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val kinds = KindFilter.entries.filter {
                    when (it) {
                        KindFilter.All -> true
                        KindFilter.Series -> hasSonarr
                        KindFilter.Movies -> hasRadarr
                        KindFilter.Adult -> hasWhisparr
                    }
                }
                if (kinds.size > 2) {
                    kinds.forEach { k ->
                        FilterChip(
                            selected = kind == k,
                            onClick = { kind = k },
                            label = { Text(k.label) },
                            leadingIcon = if (kind == k) ({ Icon(Icons.Rounded.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }) else null,
                        )
                    }
                    VerticalDivider(Modifier.height(24.dp).padding(horizontal = 2.dp))
                }
                StatusFilter.entries.filter { it != StatusFilter.Any }.forEach { f ->
                    FilterChip(
                        selected = status == f,
                        onClick = { status = if (status == f) StatusFilter.Any else f },
                        label = { Text(f.label) },
                        leadingIcon = if (status == f) ({ Icon(Icons.Rounded.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }) else null,
                    )
                }
            }
            val partial = data?.errors.orEmpty()
            if (partial.isNotEmpty()) ErrorBanner(partial.joinToString("\n") { it.message }, Modifier.padding(top = 4.dp))
            if (data != null && state.error != null) ErrorBanner(state.error, Modifier.padding(top = 4.dp))

            PullToRefreshBox(
                // Only for the user's pull: refetches after an add elsewhere happen quietly.
                isRefreshing = state.userRefreshing && data != null,
                onRefresh = { loader.pullRefresh() },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                when {
                    data == null && state.error != null -> FullScreenScroll { m ->
                        ErrorBox(state.error, m, onRetry = { loader.refresh() })
                    }
                    data == null -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(100.dp),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        userScrollEnabled = false,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(18) { SkeletonCard() }
                    }
                    shown.isEmpty() && everything.isEmpty() -> FullScreenScroll { m ->
                        MessageBox(
                            Icons.Rounded.VideoLibrary, "Your library is empty", m,
                            hint = "Add a series or movie to start building it.",
                            action = "Add", onAction = { nav.navigate(LibraryAddRoute(addKind)) },
                            scene = SeaScene.Harbor,
                        )
                    }
                    shown.isEmpty() -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                        run {
                            MessageBox(
                                Icons.Rounded.SearchOff, "Nothing matches",
                                hint = if (query.isNotBlank()) "No ${kind.takeIf { it != KindFilter.All }?.label?.lowercase() ?: "titles"} match “${query.trim()}” with these filters." else "No titles match these filters.",
                                action = "Clear filters", onAction = { query = ""; kind = KindFilter.All; status = StatusFilter.Any },
                                scene = SeaScene.Spyglass,
                                fullBleed = false,
                            )
                            if (query.isNotBlank()) {
                                TextButton(onClick = { nav.navigate(LibraryAddRoute(addKind, query.trim())) }) {
                                    Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Search to add “${query.trim()}”")
                                }
                            }
                        }
                        Spacer(Modifier.height(96.dp))
                    }
                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(100.dp),
                        state = grid,
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 104.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(shown, key = { it.key }, contentType = { "poster" }) { item ->
                            PosterCard(
                                item, byId[item.serviceId],
                                onClick = {
                                    keyboard?.hide()
                                    if (item.kind == LibKind.Series) nav.navigate(SeriesRoute(item.serviceId, item.id))
                                    else nav.navigate(MovieRoute(item.serviceId, item.id))
                                },
                                modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null),
                            )
                        }
                    }
                }
            }
        }
    }
}
