package app.bosunarr.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Sailing
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalAutofillManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.bosunarr.app
import app.bosunarr.data.Credentials
import app.bosunarr.data.ServiceConfig
import app.bosunarr.data.ServiceType
import app.bosunarr.data.normalizeUrl
import app.bosunarr.net.userMessage
import app.bosunarr.ui.settings.ServiceBadge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private sealed interface Step {
    data object Welcome : Step
    data object Pick : Step
    data class Setup(val index: Int) : Step
    data object Done : Step
}

private data class Connected(val type: ServiceType, val name: String, val version: String?)

/**
 * First-run setup. Full screen (no bottom bar): a short welcome, pick the services you run, connect each one in
 * turn (connection is tested before saving), then a summary. Can be left at any point; Back steps back.
 */
@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    var step by remember { mutableStateOf<Step>(Step.Welcome) }
    var forward by remember { mutableStateOf(true) }
    val picked = rememberSaveable(saver = listSaver(), init = { mutableStateListOf<String>() })
    val connected = remember { mutableStateListOf<Connected>() }

    fun go(to: Step, fwd: Boolean = true) { forward = fwd; step = to }
    fun finish() { app.updateState { it.copy(onboarded = true) }; onFinish() }
    val types = picked.mapNotNull { ServiceType.of(it) }

    BackHandler(enabled = step != Step.Welcome) {
        when (val s = step) {
            Step.Pick -> go(Step.Welcome, false)
            is Step.Setup -> go(if (s.index == 0) Step.Pick else Step.Setup(s.index - 1), false)
            Step.Done -> go(Step.Setup((types.size - 1).coerceAtLeast(0)), false)
            Step.Welcome -> Unit
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val dir = if (forward) 1 else -1
                (slideInHorizontally(tween(300)) { dir * it / 5 } + fadeIn(tween(300))) togetherWith
                    (slideOutHorizontally(tween(250)) { -dir * it / 5 } + fadeOut(tween(200)))
            },
            label = "onboarding",
        ) { s ->
            when (s) {
                Step.Welcome -> WelcomeStep(onStart = { go(Step.Pick) }, onSkip = { finish() })
                Step.Pick -> PickStep(
                    picked = picked,
                    onBack = { go(Step.Welcome, false) },
                    onNext = { if (types.isEmpty()) finish() else go(Step.Setup(0)) },
                )
                is Step.Setup -> {
                    val type = types.getOrNull(s.index)
                    if (type == null) go(Step.Done) else SetupStep(
                        type = type,
                        index = s.index,
                        total = types.size,
                        onBack = { go(if (s.index == 0) Step.Pick else Step.Setup(s.index - 1), false) },
                        onDone = { c ->
                            if (c != null) { connected.removeAll { it.type == c.type }; connected += c }
                            go(if (s.index + 1 < types.size) Step.Setup(s.index + 1) else Step.Done)
                        },
                    )
                }
                Step.Done -> DoneStep(connected, onFinish = { finish() })
            }
        }
    }
}

private fun listSaver() = androidx.compose.runtime.saveable.listSaver<androidx.compose.runtime.snapshots.SnapshotStateList<String>, String>(
    save = { it.toList() },
    restore = { it.toMutableStateList() },
)

/* ------------------------------------------------------------------ welcome */

