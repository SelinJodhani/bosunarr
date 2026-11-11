package app.bosunarr.feature.discover

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.navigation.NavController
import app.bosunarr.data.ServiceConfig
import app.bosunarr.ui.common.friendlyMessage
import app.bosunarr.ui.common.AppScaffold
import app.bosunarr.ui.common.ConfirmDialog
import app.bosunarr.ui.common.ErrorBox
import app.bosunarr.ui.common.LocalSnackbar
import app.bosunarr.ui.common.Poster
import app.bosunarr.ui.common.SectionTitle
import app.bosunarr.ui.common.ServiceImage
import app.bosunarr.ui.common.Tag
import app.bosunarr.ui.common.back
import app.bosunarr.ui.common.date
import app.bosunarr.ui.common.plural
import app.bosunarr.ui.common.relative
import app.bosunarr.ui.common.rememberLoader
import app.bosunarr.ui.common.runtime
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.Locale

/* ------------------------------------------------------------------ Detail model */

data class SeasonRow(val number: Int, val name: String, val episodes: Int, val airDate: String?, val status: Avail, val status4k: Avail)

data class MediaDetail(
    val type: String,
    val d: SeerrDetail,
    val item: MediaItem,
    val seasons: List<SeasonRow>,
    /** Seerr has a 4K Radarr (movie) / Sonarr (tv) configured. */
    val can4k: Boolean,
    val canManage: Boolean,
    val servers: List<ArrServer>,
) {
    val isMovie get() = type == "movie"
    val regularOpen get() = if (isMovie) item.status == Avail.None else seasons.any { it.status == Avail.None }
    val uhdOpen get() = can4k && (if (isMovie) item.status4k == Avail.None else seasons.any { it.status4k == Avail.None })
    val requests: List<SeerrRequest> get() = d.mediaInfo?.requests.orEmpty().sortedByDescending { it.createdAt.orEmpty() }
}

suspend fun loadDetail(s: ServiceConfig, type: String, id: Int): MediaDetail = coroutineScope {
    val servers = async { Seerr.servers(s, type) }
    val manage = async { Seerr.canManage(s) }
    val d = Seerr.detail(s, type, id)
    val item = SeerrResult(
        id = id, mediaType = type, title = d.title, name = d.name, posterPath = d.posterPath, backdropPath = d.backdropPath,
        releaseDate = d.releaseDate, firstAirDate = d.firstAirDate, voteAverage = d.voteAverage, mediaInfo = d.mediaInfo,
    ).toItem() ?: throw IllegalStateException("Not found")
    Peek.remember(listOf(item))
    MediaDetail(type, d, item, seasonsOf(type, d), servers.await().any { it.is4k }, manage.await(), servers.await())
}

/** Per-season status per copy; a pending/approved request counts even before Seerr updates the season rows. */
private fun seasonsOf(type: String, d: SeerrDetail): List<SeasonRow> {
    if (type != "tv") return emptyList()
    val mi = d.mediaInfo
    val rows = mi?.seasons.orEmpty().associateBy { it.seasonNumber }
    val requested = HashMap<String, Avail>()
    for (r in mi?.requests.orEmpty()) {
        val a = when (r.reqStatus) { ReqStatus.Pending -> Avail.Pending; ReqStatus.Approved -> Avail.Processing; else -> continue }
        for (se in r.seasons) requested["${if (r.is4k) 1 else 0}:${se.seasonNumber}"] = a
    }
    fun merge(a: Avail, req: Avail?) = if (a == Avail.None && req != null) req else a
    return d.seasons.filter { it.seasonNumber > 0 }.sortedBy { it.seasonNumber }.distinctBy { it.seasonNumber }.map { x ->
        val st = rows[x.seasonNumber]
        SeasonRow(
            x.seasonNumber, x.name?.takeIf { it.isNotBlank() } ?: "Season ${x.seasonNumber}", x.episodeCount, x.airDate,
            merge(avail(st?.status), requested["0:${x.seasonNumber}"]), merge(avail(st?.status4k), requested["1:${x.seasonNumber}"]),
        )
    }
}

/** The request buttons: explicit about which copy each asks for. `uhd` null = let the sheet decide. */
private data class RequestButton(val label: String, val uhd: Boolean?)

