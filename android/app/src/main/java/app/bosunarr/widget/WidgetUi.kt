package app.bosunarr.widget

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.CircularProgressIndicator
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.bosunarr.R
import app.bosunarr.data.ServiceType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle as JTextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Online green that reads on both light and dark widget backgrounds. */
internal val OnlineColor = ColorProvider(day = Color(0xFF1E8E3E), night = Color(0xFF81C995))

internal fun open(context: Context, target: String): Action = actionStartActivity(DeepLink.intent(context, target))

@Composable
internal fun Txt(
    text: String,
    modifier: GlanceModifier = GlanceModifier,
    size: TextUnit = 13.sp,
    weight: FontWeight = FontWeight.Normal,
    color: ColorProvider = GlanceTheme.colors.onSurface,
    maxLines: Int = 1,
) = Text(text, modifier, style = TextStyle(color = color, fontSize = size, fontWeight = weight), maxLines = maxLines)

@Composable
internal fun Icon(res: Int, description: String?, size: Dp = 16.dp, color: ColorProvider = GlanceTheme.colors.onSurfaceVariant, modifier: GlanceModifier = GlanceModifier) =
    Image(ImageProvider(res), description, modifier.size(size), colorFilter = ColorFilter.tint(color))

/** Rounded widget surface (system radius on Android 12+) that opens [target] when tapped. */
@Composable
internal fun WidgetSurface(target: String, padding: Dp = 12.dp, content: @Composable ColumnScope.() -> Unit) {
    val context = LocalContext.current
    var m = GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).appWidgetBackground()
    m = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) m.cornerRadius(android.R.dimen.system_app_widget_background_radius) else m.cornerRadius(20.dp)
    Column(m.clickable(open(context, target)).padding(padding), content = content)
}

/** Title row with the refresh button (a spinner while a refresh the user asked for is running). */
@Composable
internal fun Header(title: String, refreshing: Boolean, trailing: String? = null) {
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Txt(title, size = 14.sp, weight = FontWeight.Medium, color = GlanceTheme.colors.primary)
        if (trailing != null) {
            Spacer(GlanceModifier.width(8.dp))
            Txt(trailing, GlanceModifier.defaultWeight(), size = 12.sp, color = GlanceTheme.colors.onSurfaceVariant)
        } else Spacer(GlanceModifier.defaultWeight())
        RefreshButton(refreshing)
    }
}

@Composable
internal fun RefreshButton(refreshing: Boolean) {
    Box(
        GlanceModifier.size(28.dp).cornerRadius(14.dp).clickable(actionRunCallback<RefreshWidgetsAction>())
            .semantics { contentDescription = "Refresh" },
        contentAlignment = Alignment.Center,
    ) {
        if (refreshing) CircularProgressIndicator(GlanceModifier.size(16.dp), color = GlanceTheme.colors.primary)
        else Icon(R.drawable.ic_widget_refresh, "Refresh", 18.dp)
    }
}

/** "Updated 14:05", or why the last refresh failed while older data is shown. */
@Composable
internal fun Footer(okAt: Long, error: String?, partial: String?) {
    val updated = updatedLabel(okAt)
    val text = when {
        error != null -> if (okAt > 0) "$error · $updated" else error
        partial != null -> "$partial · $updated"
        else -> updated
    }
    Txt(
        text, GlanceModifier.fillMaxWidth(), size = 11.sp,
        color = if (error != null || partial != null) GlanceTheme.colors.error else GlanceTheme.colors.onSurfaceVariant,
    )
}

/** Friendly empty / setup / error state, centred. */
@Composable
internal fun ColumnScope.Message(title: String, hint: String?) {
    Column(GlanceModifier.fillMaxWidth().defaultWeight(), verticalAlignment = Alignment.CenterVertically, horizontalAlignment = Alignment.CenterHorizontally) {
        Txt(title, size = 13.sp, weight = FontWeight.Medium, maxLines = 2)
        if (hint != null) {
            Spacer(GlanceModifier.height(2.dp))
            Txt(hint, size = 12.sp, color = GlanceTheme.colors.onSurfaceVariant, maxLines = 2)
        }
    }
}

private val TIME = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
private val DAY_MONTH = DateTimeFormatter.ofPattern("d MMM")

internal fun clock(epochMs: Long): String = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(TIME)

/**
 * Absolute ("Updated 14:05", "Updated yesterday 22:10"): a widget is a static snapshot between refreshes, so a
 * relative "3 min ago" would be frozen and wrong until the next redraw.
 */
internal fun updatedLabel(okAt: Long): String {
    if (okAt <= 0) return "Not updated yet"
    val zone = ZoneId.systemDefault()
    val at = Instant.ofEpochMilli(okAt).atZone(zone)
    val days = ChronoUnit.DAYS.between(at.toLocalDate(), LocalDate.now(zone))
    return when (days) {
        0L -> "Updated ${at.format(TIME)}"
        1L -> "Updated yesterday ${at.format(TIME)}"
        else -> "Updated ${at.format(DAY_MONTH)}"
    }
}

/** "Today", "Tomorrow", "Fri", "in 9 days", "28 Dec". */
internal fun dayLabel(epochDay: Long, today: LocalDate = LocalDate.now()): String {
    val d = LocalDate.ofEpochDay(epochDay)
    val diff = ChronoUnit.DAYS.between(today, d)
    return when {
        diff < 0 -> "Aired"
        diff == 0L -> "Today"
        diff == 1L -> "Tomorrow"
        diff < 7 -> d.dayOfWeek.getDisplayName(JTextStyle.SHORT, Locale.getDefault())
        diff < 14 -> "in $diff days"
        else -> d.format(DAY_MONTH)
    }
}

/** Refresh spinner shows only for a recent tap (a killed refresh must not spin forever). */
internal fun isRefreshing(since: Long): Boolean = since > 0 && System.currentTimeMillis() - since < 2 * 60_000

/** Service logos for the status widget (Whisparr deliberately absent: it never appears on widgets). */
internal fun serviceIcon(type: String): Int? = when (ServiceType.of(type)) {
    ServiceType.Sonarr -> R.drawable.svc_sonarr
    ServiceType.Radarr -> R.drawable.svc_radarr
    ServiceType.Prowlarr -> R.drawable.svc_prowlarr
    ServiceType.Bazarr -> R.drawable.svc_bazarr
    ServiceType.Jellyseerr -> R.drawable.svc_jellyseerr
    ServiceType.Overseerr -> R.drawable.svc_overseerr
    ServiceType.QBittorrent -> R.drawable.svc_qbittorrent
    ServiceType.Jellyfin -> R.drawable.svc_jellyfin
    ServiceType.Jellystat -> R.drawable.svc_jellystat
    ServiceType.Wizarr -> R.drawable.svc_wizarr
    else -> null
}
