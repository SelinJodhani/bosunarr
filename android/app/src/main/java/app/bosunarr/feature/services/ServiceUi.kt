package app.bosunarr.feature.services

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import app.bosunarr.app
import app.bosunarr.data.ServiceConfig
import app.bosunarr.nav.ServiceEditRoute
import app.bosunarr.ui.common.AppScaffold
import app.bosunarr.ui.common.Loader
import app.bosunarr.ui.common.LocalSnackbar
import app.bosunarr.ui.common.back
import app.bosunarr.ui.common.bytes
import app.bosunarr.ui.common.expandTopBarFirst
import app.bosunarr.ui.settings.ServiceBadge
import app.bosunarr.ui.common.rememberLoader
import app.bosunarr.ui.common.friendlyMessage
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/* ================================================================ refresh hub */

/**
 * Several independent loaders on one screen (dashboard cards, service sections) refreshed together by one
 * pull-to-refresh. Each loader registers its loading state; the indicator spins until all are done.
 */
class RefreshHub {
    var tick by mutableIntStateOf(0)
        private set
    var pulling by mutableStateOf(false)
        internal set
    internal val loading = mutableStateMapOf<String, Boolean>()
    /** Last tick each loader has acted on (plain map: not read by composition). */
    internal val handled = HashMap<String, Int>()

    fun refreshAll() {
        pulling = true
        tick++
    }
}

@Composable
fun rememberRefreshHub(): RefreshHub = remember { RefreshHub() }

/** When each loader key last started a fetch: lets a screen we come back to refresh data that went stale meanwhile. */
private val lastFetch = ConcurrentHashMap<String, Long>()

/**
 * [rememberLoader] tied to a [RefreshHub]: refreshes on pull, polls every [refreshMs] while visible, and refreshes
 * on return when the data is older than [staleMs].
 */
@Composable
fun <T> rememberHubLoader(hub: RefreshHub, key: String, refreshMs: Long? = 30_000, staleMs: Long = 20_000, fetch: suspend () -> T): Loader<T> {
    val loader = rememberLoader(key, refreshMs) {
        lastFetch[key] = System.currentTimeMillis()
        fetch()
    }
    val loading = loader.state.loading
    LaunchedEffect(key, loading) { hub.loading[key] = loading }
    DisposableEffect(key) { onDispose { hub.loading.remove(key) } }
    LaunchedEffect(key, hub.tick) {
        val last = hub.handled.put(key, hub.tick)
        if (last != null && last != hub.tick) loader.refresh()
    }
    LaunchedEffect(key) {
        val t = lastFetch[key]
        if (t != null && System.currentTimeMillis() - t > staleMs) loader.refresh()
    }
    return loader
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HubRefreshBox(hub: RefreshHub, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    LaunchedEffect(hub.tick) {
        if (!hub.pulling) return@LaunchedEffect
        delay(350) // let every loader see the tick and start
        withTimeoutOrNull(60_000) { snapshotFlow { hub.loading.values.any { it } }.first { !it } }
        hub.pulling = false
    }
    PullToRefreshBox(isRefreshing = hub.pulling, onRefresh = { hub.refreshAll() }, modifier = modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().expandTopBarFirst()) { content() }
    }
}

/* ================================================================ actions */

/**
 * Runs service actions (commands, grabs, deletes) on an app-wide scope so they finish even if the screen closes,
 * reports the outcome in the app snackbar, and tracks which actions are in flight to disable their buttons.
 */
class ActionRunner(private val snackbar: SnackbarHostState) {
    private val scope = CoroutineScope(app.scope.coroutineContext + Dispatchers.Main.immediate)
    private val busy = mutableStateMapOf<String, Boolean>()

    fun isBusy(key: String) = busy[key] == true

    /** [block] returns the success message (or null for [success]); [after] runs on success and failure (refresh). */
    fun run(key: String, success: String? = null, after: (() -> Unit)? = null, block: suspend () -> String?) {
        if (busy[key] == true) return
        busy[key] = true
        scope.launch {
            val msg = try {
                block() ?: success
            } catch (e: CancellationException) {
                busy.remove(key); throw e
            } catch (e: Exception) {
                e.friendlyMessage()
            }
            busy.remove(key)
            after?.invoke()
            if (msg != null) launch { snackbar.showSnackbar(msg) }
        }
    }

    fun toast(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }
}

@Composable
fun rememberActionRunner(): ActionRunner {
    val snackbar = LocalSnackbar.current
    return remember(snackbar) { ActionRunner(snackbar) }
}

/* ================================================================ frame + navigation */

/** Standard service screen frame: name, type/version subtitle, back, and an edit action. */
@Composable
fun ServiceFrame(
    service: ServiceConfig,
    nav: NavController,
    subtitle: String? = service.serviceType?.label,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit,
) {
    AppScaffold(
        service.name,
        subtitle = subtitle,
        onBack = { nav.back() },
        actions = {
            actions()
            IconButton(onClick = { nav.navigate(ServiceEditRoute(id = service.id)) }) { Icon(Icons.Rounded.Edit, "Edit service") }
        },
        floatingActionButton = floatingActionButton,
        titleIcon = { ServiceBadge(service.serviceType, size = 32.dp) },
        content = content,
    )
}

