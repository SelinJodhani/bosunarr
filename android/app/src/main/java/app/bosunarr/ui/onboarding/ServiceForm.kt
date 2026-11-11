package app.bosunarr.ui.onboarding

import androidx.compose.material3.LocalContentColor
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalAutofillManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.bosunarr.app
import app.bosunarr.data.Credentials
import app.bosunarr.data.ServiceConfig
import app.bosunarr.data.ServiceType
import app.bosunarr.data.normalizeUrl
import app.bosunarr.feature.server.HostKeyDialog
import app.bosunarr.feature.server.HostKeyMismatch
import app.bosunarr.feature.server.HostKeyUnknown
import app.bosunarr.feature.server.ServerProbe
import app.bosunarr.feature.server.SshException
import app.bosunarr.feature.server.SshFormFields
import app.bosunarr.feature.server.SshTarget
import app.bosunarr.feature.server.sshHelp
import app.bosunarr.feature.services.okColor
import app.bosunarr.net.userMessage
import app.bosunarr.ui.settings.ServiceBadge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/* ------------------------------------------------------------------ form state */

/** What the user typed for one service: shared by onboarding, Settings → Add service and the edit screen. */
@Stable
internal class ServiceForm(
    val type: ServiceType,
    url: String,
    altUrl: String,
    name: String,
    apiKey: String,
    username: String,
    password: String,
    more: Boolean,
    sshHost: String = "",
    sshPort: String = "22",
    sshAuth: String = "key",
    sshKey: String = "",
    sshHostKey: String = "",
) {
    var url by mutableStateOf(url)
    var altUrl by mutableStateOf(altUrl)
    var name by mutableStateOf(name)
    var apiKey by mutableStateOf(apiKey)
    var username by mutableStateOf(username)
    var password by mutableStateOf(password)
    /** "More options" (name, fallback address) expanded. */
    var more by mutableStateOf(more)

    // Server (SSH). [username] is the SSH user; [password] the login password or the key's passphrase.
    var sshHost by mutableStateOf(sshHost)
    var sshPort by mutableStateOf(sshPort)
    /** "key" or "password". */
    var sshAuth by mutableStateOf(sshAuth)
    /** Private key text (OpenSSH/PEM). Kept out of saved instance state: see [saver]. */
    var sshKey by mutableStateOf(sshKey)
    /** Pinned host key ("<type> SHA256:..."), set when the user trusts it in the test dialog. */
    var sshHostKey by mutableStateOf(sshHostKey)

    private val sshTarget get() = SshTarget.parse(sshHost, sshPort.trim().toIntOrNull() ?: 22)

    val ready: Boolean
        get() = when (type.credentials) {
            Credentials.ApiKey -> url.isNotBlank() && apiKey.isNotBlank()
            Credentials.UserPass -> url.isNotBlank() && username.isNotBlank()
            Credentials.None -> url.isNotBlank()
            // Server: the address is host + port (the form's `url` isn't used).
            Credentials.Ssh -> sshTarget != null && username.isNotBlank() &&
                (if (sshAuth == "password") password.isNotEmpty() else sshKey.isNotBlank())
        }

    fun config(id: String, sort: Int = 0): ServiceConfig = if (type.credentials == Credentials.Ssh) sshConfig(id, sort) else ServiceConfig(
        id = id,
        type = type.id,
        name = name.trim().ifEmpty { type.label },
        url = normalizeUrl(url),
        altUrl = normalizeUrl(altUrl).ifEmpty { null },
        apiKey = apiKey.trim().ifEmpty { null }.takeIf { type.credentials == Credentials.ApiKey },
        username = username.trim().ifEmpty { null }.takeIf { type.credentials == Credentials.UserPass },
        password = password.ifEmpty { null }.takeIf { type.credentials == Credentials.UserPass },
        sort = sort,
    )

    private fun sshConfig(id: String, sort: Int): ServiceConfig {
        val port = sshPort.trim().toIntOrNull() ?: 22
        return ServiceConfig(
            id = id,
            type = type.id,
            name = name.trim().ifEmpty { type.label },
            url = sshTarget?.let { "ssh://${it.hostPort}" } ?: "ssh://${sshHost.trim()}",
            altUrl = SshTarget.parse(altUrl, port)?.let { "ssh://${it.hostPort}" },
            username = username.trim().ifEmpty { null },
            password = password.ifEmpty { null },
            sshAuth = sshAuth,
            // Kept in password mode too: switching back shouldn't lose a key that's already on the server.
            sshPrivateKey = sshKey.trim().ifEmpty { null }?.let { it + "\n" },
            sshHostKey = sshHostKey.ifBlank { null },
            sort = sort,
        )
    }

    companion object {
        /**
         * The private key never goes into the saved-state Bundle: only a token for an in-memory copy, so it survives
         * rotation but not process death — then [savedKey] (the key already in the encrypted store) is used, or a
         * key generated in an unsaved form has to be generated again.
         */
        fun saver(type: ServiceType, savedKey: String = "") = listSaver<ServiceForm, Any>(
            save = { listOf(it.url, it.altUrl, it.name, it.apiKey, it.username, it.password, it.more, it.sshHost, it.sshPort, it.sshAuth, PendingKeys.put(it.sshKey), it.sshHostKey) },
            restore = {
                ServiceForm(
                    type, it[0] as String, it[1] as String, it[2] as String, it[3] as String, it[4] as String, it[5] as String, it[6] as Boolean,
                    it[7] as String, it[8] as String, it[9] as String, PendingKeys.get(it[10] as String, savedKey), it[11] as String,
                )
            },
        )
    }
}

