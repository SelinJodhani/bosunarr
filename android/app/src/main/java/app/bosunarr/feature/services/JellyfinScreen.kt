package app.bosunarr.feature.services

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import app.bosunarr.app
import app.bosunarr.data.ServiceConfig
import app.bosunarr.ui.common.ConfirmDialog
import app.bosunarr.ui.common.Poster
import app.bosunarr.ui.common.ServiceImage
import app.bosunarr.ui.common.Tag
import app.bosunarr.ui.common.parseInstant
import app.bosunarr.ui.common.relative
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/* ================================================================ API shapes */

private const val TICKS = 10_000_000.0

@Serializable
private data class JfInfo(
    @SerialName("ServerName") val serverName: String? = null,
    @SerialName("Version") val version: String? = null,
    @SerialName("OperatingSystemDisplayName") val os: String? = null,
    @SerialName("HasPendingRestart") val restartPending: Boolean = false,
    @SerialName("HasUpdateAvailable") val updateAvailable: Boolean = false,
)

@Serializable
private data class JfCounts(
    @SerialName("MovieCount") val movies: Int = 0,
    @SerialName("SeriesCount") val series: Int = 0,
    @SerialName("EpisodeCount") val episodes: Int = 0,
    @SerialName("AlbumCount") val albums: Int = 0,
    @SerialName("SongCount") val songs: Int = 0,
    @SerialName("BookCount") val books: Int = 0,
)

@Serializable
private data class JfFolder(
    @SerialName("Name") val name: String? = null,
    @SerialName("ItemId") val itemId: String? = null,
    @SerialName("CollectionType") val collectionType: String? = null,
    @SerialName("RefreshStatus") val refreshStatus: String? = null,
    @SerialName("RefreshProgress") val refreshProgress: Double? = null,
)

@Serializable
private data class JfTotal(@SerialName("TotalRecordCount") val total: Int? = null)

@Serializable
private data class JfPolicy(@SerialName("IsAdministrator") val admin: Boolean = false, @SerialName("IsDisabled") val disabled: Boolean = false)

@Serializable
private data class JfUser(
    @SerialName("Id") val id: String = "",
    @SerialName("Name") val name: String? = null,
    @SerialName("LastActivityDate") val lastActivity: String? = null,
    @SerialName("Policy") val policy: JfPolicy = JfPolicy(),
)

@Serializable
private data class JfItem(
    @SerialName("Id") val id: String = "",
    @SerialName("Name") val name: String? = null,
    @SerialName("Type") val type: String? = null,
    @SerialName("SeriesName") val seriesName: String? = null,
    @SerialName("SeriesId") val seriesId: String? = null,
    @SerialName("ParentIndexNumber") val season: Int? = null,
    @SerialName("IndexNumber") val episode: Int? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("ProductionYear") val year: Int? = null,
    @SerialName("DateCreated") val created: String? = null,
    @SerialName("ImageTags") val imageTags: Map<String, String> = emptyMap(),
    @SerialName("SeriesPrimaryImageTag") val seriesImageTag: String? = null,
    @SerialName("AlbumId") val albumId: String? = null,
    @SerialName("AlbumPrimaryImageTag") val albumImageTag: String? = null,
)

@Serializable
private data class JfPlayState(
    @SerialName("PositionTicks") val position: Long? = null,
    @SerialName("IsPaused") val paused: Boolean = false,
    @SerialName("PlayMethod") val method: String? = null,
)

@Serializable
private data class JfTranscode(
    @SerialName("IsVideoDirect") val videoDirect: Boolean? = null,
    @SerialName("IsAudioDirect") val audioDirect: Boolean? = null,
    @SerialName("VideoCodec") val videoCodec: String? = null,
    @SerialName("AudioCodec") val audioCodec: String? = null,
    @SerialName("Container") val container: String? = null,
    @SerialName("Bitrate") val bitrate: Long? = null,
    /** Array in 10.9+, comma-separated string before. */
    @SerialName("TranscodeReasons") val reasons: JsonElement? = null,
    @SerialName("HardwareAccelerationType") val hwAccel: String? = null,
)

@Serializable
private data class JfSession(
    @SerialName("Id") val id: String = "",
    @SerialName("UserName") val user: String? = null,
    @SerialName("Client") val client: String? = null,
    @SerialName("DeviceName") val device: String? = null,
    @SerialName("NowPlayingItem") val item: JfItem? = null,
    @SerialName("PlayState") val playState: JfPlayState = JfPlayState(),
    @SerialName("TranscodingInfo") val transcoding: JfTranscode? = null,
)

/* ================================================================ loaders */

private data class JfLibrary(val id: String, val name: String, val kind: String, val count: Int?, val scanning: Boolean, val progress: Double?)

