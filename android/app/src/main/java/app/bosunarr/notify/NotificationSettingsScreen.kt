package app.bosunarr.notify

import app.bosunarr.ui.common.screen
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.bosunarr.app
import app.bosunarr.data.ServiceType
import app.bosunarr.ui.common.AppScaffold
import app.bosunarr.ui.common.LocalSnackbar
import app.bosunarr.ui.common.back
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable object NotificationSettingsRoute

fun NavGraphBuilder.notificationsGraph(nav: NavController) {
    screen<NotificationSettingsRoute> { NotificationSettingsScreen(nav) }
}

/** Icon for the Settings row. */
val NotificationsIcon: ImageVector get() = Icons.Rounded.Notifications

/** The "Notifications" row on the main Settings list (Settings supplies its own row chrome). */
@Composable
fun NotificationsSettingsRow(leading: @Composable () -> Unit, colors: ListItemColors = ListItemDefaults.colors()) {
    val ctx = LocalContext.current
    val s by remember { NotifyPrefs.get(ctx) }.settings.collectAsState()
    ListItem(
        headlineContent = { Text("Notifications") },
        supportingContent = { Text(if (s.enabled) "On · checks every ${s.intervalMin} min" else "Off") },
        leadingContent = leading,
        colors = colors,
    )
}

/* ------------------------------------------------------------------ grouped rows (same look as Settings) */

private fun groupShape(index: Int, count: Int): Shape {
    val outer = 24.dp
    val inner = 6.dp
    return RoundedCornerShape(
        topStart = if (index == 0) outer else inner,
        topEnd = if (index == 0) outer else inner,
        bottomStart = if (index == count - 1) outer else inner,
        bottomEnd = if (index == count - 1) outer else inner,
    )
}

@Composable
private fun Header(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun Group(index: Int, count: Int, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val shape = groupShape(index, count)
    val color = MaterialTheme.colorScheme.surfaceContainerLow
    val m = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = if (index == count - 1) 0.dp else 2.dp)
    if (onClick != null) Surface(onClick = onClick, shape = shape, color = color, modifier = m) { content() }
    else Surface(shape = shape, color = color, modifier = m) { content() }
}

private val Clear @Composable get() = ListItemDefaults.colors(containerColor = Color.Transparent)

@Composable
private fun Bubble(icon: ImageVector) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(40.dp)) {
        Icon(icon, null, modifier = Modifier.padding(9.dp))
    }
}

@Composable
private fun ToggleRow(icon: ImageVector, title: String, text: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(text) },
        leadingContent = { Bubble(icon) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange, enabled = enabled) },
        colors = Clear,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

/* ------------------------------------------------------------------ screen */

private fun openSystemNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

private fun ago(ms: Long): String {
    val d = (System.currentTimeMillis() - ms).coerceAtLeast(0) / 60_000
    return when {
        d < 1 -> "just now"
        d < 60 -> "$d min ago"
        d < 48 * 60 -> "${d / 60} h ago"
        else -> "${d / (24 * 60)} days ago"
    }
}

