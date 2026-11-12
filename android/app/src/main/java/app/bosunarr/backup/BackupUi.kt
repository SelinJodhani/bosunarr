package app.bosunarr.backup

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.ui.graphics.vector.ImageVector
import app.bosunarr.security.SettingsRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.bosunarr.BuildConfig
import app.bosunarr.app
import app.bosunarr.data.AppState
import app.bosunarr.notify.NotifyPrefs
import app.bosunarr.notify.NotifyScheduler
import app.bosunarr.security.AppLock
import app.bosunarr.ui.common.LocalSnackbar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Largest file Import will read (a backup with hundreds of services is still well under 1 MB). */
private const val MAX_FILE = 8L * 1024 * 1024

/**
 * Backup & restore flow state. Lives in a ViewModel so a rotation mid-flow doesn't lose it. Only ciphertext and
 * the decrypted payload (in memory) are held — never the passphrase, and nothing is written in plaintext.
 */
class BackupViewModel : ViewModel() {
    sealed interface Step {
        data object Idle : Step
        data object ExportPassphrase : Step
        data object Encrypting : Step
        /** Encrypted and waiting for the user to pick where to save it. */
        class AwaitingLocation(val bytes: ByteArray, val fileName: String) : Step {
            /** The picker was opened (don't open it again after a rotation). */
            var launched = false
        }
        class ImportPassphrase(val file: ByteArray, val error: String? = null) : Step
        data object Decrypting : Step
        class Preview(val payload: BackupPayload) : Step
        class Confirm(val payload: BackupPayload, val mode: RestoreMode, val parts: Set<BackupPayload.Part>) : Step
        data object Working : Step
        data class Failed(val title: String, val message: String) : Step
    }

    var step by mutableStateOf<Step>(Step.Idle)
        private set

    fun cancel() { step = Step.Idle }
    fun startExport() { step = Step.ExportPassphrase }

    fun encrypt(context: Context, passphrase: String) {
        step = Step.Encrypting
        val st = app.state.value
        val notify = NotifyPrefs.get(context).settings.value
        viewModelScope.launch {
            step = try {
                val bytes = withContext(Dispatchers.Default) {
                    val plain = BackupPayload.from(st ?: AppState(), notify, BuildConfig.VERSION_NAME, System.currentTimeMillis()).encode()
                    try { BackupCrypto.encrypt(plain, passphrase) } finally { plain.fill(0) }
                }
                Step.AwaitingLocation(bytes, "bosunarr-backup-${LocalDate.now()}.bosunarr")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // OutOfMemoryError included: Argon2 needs 64 MiB.
                Step.Failed("Couldn't create the backup", e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun save(context: Context, uri: Uri?, done: (String) -> Unit) {
        val s = step as? Step.AwaitingLocation
        if (s == null) {
            // The app was killed while the picker was open: the encrypted bytes are gone. Remove the empty file.
            if (uri != null) {
                runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }
                step = Step.Failed("Backup not saved", "Bosunarr was closed while you chose a location. Export again.")
            }
            return
        }
        if (uri == null) { step = Step.Idle; return }
        step = Step.Working
        viewModelScope.launch {
            step = try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(s.bytes) } ?: error("Couldn't open the file")
                }
                done("Backup saved")
                Step.Idle
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Step.Failed("Couldn't save the backup", e.message ?: "Write failed")
            }
        }
    }

