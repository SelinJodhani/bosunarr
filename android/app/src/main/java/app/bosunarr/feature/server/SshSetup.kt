package app.bosunarr.feature.server

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.bosunarr.ui.common.ConfirmDialog
import app.bosunarr.ui.onboarding.ServiceForm
import app.bosunarr.ui.settings.ServiceBadge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Comment for generated keys, so they're recognisable in authorized_keys. */
private fun keyComment(): String = "bosunarr@" + Build.MODEL.lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-').ifEmpty { "android" }

/** Hint under a failed SSH test. */
fun sshHelp(msg: String): String? = when {
    msg.contains("timed out") || msg.contains("unreachable") || msg.contains("refused") || msg.contains("No route") || msg.contains("Can't find") ->
        "Check the address and port, and that this phone can reach the server — same Wi-Fi, or your VPN (e.g. Tailscale) is on."
    msg.contains("didn't accept the key") ->
        "Run the command shown under the public key on the server (as this user), then test again."
    msg.contains("Login failed") -> "Many servers only allow keys: switch to Key and add the generated key instead."
    msg.contains("host key", true) -> "Compare the fingerprint with the server's own before trusting it."
    else -> null
}

internal fun shareText(ctx: Context, text: String, title: String) {
    val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
    runCatching { ctx.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Key info derived from the form's private key (parsed off the main thread: encrypted keys run a slow KDF). */
private sealed interface KeyInfo {
    data object Loading : KeyInfo
    data class Ok(val key: SshKey) : KeyInfo
    data class Bad(val message: String) : KeyInfo
}

/**
 * The Server type's fields: host + port, user, sign-in method; for keys, generate or paste one and show the
 * public half with copy/share and the command that installs it; the pinned host key with a way to forget it.
 */
@Composable
internal fun SshFormFields(form: ServiceForm, onEdited: () -> Unit, onSubmit: () -> Unit) {
    val type = form.type
    val scope = rememberCoroutineScope()
    var show by remember { mutableStateOf(false) }
    var pasting by remember { mutableStateOf(false) }
    var generating by remember { mutableStateOf(false) }
    var confirmReplace by remember { mutableStateOf<String?>(null) }

    fun generate() {
        generating = true
        scope.launch {
            val key = withContext(Dispatchers.Default) { runCatching { Ssh.generateKey(keyComment()) } }
            generating = false
            key.onSuccess {
                form.sshKey = it.privateKey.trim()
                form.password = ""
                onEdited()
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            OutlinedTextField(
                form.sshHost,
                {
                    // A different machine has a different host key: don't carry the old pin over.
                    if (it.trim() != form.sshHost.trim()) form.sshHostKey = ""
                    form.sshHost = it; onEdited()
                },
                label = { Text("Host") },
                placeholder = { Text("192.168.1.10") },
                supportingText = { Text("IP or hostname of the machine") },
                singleLine = true,
                leadingIcon = { ServiceBadge(type, size = 28.dp) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                form.sshPort, { v -> form.sshPort = v.filter { it.isDigit() }.take(5); onEdited() },
                label = { Text("Port") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                modifier = Modifier.width(92.dp),
            )
        }
        OutlinedTextField(
            form.username, { form.username = it.trim(); onEdited() },
            label = { Text("Username") },
            supportingText = { Text("Ideally in the docker group, to manage containers") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Username },
        )

        Text("Sign in with", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val modes = listOf("key" to "SSH key", "password" to "Password")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            modes.forEachIndexed { i, (v, label) ->
                SegmentedButton(
                    selected = form.sshAuth == v,
                    onClick = {
                        if (form.sshAuth != v) {
                            form.sshAuth = v
                            form.password = "" // passphrase vs login password: never reuse one as the other
                            onEdited()
                        }
                    },
                    shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                ) { Text(label, maxLines = 1) }
            }
        }

        val eye = @Composable {
            IconButton(onClick = { show = !show }) { Icon(if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (show) "Hide" else "Show") }
        }
        if (form.sshAuth == "password") {
            OutlinedTextField(
                form.password, { form.password = it; onEdited() },
                label = { Text("Password") },
                supportingText = { Text("Keys are safer — and some servers only allow keys") },
                singleLine = true,
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = eye,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Password },
            )
        } else if (form.sshKey.isBlank()) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("No key yet", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Generate a key on this phone (it never leaves it, stored encrypted) and add its public half to the server — or paste a private key you already use.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { generate() }, enabled = !generating, modifier = Modifier.weight(1f)) {
                            if (generating) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                            else Icon(Icons.Rounded.Key, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Generate key", maxLines = 1)
                        }
                        OutlinedButton(onClick = { pasting = true }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.ContentPaste, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Paste key", maxLines = 1)
                        }
                    }
                }
            }
        } else {
            KeyCard(
                form,
                onReplace = { confirmReplace = it },
                passphraseField = {
                    OutlinedTextField(
                        form.password, { form.password = it; onEdited() },
                        label = { Text("Key passphrase") },
                        singleLine = true,
                        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = eye,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
            )
        }

        val pin = HostKeyPin.parse(form.sshHostKey)
        if (pin != null) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.VerifiedUser, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Trusted host key · ${pin.type}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(pin.fingerprint, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 2)
                    }
                    TextButton(onClick = { form.sshHostKey = ""; onEdited() }) { Text("Forget") }
                }
            }
        }
    }

    if (pasting) {
        PasteKeyDialog(
            onDismiss = { pasting = false },
            onKey = { key, passphrase ->
                pasting = false
                form.sshKey = key
                form.password = passphrase
                onEdited()
            },
        )
    }
    confirmReplace?.let { how ->
        ConfirmDialog(
            title = "Replace the key?",
            text = "The current key is deleted from this phone when you save. Remove its line from ~/.ssh/authorized_keys on the server too.",
            confirm = if (how == "generate") "Generate new" else "Paste another",
            destructive = true,
            onConfirm = {
                confirmReplace = null
                if (how == "generate") generate() else pasting = true
            },
            onDismiss = { confirmReplace = null },
        )
    }
}

