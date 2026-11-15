package app.bosunarr.feature.services

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.bosunarr.app
import app.bosunarr.data.ServiceConfig
import app.bosunarr.ui.common.ConfirmDialog
import app.bosunarr.ui.common.Loader
import app.bosunarr.ui.common.friendlyMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/* ================================================================ list (Indexers tab) */

@Composable
internal fun DownloadClientsCard(s: ServiceConfig, loader: Loader<List<PDownloadClient>>, onAdd: () -> Unit) {
    val runner = rememberActionRunner()
    var removing by remember { mutableStateOf<PDownloadClient?>(null) }
    SectionCard("Download clients", icon = Icons.Rounded.Download) {
        CardLoad(loader, placeholder = { SkeletonRows(1, rowHeight = 40.dp) }) { list ->
            if (list.isEmpty()) {
                CardEmpty("None yet, so releases you grab from Search have nowhere to go. Add one and point movies and TV at the categories Radarr and Sonarr import from.")
            }
            list.forEachIndexed { i, c ->
                if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(if (c.enable) okColor() else MaterialTheme.colorScheme.outline, size = 10.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val meta = listOfNotNull(
                            c.implementationName?.takeIf { it.isNotBlank() && it != c.name },
                            if (!c.enable) "Disabled" else null,
                        ).joinToString(" · ")
                        if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            mappingSummary(c.categories) ?: "No categories mapped: grabs aren't imported into your library",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (c.categories.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { removing = c }, enabled = !runner.isBusy("prowlarr:${s.id}:dc:${c.id}")) {
                        Icon(Icons.Rounded.DeleteOutline, "Remove ${c.name}")
                    }
                }
            }
            FilledTonalButton(onClick = onAdd, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Add download client")
            }
        }
    }
    removing?.let { c ->
        ConfirmDialog(
            title = "Remove ${c.name}?",
            text = "Prowlarr stops sending grabs to it. Nothing in the download client itself changes.",
            confirm = "Remove",
            destructive = true,
            onConfirm = {
                runner.run("prowlarr:${s.id}:dc:${c.id}", after = { loader.refresh() }) {
                    deleteProwlarrClient(s, c.id)
                    "Removed ${c.name}"
                }
            },
            onDismiss = { removing = null },
        )
    }
}

/* ================================================================ add sheet */

