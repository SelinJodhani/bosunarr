package app.bosunarr.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Launch
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalAutofillManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import app.bosunarr.BuildConfig
import app.bosunarr.app
import app.bosunarr.update.UpdateSettingsRow
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.PrivacyTip
import app.bosunarr.ui.common.LicencesRoute
import app.bosunarr.ui.common.PRIVACY_URL
import app.bosunarr.ui.common.feedbackUrl
import app.bosunarr.ui.common.safeOpen
import app.bosunarr.data.ServiceConfig
import app.bosunarr.data.ServiceType
import app.bosunarr.feature.services.okColor
import app.bosunarr.nav.ServiceEditRoute
import app.bosunarr.nav.ServiceRoute
import app.bosunarr.nav.SettingsRoute
import app.bosunarr.net.userMessage
import app.bosunarr.notify.NotificationSettingsRoute
import app.bosunarr.notify.NotificationsIcon
import app.bosunarr.notify.NotificationsSettingsRow
import app.bosunarr.ui.common.AppMark
import app.bosunarr.ui.common.AppScaffold
import app.bosunarr.ui.common.ConfirmDialog
import app.bosunarr.ui.common.LoadingBox
import app.bosunarr.ui.common.LocalSnackbar
import app.bosunarr.ui.common.MessageBox
import app.bosunarr.ui.common.ServiceStatuses
import app.bosunarr.ui.common.back
import app.bosunarr.ui.onboarding.Probe
import app.bosunarr.ui.onboarding.ProbeResult
import app.bosunarr.ui.onboarding.ServiceFormFields
import app.bosunarr.ui.onboarding.SetupStep
import app.bosunarr.ui.onboarding.connectedText
import app.bosunarr.ui.onboarding.rememberServiceForm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val GITHUB_URL = "https://github.com/SelinJodhani/bosunarr"
private const val WEBSITE_URL = "https://selinjodhani.github.io/bosunarr/"

fun NavGraphBuilder.settingsGraph(nav: NavController) {
    composable<SettingsRoute> { SettingsScreen(nav) }
    composable<ServiceEditRoute> {
        val r = it.toRoute<ServiceEditRoute>()
        ServiceEditScreen(nav, r.id, r.type)
    }
}

/* ================================================================ grouped list */

private val GroupOuter = 24.dp
private val GroupInner = 6.dp

/** Rows of one group share a rounded block: large outer corners, small inner ones, 2dp gaps between rows. */
private fun groupShape(index: Int, count: Int): Shape = RoundedCornerShape(
    topStart = if (index == 0) GroupOuter else GroupInner,
    topEnd = if (index == 0) GroupOuter else GroupInner,
    bottomStart = if (index == count - 1) GroupOuter else GroupInner,
    bottomEnd = if (index == count - 1) GroupOuter else GroupInner,
)

@Composable
private fun GroupHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun GroupRow(index: Int, count: Int, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val shape = groupShape(index, count)
    val color = MaterialTheme.colorScheme.surfaceContainerLow
    val m = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = if (index == count - 1) 0.dp else 2.dp)
    if (onClick != null) Surface(onClick = onClick, shape = shape, color = color, modifier = m) { content() }
    else Surface(shape = shape, color = color, modifier = m) { content() }
}

private val TransparentListItem @Composable get() = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)

/** Leading icon in a tonal circle, for plain settings rows. */
@Composable
private fun RowIcon(icon: ImageVector) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(40.dp)) {
        Icon(icon, null, modifier = Modifier.padding(9.dp))
    }
}

