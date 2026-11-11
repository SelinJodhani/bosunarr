package app.bosunarr.feature.discover

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import app.bosunarr.data.ServiceConfig
import app.bosunarr.nav.MediaRoute
import app.bosunarr.net.userMessage
import app.bosunarr.ui.common.AppScaffold
import app.bosunarr.ui.common.ConfirmDialog
import app.bosunarr.ui.common.ErrorBox
import app.bosunarr.ui.common.LocalSnackbar
import app.bosunarr.ui.common.MessageBox
import app.bosunarr.ui.common.Poster
import app.bosunarr.ui.common.Tag
import app.bosunarr.ui.common.back
import app.bosunarr.ui.common.rememberLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/* ------------------------------------------------------------------ Filters */

/**
 * Seerr's `filter` values. Seerr has no "declined" filter, so Declined pages through `all` and keeps status 3.
 * Seerr's media-status filters (available / processing) look at the copy matching each request's is4k.
 */
enum class ReqFilter(val label: String, val api: String, val clientSide: Boolean = false) {
    All("All", "all"), Pending("Pending", "pending"), Approved("Approved", "approved"), Processing("Processing", "processing"),
    Available("Available", "available"), Failed("Failed", "failed"), Declined("Declined", "all", clientSide = true);

    /** Does this request (still) belong in this filter? Used for optimistic updates. */
    fun matches(r: SeerrRequest): Boolean = when (this) {
        All -> true
        Pending -> r.reqStatus == ReqStatus.Pending
        Approved -> r.reqStatus == ReqStatus.Approved
        Processing -> r.reqStatus == ReqStatus.Approved && r.ownCopy != Avail.Available
        Available -> r.ownCopy == Avail.Available
        Failed -> r.reqStatus == ReqStatus.Failed
        Declined -> r.reqStatus == ReqStatus.Declined
    }
}

data class ReqList(
    val items: List<SeerrRequest> = emptyList(),
    /** Raw rows consumed from Seerr (the next `skip`). */
    val skip: Int = 0,
    val hasMore: Boolean = false,
    val loaded: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    /** Consecutive client-filtered pages that added nothing (stop auto-paging after a few). */
    val emptyStreak: Int = 0,
)

class RequestsVm : ViewModel() {
    var filter by mutableStateOf(ReqFilter.Pending)
        private set
    val lists = mutableStateMapOf<ReqFilter, ReqList>()
    val busy = mutableStateMapOf<Int, Boolean>()
    private val jobs = HashMap<ReqFilter, Job>()

    fun list(f: ReqFilter = filter) = lists[f] ?: ReqList()

    fun select(s: ServiceConfig, f: ReqFilter) {
        filter = f
        val st = list(f)
        // Show what we have at once and quietly refresh it.
        if (!st.refreshing) refresh(s, f)
    }

    private fun pageSize(f: ReqFilter) = if (f.clientSide) 50 else 20