private fun requestButtons(m: MediaDetail): List<RequestButton> {
    if (m.isMovie) {
        if (m.item.status == Avail.None) return listOf(RequestButton(if (m.item.status4k != Avail.None) "Request HD" else "Request", false))
        if (m.can4k && m.item.status4k == Avail.None) return listOf(RequestButton("Request 4K", true))
        return emptyList()
    }
    val open = m.seasons.count { it.status == Avail.None }
    if (open > 0) return listOf(RequestButton(if (open < m.seasons.size) "Request more seasons" else "Request", null))
    if (m.can4k && m.seasons.any { it.status4k == Avail.None }) return listOf(RequestButton("Request 4K", true))
    return emptyList()
}

/* ------------------------------------------------------------------ Screen */

@Composable
fun MediaScreen(nav: NavController, type: String, tmdbId: Int) {
    val list = rememberSeerrServices()
    val service = list?.let { currentSeerr(it) }
    if (list == null || service == null || (type != "movie" && type != "tv")) {
        AppScaffold(if (list != null) "Discover" else "", onBack = { nav.back() }) { p -> if (list != null) NoSeerr(nav, Modifier.padding(p)) }
        return
    }
    key(service.id) { MediaContent(nav, service, type, tmdbId) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun MediaContent(nav: NavController, s: ServiceConfig, type: String, id: Int) {
    val loader = rememberLoader("seerr-detail:${s.id}:$type:$id") { loadDetail(s, type, id) }
    val related = rememberLoader("seerr-related:${s.id}:$type:$id") {
        coroutineScope {
            val recs = async { runCatchingCancellable { Seerr.related(s, type, id, "recommendations") }.getOrElse { emptyList() } }
            val similar = async { runCatchingCancellable { Seerr.related(s, type, id, "similar") }.getOrElse { emptyList() } }
            recs.await() to similar.await()
        }
    }
    val ratings = rememberLoader("seerr-ratings:${s.id}:$type:$id") { Seerr.ratings(s, type, id) }
    OnSeerrWrite { loader.refresh() }

    val peek = remember(type, id) { Peek.get(type, id) }
    val m = loader.value
    val item = m?.item ?: peek
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current

    var sheetFor by remember { mutableStateOf<Boolean?>(null) }
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    var confirmDecline by remember { mutableStateOf<SeerrRequest?>(null) }
    var busyRequest by remember { mutableStateOf<Int?>(null) }

    fun act(r: SeerrRequest, action: Seerr.Action) {
        busyRequest = r.id
        scope.launch {
            try {
                Seerr.act(s, r.id, action)
                snackbar.showSnackbar(if (action == Seerr.Action.Approve) "Request approved" else "Request declined")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                snackbar.showSnackbar(e.friendlyMessage())
            } finally {
                busyRequest = null
            }
        }
    }

    val list = rememberLazyListState()
    val density = LocalDensity.current
    val solid by remember { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > with(density) { 140.dp.toPx() } } }
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // Light status-bar icons while the backdrop is behind them; the theme's own once the bar turns solid.
    LightStatusBarIcons(!solid)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = navBottom + 32.dp)) {
                item(key = "hero") { Hero(s, item, m) }
                item(key = "status") { StatusAndActions(m, item, ratings.value.orEmpty(), onRequest = { uhd -> sheetFor = uhd; sheetOpen = true }) }
                when {
                    m != null -> {
                        overviewSection(m)
                        if (m.seasons.isNotEmpty()) item(key = "seasons") { SeasonsCard(m) }
                        if (m.requests.isNotEmpty()) item(key = "requests") {
                            RequestsCard(s, m, busyRequest, onApprove = { act(it, Seerr.Action.Approve) }, onDecline = { confirmDecline = it })
                        }
                        val cast = m.d.credits.cast.sortedBy { it.order }.distinctBy { it.id }.take(20)
                        if (cast.isNotEmpty()) item(key = "cast") { CastRow(s, cast) }
                        item(key = "details") { DetailsCard(m, onOpen = { url -> runCatching { uri.openUri(url) } }) }
                    }
                    loader.state.error != null -> item(key = "error") { ErrorBox(loader.state.error!!, onRetry = { loader.refresh() }) }
                    else -> item(key = "skeleton") { DetailSkeleton() }
                }
                related.value?.let { (recs, similar) ->
                    if (recs.isNotEmpty()) item(key = "recs") { MediaCarousel("Recommendations", s, recs, nav) }
                    if (similar.isNotEmpty()) item(key = "similar") { MediaCarousel(if (type == "movie") "Similar movies" else "Similar series", s, similar, nav) }
                }
            }

            val bar by animateColorAsState(if (solid) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent, label = "bar")
            val icon by animateColorAsState(if (solid) MaterialTheme.colorScheme.onSurface else Color.White, label = "icon")
            val scrim by animateColorAsState(if (solid) Color.Transparent else Color.Black.copy(alpha = 0.35f), label = "scrim")
            TopAppBar(
                title = {
                    AnimatedVisibility(solid && item != null, enter = fadeIn(), exit = fadeOut()) {
                        Text(item?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { nav.back() }, colors = IconButtonDefaults.iconButtonColors(containerColor = scrim, contentColor = icon)) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { runCatching { uri.openUri("${s.url.trimEnd('/')}/$type/$id") } },
                        colors = IconButtonDefaults.iconButtonColors(containerColor = scrim, contentColor = icon),
                    ) { Icon(Icons.AutoMirrored.Rounded.OpenInNew, "Open in ${s.name}") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = bar, scrolledContainerColor = bar, titleContentColor = MaterialTheme.colorScheme.onSurface),
            )
        }
    }

    if (sheetOpen && m != null) {
        RequestSheet(
            s, m, preset4k = sheetFor,
            onDismiss = { sheetOpen = false },
            onDone = { msg -> sheetOpen = false; loader.refresh(); scope.launch { snackbar.showSnackbar(msg) } },
        )
    }
    confirmDecline?.let { r ->
        ConfirmDialog(
            title = "Decline this request?",
            text = "${item?.title.orEmpty()}${if (r.is4k) " (4K)" else ""} — requested by ${r.requestedBy?.label ?: "Unknown"}. The requester will see it as declined.",
            confirm = "Decline", destructive = true,
            onConfirm = { act(r, Seerr.Action.Decline) },
            onDismiss = { confirmDecline = null },
        )
    }
}

