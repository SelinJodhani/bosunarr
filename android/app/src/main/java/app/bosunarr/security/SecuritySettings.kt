package app.bosunarr.security

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Row
import app.bosunarr.app
import app.bosunarr.ui.common.LocalSnackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** A settings row slot (the Settings screen's grouped-row surface). */
typealias SettingsRow = @Composable (index: Int, count: Int, onClick: (() -> Unit)?, content: @Composable () -> Unit) -> Unit

/**
 * Settings → Security: "Require unlock", "Lock after" and "Hide in recent apps". Turning the lock on or off asks
 * for the biometric/screen lock first, so it's known to work (and can't be switched off by someone else).
 */
@Composable
fun SecuritySettingsRows(row: SettingsRow, icon: @Composable (ImageVector) -> Unit, colors: ListItemColors) {
    val ctx = LocalContext.current
    val snackbar = LocalSnackbar.current
    val s by AppLock.settings.collectAsState()
    var choosingTimeout by rememberSaveable { mutableStateOf(false) }
    fun say(msg: String) { app.scope.launch(Dispatchers.Main) { snackbar.showSnackbar(msg) } }

    fun toggle() {
        val activity = ctx.findFragmentActivity() ?: return
        if (!s.enabled && !AppLock.deviceSecure(ctx)) {
            say("Set a screen lock (PIN, pattern or password) in Android settings first")
            return
        }
        val on = !s.enabled
        authenticate(activity, if (on) "Turn on app lock" else "Turn off app lock", "Confirm it's you") { e ->
            when {
                e == null -> AppLock.update(ctx) { it.copy(enabled = on) }
                e.isNotEmpty() -> say(e)
            }
        }
    }

    val count = if (s.enabled) 3 else 1
    Column {
        row(0, count, { toggle() }) {
            ListItem(
                headlineContent = { Text("Require unlock") },
                supportingContent = { Text("Fingerprint, face or screen lock to open Bosunarr") },
                leadingContent = { icon(Icons.Rounded.Fingerprint) },
                trailingContent = { Switch(checked = s.enabled, onCheckedChange = { toggle() }) },
                colors = colors,
            )
        }
        if (s.enabled) {
            row(1, count, { choosingTimeout = true }) {
                ListItem(
                    headlineContent = { Text("Lock") },
                    supportingContent = { Text(if (s.timeoutMs == 0L) "As soon as you leave the app" else "${AppLock.timeoutLabel(s.timeoutMs)} in the background") },
                    leadingContent = { icon(Icons.Rounded.Timer) },
                    colors = colors,
                )
            }
            row(2, count, { AppLock.update(ctx) { it.copy(hideInRecents = !it.hideInRecents) } }) {
                ListItem(
                    headlineContent = { Text("Hide in recent apps") },
                    supportingContent = {
                        Text(
                            if (android.os.Build.VERSION.SDK_INT >= 33) "Blank preview in the app switcher"
                            else "Blank preview in the app switcher; also blocks screenshots",
                        )
                    },
                    leadingContent = { icon(Icons.Rounded.VisibilityOff) },
                    trailingContent = { Switch(checked = s.hideInRecents, onCheckedChange = { on -> AppLock.update(ctx) { it.copy(hideInRecents = on) } }) },
                    colors = colors,
                )
            }
        }
    }

    if (choosingTimeout) {
        AlertDialog(
            onDismissRequest = { choosingTimeout = false },
            title = { Text("Lock") },
            text = {
                Column {
                    AppLock.TIMEOUTS.forEach { t ->
                        Row(
                            Modifier.fillMaxWidth().selectable(s.timeoutMs == t, role = Role.RadioButton) {
                                AppLock.update(ctx) { it.copy(timeoutMs = t) }
                                choosingTimeout = false
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = s.timeoutMs == t, onClick = null, modifier = Modifier.padding(12.dp))
                            Text(if (t == 0L) "Immediately" else "After ${t / 60_000} min in the background")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosingTimeout = false }) { Text("Close") } },
        )
    }
}
