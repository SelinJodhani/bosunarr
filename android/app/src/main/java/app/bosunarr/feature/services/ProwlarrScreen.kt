package app.bosunarr.feature.services

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.automirrored.rounded.FactCheck
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import app.bosunarr.app
import app.bosunarr.data.ServiceConfig
import app.bosunarr.net.AppJson
import app.bosunarr.net.ServiceException
import app.bosunarr.ui.art.SeaScene
import app.bosunarr.ui.common.ConfirmDialog
import app.bosunarr.ui.common.Loader
import app.bosunarr.ui.common.MessageBox
import app.bosunarr.ui.common.Tag
import app.bosunarr.ui.common.bytes
import app.bosunarr.ui.common.parseInstant
import app.bosunarr.ui.common.plural
import app.bosunarr.ui.common.relative
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/* ================================================================ API shapes */

@Serializable
private data class PStatus(val version: String? = null)

@Serializable
private data class PIndexer(
    val id: Int = 0,
    val name: String = "",
    val enable: Boolean = false,
    val protocol: String? = null,
    val privacy: String? = null,
    val priority: Int = 25,
    val implementationName: String? = null,
)

@Serializable
private data class PIndexerStatus(val indexerId: Int = 0, val initialFailure: String? = null, val mostRecentFailure: String? = null, val disabledTill: String? = null)

@Serializable
private data class PHealth(val source: String? = null, val type: String? = null, val message: String? = null)

@Serializable
private data class PCategory(val id: Int = 0, val name: String? = null)

@Serializable
private data class PRelease(
    val guid: String = "",
    val indexerId: Int = 0,
    val indexer: String? = null,
    val title: String? = null,
    val size: Long = 0,
    val publishDate: String? = null,
    val ageHours: Double? = null,
    val seeders: Int? = null,
    val leechers: Int? = null,
    val grabs: Int? = null,
    val protocol: String? = null,
    val categories: List<PCategory> = emptyList(),
)

@Serializable
private data class PStat(
    val indexerId: Int = 0,
    val indexerName: String? = null,
    val averageResponseTime: Double = 0.0,
    val numberOfQueries: Int = 0,
    val numberOfGrabs: Int = 0,
    val numberOfRssQueries: Int = 0,
    val numberOfFailedQueries: Int = 0,
    val numberOfFailedGrabs: Int = 0,
)

@Serializable
private data class PStats(val indexers: List<PStat> = emptyList())

@Serializable
private data class PFailure(val propertyName: String? = null, val errorMessage: String? = null)

@Serializable
private data class PTestResult(val id: Int = 0, val isValid: Boolean = true, val validationFailures: List<PFailure> = emptyList())

/* ================================================================ overview */

private data class IndexerRow(val i: PIndexer, val failingSince: Instant?, val disabledTill: Instant?)

private data class ProwlarrOverview(val version: String?, val health: List<PHealth>, val indexers: List<IndexerRow>)

private suspend fun loadProwlarr(s: ServiceConfig): ProwlarrOverview = coroutineScope {
    val c = app.client
    val status = async { runCatching { c.get<PStatus>(s, "/api/v1/system/status") }.getOrNull() }
    val health = async { runCatching { c.get<List<PHealth>>(s, "/api/v1/health") }.getOrDefault(emptyList()) }
    val statuses = async { runCatching { c.get<List<PIndexerStatus>>(s, "/api/v1/indexerstatus") }.getOrDefault(emptyList()) }
    val list = c.get<List<PIndexer>>(s, "/api/v1/indexer")
    val now = Instant.now()
    val byId = statuses.await().associateBy { it.indexerId }
    ProwlarrOverview(
        version = status.await()?.version,
        health = health.await().filter { !it.message.isNullOrBlank() && it.type?.lowercase() in setOf("notice", "warning", "error") },
        indexers = list.map { i ->
            val st = byId[i.id]
            val till = parseInstant(st?.disabledTill)?.takeIf { it.isAfter(now) }
            val failing = st != null && (st.mostRecentFailure != null || till != null)
            IndexerRow(i, if (failing) parseInstant(st.initialFailure ?: st.mostRecentFailure) ?: now else null, till)
        }.sortedWith(compareBy({ !it.i.enable }, { it.i.name.lowercase() })),
    )
}

