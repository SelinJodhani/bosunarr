package app.bosunarr.feature.library

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.bosunarr.app
import app.bosunarr.data.ServiceConfig
import app.bosunarr.net.userMessage
import app.bosunarr.ui.common.Poster
import app.bosunarr.ui.common.ServiceImage
import androidx.compose.material3.SnackbarHostState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/* ---------------------------------------------------------------- actions */

/**
 * Run a service call outside the screen's lifetime (so leaving the screen doesn't cancel a half-sent change),
 * reporting the outcome in the app snackbar. [onError] rolls back optimistic UI.
 */
fun runAction(
    snackbar: SnackbarHostState,
    success: String?,
    onError: () -> Unit = {},
    onDone: () -> Unit = {},
    block: suspend () -> Unit,
) {
    app.scope.launch(Dispatchers.Main.immediate) {
        try {
            block()
            if (success != null) launch { snackbar.showSnackbar(success) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError()
            launch { snackbar.showSnackbar(e.userMessage()) }
        } finally {
            onDone()
        }
    }
}

/* ---------------------------------------------------------------- grid card */

/** Small round status marker on a poster's corner. */
@Composable
fun StatusBadge(item: LibItem, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val progress = item.progress
    when {
        item.status == LibStatus.Partial && progress != null -> Surface(
            modifier = modifier, shape = RoundedCornerShape(50), color = cs.tertiaryContainer, contentColor = cs.onTertiaryContainer, shadowElevation = 2.dp,
        ) {
            Text("${(progress * 100).toInt().coerceIn(1, 99)}%", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp), maxLines = 1)
        }
        else -> {
            val (icon, bg, fg) = when (item.status) {
                LibStatus.Downloaded -> Triple(Icons.Rounded.Check, cs.primary, cs.onPrimary)
                LibStatus.Missing, LibStatus.Partial -> Triple(null, cs.error, cs.onError)
                LibStatus.Upcoming -> Triple(Icons.Rounded.Schedule, cs.secondaryContainer, cs.onSecondaryContainer)
                LibStatus.Unmonitored -> Triple(Icons.Rounded.BookmarkBorder, cs.surfaceContainerHighest, cs.onSurfaceVariant)
            }
            Surface(modifier = modifier.size(22.dp), shape = CircleShape, color = bg, contentColor = fg, shadowElevation = 2.dp) {
                Box(contentAlignment = Alignment.Center) {
                    if (icon != null) Icon(icon, item.status.label, Modifier.size(14.dp))
                    else Text("!", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun PosterCard(item: LibItem, service: ServiceConfig?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick).padding(bottom = 6.dp)) {
        Box {
            Poster(service, item.poster, Modifier.fillMaxWidth().alpha(if (item.monitored) 1f else 0.55f), title = item.title)
            StatusBadge(item, Modifier.align(Alignment.TopEnd).padding(6.dp))
            if (item.is4k) {
                Surface(Modifier.align(Alignment.TopStart).padding(6.dp), shape = RoundedCornerShape(6.dp), color = Color.Black.copy(alpha = 0.6f), contentColor = Color.White) {
                    Text("4K", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
                }
            }
            val p = item.progress
            if (p != null && p < 1f) {
                LinearProgressIndicator(
                    progress = { p },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp).height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = if (item.monitored) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    trackColor = Color.Black.copy(alpha = 0.45f),
                    drawStopIndicator = {},
                    gapSize = 0.dp,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(item.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 2.dp))
        Text(
            listOfNotNull(item.year?.toString(), if (item.kind == LibKind.Series && item.total > 0) "${item.have}/${item.total}" else null).joinToString(" · ").ifEmpty { " " },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

/** Grey poster-shaped card shown while the library loads (same size as a real card, so nothing jumps). */
@Composable
fun SkeletonCard(modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(modifier.padding(bottom = 6.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)).background(c))
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth(0.8f).height(12.dp).clip(RoundedCornerShape(4.dp)).background(c))
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth(0.4f).height(10.dp).clip(RoundedCornerShape(4.dp)).background(c))
    }
}

/* ---------------------------------------------------------------- detail screen frame */

val HeaderHeight = 236.dp

/**
 * Detail frame: the top bar floats transparent over the backdrop and turns solid (showing the title) once the
 * header scrolls away. Content draws edge to edge; [content] gets the bottom padding to apply.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScaffold(
    title: String,
    listState: LazyListState,
    onBack: () -> Unit,
    actions: @Composable RowScope.(tint: Color) -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val density = LocalDensity.current
    val threshold = with(density) { (HeaderHeight - 96.dp).toPx() }
    val solid by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > threshold } }
    val container by animateColorAsState(if (solid) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent, label = "bar")
    val tint by animateColorAsState(if (solid) MaterialTheme.colorScheme.onSurface else Color.White, label = "tint")
    val titleAlpha by animateFloatAsState(if (solid) 1f else 0f, label = "title")
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.alpha(titleAlpha)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = tint) } },
                actions = { actions(tint) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = container, scrolledContainerColor = container, titleContentColor = MaterialTheme.colorScheme.onSurface),
            )
        },
    ) { p ->
        // Ignore the top padding: the header sits under the transparent bar.
        val bottom = p.calculateBottomPadding()
        content(PaddingValues(bottom = bottom))
    }
}

/** Backdrop with a poster and title block overlapping its bottom edge. */
@Composable
fun DetailHeader(
    service: ServiceConfig?,
    fanart: String?,
    poster: String?,
    title: String,
    lines: List<String>,
    tags: @Composable RowScope.() -> Unit = {},
) {
    val surface = MaterialTheme.colorScheme.surface
    Box(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(HeaderHeight)) {
            ServiceImage(service, fanart, Modifier.fillMaxSize(), placeholderIcon = Icons.Rounded.Tv)
            // Scrim at the top for the bar icons, fade into the page at the bottom.
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.55f), 0.35f to Color.Transparent)))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to surface)))
        }
        Row(
            Modifier.fillMaxWidth().padding(top = HeaderHeight - 96.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(shape = RoundedCornerShape(12.dp), shadowElevation = 6.dp) { Poster(service, poster, width = 112.dp, title = title) }
            Column(Modifier.weight(1f).padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                lines.filter { it.isNotBlank() }.forEach {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, content = tags)
            }
        }
    }
}

