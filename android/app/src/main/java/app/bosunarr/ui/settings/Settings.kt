package app.bosunarr.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalAutofillManager
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import app.bosunarr.BuildConfig
import app.bosunarr.app
import app.bosunarr.data.Credentials
import app.bosunarr.data.ServiceConfig
import app.bosunarr.data.ServiceType
import app.bosunarr.data.normalizeUrl
import app.bosunarr.nav.ServiceEditRoute
import app.bosunarr.nav.ServiceRoute
import app.bosunarr.nav.SettingsRoute
import app.bosunarr.net.userMessage
import app.bosunarr.ui.common.AppScaffold
import app.bosunarr.ui.common.ConfirmDialog
import app.bosunarr.ui.common.LoadingBox
import app.bosunarr.ui.common.MessageBox
import app.bosunarr.ui.common.SectionTitle
import app.bosunarr.ui.common.back
import kotlinx.coroutines.launch

fun NavGraphBuilder.settingsGraph(nav: NavController) {
    composable<SettingsRoute> { SettingsScreen(nav) }
    composable<ServiceEditRoute> {
        val r = it.toRoute<ServiceEditRoute>()
        ServiceEditScreen(nav, r.id, r.type)
    }
}

/** Coloured initial badge for a service type. */
@Composable
fun ServiceBadge(type: ServiceType?, modifier: Modifier = Modifier) {
    val c = type?.color ?: MaterialTheme.colorScheme.primary
    Box(modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(c.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
        Text(type?.label?.take(1) ?: "?", color = c, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nav: NavController) {
    val services by app.services.collectAsState()
    val state by app.state.collectAsState()
    var picking by rememberSaveable { mutableStateOf(false) }

    AppScaffold(
        "Settings",
        onBack = { nav.back() },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { picking = true }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Add service") })
        },
    ) { p ->
        LazyColumn(Modifier.padding(p), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 112.dp)) {
            item { SectionTitle("Services") }
            if (services.isEmpty()) item {
                MessageBox(Icons.Rounded.Dns, "No services yet", hint = "Add Sonarr, Radarr, qBittorrent, Jellyseerr and the rest. Everything stays on this phone, encrypted.")
            }
            items(services, key = { it.id }) { s ->
                ListItem(
                    headlineContent = { Text(s.name) },
                    supportingContent = { Text(s.url, maxLines = 1) },
                    leadingContent = { ServiceBadge(s.serviceType) },
                    modifier = Modifier.clickable { nav.navigate(ServiceEditRoute(id = s.id)) },
                    trailingContent = {
                        androidx.compose.material3.TextButton(onClick = { nav.navigate(ServiceRoute(s.id)) }) { Text("Open") }
                    },
                )
            }
            item { SectionTitle("Appearance") }
            item {
                val modes = listOf("system" to "System", "light" to "Light", "dark" to "Dark")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    modes.forEachIndexed { i, (v, label) ->
                        SegmentedButton(
                            selected = (state?.themeMode ?: "system") == v,
                            onClick = { app.updateState { it.copy(themeMode = v) } },
                            shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                        ) { Text(label) }
                    }
                }
            }
            item { SectionTitle("About") }
            item {
                ListItem(
                    headlineContent = { Text("Bosunarr ${BuildConfig.VERSION_NAME}") },
                    supportingContent = { Text("Open-source *arr manager. Talks to your services directly; nothing leaves this phone.") },
                )
            }
        }
    }

    if (picking) {
        ModalBottomSheet(onDismissRequest = { picking = false }) {
            Text("Add a service", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
                ServiceType.entries.forEach { t ->
                    ListItem(
                        headlineContent = { Text(t.label) },
                        supportingContent = { Text(t.category.label) },
                        leadingContent = { ServiceBadge(t) },
                        modifier = Modifier.clickable { picking = false; nav.navigate(ServiceEditRoute(type = t.id)) },
                    )
                }
            }
        }
    }
}

private sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data class Ok(val text: String) : TestState
    data class Failed(val text: String) : TestState
}

