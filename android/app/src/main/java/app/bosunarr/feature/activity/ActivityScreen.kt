package app.bosunarr.feature.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import app.bosunarr.app
import app.bosunarr.data.ServiceConfig
import app.bosunarr.data.ServiceType
import app.bosunarr.feature.activity.qbit.Torrent
import app.bosunarr.feature.activity.queue.QueueEntry
import app.bosunarr.ui.art.SeaScene
import app.bosunarr.ui.common.Loader
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.lazy.LazyListScope
import app.bosunarr.nav.MovieRoute
import app.bosunarr.nav.SeriesRoute
import app.bosunarr.nav.ServiceRoute
import app.bosunarr.nav.SettingsRoute
import app.bosunarr.ui.common.AppScaffold
import app.bosunarr.ui.common.ErrorBanner
import app.bosunarr.ui.common.ListPadding
import app.bosunarr.ui.common.LoadingBox
import app.bosunarr.ui.common.LocalSnackbar
import app.bosunarr.ui.common.MessageBox
import app.bosunarr.ui.common.OnTabReselected
import app.bosunarr.feature.usenet.UsenetQueueList
import app.bosunarr.feature.usenet.isUsenet
import app.bosunarr.ui.common.rememberLoader
import kotlinx.coroutines.launch

/** Activity tab: Sonarr/Radarr queues and every qBittorrent's torrents, live. */
@Composable
fun ActivityScreen(nav: NavController) {
    val appState by app.state.collectAsState()
    val services by app.services.collectAsState()
    val arrs = remember(services) { services.filter { it.serviceType == ServiceType.Sonarr || it.serviceType == ServiceType.Radarr || it.serviceType == ServiceType.Whisparr } }
    val qbits = remember(services) { services.filter { it.serviceType == ServiceType.QBittorrent } }
    val usenet = remember(services) { services.filter { isUsenet(it.serviceType) } }
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()

    val queueLoader = rememberLoader("activity:queue:" + arrs.joinToString(",") { it.id }, refreshMs = 5_000) { loadQueue(arrs) }
    val torrentLoader = rememberLoader("activity:torrents:" + qbits.joinToString(",") { it.id }, refreshMs = 2_500) { loadTorrents(qbits) }
    val torrents = rememberTorrentController { torrentLoader.refresh() }
    val queue = rememberQueueController { queueLoader.refresh() }

    // Queue and Torrents always; Usenet only when SABnzbd/NZBGet is configured.
    val pageCount = if (usenet.isNotEmpty()) 3 else 2
    val pager = rememberPagerState { pageCount }
    val usenetList = rememberLazyListState()
    val queueList = rememberLazyListState()
    val torrentList = rememberLazyListState()
    val listState = rememberTorrentListState("activity")

    var showAdd by rememberSaveable { mutableStateOf(false) }
    var addInitial by rememberSaveable { mutableStateOf<String?>(null) }

    // A magnet shared into the app opens the Add sheet, pre-filled.
    val pending by app.pendingTorrent.collectAsState()
    LaunchedEffect(pending, appState != null, qbits.isEmpty()) {
        val link = pending ?: return@LaunchedEffect
        if (appState == null) return@LaunchedEffect
        app.pendingTorrent.value = null
        if (qbits.isEmpty()) {
            snackbar.showSnackbar("Add a qBittorrent service in Settings to add torrents")
            return@LaunchedEffect
        }
        addInitial = link
        showAdd = true
        pager.animateScrollToPage(1)
    }

    LaunchedEffect(pager.currentPage) { if (pager.currentPage != 1) listState.clearSelection() }

    val torrentData = torrentLoader.value
    val queueData = queueLoader.value
    val noServices = appState != null && arrs.isEmpty() && qbits.isEmpty() && usenet.isEmpty()

    AppScaffold(
        title = "Activity",
        actions = {
            if (!noServices) OverflowMenu(
                qbits = qbits,
                hasTorrents = !torrentData?.torrents.isNullOrEmpty(),
                onPauseAll = { torrentData?.let { torrents.stopAll(it.torrents) } },
                onResumeAll = { torrentData?.let { torrents.startAll(it.torrents) } },
                onOpenService = { nav.navigate(ServiceRoute(it.id)) },
                onSettings = { nav.navigate(SettingsRoute) },
            )
        },
        floatingActionButton = {
            if (qbits.isNotEmpty() && !listState.selecting) {
                val atTop by remember {
                    derivedStateOf {
                        val l = if (pager.currentPage == 0) queueList else torrentList
                        l.firstVisibleItemIndex == 0
                    }
                }
                ExtendedFloatingActionButton(
                    text = { Text("Add torrent") },
                    icon = { Icon(Icons.Rounded.Add, null) },
                    onClick = { addInitial = null; showAdd = true },
                    expanded = atTop,
                )
            }
        },
    ) { p ->
        Box(Modifier.padding(p).fillMaxSize()) {
            when {
                appState == null -> LoadingBox()
                noServices -> MessageBox(
                    Icons.Rounded.Download,
                    "Nothing to show yet",
                    hint = "Add Sonarr, Radarr or qBittorrent in Settings to see downloads here.",
                    action = "Open Settings",
                    onAction = { nav.navigate(SettingsRoute) },
                    scene = SeaScene.Harbor,
                )
                else -> Column(Modifier.fillMaxSize()) {
                    SecondaryTabRow(selectedTabIndex = pager.currentPage) {
                        (listOf("Queue" to queueData?.let { queue.apply(it.entries).size }, "Torrents" to torrentData?.let { torrents.apply(it.torrents).size }) +
                            (if (usenet.isNotEmpty()) listOf("Usenet" to null) else emptyList()))
                            .forEachIndexed { i, (label, count) ->
                                Tab(
                                    selected = pager.currentPage == i,
                                    onClick = { scope.launch { pager.animateScrollToPage(i) } },
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(label, maxLines = 1)
                                            if (count != null && count > 0) Badge(
                                                containerColor = if (pager.currentPage == i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                                                contentColor = if (pager.currentPage == i) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            ) { Text(count.toString(), style = MaterialTheme.typography.labelSmall.tnum()) }
                                        }
                                    },
                                )
                            }
                    }
                    HorizontalPager(pager, Modifier.weight(1f), beyondViewportPageCount = 1, key = { it }) { page ->
                        if (page == 0) QueuePage(arrs, queueLoader, queue, queueList, nav)
                        else if (page == 2) UsenetQueueList(usenet, Modifier.fillMaxSize())
                        else TorrentsPage(qbits, torrentLoader, torrents, listState, torrentList, nav, onAdd = { addInitial = null; showAdd = true })
                    }
                    // Re-tapping the Activity tab scrolls the visible page back to the top.
                    OnTabReselected {
                        val l = if (pager.currentPage == 0) queueList else torrentList
                        if (l.firstVisibleItemIndex > 30) l.scrollToItem(0) else l.animateScrollToItem(0)
                    }
                }
            }
        }
    }

    if (showAdd && qbits.isNotEmpty()) {
        AddTorrentSheet(
            clients = qbits,
            initial = addInitial,
            onDismiss = { showAdd = false; addInitial = null },
            onAdded = { msg -> torrents.show(msg); torrentLoader.refresh() },
        )
    }
}

