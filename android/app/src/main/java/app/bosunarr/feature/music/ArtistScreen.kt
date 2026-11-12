package app.bosunarr.feature.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import app.bosunarr.app
import app.bosunarr.data.ServiceConfig
import app.bosunarr.feature.library.DeleteDialog
import app.bosunarr.feature.library.DetailAction
import app.bosunarr.feature.library.DetailHeader
import app.bosunarr.feature.library.DetailScaffold
import app.bosunarr.feature.library.ExpandableText
import app.bosunarr.feature.library.InfoCard
import app.bosunarr.feature.library.LibKind
import app.bosunarr.feature.library.LibStatus
import app.bosunarr.feature.library.LibraryStore
import app.bosunarr.feature.library.backFrom
import app.bosunarr.feature.library.libKey
import app.bosunarr.feature.library.releaseDate
import app.bosunarr.feature.library.runAction
import app.bosunarr.nav.AlbumRoute
import app.bosunarr.ui.common.ErrorBox
import app.bosunarr.ui.common.LoadingBox
import app.bosunarr.ui.common.LocalSnackbar
import app.bosunarr.ui.common.SectionTitle
import app.bosunarr.ui.common.Tag
import app.bosunarr.ui.common.back
import app.bosunarr.ui.common.bytes
import app.bosunarr.ui.common.date
import app.bosunarr.ui.common.plural
import app.bosunarr.ui.common.rememberLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/** Album-type filter on the artist screen ("Album", "EP", "Single"…); null = all. */
private fun albumTypes(albums: List<LidarrAlbum>): List<String> {
    val order = listOf("Album", "EP", "Single", "Broadcast", "Other")
    return albums.mapNotNull { it.albumType?.takeIf { t -> t.isNotBlank() } }.distinct().sortedBy { order.indexOf(it).let { i -> if (i < 0) 99 else i } }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ArtistScreen(nav: NavController, entry: NavBackStackEntry, service: ServiceConfig, id: Int) {
    val key = libKey(service.id, LibKind.Music, id)
    val loader = rememberLoader("artist:${service.id}:$id") { MusicApi.artist(service, id) }
    val state = loader.state
    val d = state.value
    val cached = remember(key) { LibraryStore.items[key] }
    val snackbar = LocalSnackbar.current
    val list = rememberLazyListState()
    val busy = remember { mutableStateMapOf<String, Boolean>() }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var typeFilter by rememberSaveable { mutableStateOf<String?>(null) }

    val a = d?.artist
    val title = a?.artistName ?: cached?.title ?: ""

    fun busyAction(tag: String, success: String?, block: suspend () -> Unit) {
        if (busy[tag] == true) return
        busy[tag] = true
        runAction(snackbar, success, onDone = { busy.remove(tag) }, block = block)
    }

    fun toggleArtist() {
        val cur = a ?: return
        val to = !cur.monitored
        loader.mutate { it.copy(artist = it.artist.copy(monitored = to)) }
        LibraryStore.setMonitored(key, to)
        runAction(snackbar, null, onError = {
            loader.mutate { it.copy(artist = it.artist.copy(monitored = !to)) }
            LibraryStore.setMonitored(key, !to)
        }) { MusicApi.setArtistMonitored(service, id, to) }
    }

    fun toggleAlbum(al: LidarrAlbum) {
        val to = !al.monitored
        loader.mutate { v -> v.copy(albums = v.albums.map { if (it.id == al.id) it.copy(monitored = to) else it }) }
        runAction(snackbar, null, onError = {
            loader.mutate { v -> v.copy(albums = v.albums.map { if (it.id == al.id) it.copy(monitored = !to) else it }) }
        }) {
            MusicApi.setAlbumsMonitored(service, listOf(al.id), to)
            loader.refresh() // the artist's statistics follow album monitoring
        }
    }

    val uri = LocalUriHandler.current
    DetailScaffold(
        title, list, onBack = { nav.back() },
        menu = if (a == null) null else { close ->
            a.foreignArtistId?.takeIf { it.isNotBlank() }?.let { mbid ->
                DropdownMenuItem(
                    text = { Text("Open in ${service.name}") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null) },
                    onClick = { close(); runCatching { uri.openUri("${app.client.baseUrl(service).trimEnd('/')}/artist/$mbid") } },
                )
            }
            DropdownMenuItem(
                text = { Text("Delete artist…", color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                enabled = busy["delete"] != true,
                onClick = { close(); confirmDelete = true },
            )
        },
    ) { p ->
        val pull = rememberPullToRefreshState()
        val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
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
                        fanart = a?.let { artistFanart(service, it) },
                        poster = a?.let { artistPoster(service, it) } ?: cached?.poster,
                        title = title,
                        lines = listOf(
                            listOfNotNull(
                                a?.artistType?.takeIf { it.isNotBlank() },
                                a?.disambiguation?.takeIf { it.isNotBlank() },
                                if (a?.ended == true || a?.status == "ended") "Ended" else null,
                            ).joinToString(" · "),
                            a?.statistics?.albumCount?.takeIf { it > 0 }?.let { plural(it, "album") } ?: cached?.albums?.takeIf { it > 0 }?.let { plural(it, "album") } ?: "",
                        ),
                        square = true,
                        placeholderIcon = Icons.Rounded.MusicNote,
                    )
                }
                when {
                    d == null && state.error != null -> item("error") { ErrorBox(state.error, Modifier.fillParentMaxSize(), onRetry = { loader.refresh() }) }
                    d == null || a == null -> item("loading") { LoadingBox() }
                    else -> {
                        item("actions") {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 16.dp)) {
                                DetailAction(if (a.monitored) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, if (a.monitored) "Monitored" else "Unmonitored", ::toggleArtist, selected = a.monitored, toggle = true)
                                DetailAction(Icons.Rounded.Search, "Search", { busyAction("search", "Searching for monitored albums") { MusicApi.searchArtist(service, id) } }, busy = busy["search"] == true)
                                DetailAction(Icons.Rounded.Refresh, "Refresh", {
                                    busyAction("refresh", "Refreshing and rescanning") {
                                        MusicApi.refreshArtist(service, id)
                                        app.scope.launch(Dispatchers.Main) { delay(5000); loader.refresh() }
                                    }
                                }, busy = busy["refresh"] == true)
                            }
                        }
                        item("facts") {
                            val cs = MaterialTheme.colorScheme
                            val st = a.statistics
                            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (st != null && st.trackCount > 0) {
                                    val done = st.trackFileCount >= st.trackCount
                                    Tag(
                                        "${st.trackFileCount}/${st.trackCount} tracks",
                                        container = if (done) cs.primaryContainer else cs.tertiaryContainer,
                                        content = if (done) cs.onPrimaryContainer else cs.onTertiaryContainer,
                                    )
                                }
                                if ((st?.sizeOnDisk ?: 0) > 0) Tag(bytes(st?.sizeOnDisk))
                                d.profile?.let { Tag(it) }
                                d.metadataProfile?.let { Tag(it) }
                                a.ratings?.value?.takeIf { it > 0 }?.let { Tag("★ ${String.format(Locale.getDefault(), "%.1f", it)}") }
                            }
                        }
                        if (!a.overview.isNullOrBlank()) item("overview") { ExpandableText(a.overview, Modifier.padding(horizontal = 16.dp, vertical = 16.dp).fillMaxWidth()) }
                        if (a.genres.isNotEmpty()) item("genres") {
                            Text(
                                a.genres.take(6).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp),
                            )
                        }
                        item("info") {
                            InfoCard(
                                listOf(
                                    "New albums" to a.monitorNewItems?.let { MONITOR_NEW_LABELS[it] ?: it },
                                    "Added" to a.added?.let { date(it) },
                                    "Path" to a.path,
                                    "Instance" to service.name,
                                ),
                            )
                        }
                        val types = albumTypes(d.albums)
                        val shown = d.albums.filter { typeFilter == null || it.albumType == typeFilter }
                        item("albums-title") { SectionTitle(if (d.albums.isEmpty()) "Albums" else "Albums (${d.albums.size})", Modifier.padding(top = 8.dp)) }
                        if (types.size > 1) item("album-types") {
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                types.forEach { t ->
                                    FilterChip(
                                        selected = typeFilter == t,
                                        onClick = { typeFilter = if (typeFilter == t) null else t },
                                        label = { Text(t) },
                                        leadingIcon = if (typeFilter == t) ({ Icon(Icons.Rounded.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }) else null,
                                    )
                                }
                            }
                        }
                        if (d.albums.isEmpty()) item("albums-empty") {
                            Text(
                                "No albums yet. Lidarr's metadata profile decides which releases are listed.",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        items(shown, key = { "al${it.id}" }, contentType = { "album" }) { al ->
                            AlbumRow(
                                service, al,
                                modifier = Modifier.animateItem(),
                                artistMonitored = a.monitored,
                                onClick = { nav.navigate(AlbumRoute(service.id, al.id)) },
                                onToggleMonitored = { toggleAlbum(al) },
                                searching = busy["al-${al.id}"] == true,
                                onSearch = { busyAction("al-${al.id}", "Searching for ${al.title}") { MusicApi.searchAlbums(service, listOf(al.id)) } },
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        DeleteDialog(title, "artist", onConfirm = { files ->
            busyAction("delete", "Deleted $title") {
                MusicApi.deleteArtist(service, id, files)
                LibraryStore.removed(key)
                nav.backFrom(entry)
            }
        }, onDismiss = { confirmDelete = false })
    }
}

@Composable
private fun AlbumRow(
    service: ServiceConfig,
    al: LidarrAlbum,
    modifier: Modifier = Modifier,
    artistMonitored: Boolean,
    onClick: () -> Unit,
    onToggleMonitored: () -> Unit,
    searching: Boolean,
    onSearch: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val status = albumStatus(al)
    val percent = albumPercent(al)
    val effective = al.monitored && artistMonitored
    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SquareCover(service, albumCover(service, al.images, al.id), 56.dp, al.title, Modifier.alpha(if (effective) 1f else 0.55f))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(al.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(
                al.releaseDate?.let { releaseDate(it) }?.takeIf { it.isNotBlank() },
                al.albumType?.takeIf { it.isNotBlank() && it != "Album" },
                al.statistics?.totalTrackCount?.takeIf { it > 0 }?.let { plural(it, "track") },
            ).joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            when {
                status == LibStatus.Upcoming -> Text("Not released yet", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                percent != null && percent in 1..99 -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(
                        progress = { percent / 100f },
                        modifier = Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = if (effective) cs.primary else cs.outline,
                        drawStopIndicator = {},
                        gapSize = 0.dp,
                    )
                    Text("$percent%", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                }
                percent == 100 -> Text("Downloaded", style = MaterialTheme.typography.labelSmall, color = cs.primary)
                effective && status == LibStatus.Missing -> Text("Missing", style = MaterialTheme.typography.labelSmall, color = cs.error)
                !al.monitored -> Text("Unmonitored", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
        }
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            if (searching) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else IconButton(onClick = onSearch, enabled = al.monitored) { Icon(Icons.Rounded.Search, "Search for ${al.title}") }
        }
        IconButton(onClick = onToggleMonitored) {
            Icon(
                if (al.monitored) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                if (al.monitored) "Monitored, tap to stop monitoring" else "Not monitored, tap to monitor",
                tint = if (al.monitored) cs.primary else cs.onSurfaceVariant,
            )
        }
    }
}