/** In-memory private keys of open forms, by opaque token (see [ServiceForm.saver]). */
private object PendingKeys {
    // Bounded: a token is minted on every state save, but only restored after a config change.
    private val keys = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 8
    }

    @Synchronized fun put(key: String): String {
        if (key.isEmpty()) return ""
        val token = java.util.UUID.randomUUID().toString()
        keys[token] = key
        return token
    }

    /** The key for [token]; [fallback] (the saved key) if there was one but it was lost with the process. */
    @Synchronized fun get(token: String, fallback: String): String = if (token.isEmpty()) "" else keys.remove(token) ?: fallback
}

/** Form for [type], pre-filled from [existing] when editing. Survives rotation and process death. */
@Composable
internal fun rememberServiceForm(type: ServiceType, existing: ServiceConfig? = null): ServiceForm =
    rememberSaveable(type.id, existing?.id, saver = ServiceForm.saver(type, existing?.sshPrivateKey?.trim().orEmpty())) {
        ServiceForm(
            type = type,
            url = existing?.url.orEmpty(),
            altUrl = existing?.altUrl.orEmpty(),
            name = existing?.name ?: type.label,
            apiKey = existing?.apiKey.orEmpty(),
            username = existing?.username.orEmpty(),
            password = existing?.password.orEmpty(),
            more = existing != null && (!existing.altUrl.isNullOrBlank() || existing.name != type.label),
            sshHost = existing?.takeIf { type.credentials == Credentials.Ssh }?.let { SshTarget.parse(it.url)?.host }.orEmpty(),
            sshPort = existing?.takeIf { type.credentials == Credentials.Ssh }?.let { SshTarget.parse(it.url)?.port?.toString() } ?: "22",
            sshAuth = existing?.sshAuth ?: "key",
            sshKey = existing?.sshPrivateKey?.trim().orEmpty(),
            sshHostKey = existing?.sshHostKey.orEmpty(),
        )
    }

/** Outcome of a connection test. */
internal sealed interface Probe {
    data object Idle : Probe
    data object Running : Probe
    data class Ok(val text: String) : Probe
    data class Failed(val message: String) : Probe
}

internal fun connectedText(version: String?, ms: Int?): String =
    listOfNotNull("Connected", version?.let { if (it.startsWith("v")) it else "v$it" }, ms?.let { "$it ms" }).joinToString(" · ")

/* ------------------------------------------------------------------ fields */

/**
 * Address (with the service's logo), credentials, and a collapsible "More options" (name, fallback address).
 * [onEdited] runs on any change that affects the connection (to clear a stale test result); [onSubmit] on the
 * keyboard's Done action.
 */
