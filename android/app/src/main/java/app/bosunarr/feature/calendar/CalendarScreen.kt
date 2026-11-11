package app.bosunarr.feature.calendar

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import app.bosunarr.ui.common.AppScaffold
import app.bosunarr.ui.common.MessageBox

@Composable
fun CalendarScreen(nav: NavController) {
    AppScaffold("Calendar") { p -> MessageBox(Icons.Rounded.CalendarMonth, "Calendar", Modifier.padding(p), hint = "Coming soon") }
}
