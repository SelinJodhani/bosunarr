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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cable
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import app.bosunarr.app
import app.bosunarr.data.ServiceConfig
import app.bosunarr.net.ServiceException
import app.bosunarr.ui.common.ConfirmDialog
import app.bosunarr.ui.common.Loader
import app.bosunarr.ui.common.Tag
import app.bosunarr.ui.common.plural
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/* ================================================================ API shapes */

@Serializable
private data class BzLang(
    val name: String? = null,
    val code2: String? = null,
    val code3: String? = null,
    // Booleans in most payloads, "True"/"False" strings in some Bazarr versions.
    @SerialName("forced") val forcedRaw: JsonElement? = null,
    @SerialName("hi") val hiRaw: JsonElement? = null,
) {
    val forced: Boolean get() = forcedRaw.truthy()
    val hi: Boolean get() = hiRaw.truthy()
}

private fun JsonElement?.truthy(): Boolean = (this as? kotlinx.serialization.json.JsonPrimitive)?.content?.lowercase() == "true"

@Serializable
private data class BzEpisodeWanted(
    val seriesTitle: String = "",
    @SerialName("episode_number") val episodeNumber: String? = null,
    val episodeTitle: String? = null,
    @SerialName("missing_subtitles") val missing: List<BzLang> = emptyList(),
    val sonarrSeriesId: Int = 0,
    val sonarrEpisodeId: Int = 0,
)

@Serializable
private data class BzMovieWanted(
    val title: String = "",
    @SerialName("missing_subtitles") val missing: List<BzLang> = emptyList(),
    val radarrId: Int = 0,
)

@Serializable
private data class BzPage<T>(val data: List<T> = emptyList(), val total: Int = 0)

@Serializable
private data class BzHistory(
    val seriesTitle: String? = null,
    @SerialName("episode_number") val episodeNumber: String? = null,
    val episodeTitle: String? = null,
    val title: String? = null,
    val action: Int = -1,
    val language: BzLang? = null,
    val provider: String? = null,
    val score: String? = null,
    val timestamp: String? = null,
)

@Serializable
private data class BzData<T>(val data: T)

@Serializable
private data class BzHealth(val `object`: String? = null, val issue: String? = null)

@Serializable
private data class BzProvider(val name: String = "", val status: String? = null, val retry: String? = null)

/* ================================================================ overview */

private enum class Kind(val label: String, val path: String, val task: String) {
    Episodes("Episodes", "episodes", "wanted_search_missing_subtitles_series"),
    Movies("Movies", "movies", "wanted_search_missing_subtitles_movies"),
}

private data class BazarrOverview(
    val version: String?,
    val sonarrVersion: String?,
    val radarrVersion: String?,
    val wantedEpisodes: Int,
    val wantedMovies: Int,
    /** null = Bazarr didn't report it (that arr isn't set up); "DOWN" = disconnected; anything else = connected. */
    val sonarrSignalr: String?,
    val radarrSignalr: String?,
    val health: List<BzHealth>,
    val providers: List<BzProvider>,
    val partial: List<String>,
)

private suspend fun loadBazarr(s: ServiceConfig): BazarrOverview = coroutineScope {
    val c = app.client
    val status = async { runCatching { c.json(s, "/api/system/status") } }
    val badges = async { runCatching { c.json(s, "/api/badges") } }
    val health = async { runCatching { c.get<BzData<List<BzHealth>>>(s, "/api/system/health").data } }
    val providers = async { runCatching { c.get<BzData<List<BzProvider>>>(s, "/api/providers").data } }
    val st = status.await()
    val bd = badges.await()
    // If neither status nor badges answered, the service is down: surface that as the error.
    if (st.isFailure && bd.isFailure) throw st.exceptionOrNull()!!
    val partial = mutableListOf<String>()
    val data = st.getOrNull().obj()?.get("data").obj() ?: JsonObject(emptyMap()).also { partial += "status" }
    val b = bd.getOrNull().obj() ?: JsonObject(emptyMap()).also { partial += "badges" }
    fun JsonObject.s(k: String): String? = this[k].str()?.takeIf { it.isNotBlank() }
    BazarrOverview(
        version = data.s("bazarr_version"),
        sonarrVersion = data.s("sonarr_version"),
        radarrVersion = data.s("radarr_version"),
        wantedEpisodes = b["episodes"].num()?.toInt() ?: 0,
        wantedMovies = b["movies"].num()?.toInt() ?: 0,
        sonarrSignalr = (b["sonarr_signalr"] as? kotlinx.serialization.json.JsonPrimitive)?.content,
        radarrSignalr = (b["radarr_signalr"] as? kotlinx.serialization.json.JsonPrimitive)?.content,
        health = health.await().getOrElse { partial += "health"; emptyList() }.filter { !it.issue.isNullOrBlank() },
        providers = providers.await().getOrElse { partial += "providers"; emptyList() },
        partial = partial,
    )
}

