package app.bosunarr.feature.usenet

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.foundation.text.KeyboardOptions
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
import app.bosunarr.data.ServiceConfig
import app.bosunarr.net.ServiceException
import app.bosunarr.net.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_NZB_FILE = 50L * 1024 * 1024
private val HTTP = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE)

/** Split pasted text into NZB links (one per line). Throws with a readable reason. */
internal fun parseNzbLinks(text: String): List<String> =
    text.split('\n', '\r').map { it.trim() }.filter { it.isNotEmpty() }.map { l ->
        if (HTTP.matches(l)) l else throw ServiceException("Not an http(s) link: ${l.take(60)}", 400)
    }

/**
 * Add NZBs to a Usenet client: links (one per line) and/or .nzb files, with an optional category, priority and
 * "start paused". [initial] pre-fills the links. With several [clients], the user picks one.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddNzbSheet(
    clients: List<ServiceConfig>,
    onDismiss: () -> Unit,
    onAdded: (message: String) -> Unit,
    initial: String? = null,
    preferredClient: String? = null,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val clipboard = LocalClipboard.current

    var clientId by rememberSaveable { mutableStateOf(preferredClient?.takeIf { id -> clients.any { it.id == id } } ?: clients.firstOrNull()?.id) }
    var links by rememberSaveable { mutableStateOf(initial.orEmpty()) }
    var category by rememberSaveable { mutableStateOf("") }
    var priorityName by rememberSaveable { mutableStateOf<String?>(null) }
    var paused by rememberSaveable { mutableStateOf(false) }
    val files = remember { mutableStateListOf<NzbUpload>() }
    var categories by remember { mutableStateOf<List<String>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val client = clients.firstOrNull { it.id == clientId } ?: clients.firstOrNull()
    val priority = priorityName?.let { n -> NzbPriority.entries.firstOrNull { it.name == n } }

    LaunchedEffect(client?.id) {
        categories = emptyList()
        val s = client ?: return@LaunchedEffect
        categories = runCatching { UsenetApi.categories(s) }.getOrDefault(emptyList())
        if (category.isNotEmpty() && category !in categories) category = ""
        val type = s.serviceType
        if (type != null && priority != null && priority !in priorities(type)) priorityName = null
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
                        } ?: uri.lastPathSegment ?: "file.nzb"
                        val bytes = ctx.contentResolver.openInputStream(uri)?.use { input ->
                            val out = java.io.ByteArrayOutputStream()
                            val buf = ByteArray(16 * 1024)
                            var total = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                total += n
                                if (total > MAX_NZB_FILE) throw IllegalStateException("$name is too big for an .nzb file")
                                out.write(buf, 0, n)
                            }
                            out.toByteArray()
                        } ?: throw IllegalStateException("Couldn't read $name")
                        // An NZB is XML with an <nzb> root; check the start so a random file isn't sent.
                        val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.UTF_8)
                        if (!head.contains("<nzb", ignoreCase = true) && !head.contains("<?xml", ignoreCase = true)) throw IllegalStateException("$name isn't an .nzb file")
                        NzbUpload(if (name.endsWith(".nzb", ignoreCase = true)) name else "$name.nzb", bytes)
                    }
                }
            }
            read.forEach { r -> r.onSuccess { f -> if (files.none { it.name == f.name }) files += f } }
            read.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { error = it.message }
        }
    }

    fun submit() {
        val s = client ?: return
        val parsed = try { parseNzbLinks(links) } catch (e: Exception) { error = e.userMessage(); return }
        if (parsed.isEmpty() && files.isEmpty()) { error = "Paste an NZB link or pick an .nzb file"; return }
        if (parsed.size + files.size > 50) { error = "Add at most 50 at a time"; return }
        busy = true
        scope.launch {
            var added = 0
            try {
                val cat = category.ifEmpty { null }
                parsed.forEach { UsenetApi.addUrl(s, it, cat, priority, paused); added++ }
                files.toList().forEach { f -> UsenetApi.addFile(s, f, cat, priority, paused); added++; files.remove(f) }
                onAdded(if (added > 1) "Added $added NZBs" else "NZB added")
                sheet.hide()
                onDismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Don't re-add links that already went through.
                if (added > 0) links = parsed.drop(added.coerceAtMost(parsed.size)).joinToString("\n")
                error = (if (added > 0) "Added $added, then: " else "") + e.userMessage()
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
            Text("Add NZB", style = MaterialTheme.typography.titleLarge)

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
                label = { Text("NZB links") },
                placeholder = { Text("https://indexer.example/getnzb/…") },
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
                OutlinedButton(onClick = { picker.launch(arrayOf("application/x-nzb", "application/xml", "text/xml", "application/octet-stream", "*/*")) }) {
                    Icon(Icons.Rounded.AttachFile, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(".nzb file")
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
                category.ifEmpty { "Default" },
                listOf("" to "Default") + categories.map { it to it },
                enabled = categories.isNotEmpty(),
            ) { category = it }

            val type = client?.serviceType
            Dropdown(
                "Priority",
                priority?.label ?: "Default",
                listOf("" to "Default") + (type?.let { priorities(it) } ?: emptyList()).map { it.name to it.label },
            ) { priorityName = it.ifEmpty { null } }

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
