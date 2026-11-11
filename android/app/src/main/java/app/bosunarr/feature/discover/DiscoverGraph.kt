package app.bosunarr.feature.discover

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.bosunarr.nav.DiscoverRoute
import app.bosunarr.ui.common.AppScaffold
import app.bosunarr.ui.common.MessageBox

/** Registers Discover, MediaRoute and RequestsRoute. */
fun NavGraphBuilder.discoverGraph(nav: NavController) {
    composable<DiscoverRoute> {
        AppScaffold("Discover") { p -> MessageBox(Icons.Rounded.Explore, "Discover", Modifier.padding(p), hint = "Coming soon") }
    }
}