@Composable
internal fun ServiceFormFields(form: ServiceForm, onEdited: () -> Unit, onSubmit: () -> Unit, modifier: Modifier = Modifier) {
    val type = form.type
    var show by remember(type.id) { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (type.credentials == Credentials.Ssh) SshFormFields(form, onEdited, onSubmit)
        else OutlinedTextField(
            form.url, { form.url = it; onEdited() },
            label = { Text("Address") },
            placeholder = { Text("http://192.168.1.10:${type.defaultPort}") },
            supportingText = { Text("Include the port, or https://… for a reverse proxy") },
            singleLine = true,
            leadingIcon = { ServiceBadge(type, size = 28.dp) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        val eye = @Composable {
            IconButton(onClick = { show = !show }) { Icon(if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (show) "Hide" else "Show") }
        }
        val secretTransform = if (show) VisualTransformation.None else PasswordVisualTransformation()
        when (type.credentials) {
            Credentials.ApiKey -> OutlinedTextField(
                form.apiKey, { form.apiKey = it; onEdited() },
                label = { Text("API key") },
                supportingText = { Text(type.keyHint) },
                singleLine = true,
                visualTransformation = secretTransform,
                trailingIcon = eye,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Password },
            )
            Credentials.UserPass -> {
                OutlinedTextField(
                    form.username, { form.username = it; onEdited() },
                    label = { Text("Username") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Username },
                )
                OutlinedTextField(
                    form.password, { form.password = it; onEdited() },
                    label = { Text("Password") },
                    supportingText = { Text(type.keyHint) },
                    singleLine = true,
                    visualTransformation = secretTransform,
                    trailingIcon = eye,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                    modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Password },
                )
            }
            Credentials.None, Credentials.Ssh -> Unit
        }

        TextButton(onClick = { form.more = !form.more }, contentPadding = PaddingValues(horizontal = 4.dp)) {
            Icon(if (form.more) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
            Spacer(Modifier.width(4.dp))
            Text("More options")
        }
        AnimatedVisibility(form.more, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(form.name, { form.name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    form.altUrl, { form.altUrl = it; onEdited() },
                    label = { Text("Fallback address") },
                    placeholder = if (type.credentials == Credentials.Ssh) ({ Text("my-server.tailnet.ts.net") }) else null,
                    supportingText = { Text("Tried when the first one is unreachable, e.g. your Tailscale address") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/* ------------------------------------------------------------------ result */

/** Inline result of a connection test: green "Connected · v4.0 · 120 ms" or a red panel with a hint. */
@Composable
internal fun ProbeResult(probe: Probe, type: ServiceType, onSaveAnyway: (() -> Unit)? = null) {
    // Keep the last result while the panel animates out, so its text doesn't vanish first.
    var last by remember { mutableStateOf<Probe>(Probe.Idle) }
    if (probe is Probe.Ok || probe is Probe.Failed) last = probe
    val visible = probe is Probe.Ok || probe is Probe.Failed
    AnimatedVisibility(visible, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        when (val r = last) {
            is Probe.Ok -> Surface(color = okColor().copy(alpha = 0.14f), shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = okColor())
                    Spacer(Modifier.width(12.dp))
                    Text(r.text, style = MaterialTheme.typography.titleSmall)
                }
            }
            is Probe.Failed -> FailurePanel(r.message, type, onSaveAnyway)
            else -> Unit
        }
    }
}

@Composable
private fun FailurePanel(msg: String, type: ServiceType, onSaveAnyway: (() -> Unit)?) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = if (onSaveAnyway != null) 4.dp else 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Error, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.width(8.dp))
                Text("Couldn't connect", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
            }
            Spacer(Modifier.height(4.dp))
            Text(msg, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            val help = when {
                type.credentials == Credentials.Ssh -> sshHelp(msg)
                msg.contains("timed out") || msg.contains("unreachable") ->
                    "Check the address and port, and that this phone can reach it — same Wi-Fi, or your VPN (e.g. Tailscale) is on."
                msg.contains("credentials") || msg.contains("login failed") -> "Double-check the ${if (type.credentials == Credentials.UserPass) "username and password" else "API key"}."
                msg.contains("web page") || msg.contains("redirected") -> "That address serves a web page, not the ${type.label} API. Check the port and any base path (like /${type.id})."
                else -> null
            }
            if (help != null) {
                Spacer(Modifier.height(4.dp))
                Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f))
            }
            if (onSaveAnyway != null) TextButton(onClick = onSaveAnyway, contentPadding = PaddingValues(0.dp)) { Text("Save anyway") }
        }
    }
}