private data class JfOverview(val info: JfInfo, val counts: JfCounts?, val libraries: List<JfLibrary>)

private val LIB_TYPES = mapOf(
    "movies" to "Movie", "tvshows" to "Series", "music" to "MusicAlbum", "musicvideos" to "MusicVideo",
    "books" to "Book", "boxsets" to "BoxSet", "playlists" to "Playlist",
)

private val ID = Regex("^[0-9a-fA-F]{32}$|^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

private suspend fun loadJfOverview(s: ServiceConfig): JfOverview = coroutineScope {
    val c = app.client
    val info = async { c.get<JfInfo>(s, "/System/Info") }
    val counts = async { runCatching { c.get<JfCounts>(s, "/Items/Counts") }.getOrNull() }
    val folders = async { runCatching { c.get<List<JfFolder>>(s, "/Library/VirtualFolders") }.getOrDefault(emptyList()) }
    val libs = folders.await().filter { it.itemId != null && ID.matches(it.itemId) }.map { f ->
        async {
            val type = LIB_TYPES[f.collectionType]
            val q = "ParentId=${f.itemId}&Recursive=true&Limit=0&EnableTotalRecordCount=true" + if (type != null) "&IncludeItemTypes=$type" else "&IsFolder=false"
            JfLibrary(
                id = f.itemId!!,
                name = f.name ?: "Library",
                kind = f.collectionType ?: "mixed",
                // A count is nice-to-have; don't fail the screen for it.
                count = runCatching { c.get<JfTotal>(s, "/Items?$q").total }.getOrNull(),
                scanning = f.refreshStatus == "Active",
                progress = f.refreshProgress,
            )
        }
    }.awaitAll()
    JfOverview(info.await(), counts.await(), libs)
}

private enum class PlayMethod(val label: String) { DirectPlay("Direct play"), DirectStream("Direct stream"), Transcode("Transcode") }

private data class NowPlaying(
    val id: String,
    val user: String,
    val device: String,
    val title: String,
    val subtitle: String?,
    val poster: String?,
    val position: Double,
    val runtime: Double,
    val paused: Boolean,
    val method: PlayMethod,
    val detail: String?,
    val reasons: List<String>,
    val hwAccel: String?,
)

/** Which item carries the poster: the item itself, else its series/album. */
private fun posterOf(it: JfItem): String? {
    val id = when {
        it.type == "Episode" || it.type == "Season" -> if (it.seriesImageTag != null) it.seriesId else if (it.imageTags["Primary"] != null) it.id else it.seriesId
        it.imageTags["Primary"] != null -> it.id
        it.albumImageTag != null -> it.albumId
        else -> null
    } ?: return null
    return "/Items/$id/Images/Primary?maxHeight=300"
}

/** "VideoCodecNotSupported" → "Video codec not supported". */
private fun humanize(reason: String): String {
    val words = reason.trim().replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1 $2").lowercase()
    return words.replaceFirstChar { it.uppercase() }
}

private fun JfSession.method(): PlayMethod = when (playState.method) {
    "Transcode" -> PlayMethod.Transcode
    "DirectStream" -> PlayMethod.DirectStream
    "DirectPlay" -> PlayMethod.DirectPlay
    else -> transcoding?.let { if (it.videoDirect == false || it.audioDirect == false) PlayMethod.Transcode else PlayMethod.DirectStream } ?: PlayMethod.DirectPlay
}

private suspend fun loadSessions(s: ServiceConfig): List<NowPlaying> =
    app.client.get<List<JfSession>>(s, "/Sessions?activeWithinSeconds=960").mapNotNull { x ->
        val it = x.item ?: return@mapNotNull null
        val method = x.method()
        val t = x.transcoding
        val isEp = it.type == "Episode"
        val reasons = when (val r = t?.reasons) {
            is kotlinx.serialization.json.JsonArray -> r.mapNotNull { e -> (e as? JsonPrimitive)?.content }
            is JsonPrimitive -> r.content.split(',').filter { s2 -> s2.isNotBlank() }
            else -> emptyList()
        }
        NowPlaying(
            id = x.id.ifBlank { "${x.user}:${it.id}" },
            user = x.user ?: "Someone",
            device = listOfNotNull(x.client, x.device).filter { d -> d.isNotBlank() }.joinToString(" · "),
            title = (if (isEp) it.seriesName else null) ?: it.name ?: "Unknown",
            subtitle = if (isEp) "${episodeCode(it.season ?: 0, it.episode ?: 0)} · ${it.name.orEmpty()}" else it.year?.toString(),
            poster = posterOf(it),
            position = ((x.playState.position ?: 0) / TICKS).coerceAtLeast(0.0),
            runtime = ((it.runTimeTicks ?: 0) / TICKS).coerceAtLeast(0.0),
            paused = x.playState.paused,
            method = method,
            detail = if (t == null || method == PlayMethod.DirectPlay) null else listOfNotNull(
                if (t.videoDirect == true) "Video direct" else t.videoCodec?.let { c -> "Video → ${c.uppercase()}" } ?: "Video converted",
                if (t.audioDirect == true) "Audio direct" else t.audioCodec?.let { c -> "Audio → ${c.uppercase()}" } ?: "Audio converted",
                t.container?.uppercase(),
                t.bitrate?.takeIf { b -> b > 0 }?.let { b -> "%.1f Mbps".format(b / 1e6) },
            ).joinToString(" · "),
            reasons = if (method == PlayMethod.DirectPlay) emptyList() else reasons.map(::humanize),
            hwAccel = t?.hwAccel?.takeIf { h -> h.isNotBlank() && !h.equals("none", true) },
        )
    }

private data class LatestItem(val id: String, val title: String, val subtitle: String?, val poster: String?)

private suspend fun adminUser(s: ServiceConfig): JfUser? {
    val users = app.client.get<List<JfUser>>(s, "/Users")
    return users.firstOrNull { it.policy.admin && !it.policy.disabled } ?: users.firstOrNull { !it.policy.disabled }
}

private suspend fun loadLatest(s: ServiceConfig): List<LatestItem> {
    val u = adminUser(s)?.takeIf { ID.matches(it.id) } ?: return emptyList()
    val items = app.client.get<List<JfItem>>(s, "/Items/Latest?userId=${u.id}&Limit=24&Fields=DateCreated&EnableImageTypes=Primary&GroupItems=true")
    return items.filter { it.id.isNotBlank() }.distinctBy { it.id }.map {
        LatestItem(
            it.id,
            (if (it.type == "Episode") it.seriesName else null) ?: it.name ?: "Untitled",
            if (it.type == "Episode") it.name else it.year?.toString(),
            posterOf(it) ?: "/Items/${it.id}/Images/Primary?maxHeight=300",
        )
    }
}

/* ================================================================ screen */

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JellyfinServiceScreen(s: ServiceConfig, nav: NavController) {
    val hub = rememberRefreshHub()
    val overview = rememberHubLoader(hub, "svc:jf:${s.id}", refreshMs = 30_000) { loadJfOverview(s) }
    val sessions = rememberHubLoader(hub, "svc:jf:${s.id}:sessions", refreshMs = 5_000, staleMs = 5_000) { loadSessions(s) }
    val latest = rememberHubLoader(hub, "svc:jf:${s.id}:latest", refreshMs = 5 * 60_000) { loadLatest(s) }
    val users = rememberHubLoader(hub, "svc:jf:${s.id}:users", refreshMs = 60_000) {
        app.client.get<List<JfUser>>(s, "/Users").sortedByDescending { it.lastActivity.orEmpty() }
    }
    val runner = rememberActionRunner()
    var confirmScan by rememberSaveable { mutableStateOf(false) }
    val version = overview.value?.info?.version

    ServiceFrame(s, nav, subtitle = listOfNotNull(s.serviceType?.label, version?.let { "v$it" }).joinToString(" · ")) { p ->
        HubRefreshBox(hub, Modifier.padding(p)) {
            CardList {
                item("server") {
                    SectionCard("Server", icon = Icons.Rounded.Dns) {
                        CardLoad(overview, placeholder = { SkeletonRows(2, rowHeight = 20.dp); StatRow { repeat(3) { SkeletonBox(Modifier.weight(1f).height(64.dp), RoundedCornerShape(16.dp)) } } }) { o ->
                            InfoLine("Name", o.info.serverName ?: s.name)
                            o.info.os?.let { InfoLine("System", it) }
                            if (o.info.restartPending || o.info.updateAvailable) {
                                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (o.info.restartPending) Tag("Restart pending", container = MaterialTheme.colorScheme.tertiaryContainer, content = MaterialTheme.colorScheme.onTertiaryContainer)
                                    if (o.info.updateAvailable) Tag("Update available", container = MaterialTheme.colorScheme.primaryContainer, content = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            }
                            o.counts?.let { c ->
                                StatRow(Modifier.padding(top = 4.dp)) {
                                    StatTile("Movies", c.movies.toString(), Modifier.weight(1f))
                                    StatTile("Series", c.series.toString(), Modifier.weight(1f))
                                    StatTile("Episodes", c.episodes.toString(), Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
                item("sessions") {
                    val count = sessions.value?.size
                    SectionCard("Now playing", icon = Icons.Rounded.Tv, trailing = {
                        if (count != null && count > 0) Tag(count.toString(), modifier = Modifier.padding(end = 8.dp), container = MaterialTheme.colorScheme.primary, content = MaterialTheme.colorScheme.onPrimary)
                    }) {
                        CardLoad(sessions, placeholder = { SkeletonRows(1, rowHeight = 72.dp) }) { list ->
                            if (list.isEmpty()) CardEmpty("Nobody is watching right now")
                            list.forEachIndexed { i, np ->
                                if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                androidx.compose.runtime.key(np.id) { SessionRow(s, np) }
                            }
                        }
                    }
                }
                item("latest") {
                    SectionCard("Latest media", icon = Icons.Rounded.NewReleases) {
                        CardLoad(latest, placeholder = {
                            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                repeat(4) { SkeletonBox(Modifier.width(96.dp).height(144.dp), RoundedCornerShape(12.dp)) }
                            }
                        }) { list ->
                            if (list.isEmpty()) CardEmpty("Nothing added recently")
                            else LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(list, key = { it.id }) { li ->
                                    Column(Modifier.width(96.dp)) {
                                        Poster(s, li.poster, Modifier.fillMaxWidth(), title = li.title)
                                        Spacer(Modifier.height(6.dp))
                                        Text(li.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(li.subtitle.orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
                item("libraries") {
                    SectionCard("Libraries", icon = Icons.Rounded.VideoLibrary, trailing = {
                        TextButton(onClick = { confirmScan = true }, enabled = !runner.isBusy("jf:${s.id}:scan")) {
                            Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Scan all")
                        }
                    }) {
                        CardLoad(overview, placeholder = { SkeletonRows(3, rowHeight = 28.dp) }) { o ->
                            if (o.libraries.isEmpty()) CardEmpty("No libraries")
                            o.libraries.forEach { l ->
                                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(l.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                        Text(
                                            listOfNotNull(l.count?.toString(), libKind(l.kind)).joinToString(" "),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                        )
                                    }
                                    if (l.scanning) {
                                        Spacer(Modifier.height(4.dp))
                                        val pr = l.progress
                                        if (pr != null) LinearProgressIndicator(progress = { (pr / 100).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
                                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                                    }
                                }
                            }
                        }
                    }
                }
                item("users") {
                    SectionCard("Users", icon = Icons.Rounded.Group) {
                        CardLoad(users, placeholder = { SkeletonRows(3, rowHeight = 28.dp) }) { list ->
                            if (list.isEmpty()) CardEmpty("No users")
                            list.forEach { u ->
                                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(u.name ?: "User", style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (u.policy.disabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                                        Text(
                                            parseInstant(u.lastActivity)?.let { "Active ${relative(it)}" } ?: "Never active",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        if (u.policy.admin) Tag("Admin")
                                        if (u.policy.disabled) Tag("Disabled", container = MaterialTheme.colorScheme.errorContainer, content = MaterialTheme.colorScheme.onErrorContainer)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmScan) {
        ConfirmDialog(
            title = "Scan all libraries?",
            text = "Jellyfin will look for new, changed and removed files in every library.",
            confirm = "Scan",
            onConfirm = {
                runner.run("jf:${s.id}:scan", success = "Library scan started", after = { overview.refresh() }) {
                    app.client.send(s, "/Library/Refresh", "POST")
                    null
                }
            },
            onDismiss = { confirmScan = false },
        )
    }
}

private fun libKind(k: String) = when (k) {
    "movies" -> "movies"
    "tvshows" -> "shows"
    "music" -> "albums"
    "musicvideos" -> "videos"
    "books" -> "books"
    "boxsets" -> "collections"
    "playlists" -> "playlists"
    "homevideos" -> "items"
    else -> "items"
}

private fun clock(sec: Double): String {
    val t = sec.toLong()
    val h = t / 3600; val m = t % 3600 / 60; val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionRow(s: ServiceConfig, np: NowPlaying) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.Top) {
        ServiceImage(s, np.poster, Modifier.width(56.dp).height(84.dp).clip(RoundedCornerShape(10.dp)), contentDescription = np.title)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (np.paused) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (np.paused) "Paused" else "Playing", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(4.dp))
                Text(np.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            np.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Text(
                listOf(np.user, np.device).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (np.runtime > 0) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { (np.position / np.runtime).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    drawStopIndicator = {},
                )
                Text("${clock(np.position)} / ${clock(np.runtime)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            }
            FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val transcode = np.method == PlayMethod.Transcode
                Tag(
                    np.method.label,
                    container = if (transcode) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                    content = if (transcode) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                )
                np.hwAccel?.let { Tag(it) }
            }
            np.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)) }
            if (np.reasons.isNotEmpty()) Text(np.reasons.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = warnColor(), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
