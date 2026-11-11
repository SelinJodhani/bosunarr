package app.bosunarr.feature.services

import app.bosunarr.ui.common.toggleRow
import app.bosunarr.ui.common.MessageBox
import androidx.compose.material.icons.rounded.Group
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import app.bosunarr.app
import app.bosunarr.data.ServiceConfig
import app.bosunarr.net.ServiceException
import app.bosunarr.ui.common.ConfirmDialog
import app.bosunarr.ui.common.Loader
import app.bosunarr.ui.common.Tag
import app.bosunarr.ui.common.date
import app.bosunarr.ui.common.parseInstant
import app.bosunarr.ui.common.plural
import app.bosunarr.ui.common.relative
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Instant

/*
 * Wizarr REST API (`/api`, header X-API-Key):
 *   GET    /api/status            {users, invites, pending, expired}
 *   GET    /api/users             {users: [...]}
 *   GET    /api/invitations       {invitations: [...]}
 *   POST   /api/invitations       {server_ids, expires_in_days (1|7|30|omitted = never), duration ("unlimited"|days), unlimited, library_ids}
 *   DELETE /api/invitations/:id
 *   GET    /api/libraries, /api/servers
 */

@Serializable
private data class WzStatus(val users: Int? = null, val invites: Int? = null, val pending: Int? = null, val expired: Int? = null)