/** Labelled round action ("Search", "Refresh"): a tonal icon button with a caption; spinner while [busy]. */
@Composable
fun RowScope.DetailAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    busy: Boolean = false,
    selected: Boolean = false,
    destructive: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FilledTonalIconButton(
            onClick = onClick,
            enabled = !busy,
            modifier = Modifier.size(width = 64.dp, height = 44.dp),
            colors = when {
                selected -> IconButtonDefaults.filledTonalIconButtonColors(containerColor = cs.primary, contentColor = cs.onPrimary)
                destructive -> IconButtonDefaults.filledTonalIconButtonColors(containerColor = cs.errorContainer, contentColor = cs.onErrorContainer)
                else -> IconButtonDefaults.filledTonalIconButtonColors()
            },
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(icon, null)
        }
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = cs.onSurfaceVariant)
    }
}

/** Overview that expands on tap when it's long. */
@Composable
fun ExpandableText(text: String, modifier: Modifier = Modifier, collapsedLines: Int = 4) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var overflows by remember { mutableStateOf(false) }
    Column(modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = overflows || expanded) { expanded = !expanded }) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) Int.MAX_VALUE else collapsedLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
        )
        if (overflows || expanded) Text(if (expanded) "Show less" else "More", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Card of label/value rows. Rows with a blank value are skipped. */
@Composable
fun InfoCard(rows: List<Pair<String, String?>>, modifier: Modifier = Modifier) {
    val shown = rows.filter { !it.second.isNullOrBlank() }
    if (shown.isEmpty()) return
    OutlinedCard(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(vertical = 8.dp)) {
            shown.forEach { (k, v) ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(k, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(112.dp))
                    Text(v.orEmpty(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** Delete confirmation with a "delete files" checkbox. */
@Composable
fun DeleteDialog(title: String, what: String, onConfirm: (deleteFiles: Boolean) -> Unit, onDismiss: () -> Unit) {
    var deleteFiles by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete $what?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("$title will be removed from the library.")
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { deleteFiles = !deleteFiles }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = deleteFiles, onCheckedChange = { deleteFiles = it })
                    Column {
                        Text("Also delete files", style = MaterialTheme.typography.bodyLarge)
                        Text("Permanently removes the folder from disk", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(deleteFiles); onDismiss() },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
            ) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Field that opens a menu of [options] (label to value). */
@Composable
fun <T> PickerField(
    label: String,
    value: String,
    options: List<Pair<String, T>>,
    onPick: (T) -> Unit,
    modifier: Modifier = Modifier,
    supporting: (T) -> String? = { null },
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()) {
        OutlinedCard(
            onClick = { open = true },
            enabled = enabled && options.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Rounded.ArrowDropDown, null)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (text, v) ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            supporting(v)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    },
                    onClick = { open = false; onPick(v) },
                )
            }
        }
    }
}
