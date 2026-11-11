package app.bosunarr.update

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.bosunarr.ui.common.bytes
import app.bosunarr.ui.common.relative
import kotlinx.coroutines.launch

/** App-wide "Update available" dialog, shown when [Updater.prompt] holds an update. */
@Composable
fun UpdatePrompt() {
    val update by Updater.prompt.collectAsState()
    val state by Updater.state.collectAsState()
    val u = update ?: return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val busy = state is Updater.State.Downloading

    Dialog(
        onDismissRequest = { if (!busy) Updater.prompt.value = null },
        properties = DialogProperties(dismissOnClickOutside = !busy, usePlatformDefaultWidth = false),
    ) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.padding(horizontal = 24.dp).fillMaxWidth()) {
            Column(Modifier.padding(24.dp).animateContentSize()) {
                Icon(Icons.Rounded.SystemUpdate, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(28.dp).align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(16.dp))
                Text("Update available", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(4.dp))
                Text(
                    listOfNotNull("Version ${u.version}", u.apkSize.takeIf { it > 0 }?.let { bytes(it) }, u.published?.let { relative(it) }.takeIf { !it.isNullOrBlank() })
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Text(
                    "You have ${Updater.currentVersion}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                if (u.notes.isNotBlank()) {
                    Spacer(Modifier.height(16.dp))
                    Text("What's new", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        u.notes,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                    )
                }
                Spacer(Modifier.height(20.dp))
                when (val s = state) {
                    is Updater.State.Downloading -> {
                        LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth().height(6.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("Downloading… ${(s.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    is Updater.State.Failed -> {
                        Text(s.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(12.dp))
                    }
                    else -> Unit
                }
                if (!busy) {
                    val ready = (state as? Updater.State.Ready)?.takeIf { it.update.version == u.version }
                    Button(
                        onClick = {
                            if (ready != null) Updater.install(ctx, ready.file)
                            else scope.launch { Updater.downloadAndInstall(ctx, u) }
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) { Text(if (ready != null) "Install" else "Update now") }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { Updater.skip(u) }) { Text("Skip this version") }
                        TextButton(onClick = { Updater.prompt.value = null }) { Text("Later") }
                    }
                }
            }
        }
    }
}

/** Settings → About row: current version, check now, and the state of any update. */
@Composable
fun UpdateSettingsRow(
    modifier: Modifier = Modifier,
    leading: @Composable (() -> Unit)? = null,
    colors: androidx.compose.material3.ListItemColors = androidx.compose.material3.ListItemDefaults.colors(),
) {
    val state by Updater.state.collectAsState()
    val (line, action) = when (val s = state) {
        Updater.State.Idle -> "Version ${Updater.currentVersion}" to "Check for updates"
        Updater.State.Checking -> "Checking GitHub for a newer version…" to null
        Updater.State.UpToDate -> "Version ${Updater.currentVersion} · you're up to date" to "Check again"
        is Updater.State.Available -> "Version ${s.update.version} is available" to "View update"
        is Updater.State.Downloading -> "Downloading ${s.update.version}… ${(s.progress * 100).toInt()}%" to null
        is Updater.State.Ready -> "Version ${s.update.version} downloaded" to "Install"
        is Updater.State.Failed -> s.message to "Try again"
    }
    androidx.compose.material3.ListItem(
        modifier = modifier,
        colors = colors,
        leadingContent = leading,
        headlineContent = { Text("Updates") },
        supportingContent = { Text(line, color = if (state is Updater.State.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingContent = { UpdateAction(state, action) },
    )
}

@Composable
private fun UpdateAction(state: Updater.State, action: String?) {
    val scope = rememberCoroutineScope()
    run {
        if (state is Updater.State.Checking || state is Updater.State.Downloading) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
        } else if (action != null) {
            TextButton(onClick = {
                when (val s = state) {
                    is Updater.State.Available -> Updater.prompt.value = s.update
                    is Updater.State.Ready -> Updater.prompt.value = s.update
                    is Updater.State.Failed -> if (s.update != null) Updater.prompt.value = s.update else scope.launch { Updater.manualCheck() }
                    else -> scope.launch { Updater.manualCheck() }
                }
            }) { Text(action) }
        }
    }
}