@Serializable
private data class WzUser(
    val id: Int = 0,
    val username: String? = null,
    val email: String? = null,
    val server: String? = null,
    @SerialName("server_type") val serverType: String? = null,
    val expires: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
private data class WzInvite(
    val id: Int = 0,
    val code: String = "",
    val url: String? = null,
    val status: String? = null,
    val created: String? = null,
    val expires: String? = null,
    @SerialName("used_at") val usedAt: String? = null,
    @SerialName("used_by") val usedBy: String? = null,
    val duration: JsonElement? = null,
    val unlimited: Boolean? = null,
    @SerialName("specific_libraries") val libraries: List<Int> = emptyList(),
    @SerialName("server_names") val serverNames: List<String> = emptyList(),
    @SerialName("display_name") val displayName: String? = null,
)

@Serializable
private data class WzLibrary(val id: Int = 0, val name: String? = null, @SerialName("server_id") val serverId: Int? = null, val enabled: Boolean = true)

@Serializable
private data class WzServer(val id: Int = 0, val name: String? = null, @SerialName("server_type") val type: String? = null, val verified: Boolean = true)

@Serializable private data class WzUsers(val users: List<WzUser> = emptyList())
@Serializable private data class WzInvites(val invitations: List<WzInvite> = emptyList())
@Serializable private data class WzLibraries(val libraries: List<WzLibrary> = emptyList())
@Serializable private data class WzServers(val servers: List<WzServer> = emptyList())
@Serializable private data class WzCreated(val invitation: WzInvite? = null)

/** Wizarr returns naive UTC timestamps ("2026-11-10T12:00:00"): make them unambiguous. */
private fun wzInstant(v: String?): Instant? {
    if (v.isNullOrBlank()) return null
    val zoned = if (Regex("[zZ]$|[+-]\\d\\d:?\\d\\d$").containsMatchIn(v)) v else "${v}Z"
    return parseInstant(zoned.replace(' ', 'T'))
}

private data class Invite(
    val id: Int,
    val code: String,
    val path: String,
    val status: String,
    val active: Boolean,
    val unlimited: Boolean,
    val created: Instant?,
    val expires: Instant?,
    val usedAt: Instant?,
    val usedBy: String?,
    val durationDays: Int?,
    val libraries: List<String>,
    val servers: List<String>,
)

private data class WizarrOverview(
    val users: Int,
    val invites: Int,
    val pending: Int,
    val expired: Int,
    val userList: List<WzUser>,
    val invitations: List<Invite>,
    val servers: List<WzServer>,
    val libraries: List<WzLibrary>,
    val warnings: List<String>,
)

/** Join-page path: only a plain relative path from Wizarr, else the default `/j/<code>`. */
private fun joinPath(code: String, url: String?): String =
    url?.takeIf { Regex("^/[A-Za-z0-9/_-]+$").matches(it) } ?: "/j/${enc(code)}"

private fun mapInvite(r: WzInvite, libNames: Map<Int, String>): Invite {
    val status = if (r.status == "used" || r.status == "expired") r.status else "pending"
    val expires = wzInstant(r.expires)
    val unlimited = r.unlimited == true
    val notExpired = expires == null || expires.isAfter(Instant.now())
    val dur = (r.duration as? JsonPrimitive)?.content?.takeIf { Regex("^\\d+$").matches(it) }?.toIntOrNull()
    return Invite(
        id = r.id,
        code = r.code,
        path = joinPath(r.code, r.url),
        status = status,
        active = status == "pending" || (unlimited && status == "used" && notExpired),
        unlimited = unlimited,
        created = wzInstant(r.created),
        expires = expires,
        usedAt = wzInstant(r.usedAt),
        usedBy = r.usedBy?.takeIf { it.isNotBlank() },
        durationDays = dur,
        libraries = r.libraries.map { libNames[it] ?: "Library $it" },
        servers = r.serverNames.ifEmpty { listOfNotNull(r.displayName?.takeIf { it.isNotBlank() }) },
    )
}

private suspend fun loadWizarr(s: ServiceConfig): WizarrOverview = coroutineScope {
    val c = app.client
    val status = async { runCatching { c.get<WzStatus>(s, "/api/status") }.getOrNull() }
    val users = async { c.get<WzUsers>(s, "/api/users").users }
    val invites = async { c.get<WzInvites>(s, "/api/invitations").invitations }
    val libraries = async { runCatching { c.get<WzLibraries>(s, "/api/libraries").libraries } }
    val servers = async { runCatching { c.get<WzServers>(s, "/api/servers").servers } }
    val warnings = mutableListOf<String>()
    val libs = libraries.await().getOrElse { warnings += "libraries"; emptyList() }
    val srvs = servers.await().getOrElse { warnings += "servers"; emptyList() }
    val names = libs.associate { it.id to (it.name ?: "Library ${it.id}") }
    val inv = invites.await().filter { it.code.isNotBlank() }.map { mapInvite(it, names) }.sortedByDescending { it.created ?: Instant.EPOCH }
    val ul = users.await()
    val st = status.await()
    val total = st?.invites ?: inv.size
    WizarrOverview(
        users = st?.users ?: ul.size,
        invites = total,
        pending = st?.pending ?: inv.count { it.status == "pending" },
        expired = st?.expired ?: inv.count { it.status == "expired" },
        userList = ul.sortedBy { it.username?.lowercase() },
        invitations = inv,
        servers = srvs,
        libraries = libs,
        warnings = warnings,
    )
}

/** Public base for invite links: prefer an https address (the one invitees can reach), else the main URL. */
private fun inviteBase(s: ServiceConfig): String =
    listOfNotNull(s.url, s.altUrl).firstOrNull { it.startsWith("https://") } ?: s.url

private fun copy(ctx: Context, label: String, text: String): Boolean = runCatching {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
    true
}.getOrDefault(false)

private fun share(ctx: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
    runCatching { ctx.startActivity(Intent.createChooser(send, "Share invite").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/* ================================================================ screen */

private data class CreatedInvite(val code: String, val link: String)

@Composable
fun WizarrServiceScreen(s: ServiceConfig, nav: NavController) {
    val hub = rememberRefreshHub()
    val loader = rememberHubLoader(hub, "svc:wizarr:${s.id}") { loadWizarr(s) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var created by remember { mutableStateOf<CreatedInvite?>(null) }
    val o = loader.value

    ServiceFrame(
        s, nav,
        floatingActionButton = {
            if (o != null) ExtendedFloatingActionButton(onClick = { creating = true }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("New invite") })
        },
    ) { p ->
        HubRefreshBox(hub, Modifier.padding(p)) {
            TabbedBody(listOf("Invitations", "Users")) { tab ->
                when (tab) {
                    0 -> InvitesTab(s, loader)
                    else -> UsersTab(loader)
                }
            }
        }
    }

    if (creating && o != null) {
        CreateInviteSheet(s, o, onDismiss = { creating = false }, onCreated = {
            creating = false
            created = it
            loader.refresh()
        })
    }
    created?.let { c ->
        val ctx = LocalContext.current
        val runner = rememberActionRunner()
        AlertDialog(
            onDismissRequest = { created = null },
            title = { Text("Invite created") },
            text = {
                Column {
                    Text("Send this link to the person you're inviting:")
                    Spacer(Modifier.height(12.dp))
                    Text(c.link, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(4.dp))
                    Text("Code ${c.code}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (copy(ctx, "Invite link", c.link) && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) runner.toast("Link copied")
                    created = null
                }) { Text("Copy link") }
            },
            dismissButton = {
                TextButton(onClick = { share(ctx, c.link); created = null }) { Text("Share") }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InvitesTab(s: ServiceConfig, loader: Loader<WizarrOverview>) {
    val runner = rememberActionRunner()
    val ctx = LocalContext.current
    var deleting by remember { mutableStateOf<Invite?>(null) }
    val st = loader.state
    val o = st.value
    LazyColumn(contentPadding = PaddingValues(top = 12.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item("stats") {
            if (o == null) StatRow { repeat(4) { SkeletonBox(Modifier.weight(1f).height(64.dp), RoundedCornerShape(16.dp)) } }
            else StatRow {
                StatTile("Users", o.users.toString(), Modifier.weight(1f))
                StatTile("Invites", o.invites.toString(), Modifier.weight(1f))
                StatTile("Pending", o.pending.toString(), Modifier.weight(1f), tint = if (o.pending > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                StatTile("Expired", o.expired.toString(), Modifier.weight(1f))
            }
        }
        when {
            o != null -> {
                if (st.error != null) item("err") { InlineError(st.error, compact = true) }
                if (o.warnings.isNotEmpty()) item("warn") { InlineError("Couldn't load ${o.warnings.joinToString(", ")}", compact = true) }
                if (o.invitations.isEmpty()) item("empty") { MessageBox(Icons.Rounded.Share, "No invitations yet", hint = "Create one to let someone join your server.") }
                items(o.invitations, key = { it.id }) { inv ->
                    InviteCard(
                        inv,
                        busy = runner.isBusy("wizarr:${s.id}:del:${inv.id}"),
                        onCopy = {
                            val link = inviteBase(s).trimEnd('/') + inv.path
                            if (copy(ctx, "Invite link", link) && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) runner.toast("Link copied")
                        },
                        onShare = { share(ctx, inviteBase(s).trimEnd('/') + inv.path) },
                        onDelete = { deleting = inv },
                    )
                }
            }
            st.error != null -> item("err") { InlineError(st.error, onRetry = { loader.refresh() }) }
            else -> item("skeleton") { SkeletonRows(3, rowHeight = 96.dp) }
        }
    }
    deleting?.let { inv ->
        ConfirmDialog(
            title = "Delete invite ${inv.code}?",
            text = if (inv.active) "The link stops working immediately. People who already joined keep their accounts." else "This removes it from Wizarr's list.",
            confirm = "Delete",
            destructive = true,
            onConfirm = {
                runner.run("wizarr:${s.id}:del:${inv.id}", success = "Invite deleted", after = { loader.refresh() }) {
                    app.client.send(s, "/api/invitations/${inv.id}", "DELETE")
                    null
                }
            },
            onDismiss = { deleting = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InviteCard(inv: Invite, busy: Boolean, onCopy: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(inv.code, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                val (label, bg, fg) = when {
                    inv.status == "expired" -> Triple("Expired", MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant)
                    inv.status == "used" && inv.active -> Triple("Active", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                    inv.status == "used" -> Triple("Used", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                    else -> Triple("Pending", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                }
                Tag(label, container = bg, content = fg, modifier = Modifier.padding(end = 12.dp))
            }
            val lines = listOfNotNull(
                when {
                    inv.expires == null -> "Never expires"
                    inv.expires.isAfter(Instant.now()) -> "Expires ${relative(inv.expires)}"
                    else -> "Expired ${relative(inv.expires)}"
                },
                if (inv.unlimited) "Multi-use" else "Single use",
                inv.durationDays?.let { "Access for ${plural(it, "day")}" } ?: "Permanent access",
            )
            Text(lines.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp, end = 12.dp))
            if (inv.usedBy != null) Text(
                "Used by ${inv.usedBy}" + (inv.usedAt?.let { " · ${relative(it)}" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp, end = 12.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val scope = inv.servers + inv.libraries
            if (scope.isNotEmpty()) FlowRow(Modifier.padding(top = 6.dp, end = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                inv.servers.forEach { Tag(it) }
                inv.libraries.forEach { Tag(it, container = MaterialTheme.colorScheme.surfaceContainerHighest, content = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    inv.created?.let { "Created ${date(it.toString())}" }.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (inv.active) {
                    IconButton(onClick = onCopy) { Icon(Icons.Rounded.ContentCopy, "Copy link") }
                    IconButton(onClick = onShare) { Icon(Icons.Rounded.Share, "Share link") }
                }
                IconButton(onClick = onDelete, enabled = !busy) { Icon(Icons.Rounded.Delete, "Delete invite") }
            }
        }
    }
}

@Composable
private fun UsersTab(loader: Loader<WizarrOverview>) {
    val st = loader.state
    val o = st.value
    LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp)) {
        when {
            o != null -> {
                if (st.error != null) item("err") { InlineError(st.error, compact = true) }
                if (o.userList.isEmpty()) item("empty") { MessageBox(Icons.Rounded.Group, "No users yet", hint = "People who join through a Wizarr invitation show up here.") }
                items(o.userList, key = { it.id }) { u ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(u.username ?: "User ${u.id}", style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            u.server?.takeIf { it.isNotBlank() }?.let { Tag(it) }
                        }
                        // Wizarr stores "empty"/"None" when no email was given.
                        val email = u.email?.takeIf { it.contains('@') }
                        val exp = wzInstant(u.expires)
                        val meta = listOfNotNull(
                            email,
                            wzInstant(u.createdAt)?.let { "Joined ${date(it.toString())}" },
                            exp?.let { if (it.isAfter(Instant.now())) "Access ends ${relative(it)}" else "Access ended" },
                        )
                        if (meta.isNotEmpty()) Text(
                            meta.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (exp != null && !exp.isAfter(Instant.now())) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            st.error != null -> item("err") { InlineError(st.error, onRetry = { loader.refresh() }) }
            else -> item("skeleton") { SkeletonRows(6, rowHeight = 40.dp) }
        }
    }
}

/* ================================================================ create */

private val EXPIRY = listOf(1 to "1 day", 7 to "7 days", 30 to "30 days", 0 to "Never")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CreateInviteSheet(s: ServiceConfig, o: WizarrOverview, onDismiss: () -> Unit, onCreated: (CreatedInvite) -> Unit) {
    val usable = o.servers.filter { it.verified }
    val serverIds = remember { mutableStateListOf<Int>().apply { if (usable.size == 1) add(usable[0].id) } }
    val libraryIds = remember { mutableStateListOf<Int>() }
    var expiry by rememberSaveable { mutableStateOf(7) }
    var duration by rememberSaveable { mutableStateOf("") }
    var unlimited by rememberSaveable { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != androidx.compose.material3.SheetValue.Hidden || !busy })
    val runner = rememberActionRunner()

    val libs = o.libraries.filter { it.enabled && (it.serverId == null || it.serverId in serverIds) }
    val durationError = duration.isNotBlank() && (duration.toIntOrNull()?.let { it !in 1..3650 } ?: true)
    val canCreate = serverIds.isNotEmpty() && !durationError && !busy

    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("New invite", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))

            Text("Servers", style = MaterialTheme.typography.titleSmall)
            if (usable.isEmpty()) Text("Wizarr has no verified media server. Add one in Wizarr first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                usable.forEach { sv ->
                    FilterChip(
                        selected = sv.id in serverIds,
                        onClick = {
                            if (sv.id in serverIds) serverIds.remove(sv.id) else serverIds.add(sv.id)
                            // Drop libraries that no longer belong to a chosen server.
                            val allowed = o.libraries.filter { it.serverId == null || it.serverId in serverIds }.map { it.id }.toSet()
                            libraryIds.retainAll { it in allowed }
                        },
                        label = { Text(listOfNotNull(sv.name ?: "Server ${sv.id}", sv.type?.replaceFirstChar { it.uppercase() }).joinToString(" · ")) },
                    )
                }
            }

            if (libs.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Libraries", style = MaterialTheme.typography.titleSmall)
                Text("None selected gives access to all libraries.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    libs.forEach { l ->
                        FilterChip(
                            selected = l.id in libraryIds,
                            onClick = { if (l.id in libraryIds) libraryIds.remove(l.id) else libraryIds.add(l.id) },
                            label = { Text(l.name ?: "Library ${l.id}") },
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("Invite expires", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                EXPIRY.forEachIndexed { i, (d, label) ->
                    SegmentedButton(
                        selected = expiry == d,
                        onClick = { expiry = d },
                        shape = SegmentedButtonDefaults.itemShape(i, EXPIRY.size),
                        label = { Text(label, maxLines = 1) },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = duration,
                onValueChange = { v -> if (v.length <= 4 && v.all { it.isDigit() }) duration = v },
                label = { Text("Access (days)") },
                supportingText = { Text(if (durationError) "Enter a number from 1 to 3650." else "How long new users keep access. Leave blank for forever.") },
                isError = durationError,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).toggleRow(unlimited) { unlimited = it }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Unlimited uses", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (unlimited) "Anyone with the link can join until it expires." else "The first person to join uses it up.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = unlimited, onCheckedChange = null)
            }

            error?.let { InlineError(it, Modifier.padding(top = 4.dp)) }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { confirming = true }, enabled = canCreate) {
                    if (busy) androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    else Text("Create invite")
                }
            }
        }
    }

    if (confirming) {
        val summary = listOf(
            EXPIRY.first { it.first == expiry }.second.let { if (expiry == 0) "Never expires" else "Expires in $it" },
            if (unlimited) "multi-use" else "single use",
            duration.toIntOrNull()?.let { "access for ${plural(it, "day")}" } ?: "permanent access",
            if (libraryIds.isEmpty()) "all libraries" else plural(libraryIds.size, "library", "libraries"),
        ).joinToString(" · ")
        ConfirmDialog(
            title = "Create this invite?",
            text = summary,
            confirm = "Create",
            onConfirm = {
                busy = true
                error = null
                val days = duration.toIntOrNull()
                val servers = serverIds.toList()
                val libraries = libraryIds.toList()
                val exp = expiry
                val multi = unlimited
                runner.run("wizarr:${s.id}:create", after = { busy = false }) {
                    try {
                        val body = buildJsonObject {
                            putJsonArray("server_ids") { servers.forEach { add(it) } }
                            put("duration", days?.toString() ?: "unlimited")
                            put("unlimited", multi)
                            putJsonArray("library_ids") { libraries.forEach { add(it) } }
                            if (exp != 0) put("expires_in_days", exp)
                        }
                        val text = app.client.send(s, "/api/invitations", "POST", body)
                        val inv = runCatching { app.client.decode(s, text, WzCreated.serializer()).invitation }.getOrNull()
                            ?: throw ServiceException("Wizarr did not return the new invite")
                        onCreated(CreatedInvite(inv.code, inviteBase(s).trimEnd('/') + joinPath(inv.code, inv.url)))
                        null
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        error = (e as? ServiceException)?.message ?: "Couldn't create the invite"
                        throw e
                    }
                }
            },
            onDismiss = { confirming = false },
        )
    }
}