@Composable
fun ServiceEditScreen(nav: NavController, id: String?, typeId: String?) {
    val loaded by app.state.collectAsState()
    if (id != null && loaded == null) {
        // Still reading the encrypted store (e.g. restored after process death): don't claim "not found".
        AppScaffold("Service", onBack = { nav.back() }) { p -> LoadingBox(Modifier.padding(p)) }
        return
    }
    val existing = remember(id, loaded) { id?.let { app.service(it) } }
    val type = existing?.serviceType ?: ServiceType.of(typeId.orEmpty())
    if (type == null) {
        AppScaffold("Service", onBack = { nav.back() }) { p -> MessageBox(Icons.Rounded.Error, "Service not found", Modifier.padding(p)) }
        return
    }
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf(existing?.name ?: type.label) }
    var url by rememberSaveable { mutableStateOf(existing?.url ?: "") }
    var altUrl by rememberSaveable { mutableStateOf(existing?.altUrl ?: "") }
    var apiKey by rememberSaveable { mutableStateOf(existing?.apiKey ?: "") }
    var username by rememberSaveable { mutableStateOf(existing?.username ?: "") }
    var password by rememberSaveable { mutableStateOf(existing?.password ?: "") }
    var showSecret by rememberSaveable { mutableStateOf(false) }
    var test by remember { mutableStateOf<TestState>(TestState.Idle) }
    var confirmDelete by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val autofill = LocalAutofillManager.current

    fun current() = ServiceConfig(
        id = existing?.id ?: "test-${type.id}",
        type = type.id,
        name = name.trim().ifEmpty { type.label },
        url = normalizeUrl(url),
        altUrl = normalizeUrl(altUrl).ifEmpty { null },
        apiKey = apiKey.trim().ifEmpty { null }.takeIf { type.credentials == Credentials.ApiKey },
        username = username.trim().ifEmpty { null }.takeIf { type.credentials == Credentials.UserPass },
        password = password.ifEmpty { null }.takeIf { type.credentials == Credentials.UserPass },
        sort = existing?.sort ?: 0,
    )
    val valid = url.isNotBlank() && when (type.credentials) {
        Credentials.ApiKey -> apiKey.isNotBlank()
        Credentials.UserPass -> username.isNotBlank()
        Credentials.None -> true
    }

    AppScaffold(
        if (existing == null) "Add ${type.label}" else existing.name,
        onBack = { nav.back() },
        actions = {
            if (existing != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.Delete, "Remove service") }
        },
    ) { p ->
        Column(
            Modifier.padding(p).verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ServiceBadge(type)
                Text(type.category.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                url, { url = it; test = TestState.Idle },
                label = { Text("Address") },
                placeholder = { Text("http://192.168.1.10:${type.defaultPort}") },
                supportingText = { Text("Include the port and any base path, e.g. /sonarr") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                altUrl, { altUrl = it; test = TestState.Idle },
                label = { Text("Fallback address (optional)") },
                supportingText = { Text("Tried when the first one is unreachable, e.g. a Tailscale address") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            val eye = @Composable {
                IconButton(onClick = { showSecret = !showSecret }) {
                    Icon(if (showSecret) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (showSecret) "Hide" else "Show")
                }
            }
            when (type.credentials) {
                Credentials.ApiKey -> OutlinedTextField(
                    apiKey, { apiKey = it; test = TestState.Idle },
                    label = { Text("API key") },
                    supportingText = { Text(type.keyHint) },
                    singleLine = true,
                    visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    trailingIcon = eye,
                    modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Password },
                )
                Credentials.UserPass -> {
                    OutlinedTextField(username, { username = it; test = TestState.Idle }, label = { Text("Username") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Username })
                    OutlinedTextField(
                        password, { password = it; test = TestState.Idle },
                        label = { Text("Password") },
                        supportingText = { Text(type.keyHint) },
                        singleLine = true,
                        visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        trailingIcon = eye,
                        modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Password },
                    )
                }
                Credentials.None -> Unit
            }

            when (val t = test) {
                TestState.Idle -> Unit
                TestState.Running -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Text("Connecting…")
                }
                is TestState.Ok -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary); Text(t.text)
                }
                is TestState.Failed -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.Error, null, tint = MaterialTheme.colorScheme.error); Text(t.text, color = MaterialTheme.colorScheme.error)
                }
            }

            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    enabled = valid && test != TestState.Running,
                    onClick = {
                        test = TestState.Running
                        scope.launch {
                            val cfg = current()
                            app.client.reset(cfg.id)
                            test = try {
                                val r = app.client.test(cfg)
                                TestState.Ok("Connected${r.version?.let { v -> " · ${v.removePrefix("v").let { "v$it" }}" } ?: ""} · ${r.ms} ms")
                            } catch (e: Exception) {
                                if (e is kotlinx.coroutines.CancellationException) throw e
                                TestState.Failed(e.userMessage())
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Test") }
                Button(
                    enabled = valid && !saving,
                    onClick = {
                        if (saving) return@Button
                        saving = true
                        autofill?.commit()
                        scope.launch {
                            val cfg = current().let { if (existing == null) it.copy(id = "") else it }
                            app.saveService(cfg)
                            nav.back()
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Save") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDelete && existing != null) {
        ConfirmDialog(
            title = "Remove ${existing.name}?",
            text = "Its address and credentials are deleted from this phone. Nothing changes on the service itself.",
            confirm = "Remove",
            destructive = true,
            onConfirm = { scope.launch { app.deleteService(existing.id); nav.back() } },
            onDismiss = { confirmDelete = false },
        )
    }
}