@Composable
fun BazarrServiceScreen(s: ServiceConfig, nav: NavController) {
    val hub = rememberRefreshHub()
    val overview = rememberHubLoader(hub, "svc:bazarr:${s.id}") { loadBazarr(s) }
    val version = overview.value?.version
    ServiceFrame(s, nav, subtitle = listOfNotNull(s.serviceType?.label, version?.let { "v" + it.removePrefix("v") }).joinToString(" · ")) { p ->
        HubRefreshBox(hub, Modifier.padding(p)) {
            TabbedBody(listOf("Wanted", "History", "Status")) { tab ->
                when (tab) {
                    0 -> WantedTab(s, hub, overview)
                    1 -> BzHistoryTab(s, hub)
                    else -> BzStatusTab(s, overview)
                }
            }
        }
    }
}

/* ================================================================ shared bits */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KindSwitch(kind: Kind, onKind: (Kind) -> Unit, counts: Map<Kind, Int>? = null) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Kind.entries.forEachIndexed { i, k ->
            SegmentedButton(
                selected = kind == k,
                onClick = { onKind(k) },
                shape = SegmentedButtonDefaults.itemShape(i, Kind.entries.size),
                label = { Text(counts?.get(k)?.let { "${k.label} ($it)" } ?: k.label, maxLines = 1) },
            )
        }
    }
}

/** "1x5" → "S01E05" */
private fun episodeLabel(n: String?): String? {
    val m = n?.let { Regex("^(\\d+)x(\\d+)$").find(it) } ?: return n
    return episodeCode(m.groupValues[1].toInt(), m.groupValues[2].toInt())
}

private fun langLabel(l: BzLang): String =
    (l.code2 ?: l.code3 ?: l.name ?: "?").uppercase() + (if (l.hi) " HI" else "") + (if (l.forced) " Forced" else "")

/* ================================================================ wanted */

private data class WantedRow(val key: String, val id: Int, val seriesId: Int?, val title: String, val subtitle: String?, val missing: List<BzLang>)

private data class WantedPage(val rows: List<WantedRow>, val total: Int)

private const val PAGE = 50

private suspend fun loadWanted(s: ServiceConfig, kind: Kind): WantedPage = when (kind) {
    Kind.Episodes -> app.client.get<BzPage<BzEpisodeWanted>>(s, "/api/episodes/wanted?start=0&length=$PAGE").let { p ->
        WantedPage(
            p.data.map { x ->
                WantedRow("e:${x.sonarrEpisodeId}", x.sonarrEpisodeId, x.sonarrSeriesId, x.seriesTitle, listOfNotNull(episodeLabel(x.episodeNumber), x.episodeTitle?.takeIf { it.isNotBlank() }).joinToString(" · ").ifBlank { null }, x.missing)
            }.distinctBy { it.key },
            p.total,
        )
    }
    Kind.Movies -> app.client.get<BzPage<BzMovieWanted>>(s, "/api/movies/wanted?start=0&length=$PAGE").let { p ->
        WantedPage(p.data.map { x -> WantedRow("m:${x.radarrId}", x.radarrId, null, x.title, null, x.missing) }.distinctBy { it.key }, p.total)
    }
}

/**
 * Bazarr's PATCH only answers when the provider search is done, which can take a minute or two: run it with a long
 * timeout on the app scope, one language at a time (re-read from Bazarr, not trusted from the list).
 */
