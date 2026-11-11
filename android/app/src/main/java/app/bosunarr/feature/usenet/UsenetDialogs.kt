package app.bosunarr.feature.usenet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.bosunarr.data.ServiceConfig
import app.bosunarr.data.ServiceType
import app.bosunarr.ui.common.plural
import app.bosunarr.ui.common.speed
import java.util.Locale

@Composable
internal fun UsenetCheckRow(checked: Boolean, onChange: (Boolean) -> Unit, label: String, hint: String? = null, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, onCheckedChange = null, modifier = Modifier.padding(end = 12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun names(list: List<String>) =
    list.take(3).joinToString("\n") { "• $it" } + if (list.size > 3) "\n…and ${list.size - 3} more" else ""

/** Confirm deleting queue jobs. The extra option depends on the client(s) involved. */
@Composable
fun DeleteJobsDialog(items: List<NzbItem>, onConfirm: (purge: Boolean) -> Unit, onDismiss: () -> Unit) {
    var purge by rememberSaveable { mutableStateOf(false) }
    val types = items.map { it.type }.toSet()
    val one = items.singleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (one != null) "Delete download?" else "Delete ${plural(items.size, "download")}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(one?.name ?: names(items.map { it.name }), maxLines = 6, overflow = TextOverflow.Ellipsis)
                when {
                    types == setOf(ServiceType.Sabnzbd) ->
                        UsenetCheckRow(purge, { purge = it }, "Also delete downloaded files", if (purge) "Files are removed from disk. This can't be undone." else null)
                    types == setOf(ServiceType.Nzbget) ->
                        UsenetCheckRow(purge, { purge = it }, "Don't keep it in history", "Otherwise it's listed in history as deleted.")
                    else ->
                        UsenetCheckRow(purge, { purge = it }, "Delete files / skip history", "SABnzbd deletes the files; NZBGet keeps no history entry.")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(purge); onDismiss() },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
            ) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Confirm deleting history entries. */
@Composable
fun DeleteHistoryDialog(items: List<NzbHistoryItem>, onConfirm: (purge: Boolean) -> Unit, onDismiss: () -> Unit) {
    var purge by rememberSaveable { mutableStateOf(false) }
    val one = items.singleOrNull()
    val type = items.map { it.type }.distinct().singleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (one != null) "Remove from history?" else "Remove ${plural(items.size, "entry", "entries")}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(one?.name ?: names(items.map { it.name }), maxLines = 6, overflow = TextOverflow.Ellipsis)
                when (type) {
                    ServiceType.Sabnzbd -> if (items.any { it.outcome == NzbOutcome.Failed }) {
                        UsenetCheckRow(purge, { purge = it }, "Also delete files of failed downloads", "Completed downloads are never touched.")
                    }
                    ServiceType.Nzbget -> UsenetCheckRow(
                        purge, { purge = it }, "Delete for good",
                        "Otherwise NZBGet only hides the entry and keeps it for duplicate checks.",
                    )
                    else -> {}
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(purge); onDismiss() },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
            ) { Text("Remove") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun PriorityDialog(type: ServiceType, current: NzbPriority?, onPick: (NzbPriority) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set priority") },
        text = {
            Column {
                priorities(type).forEach { p ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(p == current, role = Role.RadioButton) { onPick(p); onDismiss() },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = p == current, onClick = null, modifier = Modifier.padding(end = 12.dp))
                        Text(p.label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Pick a category from the client (loaded when the dialog opens). "None" = the client's default. */
@Composable
fun UsenetCategoryDialog(service: ServiceConfig, current: String?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var categories by remember { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(service.id) {
        runCatching { UsenetApi.categories(service) }
            .onSuccess { categories = it }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; error = it.message; categories = emptyList() }
    }
    val options = remember(categories, current) { (listOf("") + categories.orEmpty() + listOfNotNull(current?.takeIf { it.isNotEmpty() })).distinct() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set category") },
        text = {
            when {
                categories == null -> Text("Loading categories…")
                categories!!.isEmpty() && error == null -> Text("${service.name} has no categories yet. Create them in its settings first.")
                else -> Column {
                    if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(options, key = { it }) { c ->
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(c == current.orEmpty(), role = Role.RadioButton) { onPick(c); onDismiss() },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = c == current.orEmpty(), onClick = null, modifier = Modifier.padding(end = 12.dp))
                                Text(if (c.isEmpty()) "None (default)" else c, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Set or remove the client's download speed limit. [current] in bytes/s, 0 = none. */
@Composable
fun SpeedLimitDialog(current: Long, onSet: (bytesPerSec: Long) -> Unit, onDismiss: () -> Unit) {
    val startMb = current >= 1024 * 1024
    var mb by rememberSaveable { mutableStateOf(startMb) }
    var text by rememberSaveable {
        mutableStateOf(
            when {
                current <= 0 -> ""
                startMb -> String.format(Locale.US, "%.1f", current / (1024.0 * 1024)).removeSuffix(".0")
                else -> (current / 1024).toString()
            },
        )
    }
    val value = text.replace(',', '.').toDoubleOrNull()
    val bytes = value?.let { (it * if (mb) 1024 * 1024 else 1024).toLong() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download speed limit") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (current > 0) "Now limited to ${speed(current)}." else "No limit is set.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    text, { text = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(8) },
                    label = { Text("Limit") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = text.isNotEmpty() && (bytes == null || bytes <= 0),
                    modifier = Modifier.fillMaxWidth(),
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(selected = !mb, onClick = { mb = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("KB/s") }
                    SegmentedButton(selected = mb, onClick = { mb = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("MB/s") }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSet(bytes ?: 0); onDismiss() }, enabled = bytes != null && bytes > 0) { Text("Set limit") }
        },
        dismissButton = {
            Row {
                if (current > 0) TextButton(onClick = { onSet(0); onDismiss() }) { Text("Remove limit") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