/* ================================================================ settings */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nav: NavController) {
    val services by app.services.collectAsState()
    val state by app.state.collectAsState()
    var picking by rememberSaveable { mutableStateOf(false) }
    val uri = LocalUriHandler.current

    // Lightweight reachability check, shared with the dashboard and cached for a minute.
    LaunchedEffect(services) { ServiceStatuses.refreshStale(services, maxAgeMs = 60_000) }

    AppScaffold("Settings", onBack = { nav.back() }, large = true) { p ->
        LazyColumn(Modifier.padding(p).fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item("h:services") { GroupHeader("Services") }
            val rows = services.size + 1
            itemsIndexed(services, key = { _, s -> s.id }) { i, s ->
                GroupRow(i, rows, onClick = { nav.navigate(ServiceEditRoute(id = s.id)) }) { ServiceRow(s) }
            }
            item("add") {
                GroupRow(services.size, rows, onClick = { picking = true }) {
                    ListItem(
                        headlineContent = { Text("Add service", fontWeight = FontWeight.Medium) },
                        supportingContent = if (services.isEmpty()) ({ Text("Sonarr, Radarr, qBittorrent, Jellyseerr and more. Everything stays on this phone, encrypted.") }) else null,
                        leadingContent = {
                            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.Rounded.Add, null, modifier = Modifier.padding(8.dp))
                            }
                        },
                        colors = TransparentListItem,
                    )
                }
            }

            item("notifications") { Column { GroupHeader("Notifications"); GroupRow(0, 1, onClick = { nav.navigate(NotificationSettingsRoute) }) { NotificationsSettingsRow(leading = { RowIcon(NotificationsIcon) }, colors = TransparentListItem) } } }

            item("h:appearance") { GroupHeader("Appearance") }
            item("theme") {
                GroupRow(0, 1) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Theme", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Colours follow your wallpaper (Material You).",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        val modes = listOf(Triple("system", "Auto", Icons.Rounded.BrightnessAuto), Triple("light", "Light", Icons.Rounded.LightMode), Triple("dark", "Dark", Icons.Rounded.DarkMode))
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            modes.forEachIndexed { i, (v, label, icon) ->
                                SegmentedButton(
                                    selected = (state?.themeMode ?: "system") == v,
                                    onClick = { app.updateState { it.copy(themeMode = v) } },
                                    shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                                    icon = { SegmentedButtonDefaults.Icon(active = (state?.themeMode ?: "system") == v) { Icon(icon, null, Modifier.size(SegmentedButtonDefaults.IconSize)) } },
                                ) { Text(label, maxLines = 1) }
                            }
                        }
                    }
                }
            }

            item("h:about") { GroupHeader("About") }
            val about = 7
            item("app") {
                GroupRow(0, about) {
                    ListItem(
                        headlineContent = { Text("Bosunarr", style = MaterialTheme.typography.titleMedium) },
                        supportingContent = { Text("Version ${BuildConfig.VERSION_NAME}") },
                        leadingContent = { AppMark(size = 40.dp) },
                        colors = TransparentListItem,
                    )
                }
            }
            item("updates") {
                GroupRow(1, about) {
                    UpdateSettingsRow(leading = { RowIcon(Icons.Rounded.SystemUpdate) }, colors = TransparentListItem)
                }
            }
            item("feedback") {
                GroupRow(2, about, onClick = { uri.safeOpen(feedbackUrl()) }) {
                    ListItem(
                        headlineContent = { Text("Send feedback or report a bug") },
                        supportingContent = { Text("Opens a GitHub issue with your app and Android version") },
                        leadingContent = { RowIcon(Icons.Rounded.BugReport) },
                        trailingContent = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        colors = TransparentListItem,
                    )
                }
            }
            item("privacy") {
                GroupRow(3, about, onClick = { uri.safeOpen(PRIVACY_URL) }) {
                    ListItem(
                        headlineContent = { Text("Privacy policy") },
                        supportingContent = { Text("No accounts, no analytics, no tracking") },
                        leadingContent = { RowIcon(Icons.Rounded.PrivacyTip) },
                        trailingContent = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        colors = TransparentListItem,
                    )
                }
            }
            item("licences") {
                GroupRow(4, about, onClick = { nav.navigate(LicencesRoute) }) {
                    ListItem(
                        headlineContent = { Text("Open-source licences") },
                        leadingContent = { RowIcon(Icons.Rounded.Gavel) },
                        trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        colors = TransparentListItem,
                    )
                }
            }
            item("github") {
                GroupRow(5, about, onClick = { uri.safeOpen(GITHUB_URL) }) {
                    ListItem(
                        headlineContent = { Text("Source code") },
                        supportingContent = { Text("github.com/SelinJodhani/bosunarr", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { RowIcon(Icons.Rounded.Code) },
                        trailingContent = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        colors = TransparentListItem,
                    )
                }
            }
            item("web") {
                GroupRow(6, about, onClick = { uri.safeOpen(WEBSITE_URL) }) {
                    ListItem(
                        headlineContent = { Text("Website") },
                        supportingContent = { Text("selinjodhani.github.io/bosunarr", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { RowIcon(Icons.Rounded.Public) },
                        trailingContent = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        colors = TransparentListItem,
                    )
                }
            }
            item("footer") {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Talks to your services directly. Your addresses and credentials stay on this phone, encrypted.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (picking) {
        val existing = services.mapNotNull { it.serviceType }.toSet()
        ModalBottomSheet(onDismissRequest = { picking = false }) {
            Text("Add a service", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp))
            Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
                ServiceType.entries.forEach { t ->
                    ListItem(
                        headlineContent = { Text(t.label) },
                        supportingContent = { Text(if (t in existing) "${t.role} · already added" else t.role) },
                        leadingContent = { ServiceBadge(t) },
                        trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        colors = TransparentListItem,
                        modifier = Modifier.padding(horizontal = 8.dp).clip(RoundedCornerShape(16.dp))
                            .clickable { picking = false; nav.navigate(ServiceEditRoute(type = t.id)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ServiceRow(s: ServiceConfig) {
    val status = ServiceStatuses.get(s)
    val ring = MaterialTheme.colorScheme.surfaceContainerLow
    ListItem(
        headlineContent = { Text(s.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(s.url.substringAfter("://"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                val (text, color) = when {
                    status == null -> "Checking…" to MaterialTheme.colorScheme.onSurfaceVariant
                    status.ok -> (listOfNotNull("Online", status.version?.let { "v" + it.removePrefix("v") }).joinToString(" · ")) to MaterialTheme.colorScheme.onSurfaceVariant
                    else -> (status.error ?: "Offline") to MaterialTheme.colorScheme.error
                }
                Text(text, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        leadingContent = {
            val dot = when {
                status == null -> MaterialTheme.colorScheme.outline
                status.ok -> okColor()
                else -> MaterialTheme.colorScheme.error
            }
            ServiceBadgeWithStatus(s.serviceType, dot, size = 40.dp, ring = ring)
        },
        trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        colors = TransparentListItem,
    )
}

/* ================================================================ add / edit a service */

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
    if (type == null || (id != null && existing == null)) {
        AppScaffold("Service", onBack = { nav.back() }) { p -> MessageBox(Icons.Rounded.Error, "Service not found", Modifier.padding(p), hint = "It may have been removed.") }
        return
    }
    if (existing == null) AddServiceScreen(nav, type) else EditServiceScreen(nav, existing, type)
}

/** Adding one service from Settings: the same guided, tested setup as onboarding, without the progress bar. */
@Composable
private fun AddServiceScreen(nav: NavController, type: ServiceType) {
    val snackbar = LocalSnackbar.current
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        SetupStep(
            type = type,
            title = "Add ${type.label}",
            subtitle = type.role,
            progress = null,
            onBack = { nav.back() },
            skipLabel = "Cancel",
            onDone = { c ->
                nav.back()
                if (c != null) app.scope.launch(Dispatchers.Main) {
                    snackbar.showSnackbar(if (c.version != null) "${c.name} connected" else "${c.name} saved")
                }
            },
        )
    }
}

@Composable
private fun EditServiceScreen(nav: NavController, existing: ServiceConfig, type: ServiceType) {
    val scope = rememberCoroutineScope()
    val autofill = LocalAutofillManager.current
    val form = rememberServiceForm(type, existing)
    var probe by remember { mutableStateOf<Probe>(Probe.Idle) }
    var confirmDelete by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    fun test() {
        if (!form.ready || probe == Probe.Running) return
        probe = Probe.Running
        scope.launch {
            val cfg = form.config(id = "test-${existing.id}")
            app.client.reset(cfg.id)
            probe = try {
                val r = app.client.test(cfg)
                Probe.Ok(connectedText(r.version, r.ms))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Probe.Failed(e.userMessage())
            }
        }
    }

    AppScaffold(
        existing.name,
        subtitle = type.label,
        onBack = { nav.back() },
        actions = {
            IconButton(onClick = { nav.navigate(ServiceRoute(existing.id)) }) { Icon(Icons.AutoMirrored.Rounded.Launch, "Open ${existing.name}") }
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.Delete, "Remove service") }
        },
    ) { p ->
        Column(Modifier.padding(p).fillMaxSize().imePadding()) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(Modifier.padding(top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ServiceBadge(type, size = 56.dp)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(type.label, style = MaterialTheme.typography.titleLarge)
                        Text(type.category.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                ServiceFormFields(form, onEdited = { if (probe != Probe.Running) probe = Probe.Idle }, onSubmit = { test() })
                ProbeResult(probe, type)
                Spacer(Modifier.height(16.dp))
            }
            Surface(tonalElevation = 2.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = { test() }, enabled = form.ready && probe != Probe.Running, modifier = Modifier.weight(1f).height(48.dp)) {
                        if (probe == Probe.Running) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                            Spacer(Modifier.width(10.dp))
                            Text("Testing…", maxLines = 1)
                        } else {
                            if (probe is Probe.Ok) {
                                Icon(Icons.Rounded.CheckCircle, null, Modifier.size(18.dp), tint = okColor())
                                Spacer(Modifier.width(8.dp))
                            }
                            Text("Test", maxLines = 1)
                        }
                    }
                    Button(
                        enabled = form.ready && !saving,
                        onClick = {
                            if (saving) return@Button
                            saving = true
                            autofill?.commit()
                            scope.launch {
                                app.saveService(form.config(id = existing.id, sort = existing.sort))
                                nav.back()
                            }
                        },
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) { Text("Save", maxLines = 1) }
                }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Remove ${existing.name}?",
            text = "Its address and credentials are deleted from this phone. Nothing changes on the service itself.",
            confirm = "Remove",
            destructive = true,
            onConfirm = {
                // Leave first: the screen would otherwise flash "not found" once the service is gone.
                nav.back()
                app.scope.launch { app.deleteService(existing.id) }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}
