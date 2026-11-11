package app.bosunarr.feature.discover

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.bosunarr.data.ServiceConfig
import app.bosunarr.net.userMessage
import app.bosunarr.ui.common.bytes
import app.bosunarr.ui.common.plural
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Request a movie or series. For series: pick seasons (already requested/available ones are shown checked and
 * disabled). 4K switch only when both copies are still requestable. Advanced: server / quality profile / root
 * folder, sent only when the user changes them (Seerr's own defaults — including anime overrides — apply otherwise).
 * `preset4k`: open on the 4K (true) or regular (false) copy; null picks whichever is still possible.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RequestSheet(s: ServiceConfig, m: MediaDetail, preset4k: Boolean?, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    val regularOpen = m.regularOpen
    val uhdOpen = m.uhdOpen
    val showSwitch = regularOpen && uhdOpen
    var is4k by rememberSaveable {
        mutableStateOf(
            when {
                !uhdOpen -> false
                !regularOpen -> true
                else -> preset4k ?: false
            },
        )
    }
    fun statusOf(se: SeasonRow) = if (is4k) se.status4k else se.status
    val requestable = m.seasons.filter { statusOf(it) == Avail.None }
    /** Default: the latest season still requestable (never every season — a talk show can have 25). */
    fun defaultPick(): Set<Int> = requestable.lastOrNull()?.let { setOf(it.number) } ?: emptySet()
    var picked by rememberSaveable(is4k) { mutableStateOf(defaultPick().toList()) }
    val chosen = requestable.map { it.number }.filter { it in picked }

    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Advanced options.
    val servers = m.servers.filter { it.is4k == is4k }
    var advancedOpen by rememberSaveable { mutableStateOf(false) }
    var touched by rememberSaveable(is4k) { mutableStateOf(false) }
    var serverId by rememberSaveable(is4k) { mutableStateOf((servers.firstOrNull { it.isDefault } ?: servers.firstOrNull())?.id) }
    var profileId by rememberSaveable(is4k) { mutableStateOf<Int?>(null) }
    var rootFolder by rememberSaveable(is4k) { mutableStateOf<String?>(null) }
    var serverDetail by remember(is4k) { mutableStateOf<ArrServerDetail?>(null) }
    var serverError by remember(is4k) { mutableStateOf<String?>(null) }
    LaunchedEffect(advancedOpen, serverId) {
        val sid = serverId ?: return@LaunchedEffect
        if (!advancedOpen || serverDetail?.server?.id == sid) return@LaunchedEffect
        serverError = null
        try {
            val det = Seerr.serverDetail(s, m.type, sid)
            serverDetail = det
            val srv = servers.firstOrNull { it.id == sid }
            if (profileId == null || det.profiles.none { it.id == profileId }) profileId = srv?.activeProfileId ?: det.profiles.firstOrNull()?.id
            if (rootFolder == null || det.rootFolders.none { it.path == rootFolder }) rootFolder = srv?.activeDirectory ?: det.rootFolders.firstOrNull()?.path
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            serverError = e.userMessage()
        }
    }

    val canSubmit = !busy && (if (m.isMovie) canSubmitAtAll(m, is4k) else chosen.isNotEmpty())
    val q = if (is4k) " in 4K" else ""
    val submitLabel = when {
        busy -> "Requesting…"
        !m.isMovie && chosen.isNotEmpty() -> "Request ${plural(chosen.size, "season")}$q"
        else -> "Request$q"
    }

    fun close() {
        scope.launch { sheet.hide() }.invokeOnCompletion { if (!sheet.isVisible) onDismiss() }
    }

    fun submit() {
        busy = true
        error = null
        scope.launch {
            try {
                val adv = touched && advancedOpen && serverDetail != null
                Seerr.create(
                    s,
                    CreateRequestBody(
                        mediaType = m.type, mediaId = m.item.tmdbId, is4k = is4k,
                        seasons = if (m.isMovie) null else chosen.sorted(),
                        tvdbId = if (m.isMovie) null else m.d.externalIds.tvdbId,
                        serverId = if (adv) serverId else null,
                        profileId = if (adv) profileId else null,
                        rootFolder = if (adv) rootFolder else null,
                    ),
                )
                busy = false
                sheet.hide()
                onDone("Requested ${m.item.title}$q")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Errors stay in the sheet: it sits above the snackbar.
                busy = false
                error = e.userMessage()
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Text(
                "Request ${m.item.title}", style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp),
            )
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                if (showSwitch) {
                    Row(
                        Modifier.fillMaxWidth().toggleable(is4k, enabled = !busy, role = Role.Switch) { is4k = it }.padding(horizontal = 24.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("4K", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text("Request the 4K copy instead of the regular one", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = is4k, onCheckedChange = null)
                    }
                }
                if (m.isMovie) {
                    val st = if (is4k) m.item.status4k else m.item.status
                    Note(
                        if (st != Avail.None) "Already ${st.label.lowercase()}$q."
                        else "${m.item.title}${m.item.year?.let { " ($it)" } ?: ""} will be requested$q through ${s.name}.",
                    )
                } else if (m.seasons.isEmpty()) {
                    Note("No seasons listed yet.")
                } else {
                    if (!showSwitch && is4k) Note("Requesting the 4K copy — the regular one is already taken care of.")
                    if (requestable.size > 1) {
                        val all = chosen.size == requestable.size
                        val state = when { all -> ToggleableState.On; chosen.isEmpty() -> ToggleableState.Off; else -> ToggleableState.Indeterminate }
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = !busy, role = Role.Checkbox) {
                                picked = if (all) emptyList() else requestable.map { it.number }
                            }.padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TriStateCheckbox(state = state, onClick = null, modifier = Modifier.padding(12.dp))
                            Text("All seasons", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            Text("${chosen.size} of ${requestable.size}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 12.dp))
                        }
                        HorizontalDivider(Modifier.padding(horizontal = 24.dp))
                    }
                    m.seasons.forEach { se ->
                        val st = statusOf(se)
                        val open = st == Avail.None
                        val checked = !open || se.number in picked
                        Row(
                            Modifier.fillMaxWidth()
                                .toggleable(checked, enabled = open && !busy, role = Role.Checkbox) { on ->
                                    picked = if (on) (picked + se.number).distinct() else picked - se.number
                                }
                                .padding(horizontal = 12.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null, enabled = open, modifier = Modifier.padding(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(se.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = if (open) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    listOfNotNull(plural(se.episodes, "episode"), yearOf(se.airDate)?.toString()).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (!open) StatusBadge(st.label, st.tone, Modifier.padding(start = 8.dp, end = 12.dp))
                        }
                    }
                    if (requestable.isEmpty()) Note("Every season is already requested or available$q.")
                }

                if (servers.isNotEmpty() && canSubmitAtAll(m, is4k)) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp).clickable { advancedOpen = !advancedOpen }.padding(horizontal = 24.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Advanced", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Icon(if (advancedOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (advancedOpen) "Hide advanced options" else "Show advanced options")
                    }
                    AnimatedVisibility(advancedOpen) {
                        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            val det = serverDetail
                            if (servers.size > 1) Picker(
                                "Server", servers.map { it.id.toString() to (it.name + if (it.isDefault) " (default)" else "") }, serverId?.toString(),
                            ) { v -> serverId = v.toInt(); serverDetail = null; profileId = null; rootFolder = null; touched = true }
                            when {
                                serverError != null -> Text(serverError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                det == null || det.server.id != serverId -> Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
                                else -> {
                                    Picker("Quality profile", det.profiles.map { it.id.toString() to it.name }, profileId?.toString()) { profileId = it.toInt(); touched = true }
                                    Picker(
                                        "Root folder",
                                        det.rootFolders.map { f -> f.path to (f.path + (f.freeSpace?.let { " · ${bytes(it)} free" } ?: "")) },
                                        rootFolder,
                                    ) { rootFolder = it; touched = true }
                                }
                            }
                        }
                    }
                }

                error?.let {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer, shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                        Text("Couldn't request: $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { close() }, enabled = !busy) { Text("Cancel") }
                Button(onClick = { submit() }, enabled = canSubmit) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(submitLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private fun canSubmitAtAll(m: MediaDetail, is4k: Boolean): Boolean =
    if (m.isMovie) (if (is4k) m.item.status4k else m.item.status) == Avail.None
    else m.seasons.any { (if (is4k) it.status4k else it.status) == Avail.None }

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
}

/** Labelled dropdown (options are value → label). */
@Composable
private fun Picker(label: String, options: List<Pair<String, String>>, value: String?, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedCard(onClick = { if (options.isNotEmpty()) open = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(options.firstOrNull { it.first == value }?.second ?: if (options.isEmpty()) "None available" else "Choose…",
                        style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Rounded.ArrowDropDown, null)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (v, l) ->
                DropdownMenuItem(text = { Text(l, maxLines = 2, overflow = TextOverflow.Ellipsis) }, onClick = { onPick(v); open = false })
            }
        }
    }
}