    fun open(context: Context, uri: Uri?) {
        if (uri == null) return
        step = Step.Working
        viewModelScope.launch {
            step = try {
                val bytes = withContext(Dispatchers.IO) {
                    val size = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                    if (size > MAX_FILE) throw BackupException.NotABackup()
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            if (out.size() > MAX_FILE) throw BackupException.NotABackup()
                        }
                        out.toByteArray()
                    } ?: error("Couldn't open the file")
                }
                BackupCrypto.checkHeader(bytes)
                Step.ImportPassphrase(bytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackupException) {
                Step.Failed("Can't restore this file", e.message.orEmpty())
            } catch (e: Exception) {
                Step.Failed("Couldn't read the file", e.message ?: "Read failed")
            }
        }
    }

    fun decrypt(passphrase: String) {
        val s = step as? Step.ImportPassphrase ?: return
        step = Step.Decrypting
        viewModelScope.launch {
            step = try {
                val payload = withContext(Dispatchers.Default) {
                    val plain = BackupCrypto.decrypt(s.file, passphrase)
                    try { BackupPayload.decode(plain) } finally { plain.fill(0) }
                }
                Step.Preview(payload)
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackupException.WrongPassphrase) {
                Step.ImportPassphrase(s.file, e.message)
            } catch (e: BackupException) {
                Step.Failed("Can't restore this file", e.message.orEmpty())
            } catch (e: Throwable) {
                Step.Failed("Couldn't open the backup", e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun confirm(payload: BackupPayload, mode: RestoreMode, parts: Set<BackupPayload.Part>) {
        step = Step.Confirm(payload, mode, parts)
    }

    fun restore(context: Context, done: (String) -> Unit) {
        val s = step as? Step.Confirm ?: return
        step = Step.Working
        viewModelScope.launch {
            step = try {
                val before = app.state.value?.let { (it.services + it.realServices.orEmpty()).map { c -> c.id } }.orEmpty()
                app.updateStateNow { restoreState(it, s.payload, s.mode, s.parts) }
                // Forget cached sessions/addresses for every service whose settings may have changed.
                (before + s.payload.services.map { it.id }).distinct().forEach { app.client.reset(it) }
                if (BackupPayload.Part.Notifications in s.parts) s.payload.notifications?.let { n ->
                    NotifyPrefs.get(context).update { n.toSettings() }
                    NotifyScheduler.sync(context)
                }
                val count = s.payload.services.size
                done(if (BackupPayload.Part.Services in s.parts) "Restored ${if (count == 1) "1 service" else "$count services"}" else "Settings restored")
                Step.Idle
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Step.Failed("Restore failed", e.message ?: "Couldn't save")
            }
        }
    }

    fun fail(title: String, message: String) { step = Step.Failed(title, message) }
}

/** The two Settings actions plus every dialog of the flow. Call [BackupFlow.Dialogs] once on the screen. */
class BackupFlow internal constructor(val vm: BackupViewModel, private val pickFile: () -> Unit) {
    fun export() = vm.startExport()
    fun import() = pickFile()
}

@Composable
fun rememberBackupFlow(): BackupFlow {
    val vm: BackupViewModel = viewModel()
    val ctx = LocalContext.current
    val snackbar = LocalSnackbar.current
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> vm.open(ctx.applicationContext, uri) }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        vm.save(ctx.applicationContext, uri) { msg -> app.scope.launch(Dispatchers.Main) { snackbar.showSnackbar(msg) } }
    }
    val step = vm.step
    // Encrypted: ask where to save it (the system picker; returning from it shouldn't trip the app lock).
    LaunchedEffect(step) {
        if (step is BackupViewModel.Step.AwaitingLocation && !step.launched) {
            step.launched = true
            AppLock.expectReturn()
            runCatching { create.launch(step.fileName) }.onFailure { vm.fail("Couldn't save the backup", "No app on this phone can save files") }
        }
    }
    return remember(vm) {
        BackupFlow(vm) {
            AppLock.expectReturn()
            // Backups have no registered MIME type, so let the user pick any file; the header check rejects others.
            runCatching { open.launch(arrayOf("*/*")) }.onFailure { vm.fail("Couldn't open a file", "No file picker on this phone") }
        }
    }
}

@Composable
fun BackupDialogs(flow: BackupFlow) {
    val vm = flow.vm
    val ctx = LocalContext.current
    val snackbar = LocalSnackbar.current
    val notify: (String) -> Unit = { msg -> app.scope.launch(Dispatchers.Main) { snackbar.showSnackbar(msg) } }
    when (val s = vm.step) {
        BackupViewModel.Step.Idle, is BackupViewModel.Step.AwaitingLocation -> Unit
        BackupViewModel.Step.ExportPassphrase -> ExportPassphraseDialog(onCancel = vm::cancel, onConfirm = { vm.encrypt(ctx.applicationContext, it) })
        BackupViewModel.Step.Encrypting -> BusyDialog("Encrypting backup…")
        BackupViewModel.Step.Decrypting -> BusyDialog("Unlocking backup…")
        BackupViewModel.Step.Working -> BusyDialog("Working…")
        is BackupViewModel.Step.ImportPassphrase -> ImportPassphraseDialog(s.error, onCancel = vm::cancel, onConfirm = vm::decrypt)
        is BackupViewModel.Step.Preview -> PreviewDialog(s.payload, onCancel = vm::cancel, onRestore = { mode, parts -> vm.confirm(s.payload, mode, parts) })
        is BackupViewModel.Step.Confirm -> ConfirmRestoreDialog(s, onCancel = vm::cancel, onConfirm = { vm.restore(ctx.applicationContext, notify) })
        is BackupViewModel.Step.Failed -> AlertDialog(
            onDismissRequest = vm::cancel,
            title = { Text(s.title) },
            text = { Text(s.message) },
            confirmButton = { TextButton(onClick = vm::cancel) { Text("OK") } },
        )
    }
}

@Composable
private fun BusyDialog(text: String) {
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                Spacer(Modifier.width(16.dp))
                Text(text)
            }
        },
    )
}