@Composable
private fun KeyCard(form: ServiceForm, onReplace: (String) -> Unit, passphraseField: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<KeyInfo>(KeyInfo.Loading) }
    LaunchedEffect(form.sshKey, form.password) {
        info = KeyInfo.Loading
        info = withContext(Dispatchers.Default) {
            try {
                KeyInfo.Ok(Ssh.readKey(form.sshKey, form.password, keyComment()))
            } catch (e: SshException) {
                KeyInfo.Bad(e.message ?: "Unreadable key")
            }
        }
    }
    fun copy(label: String, text: String) = scope.launch {
        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, text)))
    }

    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Key, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    when (val i = info) {
                        is KeyInfo.Ok -> {
                            Text("Key · ${i.key.type}", style = MaterialTheme.typography.titleSmall)
                            Text(i.key.fingerprint, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                        is KeyInfo.Bad -> Text(i.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        KeyInfo.Loading -> Text("Reading key…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            val bad = info as? KeyInfo.Bad
            if (bad != null && (bad.message.contains("passphrase", true) || bad.message.contains("protected", true)) || form.password.isNotEmpty()) {
                passphraseField()
            }
            val ok = (info as? KeyInfo.Ok)?.key
            if (ok != null) {
                Text("Public key", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                CodeBox(ok.publicKey, maxLines = 4)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { copy("SSH public key", ok.publicKey) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Copy", maxLines = 1)
                    }
                    OutlinedButton(onClick = { shareText(ctx, ok.publicKey, "Share public key") }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Share", maxLines = 1)
                    }
                }
                val user = form.username.trim().ifEmpty { "your user" }
                Text(
                    "Add it to the server: run this there as $user (from an SSH session on your computer, for example):",
                    style = MaterialTheme.typography.bodyMedium,
                )
                val cmd = authorizedKeysCommand(ok.publicKey)
                CodeBox(cmd, maxLines = 6)
                TextButton(onClick = { copy("authorized_keys command", cmd) }) {
                    Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Copy command")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onReplace("generate") }) { Text("Generate new") }
                TextButton(onClick = { onReplace("paste") }) { Text("Paste another") }
            }
        }
    }
}

@Composable
internal fun CodeBox(text: String, maxLines: Int = Int.MAX_VALUE, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(10.dp), modifier = modifier.fillMaxWidth()) {
        SelectionContainer {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = maxLines,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(10.dp),
            )
        }
    }
}

@Composable
private fun PasteKeyDialog(onDismiss: () -> Unit, onKey: (String, String) -> Unit) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paste a private key") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "The whole file, e.g. ~/.ssh/id_ed25519 — from -----BEGIN to END-----. It's stored encrypted on this phone only.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    text, { text = it; error = null },
                    label = { Text("Private key") },
                    minLines = 5,
                    maxLines = 8,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    passphrase, { passphrase = it; error = null },
                    label = { Text("Passphrase (if any)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank() && !checking,
                onClick = {
                    checking = true
                    scope.launch {
                        val r = withContext(Dispatchers.Default) { runCatching { Ssh.readKey(text, passphrase, "") } }
                        checking = false
                        r.onSuccess { onKey(text.trim(), passphrase) }.onFailure { error = it.message ?: "Unreadable key" }
                    }
                },
            ) { Text(if (checking) "Checking…" else "Use key") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
