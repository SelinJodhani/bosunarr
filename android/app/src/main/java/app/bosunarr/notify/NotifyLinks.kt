package app.bosunarr.notify

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import app.bosunarr.MainActivity
import app.bosunarr.app
import app.bosunarr.nav.ActivityRoute
import app.bosunarr.nav.CalendarRoute
import app.bosunarr.nav.DiscoverRoute
import app.bosunarr.nav.LibraryRoute
import app.bosunarr.nav.DashboardRoute
import app.bosunarr.nav.MediaRoute
import app.bosunarr.nav.MovieRoute
import app.bosunarr.nav.RequestsRoute
import app.bosunarr.nav.SeriesRoute
import app.bosunarr.nav.ServiceRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * Notification taps. A notification carries a small link string ("movie/<serviceId>/<id>", "requests", …)
 * in an intent extra; [handle] (called from MainActivity) stashes it and [NotificationLinkEffect] (in AppRoot)
 * navigates once the store has loaded.
 */
object NotifyLinks {
    const val ACTION = "app.bosunarr.notify.OPEN"
    const val EXTRA_LINK = "app.bosunarr.notify.LINK"

    const val HOME = "home"
    const val ACTIVITY = "activity"
    const val REQUESTS = "requests"
    const val SETTINGS = "notifications"
    fun movie(serviceId: String, id: Int) = "movie/$serviceId/$id"
    fun series(serviceId: String, id: Int) = "series/$serviceId/$id"
    fun media(mediaType: String, tmdbId: Int) = "media/$mediaType/$tmdbId"
    fun service(serviceId: String) = "service/$serviceId"

    internal val pending = MutableStateFlow<String?>(null)
    /** A ready-made route from a widget or Quick Settings tile tap (see widget/DeepLink). */
    val pendingRoute = MutableStateFlow<Any?>(null)

    /** True if [intent] came from one of our notifications (and was taken care of). */
    fun handle(intent: Intent?): Boolean {
        if (intent?.action != ACTION) return false
        pending.value = intent.getStringExtra(EXTRA_LINK) ?: ACTIVITY
        return true
    }

    fun pendingIntent(context: Context, link: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION)
            // Unique data so PendingIntents for different links never collapse into one.
            .setData(Uri.parse("bosunarr-notify://open/" + Uri.encode(link)))
            .putExtra(EXTRA_LINK, link)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** The nav route for a link; null if it can't be parsed. */
    fun route(link: String): Any? {
        val p = link.split('/')
        return when (p.firstOrNull()) {
            HOME -> DashboardRoute
            ACTIVITY -> ActivityRoute
            REQUESTS -> RequestsRoute
            SETTINGS -> NotificationSettingsRoute
            "movie" -> p.getOrNull(2)?.toIntOrNull()?.let { MovieRoute(p[1], it) }
            "series" -> p.getOrNull(2)?.toIntOrNull()?.let { SeriesRoute(p[1], it) }
            "media" -> p.getOrNull(2)?.toIntOrNull()?.let { MediaRoute(p[1], it) }
            "service" -> p.getOrNull(1)?.let { ServiceRoute(it) }
            else -> null
        }
    }
}

/** Navigate to whatever notification the user tapped. Call once inside AppRoot, after the NavHost. */
@Composable
fun NotificationLinkEffect(nav: NavController) {
    val link by NotifyLinks.pending.collectAsState()
    val direct by NotifyLinks.pendingRoute.collectAsState()
    val loaded = app.state.collectAsState().value != null
    LaunchedEffect(link, direct, loaded) {
        if (!loaded) return@LaunchedEffect
        val route = when {
            direct != null -> direct.also { NotifyLinks.pendingRoute.value = null }
            link != null -> NotifyLinks.route(link!!).also { NotifyLinks.pending.value = null }
            else -> null
        } ?: return@LaunchedEffect
        nav.currentBackStackEntryFlow.first() // the NavHost has set its graph
        runCatching {
            // Tabs: same navigation as the bottom bar (keeps each tab's state). Everything else: push.
            if (route == ActivityRoute || route == DashboardRoute || route == CalendarRoute ||
                route == LibraryRoute || route == DiscoverRoute
            ) {
                nav.navigate(route) {
                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            } else nav.navigate(route) { launchSingleTop = true }
        }
    }
}
