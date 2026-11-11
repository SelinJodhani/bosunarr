package app.bosunarr.feature.services

import app.bosunarr.ui.art.SeaScene
import app.bosunarr.ui.common.MessageBox
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RssFeed
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import app.bosunarr.app
import app.bosunarr.data.ServiceConfig
import app.bosunarr.data.ServiceType
import app.bosunarr.feature.dashboard.DiskSpace
import app.bosunarr.feature.dashboard.dedupeDisks
import app.bosunarr.nav.ActivityRoute
import app.bosunarr.nav.LibraryRoute
import app.bosunarr.nav.MovieRoute
import app.bosunarr.nav.SeriesRoute
import app.bosunarr.net.ServiceException
import app.bosunarr.ui.common.ConfirmDialog
import app.bosunarr.ui.common.Poster
import app.bosunarr.ui.common.Tag
import app.bosunarr.ui.common.parseInstant
import app.bosunarr.ui.common.relative
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/* ================================================================ API shapes */

@Serializable
private data class ArrStatus(
    val appName: String? = null,
    val version: String = "",
    val branch: String? = null,
    val startTime: String? = null,
    val osName: String? = null,
    val osVersion: String? = null,
    val isDocker: Boolean = false,
    val databaseType: String? = null,
)

@Serializable
private data class ArrHealth(val source: String? = null, val type: String? = null, val message: String? = null)

@Serializable
private data class RootFolder(val id: Int = 0, val path: String = "", val accessible: Boolean = true, val freeSpace: Long? = null)

@Serializable
private data class ArrQueueStatus(val totalCount: Int? = null, val count: Int? = null, val errors: Boolean = false, val warnings: Boolean = false)

@Serializable
private data class ArrCommand(
    val id: Int = 0,
    val name: String? = null,
    val commandName: String? = null,
    val status: String? = null,
    val queued: String? = null,
    val started: String? = null,
    val ended: String? = null,
    val trigger: String? = null,
)

@Serializable
private data class TotalOnly(val totalRecords: Int = 0)

@Serializable
private data class ArrHistoryPage(val totalRecords: Int = 0, val records: List<ArrHistory> = emptyList())

@Serializable
private data class ArrHistory(
    val id: Int = 0,
    val eventType: String = "",
    val date: String? = null,
    val sourceTitle: String? = null,
    val quality: QualityWrap? = null,
    val data: Map<String, String?> = emptyMap(),
    val seriesId: Int? = null,
    val movieId: Int? = null,
    val series: HSeries? = null,
    val episode: HEpisode? = null,
    val movie: HMovie? = null,
)

@Serializable
private data class QualityWrap(val quality: QualityName? = null)

@Serializable
private data class QualityName(val name: String? = null)

@Serializable
private data class HSeries(val id: Int = 0, val title: String = "", val images: List<ArrImage> = emptyList())

@Serializable
private data class HEpisode(val seasonNumber: Int = 0, val episodeNumber: Int = 0, val title: String? = null)

@Serializable
private data class HMovie(val id: Int = 0, val title: String = "", val year: Int = 0, val images: List<ArrImage> = emptyList())

/* ================================================================ models */

private data class ArrDisk(val path: String, val free: Long, val total: Long?, val rootFolder: Boolean, val accessible: Boolean)

private data class ArrTask(val id: Int, val name: String, val command: String?, val status: String, val at: String?)

private data class ArrOverview(
    val status: ArrStatus,
    val health: List<ArrHealth>,
    val disks: List<ArrDisk>,
    val queue: ArrQueueStatus?,
    val missing: Int?,
    val cutoff: Int?,
    val tasks: List<ArrTask>,
    val partial: List<String>,
)

/** Actions offered as buttons, with each arr's command name. */
private enum class ArrAction(val label: String, val sonarr: String, val radarr: String, val done: String, val confirm: String?) {
    Rss("RSS sync", "RssSync", "RssSync", "RSS sync started", null),
    Refresh("Refresh all", "RefreshSeries", "RefreshMovie", "Refresh & scan started", null),
    Missing("Search missing", "MissingEpisodeSearch", "MissingMoviesSearch", "Searching for everything missing", "Search indexers for every monitored item that's missing? This can send a lot of queries to your indexers."),
    Backup("Backup", "Backup", "Backup", "Backup started", null);

    fun command(sonarr: Boolean) = if (sonarr) this.sonarr else radarr
}

