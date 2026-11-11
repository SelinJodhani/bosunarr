package app.bosunarr.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.compose.material3.SnackbarHostState
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
import app.bosunarr.nav.ServiceRoute
import app.bosunarr.ui.common.LocalSnackbar
import app.bosunarr.ui.settings.settingsGraph
import kotlin.reflect.KClass

private data class Tab(val route: Any, val cls: KClass<*>, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val TABS = listOf(
    Tab(DashboardRoute, DashboardRoute::class, "Home", Icons.Outlined.Home, Icons.Rounded.Home),
    Tab(LibraryRoute, LibraryRoute::class, "Library", Icons.Outlined.VideoLibrary, Icons.Rounded.VideoLibrary),
    Tab(DiscoverRoute, DiscoverRoute::class, "Discover", Icons.Outlined.Explore, Icons.Rounded.Explore),
    Tab(CalendarRoute, CalendarRoute::class, "Calendar", Icons.Outlined.CalendarMonth, Icons.Rounded.CalendarMonth),
    Tab(ActivityRoute, ActivityRoute::class, "Activity", Icons.Outlined.Download, Icons.Rounded.Download),
)

@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val entry by nav.currentBackStackEntryAsState()
    val dest = entry?.destination
    val onTab = TABS.any { t -> dest?.hierarchy?.any { it.hasRoute(t.cls) } == true }

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

    CompositionLocalProvider(LocalSnackbar provides snackbar) {
        Scaffold(
            contentWindowInsets = WindowInsets(0),
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                AnimatedVisibility(onTab, enter = expandVertically(), exit = shrinkVertically()) {
                    NavigationBar {
                        TABS.forEach { t ->
                            val selected = dest?.hierarchy?.any { it.hasRoute(t.cls) } == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    if (selected) {
                                        // Re-tapping the current tab returns to its root.
                                        nav.popBackStack(t.route, inclusive = false)
                                    } else nav.navigate(t.route) {
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
            },
        ) { inner ->
            NavHost(
                navController = nav,
                startDestination = DashboardRoute,
                modifier = Modifier.padding(inner).consumeWindowInsets(inner),
                enterTransition = { fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 12 } },
                exitTransition = { fadeOut(tween(160)) },
                popEnterTransition = { fadeIn(tween(220)) },
                popExitTransition = { fadeOut(tween(160)) + slideOutHorizontally(tween(220)) { it / 12 } },
            ) {
                composable<DashboardRoute> { DashboardScreen(nav) }
                libraryGraph(nav)
                discoverGraph(nav)
                composable<CalendarRoute> { CalendarScreen(nav) }
                composable<ActivityRoute> { ActivityScreen(nav) }
                settingsGraph(nav)
                composable<ServiceRoute> { ServiceScreen(it.toRoute<ServiceRoute>().id, nav) }
            }
        }
    }
}
