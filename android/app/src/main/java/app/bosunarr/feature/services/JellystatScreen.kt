package app.bosunarr.feature.services

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import app.bosunarr.app
import app.bosunarr.data.ServiceConfig
import app.bosunarr.net.AppJson
import app.bosunarr.net.ServiceException
import app.bosunarr.ui.common.Loader
import app.bosunarr.ui.common.Tag
import app.bosunarr.ui.common.parseInstant
import app.bosunarr.ui.common.relative
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Jellystat backend (auth header x-api-token, added by the client). Postgres bigint/numeric columns arrive as
 * strings, so every number goes through [n].
 *   GET  /stats/getViewsOverTime?days=  {stats: [{Key: "Nov 07, 2026", <library>: {count, duration (min)}}]}
 *   GET  /api/getLibraries               [{Name, CollectionType}]
 *   GET  /stats/getAllUserActivity       [{UserName, LastActivityDate}]
 *   POST /stats/getMostViewedByType      {days, type: Movie|Series} → [{Name, Plays, total_playback_duration (s)}]
 *   POST /stats/getMostActiveUsers       {days} → [{Name, Plays, total_playback_duration}]
 *   GET  /api/getHistory?page=&size=     {results: [...], pages}
 *   GET  /proxy/getSessions              Jellyfin sessions (served without auth upstream: checked via getLibraries)
 */