/** Which detail screen last set the status-bar icon style (detail → detail navigation overlaps two of them). */
private var statusBarOwner: Any? = null

@Composable
internal fun LightStatusBarIcons(enabled: Boolean) {
    val view = LocalView.current
    val lightTheme = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    val token = remember { Any() }
    DisposableEffect(view, enabled, lightTheme) {
        var ctx = view.context
        while (ctx is ContextWrapper && ctx !is Activity) ctx = ctx.baseContext
        val controller = (ctx as? Activity)?.window?.let { WindowCompat.getInsetsController(it, view) }
        statusBarOwner = token
        controller?.isAppearanceLightStatusBars = !enabled && lightTheme
        onDispose {
            // Only the screen that set it puts the theme default back (dark icons on a light theme).
            if (statusBarOwner === token) {
                statusBarOwner = null
                controller?.isAppearanceLightStatusBars = lightTheme
            }
        }
    }
}

/* ------------------------------------------------------------------ Sections */

@Composable
private fun Hero(s: ServiceConfig, item: MediaItem?, m: MediaDetail?) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val backdropH = min(maxWidth * 9f / 16f, 320.dp)
        val surface = MaterialTheme.colorScheme.surface
        Box(Modifier.fillMaxWidth().height(backdropH).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            val path = tmdbImage(item?.backdropPath, "w1280")
            if (path != null) ServiceImage(s, path, Modifier.fillMaxSize())
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.45f), 0.3f to Color.Transparent, 0.55f to Color.Transparent, 1f to surface,
                    ),
                ),
            )
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = backdropH - 88.dp), verticalAlignment = Alignment.Bottom) {
            Box(Modifier.width(116.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))) {
                Poster(s, tmdbImage(item?.posterPath, "w342"), Modifier.fillMaxWidth(), title = item?.title)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f).padding(bottom = 4.dp)) {
                if (item != null) {
                    Text(item.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Text(metaLine(item, m), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                } else {
                    SkeletonLine(0.8f, 24)
                    Spacer(Modifier.height(8.dp))
                    SkeletonLine(0.5f, 14)
                }
            }
        }
    }
}

