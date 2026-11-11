package app.bosunarr.feature.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.bosunarr.app
import app.bosunarr.data.ServiceConfig
import app.bosunarr.data.SshCommand
import app.bosunarr.feature.services.CardList
import app.bosunarr.feature.services.SectionCard
import app.bosunarr.net.userMessage
import app.bosunarr.ui.common.ConfirmDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

private sealed interface RunState {
    data class Running(val cmd: SshCommand) : RunState
    data class Done(val cmd: SshCommand, val result: ExecResult) : RunState
    data class Failed(val cmd: SshCommand, val message: String) : RunState
}

/** Saved commands: run one (after confirming the exact command), see its output. Stored encrypted with the service. */
@Composable
internal fun CommandsTab(s: ServiceConfig, session: ServerSession) {
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<SshCommand?>(null) }
    var confirmRun by remember { mutableStateOf<SshCommand?>(null) }
    var confirmDelete by remember { mutableStateOf<SshCommand?>(null) }
    var run by remember { mutableStateOf<RunState?>(null) }

    fun save(list: List<SshCommand>) = app.scope.launch {
        val current = app.service(s.id) ?: s
        app.saveService(current.copy(sshCommands = list))
    }

    CardList {
        item("intro") {
            SectionCard("Commands", icon = Icons.Rounded.Terminal, trailing = {
                IconButton(onClick = { editing = SshCommand("", "", "") }) { Icon(Icons.Rounded.Add, "Add command") }
            }) {
                if (s.sshCommands.isEmpty()) {
                    Text(
                        "Save commands you run often — e.g. \"cd ~/media && docker compose pull && docker compose up -d\". " +
                            "Each one asks for confirmation before it runs, as ${s.username ?: "your user"}, in your login shell.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
                s.sshCommands.forEach { c ->
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(c.command, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { editing = c }) { Icon(Icons.Rounded.Edit, "Edit ${c.name}") }
                        IconButton(onClick = { confirmDelete = c }) { Icon(Icons.Rounded.Delete, "Delete ${c.name}") }
                        FilledTonalButton(onClick = { confirmRun = c }, enabled = run !is RunState.Running) {
                            Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Run")
                        }
                    }
                }
            }
        }
    }

    editing?.let { c ->
        EditCommandDialog(
            c,
            onDismiss = { editing = null },
            onSave = { updated ->
                editing = null
                val list = if (c.id.isEmpty()) s.sshCommands + updated.copy(id = UUID.randomUUID().toString())
                else s.sshCommands.map { if (it.id == c.id) updated.copy(id = c.id) else it }
                save(list)
            },
        )
    }
    confirmDelete?.let { c ->
        ConfirmDialog(
            title = "Delete \"${c.name}\"?",
            text = null,
            confirm = "Delete",
            destructive = true,
            onConfirm = { confirmDelete = null; save(s.sshCommands.filterNot { it.id == c.id }) },
            onDismiss = { confirmDelete = null },
        )
    }
    confirmRun?.let { c ->
        AlertDialog(
            onDismissRequest = { confirmRun = null },
            icon = { Icon(Icons.Rounded.Terminal, null) },
            title = { Text("Run \"${c.name}\"?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("On ${SshTarget.parse(s.url)?.display ?: s.name} as ${s.username}:")
                    CodeBox(c.command)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRun = null
                    run = RunState.Running(c)
                    scope.launch {
                        run = try {
                            RunState.Done(c, session.runCommand(s, c.command))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            RunState.Failed(c, e.userMessage())
                        }
                    }
                }) { Text("Run") }
            },
            dismissButton = { TextButton(onClick = { confirmRun = null }) { Text("Cancel") } },
        )
    }
    run?.let { r -> OutputDialog(r, onDismiss = { if (r !is RunState.Running) run = null }) }
}

@Composable
private fun EditCommandDialog(c: SshCommand, onDismiss: () -> Unit, onSave: (SshCommand) -> Unit) {
    var name by remember { mutableStateOf(c.name) }
    var command by remember { mutableStateOf(c.command) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (c.id.isEmpty()) "New command" else "Edit command") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    command, { command = it },
                    label = { Text("Command") },
                    minLines = 2,
                    maxLines = 6,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Runs non-interactively (no password prompts — sudo only works if passwordless). Don't put secrets here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(enabled = command.isNotBlank(), onClick = { onSave(SshCommand(c.id, name.trim().ifEmpty { command.trim().take(32) }, command.trim())) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun OutputDialog(r: RunState, onDismiss: () -> Unit) {
    val cmd = when (r) {
        is RunState.Running -> r.cmd
        is RunState.Done -> r.cmd
        is RunState.Failed -> r.cmd
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(cmd.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (r) {
                    is RunState.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Running…")
                    }
                    is RunState.Failed -> Text(r.message, color = MaterialTheme.colorScheme.error)
                    is RunState.Done -> {
                        val res = r.result
                        Text(
                            when {
                                res.truncated -> "Output too long — showing the start"
                                res.ok -> "Finished (exit 0)"
                                else -> "Failed (exit ${res.exit})"
                            },
                            color = if (res.ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        if (res.out.isNotBlank()) CodeBox(res.out.trimEnd())
                        if (res.err.isNotBlank()) {
                            Text("stderr", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            CodeBox(res.err.trimEnd())
                        }
                        if (res.out.isBlank() && res.err.isBlank()) Text("No output", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { if (r !is RunState.Running) TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
