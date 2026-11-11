package app.bosunarr.feature.server

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** The `ssh-keygen` line that prints the server's own fingerprint for [type], to compare against. */
internal fun fingerprintCommand(type: String): String {
    val file = when {
        type == "ssh-ed25519" -> "ed25519"
        type.startsWith("ecdsa") -> "ecdsa"
        type.contains("rsa") -> "rsa"
        else -> "ed25519"
    }
    return "ssh-keygen -lf /etc/ssh/ssh_host_${file}_key.pub"
}

@Composable
private fun Fingerprint(label: String?, pin: HostKeyPin) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            if (label != null) Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(pin.type, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionContainer {
                Text(pin.fingerprint, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

/**
 * Asks the user about a host key: trust on first use ([HostKeyUnknown]), or a loud warning when it changed
 * ([HostKeyMismatch]) with an explicit "Trust new key". [onTrust] gets the key to pin.
 */
@Composable
fun HostKeyDialog(problem: SshException, onTrust: (HostKeyPin) -> Unit, onDismiss: () -> Unit) {
    when (problem) {
        is HostKeyUnknown -> AlertDialog(
            onDismissRequest = onDismiss,
            icon = { Icon(Icons.Rounded.VerifiedUser, null) },
            title = { Text("Trust this server?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                    Text("First connection to ${problem.host}. Make sure this is your server: its fingerprint should match what this prints on it:")
                    Text(fingerprintCommand(problem.presented.type), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    Fingerprint(null, problem.presented)
                    Text("Bosunarr remembers this key and refuses to connect if it ever changes.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { onTrust(problem.presented) }) { Text("Trust") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
        is HostKeyMismatch -> AlertDialog(
            onDismissRequest = onDismiss,
            icon = { Icon(Icons.Rounded.GppMaybe, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Host key changed!", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                    Text(
                        "${problem.host} identified itself with a different key than the one you trusted. Someone may be " +
                            "intercepting the connection (a man-in-the-middle attack) — or the server was reinstalled or its SSH keys regenerated.",
                    )
                    Fingerprint("Trusted", problem.pinned)
                    Fingerprint("Presented now", problem.presented)
                    Text("Only trust the new key if you know why it changed. Check it on the server with:", style = MaterialTheme.typography.bodySmall)
                    Text(fingerprintCommand(problem.presented.type), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = {
                TextButton(onClick = { onTrust(problem.presented) }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text("Trust new key")
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Don't connect") } },
        )
        else -> LaunchedEffect(problem) { onDismiss() }
    }
}