@Composable
private fun WelcomeStep(onStart: () -> Unit, onSkip: () -> Unit) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(56.dp))
        Box(
            Modifier.size(112.dp).clip(RoundedCornerShape(36.dp)).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Sailing, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(60.dp)) }
        Spacer(Modifier.height(28.dp))
        Text("Welcome to Bosunarr", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Your whole media stack, in one app.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(36.dp))
        Feature(Icons.Rounded.Widgets, "Everything in one place", "Sonarr, Radarr, requests, downloads, indexers, subtitles and your media server.")
        Feature(Icons.Rounded.PhoneAndroid, "Talks to your services directly", "No account and no cloud. Works on your home network or over a VPN like Tailscale.")
        Feature(Icons.Rounded.Lock, "Your keys stay on this phone", "Addresses and API keys are encrypted with a key that never leaves the device.")
        Spacer(Modifier.height(40.dp))
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Get started", style = MaterialTheme.typography.titleMedium) }
        TextButton(onClick = onSkip, modifier = Modifier.padding(vertical = 8.dp)) { Text("I'll set it up later") }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Feature(icon: ImageVector, title: String, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/* ------------------------------------------------------------------ pick */

@Composable
private fun StepHeader(title: String, subtitle: String, progress: Float, onBack: () -> Unit) {
    val p by animateFloatAsState(progress, tween(400), label = "progress")
    Column(Modifier.statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Spacer(Modifier.width(8.dp))
            LinearProgressIndicator(progress = { p }, modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)))
        }
        Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PickStep(picked: MutableList<String>, onBack: () -> Unit, onNext: () -> Unit) {
    val existing = remember { app.services.value.mapNotNull { it.serviceType }.toSet() }
    Column(Modifier.fillMaxSize()) {
        StepHeader("What do you run?", "Pick the services you want in Bosunarr. You can add more later in Settings.", 0.15f, onBack)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(ServiceType.entries, key = { it.id }) { t ->
                val on = t.id in picked
                val added = t in existing
                Surface(
                    onClick = { if (on) picked.remove(t.id) else picked.add(t.id) },
                    shape = RoundedCornerShape(20.dp),
                    color = if (on) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(if (on) 2.dp else 1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(Modifier.padding(16.dp).fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ServiceBadge(t)
                            Spacer(Modifier.weight(1f))
                            if (on) Icon(Icons.Rounded.CheckCircle, "Selected", tint = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(t.label, style = MaterialTheme.typography.titleSmall)
                        Text(if (added) "Already added" else t.role, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(8.dp)) }
        }
        Surface(tonalElevation = 2.dp) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (picked.isEmpty()) "Nothing selected" else "${picked.size} selected",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = onNext, enabled = picked.isNotEmpty(), modifier = Modifier.height(48.dp)) { Text("Continue") }
            }
        }
    }
}

/* ------------------------------------------------------------------ setup one service */

private sealed interface Probe {
    data object Idle : Probe
    data object Running : Probe
    data class Failed(val message: String) : Probe
}