private fun JsonElement?.n(): Double = (this as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0
private fun JsonElement?.s(): String = (this as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content.orEmpty()
private fun JsonObject.sOrNull(k: String): String? = this[k].s().ifBlank { null }

private fun rows(s: ServiceConfig, v: JsonElement?): List<JsonObject> =
    (v as? JsonArray)?.mapNotNull { it as? JsonObject } ?: throw ServiceException("${s.name} sent an unexpected response")

private suspend fun getJson(s: ServiceConfig, path: String): JsonElement = app.client.json(s, path)

private suspend fun postJson(s: ServiceConfig, path: String, body: JsonObject): JsonElement {
    val text = app.client.send(s, path, "POST", body)
    if (text.trimStart().startsWith("<")) throw ServiceException("${s.name} answered with a web page instead of its API — check the address")
    return runCatching { AppJson.parseToJsonElement(text) }.getOrElse { throw ServiceException("${s.name} gave an unexpected answer") }
}

/* ================================================================ overview */

private data class Period(val days: Int, val plays: Int, val minutes: Double, val activeUsers: Int)

private data class Day(val date: LocalDate, val plays: Int)

private data class JsOverview(val periods: Map<Int, Period>, val daily: List<Day>)

private val PERIODS = listOf(7, 30, 90)
private val KEY_FORMAT = DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.US)

/** Jellystat keys days as en-US "Nov 07, 2026"; accept ISO dates too. */
private fun keyToDate(key: String): LocalDate? =
    runCatching { LocalDate.parse(key.trim(), KEY_FORMAT) }.getOrNull()
        ?: runCatching { LocalDate.parse(key.trim().take(10)) }.getOrNull()

private suspend fun loadJsOverview(s: ServiceConfig): JsOverview = coroutineScope {
    val views = async { getJson(s, "/stats/getViewsOverTime?days=90") }
    val users = async { rows(s, getJson(s, "/stats/getAllUserActivity")) }
    val stats = rows(s, (views.await() as? JsonObject)?.get("stats") ?: throw ServiceException("${s.name} sent an unexpected response"))
    val byDate = sortedMapOf<LocalDate, Pair<Int, Double>>()
    for (r in stats) {
        val date = keyToDate(r["Key"].s()) ?: continue
        var plays = 0
        var minutes = 0.0
        for ((lib, v) in r) {
            if (lib == "Key" || v !is JsonObject) continue
            plays += v["count"].n().toInt()
            minutes += v["duration"].n()
        }
        val prev = byDate[date] ?: (0 to 0.0)
        byDate[date] = (prev.first + plays) to (prev.second + minutes)
    }
    val all = byDate.entries.toList()
    val activity = users.await()
    val zone = ZoneId.systemDefault()
    val periods = PERIODS.associateWith { days ->
        // Both numbers cover the same span: the last `days` calendar days, today included.
        val slice = all.takeLast(days)
        val since = (slice.firstOrNull()?.key ?: LocalDate.now(zone)).atStartOfDay(zone).toInstant()
        Period(
            days = days,
            plays = slice.sumOf { it.value.first },
            minutes = slice.sumOf { it.value.second },
            activeUsers = activity.count { u -> parseInstant(u["LastActivityDate"].s())?.let { it >= since } == true },
        )
    }
    JsOverview(periods, all.map { Day(it.key, it.value.first) })
}

/* ================================================================ top lists */

private data class TopItem(val name: String, val plays: Int, val seconds: Double?)

private data class JsTop(val movies: List<TopItem>, val shows: List<TopItem>, val users: List<TopItem>)

private fun top(list: List<JsonObject>): List<TopItem> =
    list.map { TopItem(it["Name"].s().ifBlank { "Unknown" }, it["Plays"].n().toInt(), it["total_playback_duration"]?.n()) }.take(5)

private suspend fun loadTop(s: ServiceConfig, days: Int): JsTop = coroutineScope {
    val movies = async { rows(s, postJson(s, "/stats/getMostViewedByType", buildJsonObject { put("days", days); put("type", "Movie") })) }
    val shows = async { rows(s, postJson(s, "/stats/getMostViewedByType", buildJsonObject { put("days", days); put("type", "Series") })) }
    val users = async { rows(s, postJson(s, "/stats/getMostActiveUsers", buildJsonObject { put("days", days) })) }
    JsTop(top(movies.await()), top(shows.await()), top(users.await()))
}

/* ================================================================ history + now playing */

private data class Watched(val key: String, val user: String, val title: String, val subtitle: String?, val device: String, val seconds: Double, val at: Instant?)

private fun pad2(v: JsonElement?) = "%02d".format(v.n().toInt())

private suspend fun loadRecent(s: ServiceConfig): List<Watched> {
    val res = getJson(s, "/api/getHistory?page=1&size=25") as? JsonObject ?: throw ServiceException("${s.name} sent an unexpected response")
    return rows(s, res["results"] ?: JsonArray(emptyList())).mapIndexed { i, r ->
        val ep = r.sOrNull("SeriesName") != null
        Watched(
            key = "${r["Id"].s()}:${r["NowPlayingItemId"].s()}:${r["EpisodeId"].s()}:${r["UserId"].s()}:$i",
            user = r.sOrNull("UserName") ?: "Unknown",
            title = (if (ep) r.sOrNull("SeriesName") else r.sOrNull("NowPlayingItemName")) ?: "Unknown",
            subtitle = if (ep) "S${pad2(r["SeasonNumber"])}E${pad2(r["EpisodeNumber"])} · ${r["NowPlayingItemName"].s()}" else null,
            device = listOfNotNull(r.sOrNull("Client"), r.sOrNull("DeviceName")).joinToString(" · "),
            seconds = r["PlaybackDuration"].n(),
            at = parseInstant(r.sOrNull("ActivityDateInserted")),
        )
    }
}

private data class JsSession(val key: String, val user: String, val title: String, val subtitle: String?, val device: String, val progress: Float, val paused: Boolean)

private suspend fun loadJsSessions(s: ServiceConfig): List<JsSession> = coroutineScope {
    // /proxy/getSessions works without auth upstream: prove the key first so a wrong key doesn't look fine.
    val check = async { getJson(s, "/api/getLibraries") }
    val raw = async { getJson(s, "/proxy/getSessions") }
    check.await()
    rows(s, raw.await()).mapNotNull { x ->
        val item = x["NowPlayingItem"] as? JsonObject ?: return@mapNotNull null
        val ps = x["PlayState"] as? JsonObject ?: JsonObject(emptyMap())
        val ep = item["Type"].s() == "Episode"
        val runtime = item["RunTimeTicks"].n()
        JsSession(
            key = x["Id"].s().ifBlank { "${x["UserName"].s()}:${item["Id"].s()}" },
            user = x.sOrNull("UserName") ?: "Someone",
            title = (if (ep) item.sOrNull("SeriesName") else item.sOrNull("Name")) ?: "Unknown",
            subtitle = if (ep) "S${pad2(item["ParentIndexNumber"])}E${pad2(item["IndexNumber"])} · ${item["Name"].s()}" else item.sOrNull("ProductionYear"),
            device = listOfNotNull(x.sOrNull("Client"), x.sOrNull("DeviceName")).joinToString(" · "),
            progress = if (runtime > 0) (ps["PositionTicks"].n() / runtime).toFloat().coerceIn(0f, 1f) else 0f,
            paused = ps["IsPaused"].s() == "true",
        )
    }.distinctBy { it.key }
}

/** "3d 4h", "5h 12m", "42m" from seconds. */
private fun watchTime(seconds: Double): String {
    val m = (seconds / 60).toLong()
    return when {
        m >= 60 * 24 -> "${m / (60 * 24)}d ${m % (60 * 24) / 60}h"
        m >= 60 -> "${m / 60}h ${m % 60}m"
        else -> "${m}m"
    }
}

/* ================================================================ screen */

@Composable
fun JellystatServiceScreen(s: ServiceConfig, nav: NavController) {
    val hub = rememberRefreshHub()
    var days by rememberSaveable { mutableIntStateOf(30) }
    val overview = rememberHubLoader(hub, "svc:js:${s.id}", refreshMs = 5 * 60_000) { loadJsOverview(s) }
    val top = rememberHubLoader(hub, "svc:js:${s.id}:top:$days", refreshMs = 5 * 60_000) { loadTop(s, days) }
    val now = rememberHubLoader(hub, "svc:js:${s.id}:now", refreshMs = 10_000, staleMs = 10_000) { loadJsSessions(s) }
    val recent = rememberHubLoader(hub, "svc:js:${s.id}:recent", refreshMs = 60_000) { loadRecent(s) }

    ServiceFrame(s, nav) { p ->
        HubRefreshBox(hub, Modifier.padding(p)) {
            CardList {
                item("periods") {
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PERIODS.forEach { d -> FilterChip(selected = days == d, onClick = { days = d }, label = { Text("$d days") }) }
                    }
                }
                item("overview") { OverviewCard(overview, days) }
                item("now") {
                    SectionCard("Now playing", icon = Icons.Rounded.Tv) {
                        CardLoad(now, placeholder = { SkeletonRows(1, rowHeight = 48.dp) }) { list ->
                            if (list.isEmpty()) CardEmpty("Nobody is watching right now")
                            list.forEach { x ->
                                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                                    Text(x.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    x.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                    Text(
                                        listOf(x.user, x.device).filter { it.isNotBlank() }.joinToString(" · ") + if (x.paused) " · Paused" else "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    LinearProgressIndicator(progress = { x.progress }, modifier = Modifier.fillMaxWidth().height(4.dp), trackColor = MaterialTheme.colorScheme.surfaceContainerHighest, drawStopIndicator = {})
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
                                        listOf(w.user, w.device, if (w.seconds > 0) watchTime(w.seconds) else "").filter { it.isNotBlank() }.joinToString(" · "),
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
            }
        }
    }
}

@Composable
private fun OverviewCard(loader: Loader<JsOverview>, days: Int) {
    SectionCard("Overview", icon = Icons.Rounded.BarChart) {
        CardLoad(loader, placeholder = {
            StatRow { repeat(3) { SkeletonBox(Modifier.weight(1f).height(64.dp), RoundedCornerShape(16.dp)) } }
            SkeletonBox(Modifier.fillMaxWidth().padding(16.dp).height(72.dp))
        }) { o ->
            val p = o.periods[days]
            StatRow {
                StatTile("Plays", (p?.plays ?: 0).toString(), Modifier.weight(1f))
                StatTile("Watch time", watchTime((p?.minutes ?: 0.0) * 60), Modifier.weight(1f))
                StatTile("Active users", (p?.activeUsers ?: 0).toString(), Modifier.weight(1f))
            }
            PlaysChart(o.daily.takeLast(days))
        }
    }
}

/** Plays per day: plain bars, today on the right. */
@Composable
private fun PlaysChart(days: List<Day>) {
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
private fun TopCard(title: String, icon: ImageVector, loader: Loader<JsTop>, pick: (JsTop) -> List<TopItem>) {
    SectionCard(title, icon = icon) {
        CardLoad(loader, placeholder = { SkeletonRows(3, rowHeight = 24.dp) }) { t ->
            val list = pick(t)
            if (list.isEmpty()) CardEmpty("No plays in this period")
            list.forEachIndexed { i, item ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (i == 0) androidx.compose.material3.Icon(Icons.Rounded.EmojiEvents, null, tint = warnColor(), modifier = Modifier.width(24.dp))
                    else Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(item.name, style = MaterialTheme.typography.bodyLarge, fontWeight = if (i == 0) FontWeight.Medium else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Tag(listOfNotNull("${item.plays} plays", item.seconds?.takeIf { sec -> sec > 0 }?.let(::watchTime)).joinToString(" · "))
                }
            }
        }
    }
}
