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
import app.bosunarr.nav.ReleaseSearchRoute
import app.bosunarr.feature.library.release.ReleaseSearchScreen
import app.bosunarr.nav.SeriesRoute
import app.bosunarr.ui.art.SeaScene
import app.bosunarr.ui.common.FullScreenScroll
import app.bosunarr.ui.common.AppScaffold
import app.bosunarr.ui.common.LoadingBox
import app.bosunarr.ui.common.MessageBox
import app.bosunarr.ui.common.back

/** Registers Library, SeriesRoute, MovieRoute and LibraryAddRoute. [tabFrame] wraps the tab root (adds the nav bar). */
fun NavGraphBuilder.libraryGraph(nav: NavController, tabFrame: @Composable (@Composable () -> Unit) -> Unit) {
    composable<LibraryRoute> { tabFrame { LibraryScreen(nav) } }
    composable<SeriesRoute> { entry ->
        val r = entry.toRoute<SeriesRoute>()
        WithArrService(nav, r.serviceId, setOf(ServiceType.Sonarr), "Series") { s -> SeriesScreen(nav, entry, s, r.id) }
    }
    composable<MovieRoute> { entry ->
        val r = entry.toRoute<MovieRoute>()
        WithArrService(nav, r.serviceId, setOf(ServiceType.Radarr, ServiceType.Whisparr), "Movie") { s -> MovieScreen(nav, entry, s, r.id) }
    }
    composable<LibraryAddRoute> { entry -> LibraryAddScreen(nav, entry.toRoute<LibraryAddRoute>()) }
    composable<ReleaseSearchRoute> { entry ->
        val r = entry.toRoute<ReleaseSearchRoute>()
        val types = if (r.movieId > 0) setOf(ServiceType.Radarr, ServiceType.Whisparr) else setOf(ServiceType.Sonarr)
        WithArrService(nav, r.serviceId, types, "Interactive search") { s -> ReleaseSearchScreen(nav, s, r) }
    }
}

/** Resolve the route's service (waiting for the encrypted store on a cold start), or explain why it's gone. */
@Composable
private fun WithArrService(nav: NavController, id: String, types: Set<ServiceType>, title: String, content: @Composable (ServiceConfig) -> Unit) {
    val type = types.first()
    val loaded by app.state.collectAsState()
    val services by app.services.collectAsState()
    val s = services.firstOrNull { it.id == id && it.serviceType in types }
    when {
        s != null -> content(s)
        loaded == null -> AppScaffold(title, onBack = { nav.back() }) { p -> LoadingBox(Modifier.padding(p)) }
        else -> AppScaffold(title, onBack = { nav.back() }) { p ->
            FullScreenScroll(Modifier.padding(p)) { m ->
                MessageBox(Icons.Rounded.Dns, "${type.label} not found", m, hint = "This ${type.label} service was removed from Bosunarr.", action = "Go back", onAction = { nav.back() }, scene = SeaScene.Compass)
            }
        }
    }
}