private suspend fun searchOne(s: ServiceConfig, kind: Kind, row: WantedRow): String {
    val langs = row.missing.filter { !it.code2.isNullOrBlank() }
    if (langs.isEmpty()) throw ServiceException("That item no longer needs subtitles")
    for (l in langs) {
        val q = "language=${enc(l.code2!!)}&forced=${l.forced}&hi=${l.hi}"
        val path = when (kind) {
            Kind.Episodes -> "/api/episodes/subtitles?seriesid=${row.seriesId}&episodeid=${row.id}&$q"
            Kind.Movies -> "/api/movies/subtitles?radarrid=${row.id}&$q"
        }
        longCall(s, path, "PATCH", timeoutSec = 150)
    }
    return "Subtitle search finished for ${row.title}"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WantedTab(s: ServiceConfig, hub: RefreshHub, overview: Loader<BazarrOverview>) {
    var kind by rememberSaveable { mutableStateOf(Kind.Episodes) }
    var confirmAll by rememberSaveable { mutableStateOf(false) }
    val loader = rememberHubLoader(hub, "svc:bazarr:${s.id}:wanted:${kind.name}") { loadWanted(s, kind) }
    val runner = rememberActionRunner()
    val o = overview.value
    val st = loader.state
    val page = st.value

    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
        item("stats") {
            Column(Modifier.padding(top = 12.dp)) {
                if (o == null) StatRow { repeat(2) { SkeletonBox(Modifier.weight(1f).height(64.dp), RoundedCornerShape(16.dp)) } }
                else StatRow {
                    // Bazarr's badges count missing languages, not items.
                    StatTile("Episode subs missing", o.wantedEpisodes.toString(), Modifier.weight(1f))
                    StatTile("Movie subs missing", o.wantedMovies.toString(), Modifier.weight(1f))
                }
            }
        }
        item("switch") { KindSwitch(kind, { kind = it }) }
        item("searchall") {
            val busy = runner.isBusy("bazarr:${s.id}:all:${kind.name}")
            FilledTonalButton(
                onClick = { confirmAll = true },
                enabled = !busy && (page?.total ?: 0) > 0,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                Icon(Icons.Rounded.Search, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Search all wanted ${kind.label.lowercase()}")
            }
        }
        when {
            page != null -> {
                if (st.error != null) item("err") { InlineError(st.error, compact = true) }
                if (page.rows.isEmpty()) item("empty") { CardEmpty("No ${kind.label.lowercase()} are missing subtitles") }
                else item("count") {
                    Text(
                        if (page.total > page.rows.size) "Showing ${page.rows.size} of ${page.total}" else plural(page.total, kind.label.lowercase().removeSuffix("s")),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(page.rows, key = { it.key }) { r ->
                    val busyKey = "bazarr:${s.id}:one:${r.key}"
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            r.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                r.missing.forEach { l -> Tag(langLabel(l), container = MaterialTheme.colorScheme.tertiaryContainer, content = MaterialTheme.colorScheme.onTertiaryContainer) }
                            }
                        }
                        if (runner.isBusy(busyKey)) CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
                        else IconButton(onClick = {
                            runner.toast("Searching subtitles for ${r.title}…")
                            runner.run(busyKey, after = { loader.refresh(); overview.refresh() }) { searchOne(s, kind, r) }
                        }) { Icon(Icons.Rounded.Search, "Search subtitles") }
                    }
                }
            }
            st.error != null -> item("err") { InlineError(st.error, onRetry = { loader.refresh() }) }
            else -> item("skeleton") { SkeletonRows(6, rowHeight = 56.dp, modifier = Modifier.padding(top = 8.dp)) }
        }
    }

    if (confirmAll) {
        ConfirmDialog(
            title = "Search all wanted ${kind.label.lowercase()}?",
            text = "Bazarr will search its providers for every missing subtitle. It runs in the background and can take a while.",
            confirm = "Search",
            onConfirm = {
                val k = kind
                runner.run("bazarr:${s.id}:all:${k.name}", success = "Bazarr started searching all wanted ${k.label.lowercase()}", after = { loader.refresh() }) {
                    app.client.send(s, "/api/system/tasks?taskid=${k.task}", "POST")
                    null
                }
            },
            onDismiss = { confirmAll = false },
        )
    }
}

/* ================================================================ history */

private val HISTORY_ACTIONS = listOf("Deleted", "Downloaded", "Manual", "Upgraded", "Uploaded", "Synced", "Translated", "Extracted")

private data class BzHistoryRow(val key: String, val title: String, val subtitle: String?, val action: String, val language: BzLang?, val provider: String?, val score: String?, val time: String?)

private suspend fun loadBzHistory(s: ServiceConfig, kind: Kind): List<BzHistoryRow> {
    val p = app.client.get<BzPage<BzHistory>>(s, "/api/${kind.path}/history?start=0&length=40")
    return p.data.mapIndexed { i, x ->
        val ep = kind == Kind.Episodes
        BzHistoryRow(
            key = "${kind.name}:$i",
            title = (if (ep) x.seriesTitle else x.title) ?: "Unknown",
            subtitle = if (ep) listOfNotNull(episodeLabel(x.episodeNumber), x.episodeTitle?.takeIf { it.isNotBlank() }).joinToString(" · ").ifBlank { null } else null,
            action = HISTORY_ACTIONS.getOrNull(x.action) ?: "Other",
            language = x.language?.takeIf { it.code2 != null || it.name != null },
            provider = x.provider?.takeIf { it.isNotBlank() },
            score = x.score?.takeIf { it.isNotBlank() },
            time = x.timestamp?.takeIf { it.isNotBlank() },
        )
    }
}