@Composable
private fun OverflowMenu(
    qbits: List<ServiceConfig>,
    hasTorrents: Boolean,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
    onOpenService: (ServiceConfig) -> Unit,
    onSettings: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, "More") }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            if (qbits.isNotEmpty()) {
                DropdownMenuItem(text = { Text("Resume all torrents") }, leadingIcon = { Icon(Icons.Rounded.PlayArrow, null) }, enabled = hasTorrents, onClick = { open = false; onResumeAll() })
                DropdownMenuItem(text = { Text("Pause all torrents") }, leadingIcon = { Icon(Icons.Rounded.Pause, null) }, enabled = hasTorrents, onClick = { open = false; onPauseAll() })
                HorizontalDivider()
                qbits.forEach { s ->
                    DropdownMenuItem(
                        text = { Text(s.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Icon(Icons.Rounded.Dns, null) },
                        onClick = { open = false; onOpenService(s) },
                    )
                }
                HorizontalDivider()
            }
            DropdownMenuItem(text = { Text("Settings") }, leadingIcon = { Icon(Icons.Rounded.Settings, null) }, onClick = { open = false; onSettings() })
        }
    }
}

@Composable
private fun QueuePage(
    arrs: List<ServiceConfig>,
    loader: Loader<QueueData>,
    controller: QueueController,
    listState: LazyListState,
    nav: NavController,
) {
    if (arrs.isEmpty()) {
        MessageBox(
            Icons.Rounded.DownloadDone,
            "No Sonarr or Radarr",
            hint = "Add Sonarr or Radarr in Settings to see their download queues here.",
            action = "Open Settings",
            onAction = { nav.navigate(SettingsRoute) },
            scene = SeaScene.Harbor,
        )
        return
    }
    val expanded = rememberExpandedState()
    var removing by remember { mutableStateOf<QueueEntry?>(null) }
    PolledContent(loader) { data ->
        LaunchedEffect(data) { controller.prune(data.entries) }
        val list = controller.apply(data.entries)
        val unreachable = list.isEmpty() && data.errors.size >= arrs.size
        val error = (errorText(data.errors) ?: loader.state.error).takeIf { !unreachable }
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = ListPadding) {
            if (error != null) item("error") { ErrorBanner(error, Modifier.padding(top = 8.dp)) }
            if (unreachable) item("unreachable") {
                MessageBox(
                    Icons.Rounded.CloudOff,
                    "Foggy waters",
                    hint = "Can't reach ${data.errors.joinToString(", ") { it.service }}. " +
                        (data.errors.firstOrNull()?.message?.takeUnless { "unreachable" in it || "timed out" in it } ?: "Check your network or VPN."),
                    action = "Try again",
                    onAction = { loader.refresh() },
                    scene = SeaScene.Fog,
                    modifier = Modifier.fillParentMaxSize(),
                )
            } else if (list.isEmpty()) item("empty") {
                MessageBox(
                    Icons.Rounded.DownloadDone,
                    "Calm seas",
                    hint = "Nothing downloading. New grabs from ${arrNames(arrs)} sail in here.",
                    modifier = Modifier.fillParentMaxSize().animateItem(),
                    scene = SeaScene.CalmSeas,
                )
            }
            queueRows(
                list = list,
                expanded = expanded,
                onToggleExpanded = { e -> expanded[e.key] = expanded[e.key] != true },
                onOpen = { e ->
                    val id = e.libraryId ?: return@queueRows
                    nav.navigate(if (e.isEpisode) SeriesRoute(e.serviceId, id) else MovieRoute(e.serviceId, id))
                },
                onRemove = { removing = it },
            )
        }
    }
    removing?.let { e ->
        RemoveQueueDialog(e, onConfirm = { fromClient, blocklist -> controller.remove(e, fromClient, blocklist) }, onDismiss = { removing = null })
    }
}