private val COMMAND_LABELS = mapOf(
    "RssSync" to "RSS sync",
    "MissingEpisodeSearch" to "Search missing episodes",
    "MissingMoviesSearch" to "Search missing movies",
    "CutoffUnmetEpisodeSearch" to "Search cutoff unmet",
    "CutoffUnmetMoviesSearch" to "Search cutoff unmet",
    "RefreshSeries" to "Refresh & scan series",
    "RefreshMovie" to "Refresh & scan movies",
    "Backup" to "Backup",
    "DownloadedEpisodesScan" to "Scan downloaded episodes",
    "DownloadedMoviesScan" to "Scan downloaded movies",
    "EpisodeSearch" to "Episode search",
    "SeasonSearch" to "Season search",
    "SeriesSearch" to "Series search",
    "MoviesSearch" to "Movie search",
    "RenameFiles" to "Rename files",
    "RenameMovie" to "Rename movies",
    "RenameSeries" to "Rename series",
)

/**
 * Housekeeping the arr runs by itself every few minutes. Always hidden: some versions (Radarr 6.4) report them
 * as "manual" because reading the queue over the API triggers them.
 */
private val SCHEDULED = setOf(
    "RefreshMonitoredDownloads", "ProcessMonitoredDownloads", "MessagingCleanup", "Housekeeping", "CheckHealth",
    "ApplicationUpdateCheck", "ImportListSync", "CleanUpRecycleBin",
)

private fun commandLabel(c: ArrCommand) =
    COMMAND_LABELS[c.name] ?: c.commandName ?: (c.name ?: "Task").replace(Regex("([a-z])([A-Z])"), "$1 $2")

private fun instant(s: String?): Instant? = parseInstant(s)

/** Root folders first (with the total of the drive they live on), then drives that hold no root folder. */
private fun arrDisks(space: List<DiskSpace>, roots: List<RootFolder>): List<ArrDisk> {
    fun norm(p: String) = p.replace('\\', '/').trimEnd('/').lowercase()
    val used = mutableSetOf<String>()
    val out = roots.map { r ->
        val np = norm(r.path)
        val d = space.filter { val nd = norm(it.path); np == nd || np.startsWith("$nd/") || nd.isEmpty() }.maxByOrNull { norm(it.path).length }
        if (d != null) used += d.path
        ArrDisk(r.path, if (r.accessible) (r.freeSpace ?: d?.freeSpace ?: 0) else 0, if (r.accessible) d?.totalSpace else null, true, r.accessible)
    }
    val rest = dedupeDisks(space.filter { it.path !in used }).map { ArrDisk(it.path, it.free, it.total, false, true) }
    return out + rest
}

private suspend fun loadArrOverview(s: ServiceConfig): ArrOverview = coroutineScope {
    val c = app.client
    val status = async { c.get<ArrStatus>(s, "/api/v3/system/status") }
    val health = async { runCatching { c.get<List<ArrHealth>>(s, "/api/v3/health") } }
    val space = async { runCatching { c.get<List<DiskSpace>>(s, "/api/v3/diskspace") } }
    val roots = async { runCatching { c.get<List<RootFolder>>(s, "/api/v3/rootfolder") } }
    val queue = async { runCatching { c.get<ArrQueueStatus>(s, "/api/v3/queue/status") } }
    val cmds = async { runCatching { c.get<List<ArrCommand>>(s, "/api/v3/command") } }
    val missing = async { runCatching { c.get<TotalOnly>(s, "/api/v3/wanted/missing?page=1&pageSize=1&monitored=true").totalRecords } }
    val cutoff = async { runCatching { c.get<TotalOnly>(s, "/api/v3/wanted/cutoff?page=1&pageSize=1&monitored=true").totalRecords } }
    val st = status.await() // the screen is useless without it: let its error (bad key, unreachable) surface
    if (st.version.isBlank()) throw ServiceException("${s.name} gave an unexpected answer — is this really ${s.serviceType?.label}?")
    val partial = mutableListOf<String>()
    fun <T> Result<T>.or(name: String, fallback: T): T = getOrElse { partial += name; fallback }
    val now = System.currentTimeMillis()
    val tasks = cmds.await().or("tasks", emptyList())
        .filter { it.name !in SCHEDULED && (it.trigger == null || it.trigger == "manual") }
        .filter { it.status == "started" || it.status == "queued" || (instant(it.ended)?.let { e -> now - e.toEpochMilli() < 10 * 60_000 } == true) }
        .sortedByDescending { (instant(it.queued) ?: instant(it.started))?.toEpochMilli() ?: 0 }
        .distinctBy { it.name }
        .take(8)
        .map { ArrTask(it.id, commandLabel(it), it.name, it.status ?: "unknown", it.ended ?: it.started ?: it.queued) }
    ArrOverview(
        status = st,
        health = health.await().or("health", emptyList()).filter { !it.message.isNullOrBlank() }
            .sortedBy { when (it.type?.lowercase()) { "error" -> 0; "warning" -> 1; else -> 2 } },
        disks = arrDisks(space.await().or("disks", emptyList()), roots.await().or("root folders", emptyList())),
        queue = queue.await().or("queue", null),
        missing = missing.await().getOrNull(),
        cutoff = cutoff.await().getOrNull(),
        tasks = tasks,
        partial = partial,
    )
}

