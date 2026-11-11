package app.bosunarr.feature.dashboard

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import app.bosunarr.nav.SettingsRoute
import app.bosunarr.ui.common.AppScaffold
import app.bosunarr.ui.common.MessageBox

@Composable
fun DashboardScreen(nav: NavController) {
    AppScaffold("Bosunarr", actions = { IconButton(onClick = { nav.navigate(SettingsRoute) }) { Icon(Icons.Rounded.Settings, "Settings") } }) { p ->
        MessageBox(Icons.Rounded.Settings, "Dashboard", Modifier.padding(p), hint = "Coming soon", action = "Services", onAction = { nav.navigate(SettingsRoute) })
    }
}