private fun metaLine(item: MediaItem, m: MediaDetail?): String {
    val parts = mutableListOf(if (item.mediaType == "movie") "Movie" else "Series")
    item.year?.let { parts += it.toString() }
    if (m != null) {
        val rt = if (m.isMovie) m.d.runtime else m.d.episodeRunTime.firstOrNull()
        runtime(rt).takeIf { it.isNotEmpty() }?.let { parts += it }
        if (!m.isMovie && m.seasons.isNotEmpty()) parts += plural(m.seasons.size, "season")
    }
    return parts.joinToString(" · ")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusAndActions(m: MediaDetail?, item: MediaItem?, ratings: List<Seerr.Rating>, onRequest: (Boolean?) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Status per copy (further-along copy first) + ratings. From the list item too, so it's there on the first frame.
        val chips = buildList {
            if (item != null) {
                val both = item.status != Avail.None && item.status4k != Avail.None
                val copies = listOf(false to item.status, true to item.status4k).filter { it.second != Avail.None }
                val ordered = if (headline(item.status, item.status4k).second) copies.reversed() else copies
                ordered.forEach { (uhd, a) -> add(copyLabel(a, uhd, both) to a.tone) }
            }
        }
        val tmdb = item?.rating
        if (chips.isNotEmpty() || tmdb != null || ratings.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                chips.forEach { (text, tone) -> StatusBadge(text, tone, large = true) }
                if (tmdb != null) RatingChip("TMDb", String.format(Locale.US, "%.1f", tmdb), star = true)
                ratings.forEach { RatingChip(it.label, it.value, it.star) }
            }
        }
        when {
            m == null -> Box(Modifier.fillMaxWidth().height(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest))
            !m.canManage -> Unit
            else -> {
                val buttons = requestButtons(m)
                if (buttons.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    buttons.forEach { b ->
                        Button(onClick = { onRequest(b.uhd) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(b.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    // A second, explicit 4K action when both copies are still open (movie: HD + 4K).
                    if (m.isMovie && buttons.first().uhd == false && m.uhdOpen) {
                        FilledTonalButton(onClick = { onRequest(true) }) { Text("4K") }
                    }
                }
            }
        }
    }
}

@Composable
private fun RatingChip(label: String, value: String, star: Boolean) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            if (star) {
                Icon(Icons.Rounded.Star, null, Modifier.size(16.dp), tint = Color(0xFFF5C518))
                Spacer(Modifier.width(4.dp))
            }
            Text("$label $value", style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
private fun androidx.compose.foundation.lazy.LazyListScope.overviewSection(m: MediaDetail) {
    val d = m.d
    if (d.overview.isNullOrBlank() && d.tagline.isNullOrBlank() && d.genres.isEmpty()) return
    item(key = "overview") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!d.tagline.isNullOrBlank()) Text(d.tagline, style = MaterialTheme.typography.titleSmall, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!d.overview.isNullOrBlank()) {
                var expanded by rememberSaveable { mutableStateOf(false) }
                var overflows by remember { mutableStateOf(false) }
                Text(
                    d.overview, style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) Int.MAX_VALUE else 5, overflow = TextOverflow.Ellipsis,
                    onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
                )
                if (overflows || expanded) TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Text(if (expanded) "Show less" else "Show more")
                }
            }
            if (d.genres.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                d.genres.distinctBy { it.id }.forEach { Tag(it.name) }
            }
        }
    }
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeasonsCard(m: MediaDetail) {
    Card("Seasons") {
        m.seasons.forEach { se ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(se.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(plural(se.episodes, "episode"), yearOf(se.airDate)?.toString()).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(start = 8.dp)) {
                    if (se.status != Avail.None) StatusBadge(se.status.label, se.status.tone)
                    if (se.status4k != Avail.None) StatusBadge(copyLabel(se.status4k, true), se.status4k.tone)
                }
            }
        }
    }
}