@Composable
private fun PassphraseField(value: String, onValue: (String) -> Unit, label: String, isError: Boolean = false, supporting: (@Composable () -> Unit)? = null) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value, onValue,
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        supportingText = supporting,
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { show = !show }) { Icon(if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (show) "Hide" else "Show") }
        },
        // No autofill/suggestions: this passphrase protects a file that may end up anywhere.
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ExportPassphraseDialog(onCancel: () -> Unit, onConfirm: (String) -> Unit) {
    // Plain remember, not rememberSaveable: the passphrase must never go into the saved-state Bundle.
    var pass by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    val strength = PassphraseStrength.of(pass)
    val matches = pass == again
    val ok = pass.length >= PassphraseStrength.MIN_LENGTH && matches
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Export backup") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Everything is encrypted with this passphrase, including your API keys and passwords. " +
                        "There's no way to recover it — without it the backup can't be restored.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                PassphraseField(pass, { pass = it }, "Passphrase", supporting = { Text(if (pass.isEmpty()) "At least ${PassphraseStrength.MIN_LENGTH} characters; a few random words work well" else strength.label) })
                if (pass.isNotEmpty()) StrengthMeter(strength)
                PassphraseField(
                    again, { again = it }, "Confirm passphrase",
                    isError = again.isNotEmpty() && !matches,
                    supporting = if (again.isNotEmpty() && !matches) ({ Text("Doesn't match") }) else null,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(pass) }, enabled = ok) { Text("Encrypt & save") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun StrengthMeter(s: Strength) {
    val (fraction, color) = when (s) {
        Strength.TooShort -> 0.1f to MaterialTheme.colorScheme.error
        Strength.Weak -> 0.25f to MaterialTheme.colorScheme.error
        Strength.Fair -> 0.5f to Color(0xFFE0A100)
        Strength.Strong -> 0.75f to MaterialTheme.colorScheme.primary
        Strength.VeryStrong -> 1f to MaterialTheme.colorScheme.primary
    }
    LinearProgressIndicator(progress = { fraction }, color = color, modifier = Modifier.fillMaxWidth().height(4.dp))
}

@Composable
private fun ImportPassphraseDialog(error: String?, onCancel: () -> Unit, onConfirm: (String) -> Unit) {
    var pass by remember(error) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Restore backup") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Enter the passphrase this backup was made with.", style = MaterialTheme.typography.bodyMedium)
                PassphraseField(pass, { pass = it }, "Passphrase", isError = error != null, supporting = error?.let { { Text(it) } })
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(pass) }, enabled = pass.isNotEmpty()) { Text("Unlock") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun PreviewDialog(payload: BackupPayload, onCancel: () -> Unit, onRestore: (RestoreMode, Set<BackupPayload.Part>) -> Unit) {
    var parts by remember(payload) { mutableStateOf(payload.parts.toSet()) }
    var mode by remember(payload) { mutableStateOf(RestoreMode.Merge) }
    val current = app.state.value?.let { if (it.demo) it.realServices.orEmpty() else it.services }.orEmpty()
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Restore backup") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    buildString {
                        append("Contains ").append(payload.summary()).append('.')
                        if (payload.createdAt > 0) append("\nMade ").append(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(payload.createdAt)))
                        if (payload.appVersion.isNotBlank()) append(" with Bosunarr ").append(payload.appVersion)
                        if (payload.version > BackupPayload.VERSION) append("\n\nThis backup is from a newer version of Bosunarr; settings this version doesn't know are skipped.")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                payload.parts.forEach { p ->
                    val label = when (p) {
                        BackupPayload.Part.Services -> "Services (${payload.services.size})"
                        BackupPayload.Part.Dashboard -> "Dashboard layout"
                        BackupPayload.Part.Appearance -> "Appearance"
                        BackupPayload.Part.Notifications -> "Notification settings"
                    }
                    Row(
                        Modifier.fillMaxWidth().toggleable(p in parts, role = Role.Checkbox) { on -> parts = if (on) parts + p else parts - p },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = p in parts, onCheckedChange = null, modifier = Modifier.padding(8.dp))
                        Text(label)
                    }
                }
                if (BackupPayload.Part.Services in parts) {
                    Spacer(Modifier.height(8.dp))
                    Text("Services", style = MaterialTheme.typography.labelLarge)
                    ModeRow(mode == RestoreMode.Merge, "Merge", "Add new services, update ones that are already here (same service), keep the rest") { mode = RestoreMode.Merge }
                    ModeRow(mode == RestoreMode.Replace, "Replace all", "Remove the ${current.size} service(s) on this phone and use the backup's") { mode = RestoreMode.Replace }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onRestore(mode, parts) }, enabled = parts.isNotEmpty()) { Text("Restore…") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun ModeRow(selected: Boolean, title: String, text: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(8.dp))
        Column(Modifier.padding(top = 6.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ConfirmRestoreDialog(s: BackupViewModel.Step.Confirm, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val st = app.state.value
    val current = st?.let { if (it.demo) it.realServices.orEmpty() else it.services }.orEmpty()
    val services = BackupPayload.Part.Services in s.parts
    val replacing = services && s.mode == RestoreMode.Replace
    val updated = if (services && !replacing) s.payload.services.count { b -> current.any { it.id == b.id } } else 0
    val text = buildList {
        if (st?.demo == true) add("The demo ends and your own services come back first.")
        if (replacing) add("All ${current.size} service(s) on this phone are removed and replaced by the ${s.payload.services.size} in the backup.")
        else if (services) add("${s.payload.services.size - updated} service(s) will be added and $updated updated.")
        val other = s.parts - BackupPayload.Part.Services
        if (other.isNotEmpty()) add("Your current " + other.joinToString(", ") { it.name.lowercase() } + " settings are overwritten.")
        add("This can't be undone.")
    }.joinToString("\n\n")
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (replacing) "Replace everything?" else "Restore backup?") },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(if (replacing) "Replace all" else "Restore", color = if (replacing) MaterialTheme.colorScheme.error else Color.Unspecified) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/** Settings → Backup & restore: Export and Restore rows, plus the flow's dialogs. */
@Composable
fun BackupSettingsRows(row: SettingsRow, icon: @Composable (ImageVector) -> Unit, colors: ListItemColors) {
    val flow = rememberBackupFlow()
    Column {
        row(0, 2, { flow.export() }) {
            ListItem(
                headlineContent = { Text("Export backup") },
                supportingContent = { Text("Services, keys, dashboard and settings in one passphrase-encrypted file") },
                leadingContent = { icon(Icons.Rounded.Upload) },
                colors = colors,
            )
        }
        row(1, 2, { flow.import() }) {
            ListItem(
                headlineContent = { Text("Restore from backup") },
                supportingContent = { Text("Open a .bosunarr file") },
                leadingContent = { icon(Icons.Rounded.Download) },
                colors = colors,
            )
        }
    }
    BackupDialogs(flow)
}