@Composable
private fun SetupStep(type: ServiceType, index: Int, total: Int, onBack: () -> Unit, onDone: (Connected?) -> Unit) {
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val autofill = LocalAutofillManager.current
    // Keyed by type so going back/forward between services keeps each one's inputs.
    var url by rememberSaveable(type.id) { mutableStateOf("") }
    var altUrl by rememberSaveable(type.id) { mutableStateOf("") }
    var name by rememberSaveable(type.id) { mutableStateOf(type.label) }
    var apiKey by rememberSaveable(type.id) { mutableStateOf("") }
    var username by rememberSaveable(type.id) { mutableStateOf("") }
    var password by rememberSaveable(type.id) { mutableStateOf("") }
    var more by rememberSaveable(type.id) { mutableStateOf(false) }
    var show by remember(type.id) { mutableStateOf(false) }
    var probe by remember(type.id) { mutableStateOf<Probe>(Probe.Idle) }

    val ready = url.isNotBlank() && when (type.credentials) {
        Credentials.ApiKey -> apiKey.isNotBlank()
        Credentials.UserPass -> username.isNotBlank()
        Credentials.None -> true
    }

    fun config() = ServiceConfig(
        id = "",
        type = type.id,
        name = name.trim().ifEmpty { type.label },
        url = normalizeUrl(url),
        altUrl = normalizeUrl(altUrl).ifEmpty { null },
        apiKey = apiKey.trim().ifEmpty { null }.takeIf { type.credentials == Credentials.ApiKey },
        username = username.trim().ifEmpty { null }.takeIf { type.credentials == Credentials.UserPass },
        password = password.ifEmpty { null }.takeIf { type.credentials == Credentials.UserPass },
    )

    fun save(version: String?) = scope.launch {
        val saved = app.saveService(config())
        onDone(Connected(type, saved.name, version))
    }

    fun connect() {
        if (!ready || probe == Probe.Running) return
        focus.clearFocus()
        probe = Probe.Running
        scope.launch {
            val cfg = config().copy(id = "onboarding-${type.id}")
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

    val change = { _: String -> if (probe is Probe.Failed) probe = Probe.Idle }
    Column(Modifier.fillMaxSize().imePadding()) {
        StepHeader(
            "Connect ${type.label}",
            if (total > 1) "Service ${index + 1} of $total · ${type.role}" else type.role,
            0.15f + 0.75f * (index + 0.5f) / total,
            onBack,
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                url, { url = it; change(it) },
                label = { Text("Address") },
                placeholder = { Text("http://192.168.1.10:${type.defaultPort}") },
                supportingText = { Text("Include the port, or https://… for a reverse proxy") },
                singleLine = true,
                leadingIcon = { ServiceBadge(type, Modifier.size(28.dp)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            val eye = @Composable {
                IconButton(onClick = { show = !show }) { Icon(if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (show) "Hide" else "Show") }
            }
            val secretTransform = if (show) VisualTransformation.None else PasswordVisualTransformation()
            when (type.credentials) {
                Credentials.ApiKey -> OutlinedTextField(
                    apiKey, { apiKey = it; change(it) },
                    label = { Text("API key") },
                    supportingText = { Text(type.keyHint) },
                    singleLine = true,
                    visualTransformation = secretTransform,
                    trailingIcon = eye,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { connect() }),
                    modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Password },
                )
                Credentials.UserPass -> {
                    OutlinedTextField(
                        username, { username = it; change(it) },
                        label = { Text("Username") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Username },
                    )
                    OutlinedTextField(
                        password, { password = it; change(it) },
                        label = { Text("Password") },
                        supportingText = { Text(type.keyHint) },
                        singleLine = true,
                        visualTransformation = secretTransform,
                        trailingIcon = eye,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { connect() }),
                        modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Password },
                    )
                }
                Credentials.None -> Unit
            }

            TextButton(onClick = { more = !more }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                Icon(if (more) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
                Spacer(Modifier.width(4.dp))
                Text("More options")
            }
            AnimatedVisibility(more) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        altUrl, { altUrl = it; change(it) },
                        label = { Text("Fallback address") },
                        supportingText = { Text("Tried when the first one is unreachable, e.g. your Tailscale address") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            AnimatedVisibility(probe is Probe.Failed) {
                val msg = (probe as? Probe.Failed)?.message.orEmpty()
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
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
                        TextButton(onClick = { save(null) }, contentPadding = PaddingValues(0.dp)) { Text("Save anyway") }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        Surface(tonalElevation = 2.dp) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onDone(null) }) { Text("Skip") }
                Spacer(Modifier.weight(1f))
                Button(onClick = { connect() }, enabled = ready && probe != Probe.Running, modifier = Modifier.height(48.dp)) {
                    if (probe == Probe.Running) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(10.dp))
                        Text("Connecting…")
                    } else Text("Connect")
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ done */

@Composable
private fun DoneStep(connected: List<Connected>, onFinish: () -> Unit) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(72.dp))
        Box(Modifier.size(96.dp).clip(RoundedCornerShape(32.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(52.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text(if (connected.isEmpty()) "You're ready" else "You're all set", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            if (connected.isEmpty()) "Add services any time from Settings." else "Connected ${connected.size} ${if (connected.size == 1) "service" else "services"}. You can add or change them in Settings.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            connected.forEach { c ->
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        ServiceBadge(c.type)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.name, style = MaterialTheme.typography.titleSmall)
                            Text(c.version?.let { "Connected · ${if (it.startsWith("v")) it else "v$it"}" } ?: "Saved", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        Button(onClick = onFinish, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp).height(56.dp)) { Text("Open Bosunarr", style = MaterialTheme.typography.titleMedium) }
    }
}
