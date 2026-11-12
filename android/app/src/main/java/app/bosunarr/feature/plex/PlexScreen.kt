package app.bosunarr.feature.plex

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import app.bosunarr.data.ServiceConfig
import app.bosunarr.feature.services.CardEmpty
import app.bosunarr.feature.services.CardList
import app.bosunarr.feature.services.CardLoad
import app.bosunarr.feature.services.HubRefreshBox
import app.bosunarr.feature.services.InfoLine
import app.bosunarr.feature.services.SectionCard
import app.bosunarr.feature.services.ServiceFrame
import app.bosunarr.feature.services.SkeletonBox
import app.bosunarr.feature.services.SkeletonRows
import app.bosunarr.feature.services.StatRow
import app.bosunarr.feature.services.StatTile
import app.bosunarr.feature.services.rememberActionRunner
import app.bosunarr.feature.services.rememberHubLoader
import app.bosunarr.feature.services.rememberRefreshHub
import app.bosunarr.ui.common.ConfirmDialog
import app.bosunarr.ui.common.Poster
import app.bosunarr.ui.common.ServiceImage
import app.bosunarr.ui.common.Tag
import java.util.Locale

/** A pending confirmation: scan one library (or all, [key] null) or stop a stream. */
private sealed interface PlexConfirm {
    data class Scan(val key: String?, val name: String?) : PlexConfirm
    data class Stop(val sessionId: String, val title: String, val user: String) : PlexConfirm
}

