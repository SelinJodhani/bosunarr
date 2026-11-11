package app.bosunarr.feature.discover

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import app.bosunarr.app
import app.bosunarr.data.ServiceConfig
import app.bosunarr.data.ServiceType
import app.bosunarr.nav.MediaRoute
import app.bosunarr.nav.SettingsRoute
import app.bosunarr.ui.common.MessageBox
import app.bosunarr.ui.common.Poster
import app.bosunarr.ui.common.SectionTitle

/**
 * Runs [onWrite] when a Seerr write happened since this screen last looked — including writes made on another
 * screen while this one was in the back stack (the last seen version is saved with the screen).
 */
@Composable
fun OnSeerrWrite(onWrite: () -> Unit) {
    var seen by rememberSaveable { mutableIntStateOf(seerrWrites.value) }
    val latest by rememberUpdatedState(onWrite)
    LaunchedEffect(Unit) {
        seerrWrites.collect { v ->
            if (v != seen) {
                seen = v
                latest()
            }
        }
    }
}

/** null while the store is still being read; otherwise the configured Seerr instances. */
@Composable
fun rememberSeerrServices(): List<ServiceConfig>? {
    val state by app.state.collectAsState()
    val services by app.services.collectAsState()
    if (state == null) return null
    return services.filter { it.serviceType == ServiceType.Jellyseerr || it.serviceType == ServiceType.Overseerr }
}

/** The Seerr instance in use: the one the user picked, else the first. */
@Composable
fun currentSeerr(list: List<ServiceConfig>): ServiceConfig? {
    val picked by selectedSeerr.collectAsState()
    return list.firstOrNull { it.id == picked } ?: list.firstOrNull()
}

@Composable
fun NoSeerr(nav: NavController, modifier: Modifier = Modifier) {
    MessageBox(
        Icons.Rounded.Explore, "No request service", modifier,
        hint = "Add Jellyseerr or Overseerr in Settings to discover and request movies and series.",
        action = "Open Settings", onAction = { nav.navigate(SettingsRoute) },
    )
}

/** Top-bar picker shown only when several Seerr instances are configured. */
@Composable
fun SeerrPicker(list: List<ServiceConfig>, current: ServiceConfig) {
    if (list.size < 2) return
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.Storage, "Choose request service") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            list.forEach { s ->
                DropdownMenuItem(
                    text = { Text(s.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { if (s.id == current.id) Icon(Icons.Rounded.Check, null) else Spacer(Modifier.width(24.dp)) },
                    onClick = { selectedSeerr.value = s.id; open = false },
                )
            }
        }
    }
}

@Composable
fun toneColors(tone: Tone): Triple<Color, Color, Color?> {
    val c = MaterialTheme.colorScheme
    return when (tone) {
        Tone.Ok -> Triple(c.primary, c.onPrimary, null)
        Tone.Partial -> Triple(c.secondaryContainer, c.onSecondaryContainer, null)
        Tone.Progress -> Triple(c.tertiaryContainer, c.onTertiaryContainer, null)
        Tone.Wait -> Triple(c.surfaceContainerHighest, c.onSurface, c.outline)
        Tone.Bad -> Triple(c.errorContainer, c.onErrorContainer, null)
    }
}

@Composable
fun StatusBadge(text: String, tone: Tone, modifier: Modifier = Modifier, large: Boolean = false) {
    val (bg, fg, border) = toneColors(tone)
    Surface(
        color = bg, contentColor = fg, shape = RoundedCornerShape(8.dp), modifier = modifier,
        border = border?.let { BorderStroke(1.dp, it.copy(alpha = 0.6f)) },
    ) {
        Text(
            text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = if (large) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = if (large) 10.dp else 8.dp, vertical = if (large) 5.dp else 3.dp),
        )
    }
}

/** Poster card: 2:3 poster with availability badge, title (2 lines, fixed height) and year. */
@Composable
fun MediaCard(service: ServiceConfig, item: MediaItem, onClick: () -> Unit, modifier: Modifier = Modifier, width: Dp? = 112.dp) {
    val m = if (width != null) modifier.width(width) else modifier.fillMaxWidth()
    Column(m.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
            Poster(service, tmdbImage(item.posterPath, "w342"), Modifier.fillMaxWidth(), title = item.title)
            posterBadge(item.status, item.status4k)?.let { (text, tone) ->
                StatusBadge(text, tone, Modifier.align(Alignment.TopEnd).padding(6.dp))
            }
        }
        Text(
            item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
            maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 2.dp, end = 2.dp, top = 6.dp),
        )
        Text(
            listOfNotNull(item.year?.toString(), if (item.mediaType == "tv") "Series" else "Movie").joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp),
        )
    }
}

/** Grey card shape matching [MediaCard] for loading rows. */
@Composable
fun MediaCardSkeleton(width: Dp = 112.dp) {
    val c = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(Modifier.width(width)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)).background(c))
        Box(Modifier.padding(top = 8.dp).fillMaxWidth(0.85f).height(14.dp).clip(RoundedCornerShape(4.dp)).background(c))
        Box(Modifier.padding(top = 6.dp).fillMaxWidth(0.5f).height(12.dp).clip(RoundedCornerShape(4.dp)).background(c))
    }
}

@Composable
fun MediaCarousel(title: String, service: ServiceConfig, items: List<MediaItem>, nav: NavController, modifier: Modifier = Modifier) {
    Column(modifier) {
        SectionTitle(title)
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { it.key }) { item ->
                MediaCard(service, item, onClick = { nav.navigate(MediaRoute(item.mediaType, item.tmdbId)) })
            }
        }
    }
}

@Composable
fun CarouselSkeleton(modifier: Modifier = Modifier) {
    Column(modifier) {
        Box(Modifier.padding(start = 16.dp, top = 24.dp, bottom = 12.dp).width(140.dp).height(18.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), userScrollEnabled = false) {
            items(6) { MediaCardSkeleton() }
        }
    }
}