@Composable
private fun BzHistoryTab(s: ServiceConfig, hub: RefreshHub) {
    var kind by rememberSaveable { mutableStateOf(Kind.Episodes) }
    val loader = rememberHubLoader(hub, "svc:bazarr:${s.id}:history:${kind.name}") { loadBzHistory(s, kind) }
    val st = loader.state
    val rows = st.value
    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
        item("switch") { Column(Modifier.padding(top = 4.dp)) { KindSwitch(kind, { kind = it }) } }
        when {
            rows != null -> {
                if (st.error != null) item("err") { InlineError(st.error, compact = true) }
                if (rows.isEmpty()) item("empty") { CardEmpty("No subtitle history yet") }
                items(rows, key = { it.key }) { r ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(r.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            r.time?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.padding(start = 8.dp)) }
                        }
                        r.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Tag(
                                r.action,
                                container = if (r.action == "Deleted") MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                                content = if (r.action == "Deleted") MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            r.language?.let { Tag(langLabel(it)) }
                            Text(
                                listOfNotNull(r.provider, r.score?.let { "score $it" }).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            st.error != null -> item("err") { InlineError(st.error, onRetry = { loader.refresh() }) }
            else -> item("skeleton") { SkeletonRows(6, rowHeight = 56.dp) }
        }
    }
}

/* ================================================================ status */

@Composable
private fun BzStatusTab(s: ServiceConfig, loader: Loader<BazarrOverview>) {
    val runner = rememberActionRunner()
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    CardList {
        item("versions") {
            SectionCard("About", icon = Icons.Rounded.Info) {
                CardLoad(loader, placeholder = { SkeletonRows(3, rowHeight = 18.dp) }) { o ->
                    InfoLine("Bazarr", o.version ?: "—")
                    o.sonarrVersion?.let { InfoLine("Sonarr", it) }
                    o.radarrVersion?.let { InfoLine("Radarr", it) }
                    if (o.partial.isNotEmpty()) InlineError("Couldn't load ${o.partial.joinToString(", ")}", compact = true)
                }
            }
        }
        item("connections") {
            SectionCard("Live connections", icon = Icons.Rounded.Cable) {
                CardLoad(loader, placeholder = { SkeletonRows(2, rowHeight = 18.dp) }) { o ->
                    val rows = listOfNotNull(o.sonarrSignalr?.let { "Sonarr" to it }, o.radarrSignalr?.let { "Radarr" to it })
                    if (rows.isEmpty()) CardEmpty("Bazarr reports no Sonarr or Radarr connection")
                    rows.forEach { (name, v) ->
                        // badges.py: "LIVE" (or "" when the live badge is hidden) when connected, "DOWN" when not.
                        val up = !v.equals("DOWN", ignoreCase = true)
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(if (up) okColor() else MaterialTheme.colorScheme.error, size = 10.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Text(if (up) "Connected" else "Disconnected", style = MaterialTheme.typography.labelLarge, color = if (up) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
        item("health") {
            SectionCard("Health", icon = Icons.Rounded.HealthAndSafety) {
                CardLoad(loader, placeholder = { SkeletonRows(1, rowHeight = 24.dp) }) { o ->
                    if (o.health.isEmpty()) CardEmpty("No issues reported")
                    o.health.forEach { h -> HealthRow("warning", h.issue.orEmpty(), source = h.`object`) }
                }
            }
        }
        item("providers") {
            SectionCard("Providers", icon = Icons.Rounded.Hub) {
                CardLoad(loader, placeholder = { SkeletonRows(3, rowHeight = 24.dp) }) { o ->
                    if (o.providers.isEmpty()) CardEmpty("No providers enabled")
                    o.providers.forEachIndexed { i, pr ->
                        if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        val issue = pr.status?.takeIf { it.isNotBlank() && it != "Good" }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(if (issue == null) okColor() else MaterialTheme.colorScheme.error, size = 10.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(pr.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (issue != null) Text(
                                    issue + (pr.retry?.takeIf { it.isNotBlank() && it != "-" }?.let { " · retry $it" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    if (o.providers.any { it.status != null && it.status != "Good" }) {
                        OutlinedButton(
                            onClick = { confirmReset = true },
                            enabled = !runner.isBusy("bazarr:${s.id}:reset"),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        ) {
                            Icon(Icons.Rounded.RestartAlt, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Reset throttled providers")
                        }
                    }
                }
            }
        }
        item("subs") {
            loader.value?.let { o ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Subtitles, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${o.wantedEpisodes} episode and ${o.wantedMovies} movie languages missing",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    if (confirmReset) {
        ConfirmDialog(
            title = "Reset providers?",
            text = "Clears the throttle on every provider so Bazarr tries them again right away.",
            confirm = "Reset",
            onConfirm = {
                runner.run("bazarr:${s.id}:reset", success = "Providers reset", after = { loader.refresh() }) {
                    app.client.send(s, "/api/providers?action=reset", "POST")
                    null
                }
            },
            onDismiss = { confirmReset = false },
        )
    }
}
