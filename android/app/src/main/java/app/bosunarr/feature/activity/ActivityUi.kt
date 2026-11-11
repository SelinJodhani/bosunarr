package app.bosunarr.feature.activity

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.semantics
import app.bosunarr.ui.common.plural
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.bosunarr.feature.activity.qbit.TorrentGroup
import app.bosunarr.feature.activity.queue.QueueHealth
import app.bosunarr.ui.common.speed

/** Same style with tabular figures, so changing numbers don't shift the layout. */
fun TextStyle.tnum(): TextStyle = copy(fontFeatureSettings = "tnum")

/**
 * Text that reserves the width of [template] (measured in the current font and scale) and aligns its value to the
 * end, so "0 B/s" → "12.4 MB/s" never moves its neighbours.
 */
@Composable
fun FixedWidthText(
    text: String,
    template: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    align: Alignment = Alignment.CenterEnd,
) {
    Box(modifier, contentAlignment = align) {
        Text(template, style = style, maxLines = 1, modifier = Modifier.alpha(0f).clearAndSetSemantics {})
        Text(text, style = style, maxLines = 1, color = color)
    }
}

/** Widest string [speed] produces ("99.9 MB/s"; 100+ drops the decimal), in tabular figures. */
private const val SPEED_TEMPLATE = "88.8 MB/s"

/**
 * "↓ 12.4 MB/s": the arrow stays next to its number, and the pair sits in a box as wide as the widest speed so a
 * changing value never moves its neighbours. [align] places the pair in that box (end for list columns, start for
 * a header readout).
 */
@Composable
fun SpeedLabel(
    down: Boolean,
    bps: Long,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.labelLarge,
    color: Color = LocalContentColor.current,
    dim: Boolean = bps <= 0,
    iconTint: Color? = null,
    align: Alignment = Alignment.CenterEnd,
) {
    val c = if (dim) color.copy(alpha = 0.55f) else color
    val icon = if (down) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward
    val iconSize = 14.dp
    Box(modifier, contentAlignment = align) {
        // Invisible template reserves the width.
        Row(Modifier.alpha(0f).clearAndSetSemantics {}, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(iconSize))
            Text(SPEED_TEMPLATE, style = style.tnum(), maxLines = 1, modifier = Modifier.padding(start = 2.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = if (down) "Download speed" else "Upload speed", tint = if (dim) c else (iconTint ?: c), modifier = Modifier.size(iconSize))
            Text(speed(bps), style = style.tnum(), maxLines = 1, color = c, modifier = Modifier.padding(start = 2.dp))
        }
    }
}

/**
 * Live transfer summary at the top of the Torrents tab: total speeds across clients, what's downloading/seeding,
 * and whether alternative speed limits are on. Tapping it opens the client's own screen (one client only).
 */
@Composable
fun TransferSummary(data: TorrentData, modifier: Modifier = Modifier, onOpen: (() -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    val torrents = data.torrents
    val downloading = remember(torrents) { torrents.count(TorrentFilter.Downloading.test) }
    val seeding = remember(torrents) { torrents.count(TorrentFilter.Seeding.test) }
    val errored = remember(torrents) { torrents.count(TorrentFilter.Errored.test) }
    val alt = data.clients.any { it.server.altSpeed }
    val shape = RoundedCornerShape(20.dp)
    val body: @Composable () -> Unit = {
        Column(Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BigRate(true, data.down, cs.primary, Modifier.weight(1f))
                BigRate(false, data.up, cs.tertiary, Modifier.weight(1f))
                if (onOpen != null) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = cs.onSurfaceVariant)
            }
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        torrents.isEmpty() -> "No torrents"
                        else -> listOfNotNull(
                            "$downloading downloading",
                            "$seeding seeding",
                            errored.takeIf { it > 0 }?.let { plural(it, "error") },
                        ).joinToString(" · ")
                    },
                    style = MaterialTheme.typography.bodySmall.tnum(),
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (alt) {
                    Surface(color = cs.secondaryContainer, contentColor = cs.onSecondaryContainer, shape = RoundedCornerShape(8.dp), modifier = Modifier.padding(start = 8.dp)) {
                        Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Speed, null, Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Alt speed on", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
    val m = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    if (onOpen != null) Surface(onClick = onOpen, shape = shape, color = cs.surfaceContainerLow, modifier = m, content = body)
    else Surface(shape = shape, color = cs.surfaceContainerLow, modifier = m, content = body)
}

@Composable
private fun BigRate(down: Boolean, bps: Long, tint: Color, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (down) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward,
            contentDescription = if (down) "Download speed" else "Upload speed",
            tint = if (bps > 0) tint else cs.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(6.dp))
        FixedWidthText(
            speed(bps), SPEED_TEMPLATE, MaterialTheme.typography.titleLarge.tnum(),
            color = if (bps > 0) cs.onSurface else cs.onSurfaceVariant,
            align = Alignment.CenterStart,
        )
    }
}

@Composable
fun groupColor(g: TorrentGroup): Color {
    val cs = MaterialTheme.colorScheme
    return when (g) {
        TorrentGroup.Downloading -> cs.primary
        TorrentGroup.Stalled -> cs.primary.copy(alpha = 0.6f)
        TorrentGroup.Seeding -> cs.tertiary
        TorrentGroup.Queued, TorrentGroup.Checking -> cs.secondary
        TorrentGroup.Paused, TorrentGroup.Completed -> cs.outline
        TorrentGroup.Errored -> cs.error
    }
}

@Composable
fun healthColor(h: QueueHealth, fallback: Color = MaterialTheme.colorScheme.primary): Color = when (h) {
    QueueHealth.Error -> MaterialTheme.colorScheme.error
    QueueHealth.Warning -> MaterialTheme.colorScheme.tertiary
    QueueHealth.Ok -> fallback
}

/** Thin rounded progress bar that animates between polls instead of jumping. */
@Composable
fun SmoothProgress(progress: Float, color: Color, modifier: Modifier = Modifier, height: Int = 4) {
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), tween(600), label = "progress")
    LinearProgressIndicator(
        progress = { p },
        modifier = modifier.fillMaxWidth().height(height.dp),
        color = color,
        trackColor = color.copy(alpha = 0.18f),
        strokeCap = StrokeCap.Round,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}

/** Compact pill search field (Material search-bar look without the full-screen behaviour). */
@Composable
fun SearchPill(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val focus = LocalFocusManager.current
    val cs = MaterialTheme.colorScheme
    Row(
        modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainerHigh).padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Box(Modifier.weight(1f).padding(start = 10.dp), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
                cursorBrush = SolidColor(cs.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) IconButton(onClick = { onValueChange("") }) { Icon(Icons.Rounded.Close, "Clear search", tint = cs.onSurfaceVariant) }
        else Box(Modifier.size(14.dp))
    }
}

/** Dot-separated metadata line. */
fun meta(vararg parts: String?): String = parts.filterNot { it.isNullOrBlank() }.joinToString(" · ")

val RowSpacing = Arrangement.spacedBy(6.dp)
