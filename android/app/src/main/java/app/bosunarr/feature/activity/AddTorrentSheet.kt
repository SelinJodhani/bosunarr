package app.bosunarr.feature.activity

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import app.bosunarr.data.ServiceConfig
import app.bosunarr.feature.activity.qbit.Qbit
import app.bosunarr.feature.activity.qbit.categoryLabel
import app.bosunarr.net.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_TORRENT_FILE = 10L * 1024 * 1024

/**
 * Add torrents: magnet links / URLs (one per line) and/or .torrent files, to one qBittorrent, with an optional
 * category, save path and "start paused". [initial] pre-fills the links (a magnet shared into the app).
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AddTorrentSheet(
    clients: List<ServiceConfig>,
    initial: String?,
    onDismiss: () -> Unit,
    onAdded: (message: String) -> Unit,
    preferredClient: String? = null,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val clipboard = LocalClipboard.current

    var clientId by rememberSaveable { mutableStateOf(preferredClient?.takeIf { id -> clients.any { it.id == id } } ?: clients.firstOrNull()?.id) }
    var links by rememberSaveable { mutableStateOf(initial.orEmpty()) }
    var category by rememberSaveable { mutableStateOf("") }
    var savePath by rememberSaveable { mutableStateOf("") }
    var paused by rememberSaveable { mutableStateOf(false) }
    val files = remember { mutableStateListOf<Qbit.TorrentFileUpload>() }
    var categories by remember { mutableStateOf<List<String>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val client = clients.firstOrNull { it.id == clientId } ?: clients.firstOrNull()

    // A link shared while the sheet is already open is appended (and not re-added after rotation).
    LaunchedEffect(initial) {
        if (!initial.isNullOrBlank() && !links.contains(initial)) links = if (links.isBlank()) initial else links.trimEnd() + "\n" + initial
    }
    LaunchedEffect(client?.id) {
        categories = emptyList()
        val s = client ?: return@LaunchedEffect
        categories = runCatching { Qbit.categories(s) }.getOrDefault(emptyList())
        if (category.isNotEmpty() && category !in categories) category = ""
    }
    LaunchedEffect(links, clientId) { error = null }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val read = withContext(Dispatchers.IO) {
                uris.map { uri ->
                    runCatching {
                        val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                            if (c.moveToFirst()) c.getString(0) else null
                        } ?: uri.lastPathSegment ?: "file.torrent"
                        val bytes = ctx.contentResolver.openInputStream(uri)?.use { input ->
                            val out = java.io.ByteArrayOutputStream()
                            val buf = ByteArray(16 * 1024)
                            var total = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                total += n
                                if (total > MAX_TORRENT_FILE) throw IllegalStateException("$name is too big for a .torrent file")
                                out.write(buf, 0, n)
                            }
                            out.toByteArray()
                        } ?: throw IllegalStateException("Couldn't read $name")
                        // A .torrent is a bencoded dictionary: it always starts with 'd'.
                        if (bytes.isEmpty() || bytes[0] != 'd'.code.toByte()) throw IllegalStateException("$name isn't a .torrent file")
                        Qbit.TorrentFileUpload(name, bytes)
                    }
                }
            }
            read.forEach { r -> r.onSuccess { f -> if (files.none { it.name == f.name }) files += f } }
            read.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { error = it.message }
        }
    }

    fun submit() {
        val s = client ?: return
        val parsed = try { Qbit.parseLinks(links) } catch (e: Exception) { error = e.userMessage(); return }
        if (parsed.isEmpty() && files.isEmpty()) { error = "Paste a magnet link or pick a .torrent file"; return }
        if (parsed.size > 50) { error = "Add at most 50 at a time"; return }
        busy = true
        scope.launch {
            try {
                Qbit.add(s, parsed, files.toList(), category.ifEmpty { null }, savePath.ifBlank { null }, paused)
                val n = parsed.size + files.size
                onAdded(if (n > 1) "Added $n torrents" else "Torrent added")
                sheet.hide()
                onDismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.userMessage()
            } finally {
                busy = false
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, contentWindowInsets = { WindowInsets(0) }) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Add torrent", style = MaterialTheme.typography.titleLarge)

            if (clients.size > 1) {
                if (clients.size <= 3) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        clients.forEachIndexed { i, c ->
                            SegmentedButton(
                                selected = c.id == client?.id,
                                onClick = { clientId = c.id },
                                shape = SegmentedButtonDefaults.itemShape(i, clients.size),
                                label = { Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            )
                        }
                    }
                } else {
                    Dropdown("Download client", client?.name.orEmpty(), clients.map { it.id to it.name }) { clientId = it }
                }
            }

            OutlinedTextField(
                value = links,
                onValueChange = { links = it },
                label = { Text("Magnet links or URLs") },
                placeholder = { Text("magnet:?xt=urn:btih:…") },
                supportingText = { Text("One per line") },
                minLines = 3,
                maxLines = 6,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Default),
                trailingIcon = {
                    IconButton(onClick = {
                        scope.launch {
                            val text = clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()?.trim()
                            if (text.isNullOrEmpty()) error = "The clipboard is empty"
                            else links = if (links.isBlank()) text else links.trimEnd() + "\n" + text
                        }
                    }) { Icon(Icons.Rounded.ContentPaste, "Paste") }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { picker.launch(arrayOf("application/x-bittorrent", "application/octet-stream", "*/*")) }) {
                    Icon(Icons.Rounded.AttachFile, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(".torrent file")
                }
            }
            if (files.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    files.forEach { f ->
                        InputChip(
                            selected = false,
                            onClick = { files.remove(f) },
                            label = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = { Icon(Icons.Rounded.Description, null, Modifier.size(18.dp)) },
                            trailingIcon = { Icon(Icons.Rounded.Close, "Remove ${f.name}", Modifier.size(18.dp)) },
                        )
                    }
                }
            }

            Dropdown(
                "Category",
                if (category.isEmpty()) "None" else categoryLabel(category),
                listOf("" to "None") + categories.map { it to categoryLabel(it) },
                enabled = categories.isNotEmpty(),
            ) { category = it }

            OutlinedTextField(
                value = savePath,
                onValueChange = { savePath = it },
                label = { Text("Save path (optional)") },
                placeholder = { Text("Default for the category") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Start paused", style = MaterialTheme.typography.bodyLarge)
                    Text("Add without downloading yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(paused, onCheckedChange = { paused = it })
            }

            if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)

            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = { scope.launch { sheet.hide(); onDismiss() } }) { Text("Cancel") }
                Button(onClick = ::submit, enabled = !busy && client != null && (links.isNotBlank() || files.isNotEmpty())) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.size(8.dp))
                    }
                    Text("Add")
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Dropdown(label: String, value: String, options: List<Pair<String, String>>, enabled: Boolean = true, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open && enabled, onExpandedChange = { if (enabled) open = it }) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled),
        )
        ExposedDropdownMenu(expanded = open && enabled, onDismissRequest = { open = false }) {
            options.forEach { (v, text) ->
                DropdownMenuItem(text = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = { onPick(v); open = false })
            }
        }
    }
}