/* ------------------------------------------------------------------ testing */

internal data class ProbeOk(val text: String, val version: String?)

/**
 * "Test"/"Connect" for any type. Server goes over SSH: it throws [HostKeyUnknown] / [HostKeyMismatch] for the
 * caller to ask the user (see [HostKeyDialog]); the others use the HTTP probe.
 */
internal suspend fun probeService(cfg: ServiceConfig): ProbeOk {
    if (cfg.serviceType == ServiceType.Server) {
        val r = ServerProbe.test(cfg)
        return ProbeOk("Connected · ${r.ms} ms\n${r.uname}", r.uname.split(' ').getOrNull(2))
    }
    val r = app.client.test(cfg)
    return ProbeOk(connectedText(r.version, r.ms), r.version)
}

/* ------------------------------------------------------------------ setup one service */

internal data class Connected(val type: ServiceType, val name: String, val version: String?)

/**
 * Connect one service: fields, then Connect tests it and saves only when it answers (or "Save anyway").
 * Used for each service in onboarding ([progress] set), and for Settings → Add service ([progress] null).
 */
@Composable
internal fun SetupStep(
    type: ServiceType,
    title: String,
    subtitle: String,
    progress: Float?,
    onBack: () -> Unit,
    onDone: (Connected?) -> Unit,
    skipLabel: String = "Skip",
) {
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val autofill = LocalAutofillManager.current
    // Keyed by type so going back/forward between services keeps each one's inputs.
    val form = rememberServiceForm(type)
    var probe by remember(type.id) { mutableStateOf<Probe>(Probe.Idle) }
    var hostKeyQuestion by remember(type.id) { mutableStateOf<SshException?>(null) }

    fun save(version: String?) = scope.launch {
        val saved = app.saveService(form.config(id = ""))
        onDone(Connected(type, saved.name, version))
    }

    fun connect() {
        if (!form.ready || probe == Probe.Running) return
        focus.clearFocus()
        probe = Probe.Running
        scope.launch {
            val cfg = form.config(id = "onboarding-${type.id}")
            app.client.reset(cfg.id)
            try {
                val r = probeService(cfg)
                // Only now offer to save the key to the password manager: it's known to work.
                autofill?.commit()
                save(r.version)
            } catch (e: CancellationException) {
                throw e
            } catch (e: HostKeyUnknown) {
                probe = Probe.Idle; hostKeyQuestion = e
            } catch (e: HostKeyMismatch) {
                probe = Probe.Idle; hostKeyQuestion = e
            } catch (e: Exception) {
                probe = Probe.Failed(e.userMessage())
            }
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        StepHeader(title, subtitle, progress, onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ServiceFormFields(form, onEdited = { if (probe is Probe.Failed) probe = Probe.Idle }, onSubmit = { connect() })
            ProbeResult(probe, type, onSaveAnyway = { save(null) })
            Spacer(Modifier.height(16.dp))
        }
        Surface(tonalElevation = 2.dp) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onDone(null) }) { Text(skipLabel) }
                Spacer(Modifier.weight(1f))
                Button(onClick = { connect() }, enabled = form.ready && probe != Probe.Running, modifier = Modifier.height(48.dp)) {
                    if (probe == Probe.Running) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                        Spacer(Modifier.width(10.dp))
                        Text("Connecting…")
                    } else Text("Connect")
                }
            }
        }
    }

    hostKeyQuestion?.let { q ->
        HostKeyDialog(
            q,
            onTrust = { pin -> form.sshHostKey = pin.encode(); hostKeyQuestion = null; connect() },
            onDismiss = { hostKeyQuestion = null; probe = Probe.Failed(q.message ?: "Host key not trusted") },
        )
    }
}
