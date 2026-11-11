package app.bosunarr.feature.library

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.bosunarr.nav.LibraryRoute
import app.bosunarr.ui.common.AppScaffold
import app.bosunarr.ui.common.MessageBox

/** Registers Library, SeriesRoute, MovieRoute and LibraryAddRoute. */
fun NavGraphBuilder.libraryGraph(nav: NavController) {
    composable<LibraryRoute> {
        AppScaffold("Library") { p -> MessageBox(Icons.Rounded.VideoLibrary, "Library", Modifier.padding(p), hint = "Coming soon") }
    }
}