@Composable
private fun RequestsCard(s: ServiceConfig, m: MediaDetail, busy: Int?, onApprove: (SeerrRequest) -> Unit, onDecline: (SeerrRequest) -> Unit) {
    Card("Requests") {
        m.requests.forEach { r ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusBadge(r.reqStatus.label, r.reqStatus.tone)
                    if (r.is4k) Tag("4K")
                    seasonsLabel(r.seasons.map { it.seasonNumber })?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                RequesterLine(s, r)
                if (m.canManage && r.reqStatus == ReqStatus.Pending) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onDecline(r) }, enabled = busy != r.id) {
                        Icon(Icons.Rounded.Close, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Decline")
                    }
                    FilledTonalButton(onClick = { onApprove(r) }, enabled = busy != r.id) {
                        Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Approve")
                    }
                }
            }
        }
    }
}

/** Avatar + "Name · 3 days ago". */
@Composable
fun RequesterLine(s: ServiceConfig, r: SeerrRequest, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        val avatar = r.requestedBy?.avatar?.let { a ->
            when {
                a.startsWith("//") -> "https:$a"
                a.startsWith("/") || a.startsWith("https://") || a.startsWith("http://") -> a
                else -> null
            }
        }
        ServiceImage(s, avatar, Modifier.size(20.dp).clip(CircleShape), contentDescription = null, placeholderIcon = Icons.Rounded.Person)
        Spacer(Modifier.width(6.dp))
        Text(
            listOf(r.requestedBy?.label ?: "Unknown", relative(r.createdAt)).filter { it.isNotEmpty() }.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

fun seasonsLabel(s: List<Int>): String? {
    val list = s.distinct().sorted()
    return when {
        list.isEmpty() -> null
        list.size > 4 -> "${list.size} seasons"
        else -> list.joinToString(", ") { "S$it" }
    }
}

@Composable
private fun CastRow(s: ServiceConfig, cast: List<SeerrCast>) {
    Column {
        SectionTitle("Cast")
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(cast, key = { it.id }) { c ->
                Column(Modifier.width(84.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    ServiceImage(s, tmdbImage(c.profilePath, "w185"), Modifier.size(76.dp).clip(CircleShape), contentDescription = c.name, placeholderIcon = Icons.Rounded.Person)
                    Text(c.name, style = MaterialTheme.typography.labelLarge, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                    Text(c.character.orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailsCard(m: MediaDetail, onOpen: (String) -> Unit) {
    val d = m.d
    val rows = buildList {
        d.status?.takeIf { it.isNotBlank() }?.let { add("Status" to it) }
        date(if (m.isMovie) d.releaseDate else d.firstAirDate).takeIf { it.isNotEmpty() }?.let { add((if (m.isMovie) "Release" else "First aired") to it) }
        (if (m.isMovie) d.productionCompanies.firstOrNull()?.name else d.networks.firstOrNull()?.name)?.takeIf { it.isNotBlank() }?.let { add((if (m.isMovie) "Studio" else "Network") to it) }
        d.credits.crew.firstOrNull { it.job == "Director" }?.name?.let { add("Director" to it) }
    }
    Card("Details") {
        rows.forEach { (k, v) ->
            Row {
                Text(k, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(104.dp))
                Text(v, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            d.externalIds.imdbId?.takeIf { it.isNotBlank() }?.let { imdb -> LinkButton("IMDb") { onOpen("https://www.imdb.com/title/$imdb/") } }
            LinkButton("TMDb") { onOpen("https://www.themoviedb.org/${m.type}/${m.item.tmdbId}") }
            d.externalIds.tvdbId?.let { tvdb -> LinkButton("TVDB") { onOpen("https://thetvdb.com/?tab=series&id=$tvdb") } }
        }
    }
}

@Composable
private fun LinkButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick) {
        Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(label)
    }
}

@Composable
private fun SkeletonLine(fraction: Float, heightDp: Int) {
    Box(Modifier.fillMaxWidth(fraction).height(heightDp.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest))
}

@Composable
private fun DetailSkeleton() {
    Column(Modifier.fillMaxWidth().padding(16.dp).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SkeletonLine(1f, 14); SkeletonLine(1f, 14); SkeletonLine(1f, 14); SkeletonLine(0.6f, 14)
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest))
    }
}
