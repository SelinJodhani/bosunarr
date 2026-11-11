package app.bosunarr.feature.activity

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import app.bosunarr.data.ServiceConfig
import app.bosunarr.ui.common.AppScaffold
import app.bosunarr.ui.common.MessageBox
import app.bosunarr.ui.common.back

/** qBittorrent's own screen (opened from ServiceScreen dispatch). */
@Composable
fun QbittorrentServiceScreen(service: ServiceConfig, nav: NavController) {
    AppScaffold(service.name, onBack = { nav.back() }) { p -> MessageBox(Icons.Rounded.Download, service.name, Modifier.padding(p), hint = "Coming soon") }
}
