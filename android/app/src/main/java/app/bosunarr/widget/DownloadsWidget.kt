package app.bosunarr.widget

import android.content.Context
import androidx.annotation.Keep
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.padding
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import app.bosunarr.R
import app.bosunarr.ui.common.plural
import app.bosunarr.ui.common.speed

/** Total ↓/↑ across qBittorrent clients, the active count and (when there's room) the busiest downloads. */
// Kept by name: Glance maps placed widgets to this class name, which must survive R8 across app updates.
@Keep
class DownloadsWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact
    override val previewSizeMode = SizeMode.Responsive(setOf(DpSize(260.dp, 150.dp)))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val flow = WidgetStore.downloads(context)
        if (System.currentTimeMillis() - (flow.value?.at ?: 0) > WidgetRefresh.STALE_MS) WidgetRefresh.now(context)
        provideContent {
            val snap by flow.collectAsState()
            val since by WidgetStore.refreshingSince.collectAsState()
            GlanceTheme { DownloadsContent(snap, isRefreshing(since)) }
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { GlanceTheme { DownloadsContent(SAMPLE, refreshing = false) } }
    }

    private companion object {
        val SAMPLE = DownloadsSnapshot(
            at = System.currentTimeMillis(), okAt = System.currentTimeMillis(), hasQbit = true,
            down = 12_400_000, up = 860_000, active = 3,
            items = listOf(
                DownloadItem("Severance", "S02E07 · Chikhai Bardo", 0.72f, 6_100_000, "Downloading"),
                DownloadItem("Dune: Part Two (2024)", null, 0.41f, 4_800_000, "Downloading"),
                DownloadItem("The Bear", "S03E01 · Tomorrow", 0.18f, 1_500_000, "Downloading"),
            ),
        )
    }
}

class DownloadsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DownloadsWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetRefresh.schedule(context)
        WidgetRefresh.now(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetRefresh.onAppOpened(context) // cancels the periodic job when no widget of any kind is left
    }
}

@Composable
private fun DownloadsContent(snap: DownloadsSnapshot?, refreshing: Boolean) {
    val size = LocalSize.current
    val compact = size.height < 100.dp
    WidgetSurface(DeepLink.ACTIVITY, padding = if (compact) 10.dp else 12.dp) {
        when {
            snap == null -> Message("Loading downloads…", null)
            !snap.configured -> Message("No download services", "Add qBittorrent, Sonarr or Radarr in Bosunarr")
            compact -> Compact(snap, refreshing)
            size.width < 200.dp -> Square(snap, refreshing)
            else -> Full(snap, refreshing, size.height)
        }
    }
}

/** 2×1 / 4×1: ↓ speed, ↑ speed · active. */
@Composable
private fun ColumnScope.Compact(snap: DownloadsSnapshot, refreshing: Boolean) {
    Row(GlanceModifier.fillMaxWidth().defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
        Column(GlanceModifier.defaultWeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(R.drawable.ic_widget_down, "Download speed", 16.dp, GlanceTheme.colors.primary)
                Spacer(GlanceModifier.width(4.dp))
                Txt(speed(snap.down), size = 16.sp, weight = FontWeight.Bold)
            }
            Txt(
                when {
                    snap.error != null -> "Offline"
                    snap.hasQbit -> "↑ ${speed(snap.up)} · ${snap.active} active"
                    else -> "${snap.active} active"
                },
                size = 12.sp,
                color = if (snap.error != null) GlanceTheme.colors.error else GlanceTheme.colors.onSurfaceVariant,
            )
        }
        RefreshButton(refreshing)
    }
}

/** 2×2: big speeds and the active count. */
@Composable
private fun ColumnScope.Square(snap: DownloadsSnapshot, refreshing: Boolean) {
    Header("Downloads", refreshing)
    Column(GlanceModifier.fillMaxWidth().defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(R.drawable.ic_widget_down, "Download speed", 18.dp, GlanceTheme.colors.primary)
            Spacer(GlanceModifier.width(4.dp))
            Txt(speed(snap.down), size = 18.sp, weight = FontWeight.Bold)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(R.drawable.ic_widget_up, "Upload speed", 14.dp)
            Spacer(GlanceModifier.width(4.dp))
            Txt(speed(snap.up), size = 13.sp, color = GlanceTheme.colors.onSurfaceVariant)
        }
        Txt(plural(snap.active, "active download"), size = 12.sp, color = GlanceTheme.colors.onSurfaceVariant)
    }
    Footer(snap.okAt, snap.error, snap.partial)
}

/** 4×2 and taller: speeds in the header, then the busiest items with progress bars. */
@Composable
private fun ColumnScope.Full(snap: DownloadsSnapshot, refreshing: Boolean, height: Dp) {
    val context = LocalContext.current
    val totals = if (snap.hasQbit) "↓ ${speed(snap.down)}  ↑ ${speed(snap.up)} · ${snap.active} active" else "${snap.active} active"
    Header("Downloads", refreshing, trailing = totals)
    Spacer(GlanceModifier.height(6.dp))
    // Header ~28dp + footer ~16dp + padding 24dp; each row ~34dp.
    val rows = ((height.value - 68) / 34).toInt().coerceIn(1, 6)
    Column(GlanceModifier.fillMaxWidth().defaultWeight()) {
        if (snap.items.isEmpty()) {
            Message(if (snap.error != null) "Can't reach your download services" else "Nothing downloading", if (snap.error != null) "Tap ↻ to try again" else null)
        } else snap.items.take(rows).forEach { item ->
            Column(GlanceModifier.fillMaxWidth().padding(bottom = 6.dp).clickable(open(context, item.link))) {
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Txt(
                        if (item.subtitle != null) "${item.title} · ${item.subtitle.substringBefore(" · ")}" else item.title,
                        GlanceModifier.defaultWeight(), size = 12.sp, weight = FontWeight.Medium,
                    )
                    Spacer(GlanceModifier.width(6.dp))
                    Txt(
                        "${(item.progress * 100).toInt()}% · " + if (item.speed > 0) speed(item.speed) else item.state,
                        size = 11.sp, color = GlanceTheme.colors.onSurfaceVariant,
                    )
                }
                Spacer(GlanceModifier.height(3.dp))
                LinearProgressIndicator(
                    progress = item.progress,
                    modifier = GlanceModifier.fillMaxWidth().height(4.dp),
                    color = GlanceTheme.colors.primary,
                    backgroundColor = GlanceTheme.colors.secondaryContainer,
                )
            }
        }
    }
    Footer(snap.okAt, snap.error, snap.partial)
}
