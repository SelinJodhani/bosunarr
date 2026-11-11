package app.bosunarr.feature.activity

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.bosunarr.feature.activity.qbit.Torrent
import app.bosunarr.feature.activity.qbit.categoryLabel
import app.bosunarr.ui.common.plural

/** A checkbox with its label, the whole row tappable. */
@Composable
fun CheckRow(checked: Boolean, onChange: (Boolean) -> Unit, label: String, hint: String? = null, modifier: Modifier = Modifier) {
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

@Composable
fun DeleteTorrentsDialog(torrents: List<Torrent>, onConfirm: (deleteFiles: Boolean) -> Unit, onDismiss: () -> Unit) {
    var deleteFiles by rememberSaveable { mutableStateOf(false) }
    val one = torrents.singleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (one != null) "Delete torrent?" else "Delete ${plural(torrents.size, "torrent")}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    one?.name ?: torrents.take(3).joinToString("\n") { "• ${it.name}" } + if (torrents.size > 3) "\n…and ${torrents.size - 3} more" else "",
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
                CheckRow(deleteFiles, { deleteFiles = it }, "Also delete downloaded files", if (deleteFiles) "Files are removed from disk. This can't be undone." else null)
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(deleteFiles); onDismiss() },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
            ) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun CategoryDialog(categories: List<String>, current: String?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val options = remember(categories) { listOf("") + categories }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set category") },
        text = {
            if (categories.isEmpty()) {
                Text("This qBittorrent has no categories yet. Create them in qBittorrent first.")
            } else {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(options, key = { it }) { c ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .selectable(c == current, role = Role.RadioButton) { onPick(c); onDismiss() },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = c == current, onClick = null, modifier = Modifier.padding(end = 12.dp))
                            Text(if (c.isEmpty()) "None" else categoryLabel(c), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(if (categories.isEmpty()) "OK" else "Cancel") } },
    )
}
