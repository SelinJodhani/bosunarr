package app.bosunarr.feature.discover

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import app.bosunarr.data.ServiceConfig
import app.bosunarr.nav.MediaRoute
import app.bosunarr.nav.RequestsRoute
import app.bosunarr.ui.art.SeaScene
import app.bosunarr.ui.common.AppScaffold
import app.bosunarr.ui.common.ErrorBanner
import app.bosunarr.ui.common.ErrorBox
import app.bosunarr.ui.common.ListPadding
import app.bosunarr.ui.common.MessageBox
import app.bosunarr.ui.common.ScrollToTopOnReselect
import app.bosunarr.ui.common.friendlyMessage
import androidx.compose.foundation.lazy.rememberLazyListState
import app.bosunarr.ui.common.rememberLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun DiscoverScreen(nav: NavController) {
    val list = rememberSeerrServices()
    val service = list?.let { currentSeerr(it) }
    if (list == null || service == null) {
        AppScaffold("Discover") { p -> if (list != null) NoSeerr(nav, Modifier.padding(p)) }
        return
    }
    key(service.id) { DiscoverContent(nav, list, service) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscoverContent(nav: NavController, list: List<ServiceConfig>, s: ServiceConfig) {
    val home = rememberLoader("seerr-home:${s.id}") { Seerr.home(s) }
    val pending = rememberLoader("seerr-count:${s.id}", refreshMs = 60_000) { Seerr.count(s).pending }
    // Writes elsewhere (a request, an approval) change statuses and the pending count.
    OnSeerrWrite { pending.refresh(); home.refresh() }
    val vm: SearchVm = viewModel(key = "seerr-search:${s.id}")
    val keyboard = LocalSoftwareKeyboardController.current

    // Debounced search: 350 ms after typing stops; clearing applies at once.
    LaunchedEffect(vm.text) {
        val q = vm.text.trim().take(100)
        if (q == vm.state.query) return@LaunchedEffect
        if (q.isNotEmpty()) delay(350)
        vm.search(s, q)
    }
    BackHandler(enabled = vm.text.isNotEmpty()) { vm.text = "" }

    AppScaffold(
        "Discover",
        subtitle = if (list.size > 1) s.name else null,
        actions = {
            SeerrPicker(list, s)
            // A labelled tonal button (not a bare inbox icon), with the pending count as a badge.
            val n = pending.value ?: 0
            BadgedBox(
                badge = { if (n > 0) Badge { Text(if (n > 99) "99+" else "$n") } },
                modifier = Modifier.padding(end = 8.dp),
            ) {
                FilledTonalButton(
                    onClick = { nav.navigate(RequestsRoute) },
                    contentPadding = PaddingValues(start = 12.dp, end = 16.dp),
                    modifier = Modifier.height(36.dp).semantics { contentDescription = if (n > 0) "Requests, $n pending" else "Requests" },
                ) {
                    Icon(Icons.Rounded.Inbox, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Requests", maxLines = 1)
                }
            }
        },
    ) { p ->
        Column(Modifier.fillMaxSize().padding(top = p.calculateTopPadding())) {
            TextField(
                value = vm.text,
                onValueChange = { vm.text = it.take(100) },
                placeholder = { Text("Search movies and series") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = {
                    if (vm.text.isNotEmpty()) IconButton(onClick = { vm.text = "" }) { Icon(Icons.Rounded.Close, "Clear search") }
                },
                singleLine = true,
                shape = CircleShape,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    val q = vm.text.trim()
                    if (q != vm.state.query) vm.search(s, q)
                    keyboard?.hide()
                }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Box(Modifier.weight(1f)) {
                if (vm.text.isNotBlank() || vm.state.query.isNotEmpty()) SearchResults(nav, s, vm)
                else Home(nav, s, home, onRefresh = { home.pullRefresh(); pending.refresh() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Home(nav: NavController, s: ServiceConfig, home: app.bosunarr.ui.common.Loader<List<Seerr.HomeRow>>, onRefresh: () -> Unit) {
    val st = home.state
    // The spinner is for the user's pull only (not for the quiet reload after a request elsewhere).
    val list = rememberLazyListState()
    ScrollToTopOnReselect(list)
    PullToRefreshBox(isRefreshing = st.userRefreshing && st.value != null, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = ListPadding) {
            val rows = st.value
            when {
                rows != null -> {
                    if (st.error != null) item(key = "error") { ErrorBanner(st.error) }
                    if (rows.isEmpty()) item(key = "empty") { MessageBox(Icons.Rounded.SearchOff, "Nothing in sight", Modifier.fillParentMaxSize(), hint = "${s.name} didn't return any titles.", scene = SeaScene.Spyglass) }
                    items(rows, key = { it.id }) { row -> MediaCarousel(row.title, s, row.items, nav, Modifier.animateItem()) }
                }
                st.error != null -> item(key = "error") { ErrorBox(st.error, Modifier.fillParentMaxSize(), onRetry = { home.refresh() }) }
                else -> items(5, key = { "sk$it" }) { CarouselSkeleton() }
            }
        }
    }
}

@Composable
private fun SearchResults(nav: NavController, s: ServiceConfig, vm: SearchVm) {
    val st = vm.state
    // A fresh grid per query (saved per query too, so coming back from a detail keeps the position).
    val grid = key(st.query) { rememberLazyGridState() }
    ScrollToTopOnReselect(grid)
    val nearEnd by remember(grid) {
        derivedStateOf {
            val info = grid.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 6
        }
    }
    LaunchedEffect(nearEnd, st.page, st.loading, st.error) {
        if (nearEnd && !st.loading && st.error == null && st.page in 1 until st.totalPages) vm.more(s)
    }
    val waiting = st.query != vm.text.trim().take(100) && st.items.isEmpty()
    if (!st.loading && !waiting && st.error == null && st.page >= 1 && st.items.isEmpty()) {
        MessageBox(Icons.Rounded.SearchOff, "Nothing in sight", hint = "No results for “${st.query}”.", scene = SeaScene.Spyglass, fullBleed = false)
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(104.dp),
        state = grid,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(st.items, key = { it.key }) { item ->
            MediaCard(s, item, onClick = { nav.navigate(MediaRoute(item.mediaType, item.tmdbId)) }, Modifier.animateItem(), width = null)
        }
        if (st.loading || (waiting && st.error == null)) item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        if (st.error != null) item(key = "error", span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(friendlyMessage(st.error), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { vm.retry(s) }) { Text("Try again") }
            }
        }
    }
}

data class SearchState(
    val serviceId: String? = null,
    val query: String = "",
    val items: List<MediaItem> = emptyList(),
    /** Last page loaded (0 = none yet). */
    val page: Int = 0,
    val totalPages: Int = 1,
    val loading: Boolean = false,
    val error: String? = null,
)

class SearchVm : ViewModel() {
    var text by mutableStateOf("")
    var state by mutableStateOf(SearchState())
        private set
    private var job: Job? = null

    fun search(s: ServiceConfig, q: String) {
        job?.cancel()
        state = SearchState(serviceId = s.id, query = q)
        if (q.isNotEmpty()) load(s, 1)
    }

    fun more(s: ServiceConfig) {
        val st = state
        if (st.loading || st.error != null || st.page >= st.totalPages || st.page >= 500) return
        load(s, st.page + 1)
    }

    fun retry(s: ServiceConfig) {
        state = state.copy(error = null)
        load(s, state.page + 1)
    }

    private fun load(s: ServiceConfig, page: Int) {
        val q = state.query
        state = state.copy(loading = true, error = null)
        job = viewModelScope.launch {
            state = try {
                val r = Seerr.search(s, q, page)
                val seen = state.items.mapTo(HashSet()) { it.key }
                val fresh = r.results.toItems(seen = seen)
                Peek.remember(fresh)
                state.copy(items = state.items + fresh, page = page, totalPages = r.totalPages.coerceAtLeast(page), loading = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.copy(loading = false, error = e.friendlyMessage())
            }
        }
    }
}
