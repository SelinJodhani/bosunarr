package app.bosunarr.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.bosunarr.MainActivity
import app.bosunarr.nav.ActivityRoute
import app.bosunarr.nav.CalendarRoute
import app.bosunarr.nav.DashboardRoute
import app.bosunarr.nav.MovieRoute
import app.bosunarr.nav.SeriesRoute
import app.bosunarr.nav.ServiceRoute
import app.bosunarr.nav.SettingsRoute

/**
 * How widgets and Quick Settings tiles open a specific screen: an explicit intent to [MainActivity] carrying
 * [EXTRA] = one of
 *
 * - `dashboard` · `activity` · `calendar` · `settings`
 * - `service:<serviceId>` (a service's own screen, e.g. qBittorrent)
 * - `series:<serviceId>:<seriesId>` · `movie:<serviceId>:<movieId>` (library detail)
 *
 * The intent has no action (MainActivity's magnet/share handling ignores it) and a `bosunarr://open/<target>`
 * data URI only so PendingIntents for different targets stay distinct. [route] turns the extra into a nav route.
 */
object DeepLink {
    const val EXTRA = "bosunarr.open"

    const val DASHBOARD = "dashboard"
    const val ACTIVITY = "activity"
    const val CALENDAR = "calendar"
    const val SETTINGS = "settings"
    fun service(serviceId: String) = "service:$serviceId"
    fun series(serviceId: String, id: Int) = "series:$serviceId:$id"
    fun movie(serviceId: String, id: Int) = "movie:$serviceId:$id"

    fun intent(context: Context, target: String): Intent = Intent(context, MainActivity::class.java)
        .putExtra(EXTRA, target)
        .setData(Uri.Builder().scheme("bosunarr").authority("open").appendPath(target).build())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /** The nav route an intent asks for, or null when it carries no (valid) deep link. */
    fun route(intent: Intent?): Any? = intent?.getStringExtra(EXTRA)?.let(::route)

    fun route(target: String): Any? {
        val parts = target.split(':')
        return when (parts[0]) {
            DASHBOARD -> DashboardRoute
            ACTIVITY -> ActivityRoute
            CALENDAR -> CalendarRoute
            SETTINGS -> SettingsRoute
            "service" -> parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { ServiceRoute(it) }
            "series" -> parts.getOrNull(2)?.toIntOrNull()?.let { SeriesRoute(parts[1], it) }
            "movie" -> parts.getOrNull(2)?.toIntOrNull()?.let { MovieRoute(parts[1], it) }
            else -> null
        }
    }
}
