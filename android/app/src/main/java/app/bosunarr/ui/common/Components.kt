package app.bosunarr.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import app.bosunarr.ui.art.SeaArt
import app.bosunarr.ui.art.SeaBackdrop
import app.bosunarr.ui.art.rememberSeaTextColors
import app.bosunarr.ui.art.seaCalmTop
import app.bosunarr.ui.art.SeaScene
import app.bosunarr.ui.art.rememberReducedMotion
import androidx.compose.foundation.background
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.bosunarr.app
import app.bosunarr.data.ServiceConfig
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** App-wide snackbar for action feedback ("Search started", errors). */
val LocalSnackbar = staticCompositionLocalOf { SnackbarHostState() }

/**
 * Image from a service (arr MediaCover, Seerr /imageproxy, Jellyfin /Items/…) with that service's auth headers.
 * `path` is relative to the service URL, or an absolute URL. Shows a quiet placeholder while loading or on error.
 * Uses [AsyncImage] (not the subcomposing variant) so long poster grids scroll without jank; Coil sizes the
 * request to the layout, so only thumbnails are decoded.
 */
@Composable
fun ServiceImage(
    service: ServiceConfig?,
    path: String?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
    placeholderIcon: ImageVector = Icons.Rounded.Movie,
) {
    val ctx = LocalContext.current
    val url = when {
        path.isNullOrBlank() -> null
        path.startsWith("http") -> path
        service != null -> app.client.baseUrl(service) + path
        else -> null
    }
    var loaded by remember(url) { mutableStateOf(false) }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        if (!loaded) {
            Icon(
                placeholderIcon, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxSize(0.4f).sizeIn(maxWidth = 32.dp, maxHeight = 32.dp),
            )
        }
        if (url != null) {
            val request = remember(url, service) {
                ImageRequest.Builder(ctx).data(url).apply {
                    // Only send credentials to the service's own addresses, never to a third-party absolute URL.
                    if (service != null && listOfNotNull(service.url, service.altUrl, app.client.baseUrl(service)).any { sameOrigin(it, url) }) {
                        val h = NetworkHeaders.Builder()
                        app.client.authHeaders(service).forEach { (k, v) -> h[k] = v }
                        httpHeaders(h.build())
                    }
                }.build()
            }
            AsyncImage(
                model = request,
                contentDescription = contentDescription,
                contentScale = contentScale,
                onSuccess = { loaded = true },
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

/** 2:3 rounded poster. */
@Composable
fun Poster(service: ServiceConfig?, path: String?, modifier: Modifier = Modifier, width: Dp? = null, title: String? = null) {
    val m = (if (width != null) modifier.width(width) else modifier).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp))
    ServiceImage(service, path, m, contentDescription = title)
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 64.dp), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() }
}

/**
 * Empty or info state: a headline, a plain-language [hint] and an optional action, with either a small icon tile or,
 * when [scene] is set, a nautical illustration. Fades and scales in when it appears (unless the system has animations
 * turned off).
 *
 * With a [scene], the default is **full-bleed**: the whole area becomes the sea scene ([SeaBackdrop]) and the text sits
 * on the calm water at the bottom. That needs a known height: give it one with `Modifier.fillParentMaxSize()` inside a
 * lazy list item, or [FullScreenScroll] inside a scroll; in a bounded parent it just fills. (With no bound at all it
 * estimates the window's content height.) Pass `fullBleed = false`, or an [artSize], for the compact form (a disc
 * illustration above the text) inside cards, under toolbars and search fields, or in "no matches" rows.
 */
@Composable
fun MessageBox(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    scene: SeaScene? = null,
    artSize: Dp = Dp.Unspecified,
    fullBleed: Boolean = false,
) = MessageBoxImpl(icon, scene, title, modifier, hint, action, onAction, artSize, fullBleed)

/** [MessageBox] with an illustration and no icon. */
@Composable
fun MessageBox(
    scene: SeaScene,
    title: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    artSize: Dp = Dp.Unspecified,
    fullBleed: Boolean = false,
) = MessageBoxImpl(null, scene, title, modifier, hint, action, onAction, artSize, fullBleed)

