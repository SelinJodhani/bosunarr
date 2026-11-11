package app.bosunarr.feature.activity

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import app.bosunarr.ui.common.AppScaffold
import app.bosunarr.ui.common.MessageBox

@Composable
fun ActivityScreen(nav: NavController) {
    AppScaffold("Activity") { p -> MessageBox(Icons.Rounded.Download, "Activity", Modifier.padding(p), hint = "Coming soon") }
}