/** Switch bottom-bar tab (keeps each tab's back stack). */
fun NavController.navigateTab(route: Any) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/* ================================================================ colours */

@Composable
fun isDarkScheme(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f

@Composable
fun okColor(): Color = if (isDarkScheme()) Color(0xFF7BD88F) else Color(0xFF1E7B34)

@Composable
fun warnColor(): Color = if (isDarkScheme()) Color(0xFFFFC56B) else Color(0xFF9A6100)

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    Box(modifier.size(size).clip(CircleShape).background(color))
}

/* ================================================================ cards */

/** Corner radius of every section card (inner tiles use 16dp with 16dp gutters, so the curves nest). */
val CardShape = RoundedCornerShape(24.dp)

/** Filled M3 card with an icon + title header; tappable (chevron) when [onClick] is set. */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    val body: @Composable ColumnScope.() -> Unit = {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(12.dp))
            }
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            trailing?.invoke()
            if (onClick != null) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
        }
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) { content() }
    }
    if (onClick != null) Card(onClick = onClick, modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = CardShape, colors = colors) { body() }
    else Card(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = CardShape, colors = colors) { body() }
}

/** Body of a card driven by a [Loader]: skeleton first, inline error with retry, stale-refresh note above data. */
@Composable
fun <T> CardLoad(loader: Loader<T>, placeholder: @Composable () -> Unit, content: @Composable (T) -> Unit) {
    val s = loader.state
    val v = s.value
    when {
        v != null -> {
            if (s.error != null) InlineError(s.error, compact = true)
            content(v)
        }
        s.error != null -> InlineError(s.error, onRetry = { loader.refresh() })
        else -> placeholder()
    }
}

@Composable
fun InlineError(message: String, modifier: Modifier = Modifier, compact: Boolean = false, onRetry: (() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (compact) 2.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(if (compact) 16.dp else 20.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            friendlyMessage(message),
            style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onRetry != null) TextButton(onClick = onRetry) { Text("Retry") }
    }
}

/** Muted one-liner for empty states inside a card. */
@Composable
fun CardEmpty(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

/* ================================================================ skeletons */

@Composable
fun Modifier.shimmer(): Modifier {
    val t = rememberInfiniteTransition(label = "shimmer")
    val a by t.animateFloat(0.45f, 0.9f, infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Reverse), label = "alpha")
    return this.alpha(a)
}

@Composable
fun SkeletonBox(modifier: Modifier = Modifier, shape: RoundedCornerShape = RoundedCornerShape(8.dp)) {
    Box(modifier.shimmer().clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest))
}

/** [lines] text-ish bars, as a placeholder for list rows. */
@Composable
fun SkeletonRows(lines: Int = 3, modifier: Modifier = Modifier, rowHeight: Dp = 44.dp) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(lines) { i ->
            SkeletonBox(Modifier.fillMaxWidth(if (i % 2 == 0) 1f else 0.8f).height(rowHeight))
        }
    }
}

/* ================================================================ shared rows */

/** Big number + label. */
@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onSurface, icon: ImageVector? = null, onClick: (() -> Unit)? = null) {
    val body: @Composable () -> Unit = {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                }
                // Shrinks (rather than clipping to "12.4 MB…") at large font scales or in narrow tiles.
                val style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold, color = tint)
                BasicText(value, style = style, maxLines = 1, autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = style.fontSize))
            }
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    val shape = RoundedCornerShape(16.dp)
    val color = MaterialTheme.colorScheme.surfaceContainerHigh
    if (onClick != null) Surface(onClick = onClick, color = color, shape = shape, modifier = modifier, content = body)
    else Surface(color = color, shape = shape, modifier = modifier, content = body)
}

/** A row of [StatTile]s of equal width. */
@Composable
fun StatRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

/** One health check result: "error" / "warning" / "notice". */
@Composable
fun HealthRow(type: String?, message: String, source: String? = null, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val t = type?.lowercase()
    val (icon, tint) = when (t) {
        "error" -> Icons.Rounded.ErrorOutline to MaterialTheme.colorScheme.error
        "warning" -> Icons.Rounded.WarningAmber to warnColor()
        else -> Icons.Rounded.Info to MaterialTheme.colorScheme.primary
    }
    Row(
        modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.padding(top = 2.dp).size(18.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (!source.isNullOrBlank()) Text(source, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(message, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Disk usage bar: path, "x free of y", fill turns warning / error as it fills up. */
@Composable
fun DiskRow(path: String, free: Long, total: Long?, modifier: Modifier = Modifier, note: String? = null) {
    val used = if (total != null && total > 0) ((total - free).toFloat() / total).coerceIn(0f, 1f) else null
    val color = when {
        used == null -> MaterialTheme.colorScheme.primary
        used >= 0.95f -> MaterialTheme.colorScheme.error
        used >= 0.85f -> warnColor()
        else -> MaterialTheme.colorScheme.primary
    }
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Storage, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(path, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text(
                if (total != null && total > 0) "${bytes(free)} free of ${bytes(total)}" else "${bytes(free)} free",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (used != null) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { used },
                color = color,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                drawStopIndicator = {},
            )
        }
        if (note != null) Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
    }
}

/** Label/value line for detail lists ("Version", "4.0.11"). */
@Composable
fun InfoLine(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(112.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