@Composable
private fun MessageBoxImpl(
    icon: ImageVector?,
    scene: SeaScene?,
    title: String,
    modifier: Modifier,
    hint: String?,
    action: String?,
    onAction: (() -> Unit)?,
    artSize: Dp,
    fullBleed: Boolean,
    below: (@Composable () -> Unit)? = null,
) {
    val still = rememberReducedMotion()
    val shown = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(Unit) { if (!still) shown.animateTo(1f, tween(420, easing = FastOutSlowInEasing)) }
    if (scene != null && fullBleed && artSize == Dp.Unspecified) {
        FullBleedMessage(scene, title, modifier, hint, action, onAction, below) { shown.value }
        return
    }
    Column(
        modifier
            .graphicsLayer {
                alpha = shown.value
                val s = 0.94f + 0.06f * shown.value
                scaleX = s; scaleY = s
            }
            .fillMaxWidth()
            .padding(horizontal = if (scene != null) 24.dp else 32.dp, vertical = if (scene != null) 16.dp else 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (scene != null) {
            // Compact: a disc illustration above the text, smaller on short screens.
            val cfg = LocalConfiguration.current
            val wide = (cfg.screenWidthDp - 48).coerceIn(200, 360)
            val fit = if (cfg.screenHeightDp < 640) minOf(wide, 220) else wide
            SeaArt(scene, size = if (artSize != Dp.Unspecified) artSize else fit.dp)
        } else if (icon != null) {
            Box(Modifier.size(72.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(36.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(title, style = if (scene != null) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null && onAction != null) {
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = onAction) { Text(action) }
        }
        below?.invoke()
    }
}

/** The whole area as a sea scene, with the text on the calm water at the bottom (scrollable if it doesn't fit). */
@Composable
private fun FullBleedMessage(
    scene: SeaScene,
    title: String,
    modifier: Modifier,
    hint: String?,
    action: String?,
    onAction: (() -> Unit)?,
    below: (@Composable () -> Unit)?,
    progress: () -> Float,
) {
    val fallback = estimatedContentHeight()
    BoxWithConstraints(modifier.fillMaxSize()) {
        val height = if (constraints.hasBoundedHeight) maxHeight else fallback
        val calmTop = seaCalmTop(maxWidth, height)
        val text = rememberSeaTextColors()
        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .graphicsLayer { alpha = progress() },
        ) {
            SeaBackdrop(scene, Modifier.matchParentSize())
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(top = calmTop + 12.dp, bottom = 24.dp)
                    .padding(horizontal = 32.dp)
                    .graphicsLayer {
                        val p = progress()
                        translationY = (1f - p) * 12.dp.toPx()
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(title, style = MaterialTheme.typography.headlineSmall, color = text.title, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 480.dp))
                if (hint != null) Text(hint, style = MaterialTheme.typography.bodyMedium, color = text.hint, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 480.dp))
                if (action != null && onAction != null) {
                    Spacer(Modifier.height(4.dp))
                    Button(onClick = onAction) { Text(action) }
                }
                below?.invoke()
            }
        }
    }
}

/** Window height minus system bars and a top app bar: a fallback when a full-bleed state gets no height bound. */
@Composable
private fun estimatedContentHeight(): Dp {
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    val bars = WindowInsets.systemBars
    return with(density) { (window.height - bars.getTop(this) - bars.getBottom(this)).toDp() - 64.dp }.coerceAtLeast(360.dp)
}

/**
 * Scrollable host for a full-screen empty or error state: the content stays in a vertical scroll (so pull-to-refresh
 * keeps working) while [content] gets a modifier that sizes it to exactly the visible area, e.g.
 * `FullScreenScroll { m -> ErrorBox(error, m, onRetry = …) }`.
 */
@Composable
fun FullScreenScroll(modifier: Modifier = Modifier, content: @Composable (Modifier) -> Unit) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val fill = Modifier.fillMaxWidth().height(maxHeight)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { content(fill) }
    }
}

/**
 * Lets content draw past its parent's padding (a lazy list's contentPadding) so a full-bleed state reaches the edges.
 * Layout size is unchanged; the content is measured larger by the given amounts and offset back.
 */
fun Modifier.bleed(horizontal: Dp = 0.dp, top: Dp = 0.dp, bottom: Dp = 0.dp): Modifier = layout { measurable, c ->
    val h = horizontal.roundToPx()
    val t = top.roundToPx()
    val b = bottom.roundToPx()
    val wide = c.copy(
        minWidth = c.minWidth + 2 * h,
        maxWidth = if (c.hasBoundedWidth) c.maxWidth + 2 * h else c.maxWidth,
        minHeight = c.minHeight + t + b,
        maxHeight = if (c.hasBoundedHeight) c.maxHeight + t + b else c.maxHeight,
    )
    val p = measurable.measure(wide)
    layout((p.width - 2 * h).coerceAtLeast(0), (p.height - t - b).coerceAtLeast(0)) { p.place(-h, -t) }
}

/**
 * Full-screen load failure: a sea scene, a friendly explanation, "Try again", and the technical text behind
 * "Details". [scene] defaults to [SeaScene.Fog] when the service couldn't be reached (offline, timeout, DNS, refused)
 * and [SeaScene.Storm] for everything else. Full-bleed by default (see [MessageBox] for sizing); `fullBleed = false`
 * for the compact form.
 */