private fun count(n: Int) = String.format(Locale.getDefault(), "%,d", n)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlexServiceScreen(s: ServiceConfig, nav: NavController) {
    val hub = rememberRefreshHub()
    val overview = rememberHubLoader(hub, "svc:plex:${s.id}", refreshMs = 30_000) { loadPlexOverview(s) }
    val sessions = rememberHubLoader(hub, "svc:plex:${s.id}:sessions", refreshMs = 5_000, staleMs = 5_000) { loadPlexSessions(s) }
    val recent = rememberHubLoader(hub, "svc:plex:${s.id}:recent", refreshMs = 5 * 60_000) { loadPlexRecent(s) }
    val runner = rememberActionRunner()
    // Not saveable (holds a data class); a rotation just dismisses the dialog.
    var confirm by remember { mutableStateOf<PlexConfirm?>(null) }
    val info = overview.value?.info
    // Stopping streams is a Plex Pass feature: hide the button only when the server says it has no Plex Pass.
    val canStop = info?.plexPass != false

    ServiceFrame(s, nav, subtitle = listOfNotNull(s.serviceType?.label, shortPlexVersion(info?.version)?.let { "v$it" }).joinToString(" · ")) { p ->
        HubRefreshBox(hub, Modifier.padding(p)) {
            CardList {
                item("server") {
                    SectionCard("Server", icon = Icons.Rounded.Dns) {
                        CardLoad(overview, placeholder = { SkeletonRows(2, rowHeight = 20.dp); StatRow { repeat(3) { SkeletonBox(Modifier.weight(1f).height(64.dp), RoundedCornerShape(16.dp)) } } }) { o ->
                            InfoLine("Name", o.info.name ?: s.name)
                            o.info.platform?.let { InfoLine("System", it) }
                            o.info.owner?.let { InfoLine("Owner", it) }
                            if (o.info.plexPass == true) {
                                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                                    Tag("Plex Pass", container = MaterialTheme.colorScheme.primaryContainer, content = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            }
                            val movies = o.libraries.filter { it.section.type == "movie" }.sumOf { it.count ?: 0 }
                            val shows = o.libraries.filter { it.section.type == "show" }
                            val tiles = buildList {
                                if (o.libraries.any { it.section.type == "movie" }) add("Movies" to movies)
                                if (shows.isNotEmpty()) {
                                    add("Shows" to shows.sumOf { it.count ?: 0 })
                                    add("Episodes" to shows.sumOf { it.episodes ?: 0 })
                                }
                                if (o.libraries.any { it.section.type == "artist" }) add("Artists" to o.libraries.filter { it.section.type == "artist" }.sumOf { it.count ?: 0 })
                            }.take(3)
                            if (tiles.isNotEmpty()) StatRow(Modifier.padding(top = 4.dp)) {
                                tiles.forEach { (label, n) -> StatTile(label, count(n), Modifier.weight(1f)) }
                            }
                        }
                    }
                }
                item("sessions") {
                    val n = sessions.value?.size
                    SectionCard("Now playing", icon = Icons.Rounded.Tv, trailing = {
                        if (n != null && n > 0) Tag(n.toString(), modifier = Modifier.padding(end = 8.dp), container = MaterialTheme.colorScheme.primary, content = MaterialTheme.colorScheme.onPrimary)
                    }) {
                        CardLoad(sessions, placeholder = { SkeletonRows(1, rowHeight = 72.dp) }) { list ->
                            if (list.isEmpty()) CardEmpty("Nobody is watching right now")
                            list.forEachIndexed { i, np ->
                                if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                androidx.compose.runtime.key(np.key) {
                                    val id = np.sessionId
                                    PlexSessionRow(
                                        s, np,
                                        onStop = if (canStop && id != null) ({ confirm = PlexConfirm.Stop(id, np.title, np.user) }) else null,
                                        stopping = id != null && runner.isBusy("plex:${s.id}:stop:$id"),
                                    )
                                }
                            }
                        }
                    }
                }
                item("recent") {
                    SectionCard("Recently added", icon = Icons.Rounded.NewReleases) {
                        CardLoad(recent, placeholder = {
                            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                repeat(4) { SkeletonBox(Modifier.width(96.dp).height(144.dp), RoundedCornerShape(12.dp)) }
                            }
                        }) { list ->
                            if (list.isEmpty()) CardEmpty("Nothing added recently")
                            else LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(list, key = { it.key }) { r ->
                                    Column(Modifier.width(96.dp)) {
                                        Poster(s, r.poster, Modifier.fillMaxWidth(), title = r.title)
                                        Spacer(Modifier.height(6.dp))
                                        Text(r.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(r.subtitle.orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
                item("libraries") {
                    SectionCard("Libraries", icon = Icons.Rounded.VideoLibrary, trailing = {
                        TextButton(onClick = { confirm = PlexConfirm.Scan(null, null) }, enabled = !runner.isBusy("plex:${s.id}:scan:all")) {
                            Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Scan all")
                        }
                    }) {
                        CardLoad(overview, placeholder = { SkeletonRows(3, rowHeight = 28.dp) }) { o ->
                            if (o.libraries.isEmpty()) CardEmpty("No libraries")
                            o.libraries.forEach { l ->
                                val sec = l.section
                                val busy = sec.refreshing || runner.isBusy("plex:${s.id}:scan:${sec.key}")
                                Column(
                                    Modifier.fillMaxWidth()
                                        .clickable(enabled = !busy) { confirm = PlexConfirm.Scan(sec.key, sec.title) }
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(sec.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                        Text(
                                            listOfNotNull(
                                                l.count?.let { "${count(it)} ${libraryNoun(sec.type, it)}" },
                                                l.episodes?.let { "${count(it)} ep" },
                                            ).joinToString(" · ").ifBlank { libraryNoun(sec.type, null) },
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                        )
                                    }
                                    if (sec.refreshing) {
                                        Spacer(Modifier.height(4.dp))
                                        LinearProgressIndicator(Modifier.fillMaxWidth())
                                    }
                                }
                            }
                            if (o.libraries.isNotEmpty()) {
                                Text(
                                    "Tap a library to scan just that one.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    when (val c = confirm) {
        is PlexConfirm.Scan -> ConfirmDialog(
            title = if (c.key == null) "Scan all libraries?" else "Scan ${c.name}?",
            text = if (c.key == null) "Plex will look for new, changed and removed files in every library."
            else "Plex will look for new, changed and removed files in this library.",
            confirm = "Scan",
            onConfirm = {
                runner.run("plex:${s.id}:scan:${c.key ?: "all"}", success = "Library scan started", after = { overview.refresh() }) {
                    plexScan(s, c.key)
                    null
                }
            },
            onDismiss = { confirm = null },
        )
        is PlexConfirm.Stop -> ConfirmDialog(
            title = "Stop this stream?",
            text = "${c.user}'s playback of ${c.title} ends now, with the message \"$STOP_REASON\".",
            confirm = "Stop",
            destructive = true,
            onConfirm = {
                runner.run("plex:${s.id}:stop:${c.sessionId}", success = "Stream stopped", after = { sessions.refresh() }) {
                    plexTerminate(s, c.sessionId, STOP_REASON)
                    null
                }
            },
            onDismiss = { confirm = null },
        )
        null -> Unit
    }
}

private const val STOP_REASON = "The server admin stopped this stream."

private fun clock(sec: Double): String {
    val t = sec.toLong()
    val h = t / 3600; val m = t % 3600 / 60; val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlexSessionRow(s: ServiceConfig, np: PlexSession, onStop: (() -> Unit)?, stopping: Boolean) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.Top) {
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
            if (np.durationSec > 0) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { (np.positionSec / np.durationSec).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    drawStopIndicator = {},
                )
                Text("${clock(np.positionSec)} / ${clock(np.durationSec)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            }
            FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val transcode = np.decision == PlexDecision.Transcode
                Tag(
                    np.decision.label,
                    container = if (transcode) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                    content = if (transcode) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                )
                np.hw?.let { Tag("HW $it") }
                np.location?.let { Tag(it) }
                np.bandwidthKbps?.takeIf { it > 0 }?.let { Tag("%.1f Mbps".format(it / 1000.0)) }
            }
            np.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)) }
        }
        if (onStop != null) {
            IconButton(onClick = onStop, enabled = !stopping) {
                Icon(Icons.Rounded.StopCircle, "Stop stream", tint = if (stopping) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
            }
        }
    }
}
