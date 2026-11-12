package app.bosunarr.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.bosunarr.app
import app.bosunarr.feature.activity.ActivityScreen
import app.bosunarr.feature.calendar.CalendarScreen
import app.bosunarr.feature.dashboard.DashboardScreen
import app.bosunarr.feature.discover.discoverGraph
import app.bosunarr.feature.library.libraryGraph
import app.bosunarr.feature.services.ServiceScreen
import app.bosunarr.nav.ActivityRoute
import app.bosunarr.nav.CalendarRoute
import app.bosunarr.nav.DashboardRoute
import app.bosunarr.nav.DiscoverRoute
import app.bosunarr.nav.LibraryRoute
import app.bosunarr.nav.OnboardingRoute
import app.bosunarr.nav.ServiceRoute
import app.bosunarr.ui.common.CrashReportPrompt
import app.bosunarr.ui.common.LicencesRoute
import app.bosunarr.ui.common.LicencesScreen
import app.bosunarr.ui.common.LocalSnackbar
import app.bosunarr.ui.common.TabReselect
import app.bosunarr.ui.onboarding.OnboardingScreen
import app.bosunarr.ui.settings.settingsGraph
import app.bosunarr.update.UpdatePrompt
import app.bosunarr.update.Updater
import app.bosunarr.notify.NotificationLinkEffect
import app.bosunarr.notify.notificationsGraph
import kotlin.reflect.KClass

