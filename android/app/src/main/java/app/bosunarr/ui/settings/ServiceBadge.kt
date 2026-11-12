package app.bosunarr.ui.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.bosunarr.R
import app.bosunarr.data.ServiceType
import app.bosunarr.feature.services.StatusDot

/**
 * The service's logo (from the open-source dashboard-icons collection by homarr-labs), or null when there's none.
 * Kept in ui/ so the data enum stays free of Android resources.
 */
@DrawableRes
fun serviceLogo(type: ServiceType?): Int? = when (type) {
    ServiceType.Sonarr -> R.drawable.svc_sonarr
    ServiceType.Radarr -> R.drawable.svc_radarr
    ServiceType.Prowlarr -> R.drawable.svc_prowlarr
    ServiceType.Bazarr -> R.drawable.svc_bazarr
    ServiceType.Jellyseerr, ServiceType.Overseerr -> R.drawable.svc_seerr
    ServiceType.QBittorrent -> R.drawable.svc_qbittorrent
    ServiceType.Jellyfin -> R.drawable.svc_jellyfin
    ServiceType.Jellystat -> R.drawable.svc_jellystat
    ServiceType.Plex -> R.drawable.svc_plex
    ServiceType.Tautulli -> R.drawable.svc_tautulli
    ServiceType.Wizarr -> R.drawable.svc_wizarr
    ServiceType.Whisparr -> R.drawable.svc_whisparr
    ServiceType.Lidarr -> R.drawable.svc_lidarr
    ServiceType.Sabnzbd -> R.drawable.svc_sabnzbd
    ServiceType.Nzbget -> R.drawable.svc_nzbget
    ServiceType.Transmission -> R.drawable.svc_transmission
    ServiceType.Deluge -> R.drawable.svc_deluge
    ServiceType.Server, null -> null
}

/** A Material icon for types with no brand logo (a generic server). */
fun serviceIcon(type: ServiceType?): ImageVector? = when (type) {
    ServiceType.Server -> Icons.Rounded.Dns
    else -> null
}

/**
 * A service's logo on a rounded-square tonal tile; a letter badge in the brand colour for unknown types.
 * [size] is the tile's edge; corners and padding scale with it. A size set on [modifier] still wins
 * (older call sites pass `Modifier.size(…)`).
 */
@Composable
fun ServiceBadge(type: ServiceType?, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val shape = RoundedCornerShape(size * 0.3f)
    val logo = serviceLogo(type)
    val outline = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    if (logo != null) {
        Box(
            modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest).border(0.5.dp, outline, shape),
            contentAlignment = Alignment.Center,
        ) {
            Image(painterResource(logo), contentDescription = null, modifier = Modifier.fillMaxSize().padding(size * 0.17f))
        }
    } else if (serviceIcon(type) != null) {
        val c = type?.color ?: MaterialTheme.colorScheme.primary
        Box(
            modifier.size(size).clip(shape)
                .background(Brush.linearGradient(listOf(c.copy(alpha = 0.30f), c.copy(alpha = 0.14f))))
                .border(0.5.dp, c.copy(alpha = 0.35f), shape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(serviceIcon(type)!!, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f), modifier = Modifier.fillMaxSize().padding(size * 0.2f))
        }
    } else {
        val c = type?.color ?: MaterialTheme.colorScheme.primary
        Box(
            modifier.size(size).clip(shape)
                .background(Brush.linearGradient(listOf(c.copy(alpha = 0.30f), c.copy(alpha = 0.14f))))
                .border(0.5.dp, c.copy(alpha = 0.35f), shape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                type?.label?.take(1) ?: "?",
                color = c,
                style = TextStyle(fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Bold),
            )
        }
    }
}

/** [ServiceBadge] with a status dot on its corner (green online, red offline, grey while unknown). */
@Composable
fun ServiceBadgeWithStatus(
    type: ServiceType?,
    dot: Color,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    /** Colour behind the badge: the dot gets a ring of it so it reads as cut out of the tile. */
    ring: Color = MaterialTheme.colorScheme.surfaceContainerLow,
) {
    Box(modifier) {
        ServiceBadge(type, size = size)
        val d = (size.value * 0.26f).coerceIn(8f, 12f).dp
        Box(
            Modifier.align(Alignment.BottomEnd).offset(3.dp, 3.dp).size(d + 5.dp).clip(RoundedCornerShape(50)).background(ring),
            contentAlignment = Alignment.Center,
        ) { StatusDot(dot, size = d) }
    }
}
