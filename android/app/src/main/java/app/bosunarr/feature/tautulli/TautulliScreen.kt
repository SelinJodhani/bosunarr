package app.bosunarr.feature.tautulli

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
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
import app.bosunarr.feature.services.warnColor
import app.bosunarr.ui.common.Loader
import app.bosunarr.ui.common.Tag
import app.bosunarr.ui.common.plural
import app.bosunarr.ui.common.relative
import java.time.format.DateTimeFormatter

/** "3d 4h", "5h 12m", "42m" from seconds. */
internal fun watchTime(seconds: Double): String {
    val m = (seconds / 60).toLong()
    return when {
        m >= 60 * 24 -> "${m / (60 * 24)}d ${m % (60 * 24) / 60}h"
        m >= 60 -> "${m / 60}h ${m % 60}m"
        else -> "${m}m"
    }
}

private fun mbps(kbps: Int) = "%.1f Mbps".format(kbps / 1000.0)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TautulliServiceScreen(s: ServiceConfig, nav: NavController) {
    val hub = rememberRefreshHub()
    var days by rememberSaveable { mutableIntStateOf(30) }
    val info = rememberHubLoader(hub, "svc:tt:${s.id}:info", refreshMs = 10 * 60_000) { loadTtInfo(s) }
    val overview = rememberHubLoader(hub, "svc:tt:${s.id}", refreshMs = 5 * 60_000) { loadTtOverview(s) }
    val top = rememberHubLoader(hub, "svc:tt:${s.id}:top:$days", refreshMs = 5 * 60_000) { loadTtTop(s, days) }
    val now = rememberHubLoader(hub, "svc:tt:${s.id}:now", refreshMs = 10_000, staleMs = 10_000) { loadTtActivity(s) }
    val recent = rememberHubLoader(hub, "svc:tt:${s.id}:recent", refreshMs = 60_000) { loadTtHistory(s) }
    val runner = rememberActionRunner()
    val version = info.value?.version

    ServiceFrame(s, nav, subtitle = listOfNotNull(s.serviceType?.label, version?.let { "v$it" }).joinToString(" · ")) { p ->
        HubRefreshBox(hub, Modifier.padding(p)) {
            CardList {
                item("periods") {
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TT_PERIODS.forEach { d -> FilterChip(selected = days == d, onClick = { days = d }, label = { Text("$d days") }) }
                    }
                }
                item("overview") { OverviewCard(overview, days) }
                item("now") {
                    val a = now.value
                    SectionCard("Now playing", icon = Icons.Rounded.Tv, trailing = {
                        if (a != null && a.streams > 0) Tag(a.streams.toString(), modifier = Modifier.padding(end = 8.dp), container = MaterialTheme.colorScheme.primary, content = MaterialTheme.colorScheme.onPrimary)
                    }) {
                        CardLoad(now, placeholder = { SkeletonRows(1, rowHeight = 48.dp) }) { act ->
                            if (act.sessions.isEmpty()) CardEmpty("Nobody is watching right now")
                            else Text(
                                listOfNotNull(
                                    plural(act.streams, "stream"),
                                    act.transcodes.takeIf { it > 0 }?.let { plural(it, "transcode") },
                                    act.bandwidthKbps.takeIf { it > 0 }?.let { mbps(it) + if (act.wanKbps > 0) " (${mbps(act.wanKbps)} remote)" else "" },
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                            )
                            act.sessions.forEachIndexed { i, x ->
                                if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        val paused = x.state == "paused"
                                        Icon(if (paused) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (paused) "Paused" else "Playing", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.width(4.dp))
                                        Text(x.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    x.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                    Text(
                                        listOf(x.user, x.device).filter { it.isNotBlank() }.joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    LinearProgressIndicator(progress = { x.progress }, modifier = Modifier.fillMaxWidth().height(4.dp), trackColor = MaterialTheme.colorScheme.surfaceContainerHighest, drawStopIndicator = {})
                                    FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        val transcode = x.decision == "Transcode"
                                        Tag(
                                            x.decision,
                                            container = if (transcode) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                                            content = if (transcode) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                                        )
                                        if (x.hw) Tag("HW")
                                        x.quality?.let { Tag(it) }
                                    }
                                }
                            }
                        }
                    }
                }
                item("movies") { TopCard("Most watched movies", Icons.Rounded.Movie, top) { it.movies } }
                item("shows") { TopCard("Most watched shows", Icons.Rounded.Tv, top) { it.shows } }
                item("users") { TopCard("Most active users", Icons.Rounded.Person, top) { it.users } }
                item("recent") {
                    SectionCard("Recently watched", icon = Icons.Rounded.History) {
                        CardLoad(recent, placeholder = { SkeletonRows(4, rowHeight = 40.dp) }) { list ->
                            if (list.isEmpty()) CardEmpty("No plays recorded yet")
                            list.forEachIndexed { i, w ->
                                if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(w.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                        Text(w.at?.let { relative(it) }.orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                    }
                                    w.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                    Text(
                                        listOf(w.user, w.device, if (w.seconds > 0) watchTime(w.seconds) else "", w.percent?.let { "$it%" }.orEmpty())
                                            .filter { it.isNotBlank() }.joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
                item("server") {
                    SectionCard("Plex server", icon = Icons.Rounded.Dns, trailing = {
                        TextButton(onClick = { runner.run("tt:${s.id}:arnold") { arnold(s) } }, enabled = !runner.isBusy("tt:${s.id}:arnold")) { Text("Ask Arnold") }
                    }) {
                        CardLoad(info, placeholder = { SkeletonRows(2, rowHeight = 20.dp) }) { i ->
                            if (i.pmsName == null && i.pmsVersion == null) CardEmpty("Server details unavailable")
                            i.pmsName?.let { InfoLine("Name", it) }
                            i.pmsVersion?.let { InfoLine("Version", it) }
                            i.pmsPlatform?.let { InfoLine("System", it) }
                            if (i.plexPass == true) {
                                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                                    Tag("Plex Pass", container = MaterialTheme.colorScheme.primaryContainer, content = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OverviewCard(loader: Loader<TtOverview>, days: Int) {
    SectionCard("Overview", icon = Icons.Rounded.BarChart) {
        CardLoad(loader, placeholder = {
            StatRow { repeat(3) { SkeletonBox(Modifier.weight(1f).height(64.dp), RoundedCornerShape(16.dp)) } }
            SkeletonBox(Modifier.fillMaxWidth().padding(16.dp).height(72.dp))
        }) { o ->
            val p = o.periods[days]
            StatRow {
                StatTile("Plays", (p?.plays ?: 0).toString(), Modifier.weight(1f))
                StatTile("Watch time", watchTime(p?.seconds ?: 0.0), Modifier.weight(1f))
                StatTile("Active users", (p?.activeUsers ?: 0).toString(), Modifier.weight(1f))
            }
            PlaysChart(o.daily.takeLast(days))
        }
    }
}

/** Plays per day: plain bars, today on the right. */
@Composable
private fun PlaysChart(days: List<TtDay>) {
    if (days.isEmpty()) return
    val max = (days.maxOfOrNull { it.plays } ?: 0).coerceAtLeast(1)
    val bar = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Canvas(Modifier.fillMaxWidth().height(72.dp)) {
            val n = days.size
            val gap = if (n > 40) 1.dp.toPx() else 3.dp.toPx()
            val w = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
            days.forEachIndexed { i, d ->
                val h = if (d.plays == 0) 2.dp.toPx() else (size.height * d.plays / max).coerceAtLeast(3.dp.toPx())
                drawRoundRect(
                    color = if (d.plays == 0) empty else bar,
                    topLeft = Offset(i * (w + gap), size.height - h),
                    size = Size(w, h),
                    cornerRadius = CornerRadius(minOf(w / 2, 3.dp.toPx())),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            val f = DateTimeFormatter.ofPattern("d MMM")
            Text(days.first().date.format(f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(days.last().date.format(f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TopCard(title: String, icon: ImageVector, loader: Loader<TtTop>, pick: (TtTop) -> List<TtTopItem>) {
    SectionCard(title, icon = icon) {
        CardLoad(loader, placeholder = { SkeletonRows(3, rowHeight = 24.dp) }) { t ->
            val list = pick(t)
            if (list.isEmpty()) CardEmpty("No plays in this period")
            list.forEachIndexed { i, item ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (i == 0) Icon(Icons.Rounded.EmojiEvents, null, tint = warnColor(), modifier = Modifier.width(24.dp))
                    else Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(item.name, style = MaterialTheme.typography.bodyLarge, fontWeight = if (i == 0) FontWeight.Medium else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Tag(listOfNotNull(plural(item.plays, "play"), item.seconds?.takeIf { sec -> sec > 0 }?.let(::watchTime)).joinToString(" · "))
                }
            }
        }
    }
}
