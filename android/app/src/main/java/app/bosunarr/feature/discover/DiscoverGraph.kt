package app.bosunarr.feature.discover

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import app.bosunarr.nav.DiscoverRoute
import app.bosunarr.nav.MediaRoute
import app.bosunarr.nav.RequestsRoute

/** Registers Discover, MediaRoute and RequestsRoute. [tabFrame] wraps the tab root (adds the nav bar). */
fun NavGraphBuilder.discoverGraph(nav: NavController, tabFrame: @Composable (@Composable () -> Unit) -> Unit) {
    composable<DiscoverRoute> { tabFrame { DiscoverScreen(nav) } }
    composable<MediaRoute> {
        val r = it.toRoute<MediaRoute>()
        MediaScreen(nav, r.mediaType, r.tmdbId)
    }
    composable<RequestsRoute> { RequestsScreen(nav) }
}