@Composable
private fun TorrentsPage(
    qbits: List<ServiceConfig>,
    loader: Loader<TorrentData>,
    controller: TorrentController,
    state: TorrentListState,
    listState: LazyListState,
    nav: NavController,
    onAdd: () -> Unit,
) {
    if (qbits.isEmpty()) {
        MessageBox(
            Icons.Rounded.Dns,
            "No download client",
            hint = "Add a qBittorrent service in Settings to manage torrents here.",
            action = "Open Settings",
            onAction = { nav.navigate(SettingsRoute) },
            scene = SeaScene.Harbor,
        )
        return
    }
    PolledContent(loader) { data ->
        TorrentListBody(
            data = data,
            controller = controller,
            state = state,
            listState = listState,
            showClient = qbits.size > 1,
            error = errorText(data.errors).takeIf { data.clients.isNotEmpty() } ?: loader.state.error,
            onAdd = onAdd,
            onRetry = { loader.refresh() },
            header = {
                if (data.clients.isNotEmpty()) item("summary") {
                    TransferSummary(
                        data,
                        Modifier.animateItem(),
                        // One client: its own screen has the full transfer stats and the alt-speed switch.
                        onOpen = qbits.singleOrNull()?.let { s -> { nav.navigate(ServiceRoute(s.id)) } },
                    )
                }
            },
        )
    }
}