private data class Tab(val route: Any, val cls: KClass<*>, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val TABS = listOf(
    Tab(DashboardRoute, DashboardRoute::class, "Home", Icons.Outlined.Home, Icons.Rounded.Home),
    Tab(LibraryRoute, LibraryRoute::class, "Library", Icons.Outlined.VideoLibrary, Icons.Rounded.VideoLibrary),
    Tab(DiscoverRoute, DiscoverRoute::class, "Discover", Icons.Outlined.Explore, Icons.Rounded.Explore),
    Tab(CalendarRoute, CalendarRoute::class, "Calendar", Icons.Outlined.CalendarMonth, Icons.Rounded.CalendarMonth),
    Tab(ActivityRoute, ActivityRoute::class, "Activity", Icons.Outlined.Download, Icons.Rounded.Download),
)

private fun NavDestination.isTabRoot() = TABS.any { hasRoute(it.cls) }

/*
 * Motion. Forward: a quick Material fade-through (the new screen fades in while growing from 96%). Back: the
 * screen being left shrinks and fades away on top of the one underneath, which is already in place — driven by the
 * finger during a predictive back gesture (NavHost seeks these transitions), so nothing slides sideways. Switching
 * tabs is instant, like the system's own apps.
 */
private val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

// Pixel-style horizontal navigation (like the Settings app): forward, the new page slides in from the right while
// the old one drifts left; back, the page slides off to the right, tracking the finger (predictive back seeks
// these, hence linear easing), and the previous page slides back in from the left.
private val ForwardEnter = slideInHorizontally(tween(380, easing = Emphasized)) { it }
private val ForwardExit = slideOutHorizontally(tween(380, easing = Emphasized)) { -it / 4 }
private val BackEnter = slideInHorizontally(tween(320, easing = LinearEasing)) { -it / 4 }
private val BackExit = slideOutHorizontally(tween(320, easing = LinearEasing)) { it }

@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val entry by nav.currentBackStackEntryAsState()
    val dest = entry?.destination
    val onTab = dest?.isTabRoot() == true

    // A magnet link shared into the app → go to Activity, which opens the Add torrent sheet.
    val pending by app.pendingTorrent.collectAsState()
    LaunchedEffect(pending) {
        if (pending != null && dest?.hasRoute(ActivityRoute::class) != true) {
            nav.navigate(ActivityRoute) {
                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    // First run: open the setup flow once the store has loaded and there's nothing configured yet.
    val appState by app.state.collectAsState()
    var autoOnboarded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(appState) {
        val st = appState ?: return@LaunchedEffect
        if (!autoOnboarded && !st.onboarded && st.services.isEmpty()) {
            autoOnboarded = true
            nav.navigate(OnboardingRoute) { launchSingleTop = true }
        }
    }

    NotificationLinkEffect(nav)

    // Quiet update check once the store is loaded (at most every 12 h; release builds only).
    LaunchedEffect(appState != null) { if (appState != null) Updater.autoCheck() }

    CompositionLocalProvider(LocalSnackbar provides snackbar) {
        UpdatePrompt()
        CrashReportPrompt()
        Scaffold(
            contentWindowInsets = WindowInsets(0),
            snackbarHost = {
                // Clear the system bar, and the navigation bar on tab screens (it lives inside each tab screen).
                SnackbarHost(snackbar, Modifier.navigationBarsPadding().padding(bottom = if (onTab) 80.dp else 0.dp))
            },
        ) { inner ->
            NavHost(
                navController = nav,
                startDestination = DashboardRoute,
                modifier = Modifier.padding(inner).consumeWindowInsets(inner),
                enterTransition = {
                    if (initialState.destination.isTabRoot() && targetState.destination.isTabRoot()) EnterTransition.None else ForwardEnter
                },
                exitTransition = {
                    if (initialState.destination.isTabRoot() && targetState.destination.isTabRoot()) ExitTransition.None else ForwardExit
                },
                popEnterTransition = {
                    if (initialState.destination.isTabRoot() && targetState.destination.isTabRoot()) EnterTransition.None else BackEnter
                },
                popExitTransition = {
                    if (initialState.destination.isTabRoot() && targetState.destination.isTabRoot()) ExitTransition.None else BackExit
                },
            ) {
                tab<DashboardRoute>(nav) { DashboardScreen(nav) }
                composable<OnboardingRoute>(
                    enterTransition = { fadeIn(tween(300)) },
                    exitTransition = { fadeOut(tween(250)) },
                ) {
                    OnboardingScreen(onFinish = { if (!nav.popBackStack(DashboardRoute, inclusive = false)) nav.navigate(DashboardRoute) })
                }
                libraryGraph(nav) { content -> TabFrame(nav, LibraryRoute::class) { content() } }
                discoverGraph(nav) { content -> TabFrame(nav, DiscoverRoute::class) { content() } }
                tab<CalendarRoute>(nav) { CalendarScreen(nav) }
                tab<ActivityRoute>(nav) { ActivityScreen(nav) }
                settingsGraph(nav)
                notificationsGraph(nav)
                composable<ServiceRoute> { ServiceScreen(it.toRoute<ServiceRoute>().id, nav) }
                composable<LicencesRoute> { LicencesScreen(nav) }
            }
        }
    }
}

/** A bottom-bar destination: its screen with the navigation bar under it. */
private inline fun <reified T : Any> NavGraphBuilder.tab(nav: NavController, crossinline content: @Composable () -> Unit) {
    composable<T> { TabFrame(nav, T::class) { content() } }
}

/**
 * Tab screen + navigation bar. The bar belongs to each tab screen (not to the app frame), so it arrives and leaves
 * with the screen: a detail screen covers it, and backing out (predictive back included) reveals the tab screen
 * with its bar already in place — no bar growing in afterwards and no content jumping.
 */
@Composable
private fun TabFrame(nav: NavController, tab: KClass<*>, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        // The bar pads itself for the gesture/navigation area, so the screen above must not.
        Box(Modifier.weight(1f).consumeWindowInsets(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))) { content() }
        AppNavigationBar(nav, tab)
    }
}

/** [current] is the tab of the screen this bar sits under (not the back stack top, which differs mid-gesture). */
@Composable
private fun AppNavigationBar(nav: NavController, current: KClass<*>) {
    NavigationBar {
        TABS.forEach { t ->
            val selected = t.cls == current
            NavigationBarItem(
                selected = selected,
                onClick = {
                    // Re-tapping the current tab scrolls it back to the top.
                    if (selected) TabReselect.emit()
                    else nav.navigate(t.route) {
                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(if (selected) t.selectedIcon else t.icon, contentDescription = t.label) },
                label = null,
                alwaysShowLabel = false,
            )
        }
    }
}