@Composable
fun NotificationSettingsScreen(nav: NavController) {
    val ctx = LocalContext.current
    val prefs = remember { NotifyPrefs.get(ctx) }
    val s by prefs.settings.collectAsState()
    val status by prefs.status.collectAsState()
    val services by app.services.collectAsState()
    val hasWhisparr = services.any { it.serviceType == ServiceType.Whisparr }
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()

    var canPost by remember { mutableStateOf(Notifier.canPost(ctx)) }
    var blocked by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        canPost = Notifier.canPost(ctx)
        if (canPost) blocked = false
        onPauseOrDispose { }
    }

    fun enable() {
        // Forget any baseline from an earlier time notifications were on: everything since then isn't news.
        NotifyStateStore(ctx).clear()
        prefs.update { it.copy(enabled = true) }
        NotifyScheduler.sync(ctx)
        // First check right away: it records what's there now, so only later changes notify.
        NotifyScheduler.runNow(ctx)
        canPost = Notifier.canPost(ctx)
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) enable() else blocked = true
    }

    fun setMaster(on: Boolean) {
        if (!on) {
            prefs.update { it.copy(enabled = false) }
            NotifyScheduler.sync(ctx)
            return
        }
        val needsAsk = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsAsk) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else enable()
    }

    fun toggle(transform: (NotifySettings) -> NotifySettings) { prefs.update(transform) }

    AppScaffold("Notifications", onBack = { nav.back() }, large = true) { p ->
        LazyColumn(Modifier.padding(p).fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item("master") {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = if (s.enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    onClick = { setMaster(!s.enabled) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Row(Modifier.padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Use notifications", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        Switch(checked = s.enabled, onCheckedChange = { setMaster(it) })
                    }
                }
            }

            if ((s.enabled && !canPost) || blocked) item("blocked") {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.NotificationsOff, null)
                            Spacer(Modifier.width(12.dp))
                            Text("Android is blocking Bosunarr's notifications", style = MaterialTheme.typography.titleSmall)
                        }
                        Text("Allow them in system settings, then come back.", style = MaterialTheme.typography.bodyMedium)
                        FilledTonalButton(onClick = { openSystemNotificationSettings(ctx) }) { Text("Open settings") }
                    }
                }
            }

            item("h:interval") { Header("Check every") }
            item("interval") {
                Group(0, 1) {
                    Column(Modifier.padding(16.dp)) {
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            NotifyPrefs.INTERVALS.forEachIndexed { i, m ->
                                SegmentedButton(
                                    selected = s.intervalMin == m,
                                    onClick = {
                                        prefs.update { it.copy(intervalMin = m) }
                                        NotifyScheduler.sync(ctx)
                                    },
                                    enabled = s.enabled,
                                    shape = SegmentedButtonDefaults.itemShape(i, NotifyPrefs.INTERVALS.size),
                                ) { Text(if (m == 60) "1 hour" else "$m min", maxLines = 1) }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            if (status.lastRun > 0) "Last check ${ago(status.lastRun)}" + (status.summary?.let { " · $it" } ?: "") else "Not checked yet",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item("h:events") { Header("Tell me when") }
            item("events") {
                val rows = buildList<@Composable () -> Unit> {
                    add { ToggleRow(Icons.Rounded.Download, "Downloaded", "Sonarr or Radarr imported a movie or episode", s.downloaded, s.enabled) { v -> toggle { it.copy(downloaded = v) } } }
                    add { ToggleRow(Icons.Rounded.ErrorOutline, "Download problems", "A download failed, or an import is blocked", s.failed, s.enabled) { v -> toggle { it.copy(failed = v) } } }
                    add { ToggleRow(Icons.Rounded.PersonAdd, "New requests", "Someone asked for something in Seerr (admins)", s.requests, s.enabled) { v -> toggle { it.copy(requests = v) } } }
                    add { ToggleRow(Icons.Rounded.TaskAlt, "Request available", "Something requested from this Seerr account is ready", s.available, s.enabled) { v -> toggle { it.copy(available = v) } } }
                    add { ToggleRow(Icons.Rounded.MonitorHeart, "Service health", "Health errors, and a service going offline (2 failed checks) or coming back", s.health, s.enabled) { v -> toggle { it.copy(health = v) } } }
                    add { ToggleRow(Icons.Rounded.CloudDownload, "Torrent completed", "qBittorrent finished a download", s.torrents, s.enabled) { v -> toggle { it.copy(torrents = v) } } }
                    if (hasWhisparr || s.whisparr) add {
                        ToggleRow(Icons.Rounded.VisibilityOff, "Include Whisparr", "Off by default. When on, these never show on the lock screen", s.whisparr, s.enabled) { v -> toggle { it.copy(whisparr = v) } }
                    }
                }
                Column {
                    rows.forEachIndexed { i, row -> Group(i, rows.size) { row() } }
                }
            }

            item("h:test") { Header("Try it") }
            item("actions") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = {
                            val sent = Notifier.sendTest(ctx)
                            if (!sent) {
                                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else blocked = true
                            }
                        },
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) { Text("Send test", maxLines = 1) }
                    Button(
                        enabled = s.enabled,
                        onClick = {
                            NotifyScheduler.runNow(ctx)
                            scope.launch { snackbar.showSnackbar("Checking your services…") }
                        },
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) {
                        Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Check now", maxLines = 1)
                    }
                }
            }
            item("system") {
                Spacer(Modifier.height(12.dp))
                Group(0, 1, onClick = { openSystemNotificationSettings(ctx) }) {
                    ListItem(
                        headlineContent = { Text("Sounds and importance") },
                        supportingContent = { Text("Each kind of notification has its own Android channel") },
                        leadingContent = { Bubble(Icons.Rounded.Notifications) },
                        trailingContent = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        colors = Clear,
                    )
                }
            }

            item("note") {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 20.dp)) {
                    Icon(Icons.Rounded.Info, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "There's no Bosunarr server: your phone checks your services in the background, every " +
                                "${s.intervalMin} minutes at best. Android can delay checks further when the battery is low or " +
                                "the phone is idle, and nothing arrives while the phone can't reach your services.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "The first check only notes what's already there; you're told about what changes after that.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Coming next: instant push through ntfy, set up in your *arrs and Seerr with one tap. Still no server of ours.",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
