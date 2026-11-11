package app.bosunarr.widget

import android.content.Context
import androidx.annotation.Keep
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import app.bosunarr.R
import java.time.LocalDate

/** The next monitored Sonarr/Radarr releases with posters (Whisparr is never included). */
// Kept by name: Glance maps placed widgets to this class name, which must survive R8 across app updates.
@Keep
class UpNextWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact
    override val previewSizeMode = SizeMode.Responsive(setOf(DpSize(300.dp, 180.dp)))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val flow = WidgetStore.upNext(context)
        if (System.currentTimeMillis() - (flow.value?.at ?: 0) > WidgetRefresh.STALE_MS) WidgetRefresh.now(context)
        provideContent {
            val snap by flow.collectAsState()
            val since by WidgetStore.refreshingSince.collectAsState()
            GlanceTheme { UpNextContent(snap, isRefreshing(since)) { WidgetStore.poster(context, it) } }
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val today = LocalDate.now().toEpochDay()
        val sample = UpNextSnapshot(
            at = System.currentTimeMillis(), okAt = System.currentTimeMillis(),
            items = listOf(
                UpNextItem("1", "Severance", "S02E08 · Sweet Vitriol", today, null),
                UpNextItem("2", "The Last of Us", "S02E03", today + 1),
                UpNextItem("3", "Andor", "S02E04", today + 3),
                UpNextItem("4", "Mickey 17", "Digital release", today + 5),
            ),
        )
        provideContent { GlanceTheme { UpNextContent(sample, refreshing = false) { null } } }
    }
}

class UpNextWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = UpNextWidget()

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

@Composable
private fun UpNextContent(snap: UpNextSnapshot?, refreshing: Boolean, poster: (String) -> Bitmap?) {
    val size = LocalSize.current
    WidgetSurface(DeepLink.CALENDAR) {
        Header("Up next", refreshing)
        Spacer(GlanceModifier.height(6.dp))
        when {
            snap == null -> Message("Loading calendar…", null)
            !snap.configured -> Message("No Sonarr or Radarr", "Add one in Bosunarr to see what's coming")
            snap.items.isEmpty() && snap.error != null -> Message("Can't reach Sonarr/Radarr", "Tap ↻ to try again")
            snap.items.isEmpty() -> Message("Nothing coming up", "Nothing monitored in the next 30 days")
            size.height < 200.dp -> Strip(snap.items, size, poster)
            else -> ListRows(snap.items, size, poster)
        }
        Footer(snap?.okAt ?: 0, snap?.error, snap?.partial)
    }
}

@Composable
private fun PosterBox(name: String?, poster: (String) -> Bitmap?, modifier: GlanceModifier, title: String) {
    val bmp = name?.let(poster)
    Box(modifier.cornerRadius(8.dp).background(GlanceTheme.colors.secondaryContainer), contentAlignment = Alignment.Center) {
        if (bmp != null) Image(ImageProvider(bmp), title, GlanceModifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(R.drawable.ic_widget_movie, null, 20.dp, GlanceTheme.colors.onSecondaryContainer)
    }
}

private fun timeLabel(item: UpNextItem, today: LocalDate): String {
    val day = dayLabel(item.day, today)
    return if (item.time != null) "$day · ${clock(item.time)}" else day
}

/** 4×2: a row of posters with the day underneath. */
@Composable
private fun ColumnScope.Strip(items: List<UpNextItem>, size: DpSize, poster: (String) -> Bitmap?) {
    val context = LocalContext.current
    val today = LocalDate.now()
    val count = ((size.width.value - 24) / 78).toInt().coerceIn(2, 6).coerceAtMost(items.size)
    // padding 24 + header 28 + gap 6 + labels 30 + footer 16
    val posterH = (size.height.value - 104).coerceIn(36f, 160f).dp
    Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
        items.take(count).forEachIndexed { i, item ->
            if (i > 0) Spacer(GlanceModifier.width(8.dp))
            Column(GlanceModifier.defaultWeight().clickable(open(context, item.link))) {
                PosterBox(item.poster, poster, GlanceModifier.fillMaxWidth().height(posterH), item.title)
                Spacer(GlanceModifier.height(3.dp))
                Txt(item.title, size = 11.sp, weight = FontWeight.Medium)
                Txt(dayLabel(item.day, today), size = 11.sp, color = GlanceTheme.colors.primary)
            }
        }
        // Keep cells the same width when there are fewer items than slots.
        val slots = ((size.width.value - 24) / 78).toInt().coerceIn(2, 6)
        repeat(slots - count) {
            Spacer(GlanceModifier.width(8.dp))
            Spacer(GlanceModifier.defaultWeight())
        }
    }
}

/** 4×3 and taller: one row per release with a small poster. */
@Composable
private fun ColumnScope.ListRows(items: List<UpNextItem>, size: DpSize, poster: (String) -> Bitmap?) {
    val context = LocalContext.current
    val today = LocalDate.now()
    val rows = ((size.height.value - 74) / 60).toInt().coerceIn(1, 6)
    Column(GlanceModifier.fillMaxWidth().defaultWeight()) {
        items.take(rows).forEach { item ->
            Row(
                GlanceModifier.fillMaxWidth().padding(bottom = 6.dp).clickable(open(context, item.link)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PosterBox(item.poster, poster, GlanceModifier.size(36.dp, 54.dp), item.title)
                Spacer(GlanceModifier.width(10.dp))
                Column(GlanceModifier.defaultWeight()) {
                    Txt(item.title, size = 13.sp, weight = FontWeight.Medium)
                    if (item.subtitle != null) Txt(item.subtitle, size = 11.sp, color = GlanceTheme.colors.onSurfaceVariant)
                }
                Spacer(GlanceModifier.width(8.dp))
                Txt(timeLabel(item, today), size = 11.sp, color = GlanceTheme.colors.primary)
            }
        }
    }
}
