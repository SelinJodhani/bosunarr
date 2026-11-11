package app.bosunarr.feature.services

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import app.bosunarr.app
import app.bosunarr.data.ServiceConfig
import app.bosunarr.data.ServiceType
import app.bosunarr.feature.activity.QbittorrentServiceScreen
import app.bosunarr.feature.server.ServerServiceScreen
import app.bosunarr.feature.usenet.NzbgetServiceScreen
import app.bosunarr.feature.usenet.SabnzbdServiceScreen
import app.bosunarr.nav.DiscoverRoute
import app.bosunarr.nav.RequestsRoute
import app.bosunarr.ui.common.AppScaffold
import app.bosunarr.ui.common.LocalTopBarState
import app.bosunarr.ui.common.MessageBox
import app.bosunarr.ui.common.back
import kotlinx.serialization.Serializable

/** A service's own screen; dispatches on its type. */
@Composable
fun ServiceScreen(id: String, nav: NavController) {
    val services by app.services.collectAsState()
    val state by app.state.collectAsState()
    val s = services.firstOrNull { it.id == id } ?: app.service(id)
    if (s == null) {
        AppScaffold("Service", onBack = { nav.back() }) { p ->
            // Still reading the store: blank rather than a false "not found".
            if (state != null) MessageBox(Icons.Rounded.Dns, "Service not found", Modifier.padding(p), hint = "It may have been removed in Settings.")
        }
        return
    }
    when (s.serviceType) {
        ServiceType.Sonarr, ServiceType.Radarr, ServiceType.Whisparr -> ArrServiceScreen(s, nav)
        ServiceType.Prowlarr -> ProwlarrServiceScreen(s, nav)
        ServiceType.Bazarr -> BazarrServiceScreen(s, nav)
        ServiceType.Jellyfin -> JellyfinServiceScreen(s, nav)
        ServiceType.Jellystat -> JellystatServiceScreen(s, nav)
        ServiceType.Wizarr -> WizarrServiceScreen(s, nav)
        ServiceType.Jellyseerr, ServiceType.Overseerr -> SeerrServiceScreen(s, nav)
        ServiceType.QBittorrent -> QbittorrentServiceScreen(s, nav)
        ServiceType.Server -> ServerServiceScreen(s, nav)
        ServiceType.Sabnzbd -> SabnzbdServiceScreen(s, nav)
        ServiceType.Nzbget -> NzbgetServiceScreen(s, nav)
        null -> AppScaffold(s.name, onBack = { nav.back() }) { p ->
            MessageBox(Icons.Rounded.Dns, "Unsupported service", Modifier.padding(p), hint = "Bosunarr doesn't know the service type \"${s.type}\".")
        }
    }
}

/* ================================================================ shared screen bodies */

/** Tabs under the app bar + the selected tab's body. Tab choice survives rotation and navigation. */
@Composable
fun TabbedBody(tabs: List<String>, modifier: Modifier = Modifier, content: @Composable (Int) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // Tint with the top bar when content scrolls under them, so bar + tabs read as one header.
    val scrolled = (LocalTopBarState.current?.overlappedFraction ?: 0f) > 0.01f
    val container by animateColorAsState(
        if (scrolled) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface,
        tween(300),
        label = "tabs",
    )
    Column(modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tab, containerColor = container) {
            tabs.forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, maxLines = 1, overflow = TextOverflow.Ellipsis) })
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) { content(tab) }
    }
}

/** Scrolling list of section cards with the standard gutters and bottom clearance. */
@Composable
fun CardList(modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/* ================================================================ Jellyseerr / Overseerr */

@Serializable
private data class SeerrAbout(val version: String? = null, val totalRequests: Int? = null, val totalMediaItems: Int? = null)

@Serializable
private data class SeerrCountResponse(
    val total: Int = 0,
    val pending: Int = 0,
    val approved: Int = 0,
    val processing: Int = 0,
    val available: Int = 0,
    val declined: Int = 0,
)

private data class SeerrOverview(val about: SeerrAbout, val counts: SeerrCountResponse?)

/** Requests live in Discover; this screen gives the version, request counts and the ways in. */
@Composable
private fun SeerrServiceScreen(s: ServiceConfig, nav: NavController) {
    val hub = rememberRefreshHub()
    val loader = rememberHubLoader(hub, "svc:seerr:${s.id}") {
        val about = app.client.get<SeerrAbout>(s, "/api/v1/settings/about")
        val counts = runCatching { app.client.get<SeerrCountResponse>(s, "/api/v1/request/count") }.getOrNull()
        SeerrOverview(about, counts)
    }
    val version = loader.value?.about?.version
    ServiceFrame(s, nav, subtitle = listOfNotNull(s.serviceType?.label, version?.let { "v$it" }).joinToString(" · ")) { p ->
        HubRefreshBox(hub, Modifier.padding(p)) {
            CardList {
                item("counts") {
                    SectionCard("Requests", icon = Icons.Rounded.Inbox, onClick = { nav.navigate(RequestsRoute) }) {
                        CardLoad(loader, placeholder = { SkeletonRows(1, rowHeight = 68.dp) }) { o ->
                            val c = o.counts
                            if (c == null) CardEmpty("Request counts unavailable")
                            else {
                                StatRow {
                                    StatTile("Pending", c.pending.toString(), Modifier.weight(1f), tint = if (c.pending > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                    StatTile("Approved", c.approved.toString(), Modifier.weight(1f))
                                }
                                StatRow {
                                    StatTile("Processing", c.processing.toString(), Modifier.weight(1f))
                                    StatTile("Available", c.available.toString(), Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
                item("go") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { nav.navigate(RequestsRoute) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.Inbox, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Requests", maxLines = 1)
                        }
                        OutlinedButton(onClick = { nav.navigateTab(DiscoverRoute) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.Explore, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Discover", maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
