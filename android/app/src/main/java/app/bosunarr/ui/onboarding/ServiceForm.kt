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
) {
    var url by mutableStateOf(url)
    var altUrl by mutableStateOf(altUrl)
    var name by mutableStateOf(name)
    var apiKey by mutableStateOf(apiKey)
    var username by mutableStateOf(username)
    var password by mutableStateOf(password)
    /** "More options" (name, fallback address) expanded. */
    var more by mutableStateOf(more)

    val ready: Boolean
        get() = url.isNotBlank() && when (type.credentials) {
            Credentials.ApiKey -> apiKey.isNotBlank()
            Credentials.UserPass -> username.isNotBlank()
            Credentials.None -> true
        }

    fun config(id: String, sort: Int = 0) = ServiceConfig(
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

    companion object {
        fun saver(type: ServiceType) = listSaver<ServiceForm, Any>(
            save = { listOf(it.url, it.altUrl, it.name, it.apiKey, it.username, it.password, it.more) },
            restore = { ServiceForm(type, it[0] as String, it[1] as String, it[2] as String, it[3] as String, it[4] as String, it[5] as String, it[6] as Boolean) },
        )
    }
}

/** Form for [type], pre-filled from [existing] when editing. Survives rotation and process death. */
@Composable
internal fun rememberServiceForm(type: ServiceType, existing: ServiceConfig? = null): ServiceForm =
    rememberSaveable(type.id, existing?.id, saver = ServiceForm.saver(type)) {
        ServiceForm(
            type = type,
            url = existing?.url.orEmpty(),
            altUrl = existing?.altUrl.orEmpty(),
            name = existing?.name ?: type.label,
            apiKey = existing?.apiKey.orEmpty(),
            username = existing?.username.orEmpty(),
            password = existing?.password.orEmpty(),
            more = existing != null && (!existing.altUrl.isNullOrBlank() || existing.name != type.label),
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
        OutlinedTextField(
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
            Credentials.None -> Unit
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
                val r = app.client.test(cfg)
                // Only now offer to save the key to the password manager: it's known to work.
                autofill?.commit()
                save(r.version)
            } catch (e: CancellationException) {
                throw e
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
}