@Composable
fun ErrorBox(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    scene: SeaScene? = null,
    fullBleed: Boolean = false,
) {
    val e = remember(message) { friendlyError(message) }
    val art = scene ?: remember(message) { if (isUnreachable(message)) SeaScene.Fog else SeaScene.Storm }
    val title = when (art) {
        SeaScene.Fog -> "Foggy waters"
        SeaScene.Storm -> "Rough seas"
        else -> "Couldn't load"
    }
    // "Sonarr timed out" alone doesn't say what to do: point at the usual culprit.
    val hint = if (art == SeaScene.Fog && listOf("network", "vpn", "address", "running").none { e.message.contains(it, ignoreCase = true) })
        "${e.message.trimEnd('.')}. Check your network or VPN." else e.message
    if (fullBleed) {
        val text = rememberSeaTextColors()
        MessageBoxImpl(
            Icons.Rounded.CloudOff, art, title, modifier, hint, if (onRetry != null) "Try again" else null, onRetry, Dp.Unspecified, true,
            below = e.details?.let { d -> { ErrorDetails(d, toggleColor = text.title) } },
        )
    } else {
        Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            MessageBox(Icons.Rounded.CloudOff, title, hint = hint, action = if (onRetry != null) "Try again" else null, onAction = onRetry, scene = art, fullBleed = false)
            if (e.details != null) ErrorDetails(e.details, Modifier.padding(horizontal = 32.dp).padding(bottom = 24.dp))
        }
    }
}

/** True for "couldn't get there at all" failures (as opposed to the service answering with an error). */
private fun isUnreachable(raw: String): Boolean {
    val l = raw.lowercase()
    if ("certificate" in l || "ssl" in l || "handshake" in l || "trust anchor" in l) return false
    return l.startsWith("network error") || listOf("unreachable", "timed out", "timeout", "unable to resolve", "no address associated", "couldn't reach", "can't reach", "refused", "failed to connect").any { it in l }
}

/** "Details" toggle revealing the technical error text (selectable, so it can be copied into a bug report). */
@Composable
fun ErrorDetails(details: String, modifier: Modifier = Modifier, toggleColor: Color = Color.Unspecified) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        TextButton(
            onClick = { open = !open },
            colors = if (toggleColor.isSpecified) ButtonDefaults.textButtonColors(contentColor = toggleColor) else ButtonDefaults.textButtonColors(),
        ) { Text(if (open) "Hide details" else "Details") }
        AnimatedVisibility(open) {
            SelectionContainer {
                Text(
                    details,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(12.dp),
                )
            }
        }
    }
}

/**
 * Standard screen body for a [Loader]: spinner on first load, error with retry, pull-to-refresh, and an inline
 * error banner when a refresh fails but old data is still shown. [content] gets the loaded value.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> LoadContent(
    loader: Loader<T>,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    val s = loader.state
    PullToRefreshBox(
        isRefreshing = s.userRefreshing && s.value != null,
        onRefresh = { loader.pullRefresh() },
        modifier = modifier.fillMaxSize(),
    ) {
        Box(Modifier.fillMaxSize().expandTopBarFirst()) {
        when {
            s.value != null -> Column(Modifier.fillMaxSize()) {
                if (s.error != null) ErrorBanner(s.error)
                Box(Modifier.weight(1f)) { content(s.value) }
            }
            s.error != null -> FullScreenScroll { m -> ErrorBox(s.error, m, onRetry = { loader.refresh() }) }
            else -> LoadingBox()
        }
        }
    }
}

@Composable
fun ErrorBanner(message: String, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp), modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(friendlyMessage(message), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

/** Small rounded label: "Downloaded", "4K", "Missing". */
@Composable
fun Tag(text: String, modifier: Modifier = Modifier, container: Color = MaterialTheme.colorScheme.secondaryContainer, content: Color = MaterialTheme.colorScheme.onSecondaryContainer) {
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(8.dp), modifier = modifier) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp), maxLines = 1)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String?,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = text?.let { { Text(it) } },
        confirmButton = {
            if (destructive) Button(onClick = { onConfirm(); onDismiss() }, colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)) { Text(confirm) }
            else Button(onClick = { onConfirm(); onDismiss() }) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Padding for scrolling content so the last item clears the bottom bar / FAB. */
val ListPadding = PaddingValues(bottom = 96.dp)

/** True when [url] is on the same scheme/host/port as [base] and under its path (so keys never leak to other hosts). */
internal fun sameOrigin(base: String, url: String): Boolean {
    val b = base.toHttpUrlOrNull() ?: return false
    val u = url.toHttpUrlOrNull() ?: return false
    if (b.scheme != u.scheme || b.host != u.host || b.port != u.port) return false
    val prefix = b.encodedPath.trimEnd('/') + "/"
    return u.encodedPath == b.encodedPath.trimEnd('/') || u.encodedPath.startsWith(prefix)
}