/* ================================================================ screen */

@Composable
fun ArrServiceScreen(s: ServiceConfig, nav: NavController) {
    val hub = rememberRefreshHub()
    val overview = rememberHubLoader(hub, "svc:arr:${s.id}", refreshMs = 15_000) { loadArrOverview(s) }
    val version = overview.value?.status?.version
    ServiceFrame(s, nav, subtitle = listOfNotNull(s.serviceType?.label, version?.let { "v$it" }).joinToString(" · ")) { p ->
        HubRefreshBox(hub, Modifier.padding(p)) {
            TabbedBody(listOf("Overview", "History")) { tab ->
                when (tab) {
                    0 -> ArrOverviewTab(s, overview, nav)
                    else -> ArrHistoryTab(s, hub, nav)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ArrOverviewTab(s: ServiceConfig, loader: app.bosunarr.ui.common.Loader<ArrOverview>, nav: NavController) {
    val sonarr = s.serviceType == ServiceType.Sonarr
    val runner = rememberActionRunner()
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }
    val o = loader.value

    fun run(a: ArrAction) = runner.run("arr:${s.id}:${a.name}", success = a.done, after = {
        loader.refresh()
    }) {
        app.client.send(s, "/api/v3/command", "POST", buildJsonObject { put("name", a.command(sonarr)) })
        delay(600) // give the arr a moment so the refresh shows the task as queued
        null
    }

    CardList {
        item("system") {
            SectionCard("System", icon = Icons.Rounded.Info) {
                CardLoad(loader, placeholder = { SkeletonRows(3, rowHeight = 18.dp) }) { ov ->
                    val st = ov.status
                    InfoLine("Version", listOfNotNull(st.version, st.branch?.let { "($it)" }).joinToString(" "))
                    instant(st.startTime)?.let { InfoLine("Uptime", relative(it).removeSuffix(" ago")) }
                    val os = listOfNotNull(st.osName?.replaceFirstChar { it.uppercase() }, st.osVersion).joinToString(" ")
                    if (os.isNotBlank()) InfoLine("System", os + if (st.isDocker) " · Docker" else "")
                    st.databaseType?.let { InfoLine("Database", it) }
                    if (ov.partial.isNotEmpty()) InlineError("Couldn't load ${ov.partial.joinToString(", ")}", compact = true)
                }
            }
        }
        item("stats") {
            if (o == null) StatRow { repeat(3) { SkeletonBox(Modifier.weight(1f).height(64.dp), androidx.compose.foundation.shape.RoundedCornerShape(16.dp)) } }
            else StatRow {
                val q = o.queue
                StatTile(
                    "In queue", q?.let { (it.totalCount ?: it.count ?: 0).toString() } ?: "—",
                    Modifier.weight(1f),
                    onClick = { nav.navigateTab(ActivityRoute) },
                    tint = when { q?.errors == true -> MaterialTheme.colorScheme.error; q?.warnings == true -> warnColor(); else -> MaterialTheme.colorScheme.onSurface },
                )
                StatTile("Missing", o.missing?.toString() ?: "—", Modifier.weight(1f))
                StatTile("Cutoff unmet", o.cutoff?.toString() ?: "—", Modifier.weight(1f))
            }
        }
        item("actions") {
            SectionCard("Actions", icon = Icons.Rounded.PlayCircle) {
                val running = o?.tasks?.filter { it.status == "started" || it.status == "queued" }?.mapNotNull { it.command }?.toSet().orEmpty()
                FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    maxItemsInEachRow = 2,
                ) {
                    ArrAction.entries.forEach { a ->
                        val busy = runner.isBusy("arr:${s.id}:${a.name}") || a.command(sonarr) in running
                        FilledTonalButton(
                            onClick = { if (a.confirm != null) confirm = a.name else run(a) },
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Icon(
                                when (a) {
                                    ArrAction.Rss -> Icons.Rounded.RssFeed
                                    ArrAction.Refresh -> Icons.Rounded.Refresh
                                    ArrAction.Missing -> Icons.Rounded.Search
                                    ArrAction.Backup -> Icons.Rounded.Backup
                                },
                                null, Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(a.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (s.serviceType != ServiceType.Whisparr) TextButton(onClick = { nav.navigateTab(LibraryRoute) }, modifier = Modifier.padding(start = 8.dp)) {
                    Icon(Icons.Rounded.VideoLibrary, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (sonarr) "Open series in Library" else "Open movies in Library")
                }
            }
        }
        if (o != null && o.tasks.isNotEmpty()) item("tasks") {
            SectionCard("Tasks", icon = Icons.Rounded.History) {
                o.tasks.forEach { t -> TaskRow(t) }
            }
        }
        item("health") {
            SectionCard("Health", icon = Icons.Rounded.HealthAndSafety) {
                CardLoad(loader, placeholder = { SkeletonRows(1, rowHeight = 24.dp) }) { ov ->
                    if (ov.health.isEmpty()) CardEmpty("No issues reported")
                    ov.health.forEach { h -> HealthRow(h.type, h.message.orEmpty(), source = h.source) }
                }
            }
        }
        item("disks") {
            SectionCard("Disk space", icon = Icons.Rounded.Storage) {
                CardLoad(loader, placeholder = { SkeletonRows(2, rowHeight = 32.dp) }) { ov ->
                    if (ov.disks.isEmpty()) CardEmpty("No disks reported")
                    ov.disks.forEach { d ->
                        DiskRow(d.path, d.free, d.total, note = when {
                            !d.accessible -> "Root folder not accessible"
                            d.rootFolder -> "Root folder"
                            else -> null
                        })
                    }
                }
            }
        }
    }

    val pending = confirm?.let { n -> ArrAction.entries.firstOrNull { it.name == n } }
    if (pending != null) {
        ConfirmDialog(
            title = "${pending.label}?",
            text = pending.confirm,
            confirm = pending.label,
            onConfirm = { run(pending) },
            onDismiss = { confirm = null },
        )
    }
}

@Composable
private fun TaskRow(t: ArrTask) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(t.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            instant(t.at)?.let { Text(relative(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.width(8.dp))
        when (t.status) {
            "started" -> Tag("Running", container = MaterialTheme.colorScheme.primaryContainer, content = MaterialTheme.colorScheme.onPrimaryContainer)
            "queued" -> Tag("Queued")
            "completed" -> Tag("Done", container = okColor().copy(alpha = 0.18f), content = MaterialTheme.colorScheme.onSurface)
            "failed", "aborted", "orphaned" -> Tag(t.status.replaceFirstChar { it.uppercase() }, container = MaterialTheme.colorScheme.errorContainer, content = MaterialTheme.colorScheme.onErrorContainer)
            else -> Tag(t.status.replaceFirstChar { it.uppercase() })
        }
    }
}

/* ================================================================ history */

private enum class HistoryFilter(val label: String) { All("All"), Grabbed("Grabbed"), Imported("Imported"), Failed("Failed") }

private data class HistoryRow(
    val key: String,
    val title: String,
    val subtitle: String?,
    val event: String,
    val tone: String,
    val source: String?,
    val quality: String?,
    val detail: String?,
    val at: Instant?,
    val poster: ImageRef?,
    val route: Any?,
)

private val EVENTS = mapOf(
    "grabbed" to ("Grabbed" to "grab"),
    "downloadFolderImported" to ("Imported" to "ok"),
    "seriesFolderImported" to ("Imported from folder" to "ok"),
    "movieFolderImported" to ("Imported from folder" to "ok"),
    "downloadFailed" to ("Download failed" to "error"),
    "episodeFileDeleted" to ("File deleted" to "other"),
    "movieFileDeleted" to ("File deleted" to "other"),
    "episodeFileRenamed" to ("File renamed" to "other"),
    "movieFileRenamed" to ("File renamed" to "other"),
    "downloadIgnored" to ("Ignored" to "other"),
)

private val DELETE_REASONS = mapOf("Manual" to "Deleted manually", "MissingFromDisk" to "Missing from disk", "Upgrade" to "Replaced by an upgrade")

private fun historyDetail(h: ArrHistory): String? {
    val d = h.data
    val client = d["downloadClientName"]?.takeIf { it.isNotBlank() } ?: d["downloadClient"]?.takeIf { it.isNotBlank() }
    return when {
        h.eventType == "downloadFailed" || h.eventType == "downloadIgnored" ->
            d["message"]?.replace(Regex("^\\s*download (failed|ignored)\\s*[:.-]\\s*", RegexOption.IGNORE_CASE), "")?.trim()?.takeIf { it.isNotEmpty() }?.replaceFirstChar { it.uppercase() }
        h.eventType.endsWith("FileDeleted") -> d["reason"]?.let { DELETE_REASONS[it] ?: it }
        h.eventType == "grabbed" -> listOfNotNull(d["indexer"]?.takeIf { it.isNotBlank() }, client).joinToString(" → ").ifBlank { null }
        h.eventType.endsWith("Imported") -> client?.let { "From $it" }
        else -> null
    }
}

private suspend fun loadHistory(s: ServiceConfig, filter: HistoryFilter): List<HistoryRow> {
    val sonarr = s.serviceType == ServiceType.Sonarr
    val include = if (sonarr) "includeSeries=true&includeEpisode=true" else "includeMovie=true"
    val ev = when (filter) {
        HistoryFilter.All -> ""
        HistoryFilter.Grabbed -> "&eventType=1"
        HistoryFilter.Failed -> "&eventType=4"
        HistoryFilter.Imported -> "&eventType=3&eventType=${if (sonarr) 2 else 7}"
    }
    val page = app.client.get<ArrHistoryPage>(s, "/api/v3/history?page=1&pageSize=30&sortKey=date&sortDirection=descending&$include$ev")
    return page.records.map { h ->
        val (label, tone) = EVENTS[h.eventType] ?: (h.eventType.replace(Regex("([a-z])([A-Z])"), "$1 $2").replaceFirstChar { it.uppercase() } to "other")
        val seriesId = h.seriesId ?: h.series?.id
        val movieId = h.movieId ?: h.movie?.id
        HistoryRow(
            key = "h:${h.id}",
            title = if (sonarr) h.series?.title ?: "Unknown series" else h.movie?.let { m -> m.title + if (m.year > 0) " (${m.year})" else "" } ?: "Unknown movie",
            subtitle = h.episode?.let { e -> episodeCode(e.seasonNumber, e.episodeNumber) + (e.title?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "") },
            event = label,
            tone = tone,
            source = h.sourceTitle,
            quality = h.quality?.quality?.name,
            detail = historyDetail(h),
            at = instant(h.date),
            poster = arrPoster(s, if (sonarr) h.series?.images else h.movie?.images, 250, ownerId = (if (sonarr) seriesId else movieId) ?: 0),
            route = if (sonarr) seriesId?.takeIf { it > 0 }?.let { SeriesRoute(s.id, it) } else movieId?.takeIf { it > 0 }?.let { MovieRoute(s.id, it) },
        )
    }
}

@Composable
private fun ArrHistoryTab(s: ServiceConfig, hub: RefreshHub, nav: NavController) {
    var filter by rememberSaveable { mutableStateOf(HistoryFilter.All) }
    val loader = rememberHubLoader(hub, "svc:arr:${s.id}:history:${filter.name}") { loadHistory(s, filter) }
    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
        item("filters") {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(HistoryFilter.entries, key = { it.name }) { f ->
                    FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
                }
            }
        }
        val st = loader.state
        val rows = st.value
        when {
            rows != null -> {
                if (st.error != null) item("err") { InlineError(st.error, compact = true) }
                if (rows.isEmpty()) item("empty") { MessageBox(Icons.Rounded.History, "Nothing logged yet", hint = if (filter == HistoryFilter.All) "Grabs, imports and failures show up here." else "No ${filter.label.lowercase()} events recently.", scene = SeaScene.Logbook) }
                items(rows, key = { it.key }) { r -> HistoryItemRow(s, r, nav) }
            }
            st.error != null -> item("err") { InlineError(st.error, onRetry = { loader.refresh() }) }
            else -> item("skeleton") { SkeletonRows(6, rowHeight = 64.dp) }
        }
    }
}

@Composable
private fun HistoryItemRow(s: ServiceConfig, r: HistoryRow, nav: NavController) {
    val route = r.route
    Row(
        Modifier.fillMaxWidth().then(if (route != null) Modifier.clickable { nav.navigate(route) } else Modifier).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Poster(r.poster?.service ?: s, r.poster?.path, width = 44.dp, title = r.title)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(r.at?.let { relative(it) }.orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            r.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                val (bg, fg) = toneColors(r.tone)
                Tag(r.event, container = bg, content = fg)
                r.quality?.let { Tag(it, container = MaterialTheme.colorScheme.surfaceContainerHighest, content = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            r.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            r.source?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun toneColors(tone: String): Pair<Color, Color> = when (tone) {
    "grab" -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    "ok" -> okColor().copy(alpha = 0.18f) to MaterialTheme.colorScheme.onSurface
    "error" -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    else -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
}