@Composable
fun ProwlarrServiceScreen(s: ServiceConfig, nav: NavController) {
    val hub = rememberRefreshHub()
    val overview = rememberHubLoader(hub, "svc:prowlarr:${s.id}") { loadProwlarr(s) }
    val version = overview.value?.version
    ServiceFrame(s, nav, subtitle = listOfNotNull(s.serviceType?.label, version?.let { "v$it" }).joinToString(" · ")) { p ->
        HubRefreshBox(hub, Modifier.padding(p)) {
            TabbedBody(listOf("Indexers", "Search", "Stats")) { tab ->
                when (tab) {
                    0 -> IndexersTab(s, overview)
                    1 -> SearchTab(s, hub)
                    else -> StatsTab(s, hub)
                }
            }
        }
    }
}

/* ================================================================ indexers */

@Composable
private fun IndexersTab(s: ServiceConfig, loader: Loader<ProwlarrOverview>) {
    val runner = rememberActionRunner()
    val o = loader.value
    CardList {
        item("summary") {
            if (o == null) StatRow { repeat(3) { SkeletonBox(Modifier.weight(1f).height(64.dp), androidx.compose.foundation.shape.RoundedCornerShape(16.dp)) } }
            else StatRow {
                StatTile("Indexers", o.indexers.size.toString(), Modifier.weight(1f))
                StatTile("Enabled", o.indexers.count { it.i.enable }.toString(), Modifier.weight(1f))
                val failing = o.indexers.count { it.failingSince != null }
                StatTile("Failing", failing.toString(), Modifier.weight(1f), tint = if (failing > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            }
        }
        item("test") {
            val busy = runner.isBusy("prowlarr:${s.id}:testall")
            FilledTonalButton(
                onClick = {
                    runner.run("prowlarr:${s.id}:testall", after = { loader.refresh() }) { testAll(s) }
                },
                enabled = !busy && o != null && o.indexers.any { it.i.enable },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.AutoMirrored.Rounded.FactCheck, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (busy) "Testing indexers…" else "Test all indexers")
            }
        }
        if (o != null && o.health.isNotEmpty()) item("health") {
            SectionCard("Health", icon = Icons.Rounded.HealthAndSafety) {
                o.health.forEach { h -> HealthRow(h.type, h.message.orEmpty(), source = h.source) }
            }
        }
        item("indexers") {
            SectionCard("Indexers", icon = Icons.Rounded.TravelExplore) {
                CardLoad(loader, placeholder = { SkeletonRows(4, rowHeight = 40.dp) }) { ov ->
                    if (ov.indexers.isEmpty()) CardEmpty("No indexers yet — add them in Prowlarr")
                    ov.indexers.forEachIndexed { idx, r ->
                        if (idx > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        IndexerItem(r)
                    }
                }
            }
        }
    }
}

@Composable
private fun IndexerItem(r: IndexerRow) {
    val i = r.i
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusDot(
            when {
                !i.enable -> MaterialTheme.colorScheme.outline
                r.failingSince != null -> MaterialTheme.colorScheme.error
                else -> okColor()
            },
            size = 10.dp,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(i.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (i.enable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            val meta = listOfNotNull(
                i.implementationName?.takeIf { it.isNotBlank() && it != i.name },
                i.protocol?.replaceFirstChar { it.uppercase() },
                i.privacy?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() },
                if (!i.enable) "Disabled" else null,
            ).joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (r.failingSince != null) {
                Text(
                    "Failing since ${relative(r.failingSince)}" + (r.disabledTill?.let { " · retrying ${relative(it)}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Tag("P${i.priority}", container = MaterialTheme.colorScheme.surfaceContainerHighest, content = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Prowlarr answers 200 when every indexer passes and 400 (with the same list) when any fails. */
private suspend fun testAll(s: ServiceConfig): String {
    val (code, text) = rawCall(s, "/api/v1/indexer/testall", "POST", "{}", timeoutSec = 180)
    if (code == 401 || code == 403) throw ServiceException("${s.name} rejected the credentials", 401)
    val results = runCatching { AppJson.decodeFromString(ListSerializer(PTestResult.serializer()), text) }.getOrNull()
        ?: if (code in 200..299) return "All indexers passed" else throw ServiceException(errorDetail(text) ?: "${s.name} returned HTTP $code", code)
    val failed = results.count { !it.isValid }
    return if (failed == 0) "All ${plural(results.size, "indexer")} passed" else "$failed of ${results.size} indexers failed the test"
}

/* ================================================================ search */

private enum class Cat(val label: String, val ids: List<Int>) { All("All", emptyList()), Movies("Movies", listOf(2000)), Tv("TV", listOf(5000)), Other("Other", listOf(3000, 4000, 6000, 7000, 8000)) }

private enum class Sort(val label: String) { Relevance("Best match"), Seeders("Seeders"), Size("Size"), Newest("Newest") }

private data class ReleaseRow(val r: PRelease, val at: Instant?)

private suspend fun search(s: ServiceConfig, query: String, cat: Cat): List<ReleaseRow> {
    val cats = cat.ids.joinToString("") { "&categories=$it" }
    val text = longCall(s, "/api/v1/search?query=${enc(query)}&type=search&limit=100&offset=0$cats", timeoutSec = 90)
    val list = runCatching { AppJson.decodeFromString(ListSerializer(PRelease.serializer()), text) }.getOrElse { throw ServiceException("${s.name} sent an unexpected search response") }
    return list.filter { it.guid.isNotBlank() && it.indexerId > 0 }
        .distinctBy { it.indexerId to it.guid } // list keys must be unique
        .map { ReleaseRow(it, parseInstant(it.publishDate)) }
}

@Composable
private fun SearchTab(s: ServiceConfig, hub: RefreshHub) {
    var input by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf("") }
    var cat by rememberSaveable { mutableStateOf(Cat.All) }
    var sort by rememberSaveable { mutableStateOf(Sort.Relevance) }
    var grab by remember { mutableStateOf<PRelease?>(null) }
    val focus = LocalFocusManager.current
    val runner = rememberActionRunner()

    fun submit() {
        val q = input.trim()
        if (q.isNotEmpty()) {
            submitted = q
            focus.clearFocus()
        }
    }

    // One loader per (query, category): results survive tab switches and navigation; pull-to-refresh re-runs it.
    val loader = if (submitted.isNotBlank()) {
        rememberHubLoader(hub, "svc:prowlarr:${s.id}:search:${cat.name}:$submitted", refreshMs = null, staleMs = Long.MAX_VALUE) { search(s, submitted, cat) }
    } else null
    val st = loader?.state
    val rows = st?.value
    val sorted = remember(rows, sort) {
        when (sort) {
            Sort.Relevance -> rows
            Sort.Seeders -> rows?.sortedByDescending { it.r.seeders ?: -1 }
            Sort.Size -> rows?.sortedByDescending { it.r.size }
            Sort.Newest -> rows?.sortedByDescending { it.at ?: Instant.EPOCH }
        }
    }

    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
        item("box") {
            OutlinedTextField(
                value = input,
                onValueChange = { if (it.length <= 200) input = it },
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
                placeholder = { Text("Search all indexers") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (input.isNotEmpty()) IconButton(onClick = { input = "" }) { Icon(Icons.Rounded.Clear, "Clear") } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
            )
        }
        item("cats") {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(Cat.entries, key = { it.name }) { c -> FilterChip(selected = cat == c, onClick = { cat = c }, label = { Text(c.label) }) }
            }
        }
        when {
            loader == null || st == null -> item("hint") {
                MessageBox(Icons.Rounded.TravelExplore, "Search your indexers", hint = "Results come straight from Prowlarr. Grabbing sends the release to Prowlarr's download client.", scene = SeaScene.Spyglass, fullBleed = false)
            }
            sorted != null -> {
                item("head") {
                    Column {
                        if (st.loading) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                        if (st.error != null) InlineError(st.error, compact = true)
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(Sort.entries, key = { it.name }) { o -> FilterChip(selected = sort == o, onClick = { sort = o }, label = { Text(o.label) }) }
                        }
                        Text(
                            plural(sorted.size, "result"),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                        if (sorted.isEmpty()) CardEmpty("Nothing found")
                    }
                }
                items(sorted, key = { "${it.r.indexerId}:${it.r.guid}" }) { r -> ReleaseItem(r) { grab = r.r } }
            }
            st.error != null -> item("error") { InlineError(st.error, onRetry = { loader.refresh() }) }
            else -> item("loading") {
                Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Searching indexers…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    grab?.let { r ->
        ConfirmDialog(
            title = "Grab this release?",
            text = "${r.title ?: "Untitled"}\n\n${listOfNotNull(r.indexer, bytes(r.size).takeIf { r.size > 0 }).joinToString(" · ")}\n\nProwlarr will send it to its download client.",
            confirm = "Grab",
            onConfirm = {
                runner.run("prowlarr:${s.id}:grab:${r.guid}") {
                    try {
                        longCall(s, "/api/v1/search", "POST", buildJsonObject { put("guid", r.guid); put("indexerId", r.indexerId) }.toString(), timeoutSec = 60)
                    } catch (e: ServiceException) {
                        throw when (e.status) {
                            404 -> ServiceException("${s.name} no longer has this release cached — search again", 410)
                            409 -> ServiceException("The indexer refused the download", 409)
                            else -> e
                        }
                    }
                    "Sent to the download client"
                }
            },
            onDismiss = { grab = null },
        )
    }
}

@Composable
private fun ReleaseItem(row: ReleaseRow, onClick: () -> Unit) {
    val r = row.r
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(r.title ?: "(untitled)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            val torrent = r.protocol == "torrent"
            Tag(if (torrent) "Torrent" else if (r.protocol == "usenet") "Usenet" else "?", container = MaterialTheme.colorScheme.surfaceContainerHighest, content = MaterialTheme.colorScheme.onSurfaceVariant)
            val age = row.at?.let { relative(it).removeSuffix(" ago") } ?: r.ageHours?.let { h -> if (h < 48) "${h.toInt()} h" else "${(h / 24).toInt()} days" }
            Text(
                listOfNotNull(r.indexer, bytes(r.size).takeIf { r.size > 0 }, age).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (torrent) {
                val seeders = r.seeders ?: 0
                Text(
                    "▲$seeders ▼${r.leechers ?: 0}",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (seeders > 0) okColor() else MaterialTheme.colorScheme.error,
                    maxLines = 1,
                )
            } else r.grabs?.let { Text(plural(it, "grab"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
        }
    }
}

/* ================================================================ stats */

private val PERIODS = listOf(7, 30, 90)

private suspend fun loadStats(s: ServiceConfig, days: Int): List<PStat> {
    val end = Instant.now()
    val start = end.minusSeconds(days * 86_400L)
    val r = app.client.get<PStats>(s, "/api/v1/indexerstats?startDate=${enc(start.toString())}&endDate=${enc(end.toString())}")
    return r.indexers.sortedByDescending { it.numberOfQueries + it.numberOfGrabs }
}

@Composable
private fun StatsTab(s: ServiceConfig, hub: RefreshHub) {
    var days by rememberSaveable { mutableIntStateOf(30) }
    val loader = rememberHubLoader(hub, "svc:prowlarr:${s.id}:stats:$days", refreshMs = 60_000) { loadStats(s, days) }
    val list = loader.value
    CardList {
        item("periods") {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PERIODS.forEach { d -> FilterChip(selected = days == d, onClick = { days = d }, label = { Text("$d days") }) }
            }
        }
        item("totals") {
            if (list == null) StatRow { repeat(3) { SkeletonBox(Modifier.weight(1f).height(64.dp), androidx.compose.foundation.shape.RoundedCornerShape(16.dp)) } }
            else StatRow {
                StatTile("Queries", list.sumOf { it.numberOfQueries }.toString(), Modifier.weight(1f))
                StatTile("Grabs", list.sumOf { it.numberOfGrabs }.toString(), Modifier.weight(1f))
                val failed = list.sumOf { it.numberOfFailedQueries + it.numberOfFailedGrabs }
                StatTile("Failures", failed.toString(), Modifier.weight(1f), tint = if (failed > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            }
        }
        item("per") {
            SectionCard("Per indexer", icon = Icons.Rounded.BarChart) {
                CardLoad(loader, placeholder = { SkeletonRows(4, rowHeight = 36.dp) }) { rows ->
                    if (rows.isEmpty()) CardEmpty("No activity in this period")
                    val max = (rows.maxOfOrNull { it.numberOfQueries } ?: 0).coerceAtLeast(1)
                    rows.forEach { st ->
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(st.indexerName ?: "Indexer ${st.indexerId}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text("${st.averageResponseTime.toInt()} ms", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.height(4.dp))
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = { st.numberOfQueries.toFloat() / max },
                                modifier = Modifier.fillMaxWidth().height(4.dp),
                                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                drawStopIndicator = {},
                            )
                            Spacer(Modifier.height(4.dp))
                            val fails = st.numberOfFailedQueries + st.numberOfFailedGrabs
                            Text(
                                "${plural(st.numberOfQueries, "query", "queries")} · ${plural(st.numberOfGrabs, "grab")} · ${st.numberOfRssQueries} RSS" + if (fails > 0) " · $fails failed" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (fails > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
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
