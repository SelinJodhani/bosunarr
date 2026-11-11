package app.bosunarr.feature.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import app.bosunarr.app
import app.bosunarr.data.ServiceConfig
import app.bosunarr.data.ServiceType
import app.bosunarr.nav.LibraryAddRoute
import app.bosunarr.nav.LibraryRoute
import app.bosunarr.nav.MovieRoute
import app.bosunarr.nav.SeriesRoute
import app.bosunarr.ui.common.AppScaffold
import app.bosunarr.ui.common.LoadingBox
import app.bosunarr.ui.common.MessageBox
import app.bosunarr.ui.common.back

/** Registers Library, SeriesRoute, MovieRoute and LibraryAddRoute. */
fun NavGraphBuilder.libraryGraph(nav: NavController) {
    composable<LibraryRoute> { LibraryScreen(nav) }
    composable<SeriesRoute> { entry ->
        val r = entry.toRoute<SeriesRoute>()
        WithArrService(nav, r.serviceId, ServiceType.Sonarr, "Series") { s -> SeriesScreen(nav, entry, s, r.id) }
    }
    composable<MovieRoute> { entry ->
        val r = entry.toRoute<MovieRoute>()
        WithArrService(nav, r.serviceId, ServiceType.Radarr, "Movie") { s -> MovieScreen(nav, entry, s, r.id) }
    }
    composable<LibraryAddRoute> { entry -> LibraryAddScreen(nav, entry.toRoute<LibraryAddRoute>()) }
}

/** Resolve the route's service (waiting for the encrypted store on a cold start), or explain why it's gone. */
@Composable
private fun WithArrService(nav: NavController, id: String, type: ServiceType, title: String, content: @Composable (ServiceConfig) -> Unit) {
    val loaded by app.state.collectAsState()
    val services by app.services.collectAsState()
    val s = services.firstOrNull { it.id == id && it.serviceType == type }
    when {
        s != null -> content(s)
        loaded == null -> AppScaffold(title, onBack = { nav.back() }) { p -> LoadingBox(Modifier.padding(p)) }
        else -> AppScaffold(title, onBack = { nav.back() }) { p ->
            Column(Modifier.padding(p).fillMaxSize().verticalScroll(rememberScrollState())) {
                MessageBox(Icons.Rounded.Dns, "${type.label} not found", hint = "This ${type.label} service was removed from Bosunarr.", action = "Go back", onAction = { nav.back() })
            }
        }
    }
}
