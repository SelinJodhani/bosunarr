package app.bosunarr.widget

import android.content.Context
import androidx.annotation.Keep
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.unit.ColorProvider
import app.bosunarr.R

/** Online/offline for every configured service except Whisparr. */
// Kept by name: Glance maps placed widgets to this class name, which must survive R8 across app updates.
@Keep
class StatusWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact
    override val previewSizeMode = SizeMode.Responsive(setOf(DpSize(160.dp, 160.dp)))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val flow = WidgetStore.status(context)
        if (System.currentTimeMillis() - (flow.value?.at ?: 0) > WidgetRefresh.STALE_MS) WidgetRefresh.now(context)
        provideContent {
            val snap by flow.collectAsState()
            val since by WidgetStore.refreshingSince.collectAsState()
            GlanceTheme { StatusContent(snap, isRefreshing(since)) }
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val now = System.currentTimeMillis()
        val sample = StatusSnapshot(
            at = now, okAt = now,
            services = listOf(
                ServiceHealth("1", "Sonarr", "sonarr", true), ServiceHealth("2", "Radarr", "radarr", true),
                ServiceHealth("3", "qBittorrent", "qbittorrent", true), ServiceHealth("4", "Jellyfin", "jellyfin", true),
                ServiceHealth("5", "Prowlarr", "prowlarr", true), ServiceHealth("6", "Seerr", "jellyseerr", true),
            ),
        )
        provideContent { GlanceTheme { StatusContent(sample, refreshing = false) } }
    }
}

class StatusWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StatusWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetRefresh.schedule(context)
        WidgetRefresh.now(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetRefresh.onAppOpened(context)
    }
}

private fun headline(snap: StatusSnapshot): String {
    val total = snap.services.size
    val online = snap.services.count { it.ok }
    return when {
        online == total -> if (total == 1) "Online" else "All $total online"
        online == 0 -> if (total == 1) "Offline" else "All $total offline"
        else -> "$online of $total online"
    }
}

@Composable
private fun StatusContent(snap: StatusSnapshot?, refreshing: Boolean) {
    val size = LocalSize.current
    val wide = size.height < 100.dp
    WidgetSurface(DeepLink.DASHBOARD, padding = if (wide) 10.dp else 12.dp) {
        when {
            snap == null -> Message("Checking services…", null)
            snap.services.isEmpty() -> Message("No services yet", "Add your services in Bosunarr")
            wide -> Wide(snap, refreshing, size)
            else -> Square(snap, refreshing, size)
        }
    }
}

@Composable
private fun dotColor(ok: Boolean): ColorProvider = if (ok) OnlineColor else GlanceTheme.colors.error

@Composable
private fun Dot(ok: Boolean, size: androidx.compose.ui.unit.Dp = 8.dp) {
    Image(
        ImageProvider(R.drawable.widget_dot), if (ok) "Online" else "Offline",
        GlanceModifier.size(size), colorFilter = ColorFilter.tint(dotColor(ok)),
    )
}

/** Offline services first, so a problem is never hidden behind "+3 more". */
private fun ordered(snap: StatusSnapshot) = snap.services.sortedBy { it.ok }

/** 4×1: headline, then a logo per service with a status dot. */
@Composable
private fun ColumnScope.Wide(snap: StatusSnapshot, refreshing: Boolean, size: DpSize) {
    val allOk = snap.services.all { it.ok }
    Row(GlanceModifier.fillMaxWidth().defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
        Column {
            Txt(headline(snap), size = 15.sp, weight = FontWeight.Bold, color = if (allOk) GlanceTheme.colors.onSurface else GlanceTheme.colors.error)
            Txt(updatedLabel(snap.okAt), size = 11.sp, color = GlanceTheme.colors.onSurfaceVariant)
        }
        Spacer(GlanceModifier.defaultWeight())
        val fit = ((size.width.value - 150) / 28).toInt().coerceAtLeast(0)
        val list = ordered(snap)
        list.take(fit).forEach { s ->
            Box(GlanceModifier.size(26.dp), contentAlignment = Alignment.BottomEnd) {
                val icon = serviceIcon(s.type)
                if (icon != null) Image(ImageProvider(icon), s.name, GlanceModifier.size(24.dp))
                else Box(GlanceModifier.size(24.dp), contentAlignment = Alignment.Center) { Txt(s.name.take(1), size = 12.sp, weight = FontWeight.Bold) }
                Dot(s.ok, 9.dp)
            }
            Spacer(GlanceModifier.width(2.dp))
        }
        if (list.size > fit && fit > 0) Txt("+${list.size - fit}", size = 11.sp, color = GlanceTheme.colors.onSurfaceVariant)
        RefreshButton(refreshing)
    }
}

/** 2×2 and up: headline and a dot + name per service (two columns when wide enough). */
@Composable
private fun ColumnScope.Square(snap: StatusSnapshot, refreshing: Boolean, size: DpSize) {
    val allOk = snap.services.all { it.ok }
    Header("Services", refreshing)
    Txt(headline(snap), size = 16.sp, weight = FontWeight.Bold, color = if (allOk) GlanceTheme.colors.onSurface else GlanceTheme.colors.error)
    Spacer(GlanceModifier.height(4.dp))
    val columns = if (size.width.value >= 170) 2 else 1
    // padding 24 + header 28 + headline 26 + footer 16; rows ~20dp.
    val rows = ((size.height.value - 94) / 20).toInt().coerceAtLeast(1)
    val list = ordered(snap)
    val capacity = rows * columns
    val shown = if (list.size > capacity) list.take(capacity - 1) else list
    Column(GlanceModifier.fillMaxWidth().defaultWeight()) {
        shown.chunked(columns).forEach { line ->
            Row(GlanceModifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                line.forEach { s ->
                    Row(GlanceModifier.defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
                        Dot(s.ok)
                        Spacer(GlanceModifier.width(6.dp))
                        Txt(s.name, size = 12.sp, color = if (s.ok) GlanceTheme.colors.onSurface else GlanceTheme.colors.error)
                    }
                }
                if (line.size < columns) Spacer(GlanceModifier.defaultWeight())
            }
        }
        if (shown.size < list.size) Txt("+${list.size - shown.size} more", size = 11.sp, color = GlanceTheme.colors.onSurfaceVariant)
    }
    Footer(snap.okAt, snap.error, null)
}
