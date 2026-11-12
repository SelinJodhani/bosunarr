package app.bosunarr.feature.music

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.bosunarr.data.ServiceConfig
import app.bosunarr.ui.common.ServiceImage

/** Square, rounded album cover (or artist photo) from Lidarr. */
@Composable
fun SquareCover(service: ServiceConfig?, path: String?, size: Dp, title: String?, modifier: Modifier = Modifier) {
    ServiceImage(
        service, path,
        modifier.width(size).aspectRatio(1f).clip(RoundedCornerShape(if (size < 64.dp) 8.dp else 12.dp)),
        contentDescription = title,
        placeholderIcon = Icons.Rounded.Album,
    )
}

/** "3:45" or "1:02:03" from milliseconds; "" when unknown. */
fun trackLength(ms: Long): String {
    if (ms <= 0) return ""
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** "52 min" / "1 h 12 min" for an album; "" when unknown. */
fun albumLength(ms: Long): String {
    if (ms <= 0) return ""
    val min = (ms / 60_000).coerceAtLeast(1)
    return if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"
}

/** Album completion 0..100 from Lidarr's statistics, or null when it has no tracks yet. */
fun albumPercent(a: LidarrAlbum): Int? {
    val st = a.statistics ?: return null
    val want = st.trackCount.takeIf { it > 0 } ?: st.totalTrackCount
    if (want <= 0) return null
    return (st.trackFileCount * 100 / want).coerceIn(0, 100)
}

val MONITOR_NEW_LABELS = mapOf("all" to "All new albums", "new" to "Only new releases", "none" to "None")