    /** Reload from the top, keeping as many rows as are loaded now (so the scroll position survives). */
    fun refresh(s: ServiceConfig, f: ReqFilter = filter) {
        jobs[f]?.cancel()
        val st = list(f)
        val take = st.skip.coerceIn(pageSize(f), 100)
        lists[f] = st.copy(refreshing = true, loadingMore = false, error = null)
        jobs[f] = viewModelScope.launch {
            try {
                val page = Seerr.requests(s, f.api, take, 0)
                val rows = if (f.clientSide) page.results.filter { f.matches(it) } else page.results
                lists[f] = ReqList(
                    items = rows.distinctBy { it.id }, skip = page.results.size,
                    hasMore = page.results.isNotEmpty() && page.results.size < page.pageInfo.results, loaded = true,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lists[f] = list(f).copy(refreshing = false, error = e.userMessage())
            }
        }
    }

    fun more(s: ServiceConfig, f: ReqFilter = filter) {
        val st = list(f)
        if (st.refreshing || st.loadingMore || !st.hasMore || st.error != null) return
        lists[f] = st.copy(loadingMore = true)
        jobs[f] = viewModelScope.launch {
            try {
                val page = Seerr.requests(s, f.api, pageSize(f), st.skip)
                val cur = list(f)
                val seen = cur.items.mapTo(HashSet()) { it.id }
                val fresh = page.results.filter { (!f.clientSide || f.matches(it)) && seen.add(it.id) }
                val skip = cur.skip + page.results.size
                lists[f] = cur.copy(
                    items = cur.items + fresh, skip = skip, loadingMore = false,
                    hasMore = page.results.isNotEmpty() && skip < page.pageInfo.results,
                    emptyStreak = if (fresh.isEmpty()) cur.emptyStreak + 1 else 0,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lists[f] = list(f).copy(loadingMore = false, error = e.userMessage())
            }
        }
    }

    /** A write happened elsewhere: refresh the visible filter, re-fetch the others when next shown. */
    fun invalidate(s: ServiceConfig) {
        refresh(s, filter)
    }

    /** Optimistic approve / decline / delete / retry, rolled back (with a snackbar) if Seerr refuses. */
    fun act(s: ServiceConfig, r: SeerrRequest, title: String, action: Seerr.Action, notify: (String) -> Unit) {
        if (busy[r.id] == true) return
        val updated: SeerrRequest? = when (action) {
            Seerr.Action.Approve, Seerr.Action.Retry -> r.copy(status = ReqStatus.Approved.code)
            Seerr.Action.Decline -> r.copy(status = ReqStatus.Declined.code)
            Seerr.Action.Delete -> null
        }
        // Where the row was in each list, for the rollback.
        val before = lists.mapValues { (_, st) -> st.items.indexOfFirst { it.id == r.id } }.filterValues { it >= 0 }
        for ((f, i) in before) {
            val st = list(f)
            val keep = updated != null && f.matches(updated)
            lists[f] = if (keep) st.copy(items = st.items.toMutableList().also { it[i] = updated })
            else st.copy(items = st.items.filterNot { it.id == r.id }, skip = (st.skip - 1).coerceAtLeast(0))
        }
        busy[r.id] = true
        viewModelScope.launch {
            try {
                Seerr.act(s, r.id, action)
                notify("${action.done} $title")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                for ((f, i) in before) {
                    val st = list(f)
                    val removed = st.items.none { it.id == r.id }
                    val items = st.items.filterNot { it.id == r.id }.toMutableList().also { it.add(i.coerceAtMost(it.size), r) }
                    lists[f] = st.copy(items = items, skip = if (removed) st.skip + 1 else st.skip)
                }
                notify("Couldn't ${action.name.lowercase()}: ${e.userMessage()}")
            } finally {
                busy.remove(r.id)
            }
        }
    }
}

/* ------------------------------------------------------------------ Screen */

@Composable
fun RequestsScreen(nav: NavController) {
    val list = rememberSeerrServices()
    val service = list?.let { currentSeerr(it) }
    if (list == null || service == null) {
        AppScaffold("Requests", onBack = { nav.back() }) { p -> if (list != null) NoSeerr(nav, Modifier.padding(p)) }
        return
    }
    key(service.id) { RequestsContent(nav, list, service) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RequestsContent(nav: NavController, all: List<ServiceConfig>, s: ServiceConfig) {
    val vm: RequestsVm = viewModel(key = "seerr-requests:${s.id}")
    val counts = rememberLoader("seerr-counts:${s.id}") { Seerr.count(s) }
    val manage = rememberLoader("seerr-manage:${s.id}") { Seerr.canManage(s) }
    // An API key acts as the Seerr admin; assume so until /auth/me says otherwise (no late-appearing buttons).
    val canManage = manage.value ?: true
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val notify: (String) -> Unit = { msg -> scope.launch { snackbar.showSnackbar(msg) } }

    LaunchedEffect(Unit) { if (!vm.list().loaded && !vm.list().refreshing) vm.refresh(s) }
    OnSeerrWrite { vm.invalidate(s); counts.refresh() }

    // The pull indicator is for pulls only; chip switches refresh quietly behind the cached rows.
    var pulled by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Triple<SeerrRequest, String, Seerr.Action>?>(null) }
    val filter = vm.filter
    val st = vm.list(filter)
    LaunchedEffect(st.refreshing) { if (!st.refreshing) pulled = false }

    AppScaffold("Requests", subtitle = if (all.size > 1) s.name else null, onBack = { nav.back() }, actions = { SeerrPicker(all, s) }) { p ->
        Column(Modifier.fillMaxSize().padding(top = p.calculateTopPadding())) {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ReqFilter.entries, key = { it.name }) { f ->
                    val n = counts.value?.pending?.takeIf { f == ReqFilter.Pending && it > 0 }
                    FilterChip(
                        selected = f == filter,
                        onClick = { if (f != filter) vm.select(s, f) },
                        label = { Text(if (n != null) "${f.label} · $n" else f.label) },
                        leadingIcon = if (f == filter) { { Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)) } } else null,
                    )
                }
            }
            PullToRefreshBox(
                isRefreshing = pulled && st.refreshing,
                onRefresh = { pulled = true; vm.refresh(s); counts.refresh() },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                // One list state per filter, so switching back keeps each one's position.
                val listState = key(filter) { rememberLazyListState() }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = p.calculateBottomPadding() + 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    when {
                        !st.loaded && st.error != null -> item(key = "error") { ErrorBox(st.error, onRetry = { vm.refresh(s) }) }
                        !st.loaded -> items(4, key = { "sk$it" }) { RequestSkeleton() }
                        st.items.isEmpty() && !st.hasMore -> item(key = "empty") {
                            if (filter == ReqFilter.Pending) MessageBox(Icons.Rounded.DoneAll, "All caught up", hint = "No requests are waiting for approval.")
                            else MessageBox(Icons.Rounded.Inbox, "No requests here", hint = if (filter == ReqFilter.All) null else "Nothing is ${filter.label.lowercase()} right now.")
                        }
                        else -> {
                            if (st.error != null) item(key = "banner") {
                                Text(st.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 4.dp))
                            }
                            items(st.items, key = { "req-${it.id}" }) { r ->
                                RequestRow(
                                    s, r, filter, canManage, busy = vm.busy[r.id] == true,
                                    onOpen = { r.media?.tmdbId?.takeIf { it > 0 }?.let { nav.navigate(MediaRoute(r.mediaType, it)) } },
                                    onAction = { title, action ->
                                        if (action == Seerr.Action.Decline || action == Seerr.Action.Delete) confirm = Triple(r, title, action)
                                        else vm.act(s, r, title, action, notify)
                                    },
                                )
                            }
                            if (st.hasMore) item(key = "more") {
                                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                    when {
                                        st.error != null -> TextButton(onClick = { vm.refreshError(); vm.more(s) }) { Text("Try again") }
                                        st.emptyStreak >= 3 && !st.loadingMore -> TextButton(onClick = { vm.resetStreak(); vm.more(s) }) { Text("Load more") }
                                        else -> {
                                            CircularProgressIndicator(Modifier.size(28.dp))
                                            LaunchedEffect(st.skip, st.refreshing, st.loadingMore) { vm.more(s) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    confirm?.let { (r, title, action) ->
        val del = action == Seerr.Action.Delete
        ConfirmDialog(
            title = if (del) "Delete this request?" else "Decline this request?",
            text = "$title${if (r.is4k) " (4K)" else ""} — requested by ${r.requestedBy?.label ?: "Unknown"}. " +
                if (del) "The request is removed from ${s.name}. Anything already downloaded stays." else "The requester will see it as declined.",
            confirm = if (del) "Delete" else "Decline",
            destructive = true,
            onConfirm = { vm.act(s, r, title, action, notify) },
            onDismiss = { confirm = null },
        )
    }
}

fun RequestsVm.refreshError() {
    lists[filter] = list().copy(error = null)
}

fun RequestsVm.resetStreak() {
    lists[filter] = list().copy(emptyStreak = 0)
}

/** One status per row: what's on disk wins over the request's own state; nothing that just repeats the filter. */
private fun rowStatus(r: SeerrRequest, filter: ReqFilter): Pair<String, Tone>? {
    val own = r.ownCopy
    if (own == Avail.Available || own == Avail.Partial) return if (filter == ReqFilter.Available && own == Avail.Available) null else own.label to own.tone
    // An HD request whose title is only on disk in 4K: say so rather than "Approved".
    val other = r.otherCopy
    if (!r.is4k && (other == Avail.Available || other == Avail.Partial) && r.reqStatus != ReqStatus.Declined) return copyLabel(other, true) to other.tone
    val st = r.reqStatus
    // "Completed" only counts when its own copy is there; otherwise say where that copy actually is.
    if (st == ReqStatus.Completed) return if (own == Avail.None) "Not available" to Tone.Wait else own.label to own.tone
    val redundant = when (filter) {
        ReqFilter.Pending -> st == ReqStatus.Pending
        ReqFilter.Approved, ReqFilter.Processing -> st == ReqStatus.Approved
        ReqFilter.Failed -> st == ReqStatus.Failed
        ReqFilter.Declined -> st == ReqStatus.Declined
        else -> false
    }
    if (redundant) return null
    if (st == ReqStatus.Approved && own == Avail.Processing) return "Processing" to Tone.Progress
    return st.label to st.tone
}

@Composable
private fun rememberTitle(s: ServiceConfig, type: String, tmdbId: Int): TitleInfo? =
    produceState(Seerr.cachedTitle(s, type, tmdbId), s.id, type, tmdbId) {
        if (value == null && tmdbId > 0) value = runCatchingCancellable { Seerr.title(s, type, tmdbId) }.getOrElse { TitleInfo(title = "TMDb $tmdbId") }
    }.value

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RequestRow(
    s: ServiceConfig,
    r: SeerrRequest,
    filter: ReqFilter,
    canManage: Boolean,
    busy: Boolean,
    onOpen: () -> Unit,
    onAction: (String, Seerr.Action) -> Unit,
) {
    val tmdbId = r.media?.tmdbId ?: 0
    val info = rememberTitle(s, r.mediaType, tmdbId)
    val title = info?.let { it.title ?: it.name } ?: "TMDb $tmdbId"
    val year = yearOf(info?.releaseDate ?: info?.firstAirDate)
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.clickable(onClick = onOpen).padding(12.dp)) {
            Poster(s, tmdbImage(info?.posterPath, "w185"), width = 64.dp, title = title)
            Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.weight(1f).padding(top = 2.dp)) {
                        if (info == null) Box(Modifier.fillMaxWidth(0.7f).height(18.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest))
                        else Text(
                            title + (year?.let { " ($it)" } ?: ""), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (canManage) RowMenu(enabled = !busy, onDelete = { onAction(title, Seerr.Action.Delete) })
                }
                val status = rowStatus(r, filter)
                if (status != null || r.is4k) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    status?.let { (t, tone) -> StatusBadge(t, tone) }
                    if (r.is4k) Tag("4K")
                }
                Text(
                    listOfNotNull(if (r.mediaType == "tv") "Series" else "Movie", seasonsLabel(r.seasons.map { it.seasonNumber })).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                RequesterLine(s, r)
                val mod = r.modifiedBy
                if (mod != null && mod.id != r.requestedBy?.id && (r.reqStatus == ReqStatus.Approved || r.reqStatus == ReqStatus.Declined)) {
                    Text("${r.reqStatus.label} by ${mod.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (canManage && (r.reqStatus == ReqStatus.Pending || r.reqStatus == ReqStatus.Failed)) {
                    Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        if (r.reqStatus == ReqStatus.Pending) {
                            OutlinedButton(onClick = { onAction(title, Seerr.Action.Decline) }, enabled = !busy) {
                                Icon(Icons.Rounded.Close, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Decline")
                            }
                            FilledTonalButton(onClick = { onAction(title, Seerr.Action.Approve) }, enabled = !busy) {
                                Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Approve")
                            }
                        } else {
                            FilledTonalButton(onClick = { onAction(title, Seerr.Action.Retry) }, enabled = !busy) {
                                Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Retry")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowMenu(enabled: Boolean, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.MoreVert, "More actions") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Delete request", color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                onClick = { open = false; onDelete() },
            )
        }
    }
}

@Composable
private fun RequestSkeleton() {
    val c = MaterialTheme.colorScheme.surfaceContainerHighest
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp)) {
            Box(Modifier.width(64.dp).height(96.dp).clip(RoundedCornerShape(12.dp)).background(c))
            Column(Modifier.weight(1f).padding(start = 12.dp, top = 2.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.fillMaxWidth(0.7f).height(18.dp).clip(RoundedCornerShape(4.dp)).background(c))
                Box(Modifier.width(80.dp).height(20.dp).clip(RoundedCornerShape(8.dp)).background(c))
                Box(Modifier.fillMaxWidth(0.5f).height(12.dp).clip(RoundedCornerShape(4.dp)).background(c))
            }
        }
    }
}