/**
 * Set up a Prowlarr download client from one the user already added to Bosunarr: its login comes from Bosunarr,
 * its server-side address and categories from Radarr/Sonarr (see [loadClientDefaults]); all editable.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun AddDownloadClientSheet(s: ServiceConfig, onDismiss: () -> Unit, onSaved: (String) -> Unit) {
    val sources = remember { app.services.value.filter { prowlarrImplementation(it.serviceType) != null } }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    var sourceId by rememberSaveable { mutableStateOf(sources.firstOrNull()?.id) }
    val source = sources.firstOrNull { it.id == sourceId }
    var name by rememberSaveable { mutableStateOf("") }
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("") }
    var useSsl by rememberSaveable { mutableStateOf(false) }
    var urlBase by rememberSaveable { mutableStateOf("") }
    var movies by rememberSaveable { mutableStateOf("") }
    var movies4k by rememberSaveable { mutableStateOf("") }
    var tv by rememberSaveable { mutableStateOf("") }
    var other by rememberSaveable { mutableStateOf("prowlarr") }
    /** The source the fields were last filled from, so a rotation doesn't overwrite the user's edits. */
    var filledFor by rememberSaveable { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var tested by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(sourceId) {
        val src = source ?: return@LaunchedEffect
        if (filledFor == src.id) return@LaunchedEffect
        loading = true
        val d = runCatching { loadClientDefaults(src) }.getOrDefault(ClientDefaults())
        name = src.name; host = d.host; port = d.port?.toString().orEmpty(); useSsl = d.useSsl; urlBase = d.urlBase
        movies = d.movies; movies4k = d.movies4k; tv = d.tv
        filledFor = src.id
        loading = false
    }

    fun act(label: String, save: Boolean) {
        error = null; tested = null
        val src = source ?: return
        val impl = prowlarrImplementation(src.serviceType) ?: return
        val p = port.trim().toIntOrNull()
        when {
            name.isBlank() -> { error = "Enter a name"; return }
            host.isBlank() -> { error = "Enter the address Prowlarr should use"; return }
            p == null || p !in 1..65535 -> { error = "Enter a port between 1 and 65535"; return }
        }
        val values = buildMap<String, JsonElement> {
            put("host", JsonPrimitive(host.trim()))
            put("port", JsonPrimitive(p))
            put("useSsl", JsonPrimitive(useSsl))
            put("urlBase", JsonPrimitive(urlBase.trim()))
            put("category", JsonPrimitive(other.trim()))
            putAll(credentialValues(src))
        }
        busy = label
        scope.launch {
            try {
                val payload = buildClientPayload(clientSchema(s), impl, name, values, categoryMappings(movies, movies4k, tv))
                    ?: throw IllegalStateException("${s.name} doesn't support ${src.serviceType?.label}")
                if (save) {
                    saveProwlarrClient(s, payload)
                    onSaved("Added ${name.trim()} to ${s.name}")
                    sheet.hide()
                    onDismiss()
                } else {
                    testProwlarrClient(s, payload)
                    tested = "${s.name} reached ${name.trim()}"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = e.friendlyMessage()
                // Prowlarr's validation only says "Should be unique" (for the name).
                error = if (msg.contains("unique", ignoreCase = true)) "${s.name} already has a download client named \"${name.trim()}\". Pick another name." else msg
            } finally {
                busy = null
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, contentWindowInsets = { WindowInsets(0) }) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Add download client", style = MaterialTheme.typography.titleLarge)
            if (sources.isEmpty()) {
                Text(
                    "Add your download client to Bosunarr first (Settings → Services: qBittorrent, Transmission, Deluge, SABnzbd or NZBGet), then come back. Bosunarr sets Prowlarr up with the login you saved there.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = onDismiss) { Text("Close") } }
                return@Column
            }
            Text("Grabs from ${s.name}'s search go to this client.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                sources.forEach { c -> FilterChip(selected = c.id == sourceId, onClick = { sourceId = c.id }, label = { Text(c.name) }) }
            }
            if (loading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Checking how Radarr and Sonarr reach it…", style = MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedTextField(name, { name = it }, label = { Text("Name in ${s.name}") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    host, { host = it }, label = { Text("Address") }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Port") }, singleLine = true, modifier = Modifier.width(96.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            Text(
                "How ${s.name} reaches it from your server. In Docker that's usually the container name (e.g. qbittorrent), the same address Radarr and Sonarr use.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Use HTTPS", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(useSsl, onCheckedChange = { useSsl = it })
            }
            OutlinedTextField(urlBase, { urlBase = it }, label = { Text("URL base (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            source?.let { Text("Signs in with the login saved for ${it.name} in Bosunarr.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }

            Text("Categories", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
            Text(
                "Radarr and Sonarr import finished downloads from their own category, so grabs here reach your library — as long as the movie or show is already in Radarr or Sonarr.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(movies, { movies = it.trim() }, label = { Text("Movies") }, placeholder = { Text("radarr") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                movies4k, { movies4k = it.trim() }, label = { Text("4K movies (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Empty: 4K movies go with the others") },
            )
            OutlinedTextField(tv, { tv = it.trim() }, label = { Text("TV") }, placeholder = { Text("sonarr") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                other, { other = it.trim() }, label = { Text("Everything else") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Not imported anywhere") },
            )

            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            tested?.let { Text(it, color = okColor(), style = MaterialTheme.typography.bodyMedium) }

            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { scope.launch { sheet.hide(); onDismiss() } }) { Text("Cancel") }
                OutlinedButton(onClick = { act("test", save = false) }, enabled = busy == null && !loading && source != null) {
                    if (busy == "test") { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                    Text("Test")
                }
                Button(onClick = { act("save", save = true) }, enabled = busy == null && !loading && source != null) {
                    if (busy == "save") {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Save")
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