/**
 * Toolbar + rows + detail sheet for a set of torrents (Activity: all clients; service screen: one).
 * [header] goes above the toolbar; [filter] narrows the list before search and chips.
 */
@Composable
fun TorrentListBody(
    data: TorrentData,
    controller: TorrentController,
    state: TorrentListState,
    listState: LazyListState,
    showClient: Boolean,
    error: String?,
    onAdd: (() -> Unit)?,
    onRetry: () -> Unit,
    filter: ((Torrent) -> Boolean)? = null,
    onResetFilter: (() -> Unit)? = null,
    leadingChips: @Composable RowScope.() -> Unit = {},
    header: LazyListScope.() -> Unit = {},
) {
    LaunchedEffect(data) { controller.prune(data.torrents) }
    val all = controller.apply(data.torrents)
    val base = remember(all, filter) { if (filter != null) all.filter(filter) else all }
    val searched = remember(base, state.query) { state.searched(base) }
    val visible = remember(searched, state.filter, state.sort) { state.visible(searched) }
    var openKey by rememberSaveable { mutableStateOf<String?>(null) }
    val open = openKey?.let { k -> all.firstOrNull { it.key == k } }
    LaunchedEffect(openKey, open == null) { if (openKey != null && open == null) openKey = null }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = ListPadding) {
            if (error != null) item("error") { ErrorBanner(error, Modifier.padding(top = 8.dp)) }
            header()
            val unreachable = all.isEmpty() && data.clients.isEmpty() && data.errors.isNotEmpty()
            // Search and status chips only mean something once there are torrents to narrow down.
            if (!unreachable && all.isNotEmpty()) item("toolbar") { TorrentToolbar(state, searched, Modifier.padding(vertical = 4.dp).animateItem(), leadingChips) }
            when {
                unreachable -> item("unreachable") {
                    MessageBox(
                        Icons.Rounded.CloudOff,
                        "Foggy waters",
                        hint = "Can't reach ${data.errors.joinToString(", ") { it.service }}. " +
                            (data.errors.first().message.takeUnless { "unreachable" in it || "timed out" in it } ?: "Check your network or VPN."),
                        action = "Try again",
                        onAction = onRetry,
                        scene = SeaScene.Fog,
                        modifier = Modifier.fillParentMaxSize(),
                    )
                }
                // The screen's FAB is the one way to add: no second button in the empty state.
                all.isEmpty() -> item("empty") {
                    MessageBox(
                        Icons.Rounded.Download,
                        "Empty dock",
                        hint = if (onAdd != null) "No torrents yet. Tap Add torrent to paste a magnet link or pick a .torrent file." else "This client has no torrents.",
                        modifier = Modifier.fillParentMaxSize().animateItem(),
                        scene = SeaScene.EmptyDock,
                    )
                }
                visible.isEmpty() -> item("nomatch") {
                    MessageBox(
                        Icons.Rounded.SearchOff,
                        when {
                            state.query.isNotBlank() -> "No torrents match “${state.query.trim()}”"
                            state.filter != TorrentFilter.All -> "No ${state.filter.label.lowercase()} torrents"
                            else -> "No torrents here"
                        },
                        action = "Show all",
                        onAction = { state.query = ""; state.filter = TorrentFilter.All; onResetFilter?.invoke() },
                        scene = SeaScene.Spyglass,
                        artSize = 120.dp,
                    )
                }
            }
            torrentRows(visible, state, showClient, onOpen = { openKey = it.key })
        }
        SelectionBar(
            state, base, controller,
            categories = { t -> data.categoriesOf(t.serviceId) },
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        )
    }

    if (open != null) {
        TorrentDetailSheet(open, data.categoriesOf(open.serviceId), controller, onDismiss = { openKey = null })
    }
}

/** "Sonarr and Radarr", "Sonarr", or the configured names when there are several of a kind. */
private fun arrNames(arrs: List<ServiceConfig>): String {
    val kinds = arrs.mapNotNull { it.serviceType }.distinct()
    val names = if (kinds.size == arrs.size) kinds.map { it.label } else arrs.map { it.name }
    return when (names.size) {
        0 -> "Sonarr and Radarr"
        1 -> names[0]
        else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
    }
}
