package app.bosunarr.feature.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.toRoute
import app.bosunarr.app
import app.bosunarr.data.ServiceConfig
import app.bosunarr.feature.library.DetailAction
import app.bosunarr.feature.library.DetailHeader
import app.bosunarr.feature.library.DetailScaffold
import app.bosunarr.feature.library.ExpandableText
import app.bosunarr.feature.library.InfoCard
import app.bosunarr.feature.library.LibStatus
import app.bosunarr.feature.library.releaseDate
import app.bosunarr.feature.library.runAction
import app.bosunarr.nav.ArtistRoute
import app.bosunarr.ui.common.ErrorBox
import app.bosunarr.ui.common.LoadingBox
import app.bosunarr.ui.common.LocalSnackbar
import app.bosunarr.ui.common.SectionTitle
import app.bosunarr.ui.common.Tag
import app.bosunarr.ui.common.back
import app.bosunarr.ui.common.bytes
import app.bosunarr.ui.common.date
import app.bosunarr.ui.common.rememberLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AlbumScreen(nav: NavController, service: ServiceConfig, id: Int) {
    val loader = rememberLoader("album:${service.id}:$id") { MusicApi.album(service, id) }
    val state = loader.state
    val d = state.value
    val snackbar = LocalSnackbar.current
    val list = rememberLazyListState()
    val busy = remember { mutableStateMapOf<String, Boolean>() }

    val al = d?.album
    val artist = d?.artist
    val title = al?.title.orEmpty()

    fun busyAction(tag: String, success: String?, block: suspend () -> Unit) {
        if (busy[tag] == true) return
        busy[tag] = true
        runAction(snackbar, success, onDone = { busy.remove(tag) }, block = block)
    }

    fun toggleMonitored() {
        val cur = al ?: return
        val to = !cur.monitored
        loader.mutate { it.copy(album = it.album.copy(monitored = to)) }
        runAction(snackbar, null, onError = { loader.mutate { it.copy(album = it.album.copy(monitored = !to)) } }) {
            MusicApi.setAlbumsMonitored(service, listOf(id), to)
        }
    }

    fun openArtist() {
        val aid = artist?.id ?: al?.artistId ?: return
        if (aid <= 0) return
        // Came from the artist: go back to it instead of stacking another copy.
        val prev = nav.previousBackStackEntry
        if (prev != null && prev.destination.hasRoute<ArtistRoute>() && runCatching { prev.toRoute<ArtistRoute>() }.getOrNull()?.let { it.id == aid && it.serviceId == service.id } == true) nav.back()
        else nav.navigate(ArtistRoute(service.id, aid))
    }

    val uri = LocalUriHandler.current
    DetailScaffold(
        title, list, onBack = { nav.back() },
        menu = if (al == null) null else { close ->
            al.foreignAlbumId?.takeIf { it.isNotBlank() }?.let { mbid ->
                DropdownMenuItem(
                    text = { Text("Open in ${service.name}") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null) },
                    onClick = { close(); runCatching { uri.openUri("${app.client.baseUrl(service).trimEnd('/')}/album/$mbid") } },
                )
            }
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
                        fanart = artist?.let { artistFanart(service, it) },
                        poster = al?.let { albumCover(service, it.images, it.id) },
                        title = title,
                        lines = listOf(
                            artist?.artistName.orEmpty(),
                            listOfNotNull(
                                al?.releaseDate?.take(4)?.takeIf { it.length == 4 && it != "0001" },
                                al?.albumType?.takeIf { it.isNotBlank() },
                                al?.let { albumLength(it.duration) }?.takeIf { it.isNotEmpty() },
                            ).joinToString(" · "),
                        ),
                        square = true,
                        placeholderIcon = Icons.Rounded.Album,
                    )
                }
                when {
                    d == null && state.error != null -> item("error") { ErrorBox(state.error, Modifier.fillParentMaxSize(), onRetry = { loader.refresh() }) }
                    d == null || al == null -> item("loading") { LoadingBox() }
                    else -> {
                        item("actions") {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 16.dp)) {
                                DetailAction(if (al.monitored) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, if (al.monitored) "Monitored" else "Unmonitored", ::toggleMonitored, selected = al.monitored, toggle = true)
                                DetailAction(Icons.Rounded.Search, "Search", { busyAction("search", "Searching for ${al.title}") { MusicApi.searchAlbums(service, listOf(id)) } }, busy = busy["search"] == true)
                                DetailAction(Icons.Rounded.Refresh, "Refresh", {
                                    busyAction("refresh", "Refreshing album") {
                                        MusicApi.refreshAlbum(service, id)
                                        app.scope.launch(Dispatchers.Main) { delay(5000); loader.refresh() }
                                    }
                                }, busy = busy["refresh"] == true)
                                if ((artist?.id ?: al.artistId) > 0) DetailAction(Icons.Rounded.Person, "Artist", ::openArtist)
                            }
                        }
                        item("facts") {
                            val cs = MaterialTheme.colorScheme
                            val st = al.statistics
                            val status = albumStatus(al)
                            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                when (status) {
                                    LibStatus.Downloaded -> Tag(status.label, container = cs.primaryContainer, content = cs.onPrimaryContainer)
                                    LibStatus.Missing -> Tag(status.label, container = cs.errorContainer, content = cs.onErrorContainer)
                                    else -> Tag(status.label)
                                }
                                val want = st?.trackCount?.takeIf { it > 0 } ?: st?.totalTrackCount ?: d.tracks.size
                                if (want > 0) Tag("${st?.trackFileCount ?: d.tracks.count { it.hasFile }}/$want tracks")
                                if ((st?.sizeOnDisk ?: 0) > 0) Tag(bytes(st?.sizeOnDisk))
                                al.ratings?.value?.takeIf { it > 0 }?.let { Tag("★ ${String.format(Locale.getDefault(), "%.1f", it)}") }
                            }
                        }
                        if (!al.overview.isNullOrBlank()) item("overview") { ExpandableText(al.overview, Modifier.padding(horizontal = 16.dp, vertical = 16.dp).fillMaxWidth()) }
                        item("info") {
                            InfoCard(
                                listOf(
                                    "Released" to al.releaseDate?.let { releaseDate(it) },
                                    "Genres" to al.genres.take(4).joinToString(", "),
                                    "Discs" to al.mediumCount.takeIf { it > 1 }?.toString(),
                                    "Instance" to service.name,
                                ),
                                Modifier.padding(top = 16.dp),
                            )
                        }
                        val discs = d.tracks.groupBy { it.mediumNumber }
                        item("tracks-title") { SectionTitle("Tracks", Modifier.padding(top = 8.dp)) }
                        if (d.tracks.isEmpty()) item("tracks-empty") {
                            Text("No tracks listed yet", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        }
                        discs.forEach { (disc, tracks) ->
                            if (discs.size > 1) item("disc$disc") {
                                Text("Disc $disc", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
                            }
                            items(tracks, key = { "t${it.id}" }, contentType = { "track" }) { t -> TrackRow(t, d.files[t.trackFileId]) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackRow(t: LidarrTrack, file: LidarrTrackFile?) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            t.trackNumber?.takeIf { it.isNotBlank() } ?: t.absoluteTrackNumber.takeIf { it > 0 }?.toString().orEmpty(),
            style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant, textAlign = TextAlign.End,
            modifier = Modifier.widthIn(min = 24.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(t.title?.takeIf { it.isNotBlank() } ?: "Track ${t.absoluteTrackNumber}", style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(
                trackLength(t.duration).takeIf { it.isNotEmpty() },
                file?.quality?.quality?.name,
                file?.size?.takeIf { it > 0 }?.let { bytes(it) },
                file?.dateAdded?.let { "imported ${date(it)}" },
                if (t.explicit) "Explicit" else null,
            ).joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (t.hasFile) Icon(Icons.Rounded.CheckCircle, "Downloaded", tint = cs.primary, modifier = Modifier.size(20.dp))
    }
}
