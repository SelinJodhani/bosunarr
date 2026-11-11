package app.bosunarr.ui.common

import androidx.compose.foundation.background
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
import coil3.compose.SubcomposeAsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** App-wide snackbar for action feedback ("Search started", errors). */
val LocalSnackbar = staticCompositionLocalOf { SnackbarHostState() }

/**
 * Image from a service (arr MediaCover, Seerr /imageproxy, Jellyfin /Items/…) with that service's auth headers.
 * `path` is relative to the service URL, or an absolute URL. Shows a quiet placeholder while loading or on error.
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
    val placeholder = @Composable {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
            Icon(placeholderIcon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(32.dp))
        }
    }
    if (url == null) {
        Box(modifier) { placeholder() }
        return
    }
    val request = ImageRequest.Builder(ctx).data(url).apply {
        // Only send credentials to the service's own addresses, never to a third-party absolute URL.
        if (service != null && listOfNotNull(service.url, service.altUrl, app.client.baseUrl(service)).any { sameOrigin(it, url) }) {
            val h = NetworkHeaders.Builder()
            app.client.authHeaders(service).forEach { (k, v) -> h[k] = v }
            httpHeaders(h.build())
        }
    }.build()
    SubcomposeAsyncImage(
        model = request,
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier,
        loading = { placeholder() },
        error = { placeholder() },
    )
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

@Composable
fun MessageBox(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(72.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null && onAction != null) {
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
fun ErrorBox(message: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) =
    MessageBox(Icons.Rounded.CloudOff, "Couldn't load", modifier, hint = message, action = if (onRetry != null) "Try again" else null, onAction = onRetry)

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
        when {
            s.value != null -> Column(Modifier.fillMaxSize()) {
                if (s.error != null) ErrorBanner(s.error)
                Box(Modifier.weight(1f)) { content(s.value) }
            }
            s.error != null -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { ErrorBox(s.error, onRetry = { loader.refresh() }) }
            else -> LoadingBox()
        }
    }
}

@Composable
fun ErrorBanner(message: String, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp), modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(message, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), maxLines = 3, overflow = TextOverflow.Ellipsis)
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
