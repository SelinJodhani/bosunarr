package app.bosunarr.feature.services

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import app.bosunarr.app
import app.bosunarr.ui.common.AppScaffold
import app.bosunarr.ui.common.MessageBox
import app.bosunarr.ui.common.back

/** A service's own screen; dispatches on its type. */
@Composable
fun ServiceScreen(id: String, nav: NavController) {
    val s = app.service(id)
    AppScaffold(s?.name ?: "Service", onBack = { nav.back() }) { p -> MessageBox(Icons.Rounded.Dns, s?.name ?: "Service not found", Modifier.padding(p), hint = "Coming soon") }
}
