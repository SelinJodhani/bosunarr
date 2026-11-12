package app.bosunarr.security

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.bosunarr.data.ServiceConfig
import app.bosunarr.ui.onboarding.ServiceForm
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The form's copy of a service's reverse-proxy extras (see [ProxyAuth]). */
@Serializable
internal data class ProxyExtras(
    val headers: List<Pair<String, String>> = emptyList(),
    val user: String = "",
    val password: String = "",
) {
    fun isEmpty() = headers.isEmpty() && user.isEmpty() && password.isEmpty()
    fun encode(): String = if (isEmpty()) "" else json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun of(s: ServiceConfig?) = ProxyExtras(s?.headers?.toList().orEmpty(), s?.basicUser.orEmpty(), s?.basicPassword.orEmpty())
        fun decode(text: String): ProxyExtras = if (text.isEmpty()) ProxyExtras() else runCatching { json.decodeFromString(serializer(), text) }.getOrElse { ProxyExtras() }
    }
}

/**
 * "Reverse proxy" part of a service's More options: custom header rows (values masked, per-row show toggle,
 * a Cloudflare Access preset) and basic auth. Values are never logged.
 */
@Composable
internal fun ProxyAuthFields(form: ServiceForm, onEdited: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Shield, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text("Reverse proxy", style = MaterialTheme.typography.titleSmall)
        }
        Text(
            "Only if a proxy in front of ${form.type.label} asks for its own login. Sent with every request to this service, never to other hosts.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text("Custom headers", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
        form.headers.forEachIndexed { i, (name, value) ->
            HeaderRow(
                name = name,
                value = value,
                duplicate = name.isNotBlank() && form.headers.take(i).any { it.first.trim().equals(name.trim(), ignoreCase = true) },
                onName = { n -> form.headers = form.headers.toMutableList().also { it[i] = n to value }; onEdited() },
                onValue = { v -> form.headers = form.headers.toMutableList().also { it[i] = name to v }; onEdited() },
                onRemove = { form.headers = form.headers.toMutableList().also { it.removeAt(i) }; onEdited() },
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = { form.headers = form.headers + ("" to ""); onEdited() },
                label = { Text("Add header") },
                leadingIcon = { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)) },
            )
            val missing = ProxyAuth.CLOUDFLARE_ACCESS.filter { h -> form.headers.none { it.first.trim().equals(h, ignoreCase = true) } }
            if (missing.isNotEmpty()) AssistChip(
                onClick = { form.headers = form.headers.filterNot { (n, v) -> n.isBlank() && v.isBlank() } + missing.map { it to "" }; onEdited() },
                label = { Text("Cloudflare Access") },
                leadingIcon = { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)) },
            )
        }

        Text("Basic auth", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        val userError = ProxyAuth.basicUserError(form.basicUser.trim())
        OutlinedTextField(
            form.basicUser, { form.basicUser = it; onEdited() },
            label = { Text("Proxy username") },
            singleLine = true,
            isError = userError != null,
            supportingText = userError?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        var showPw by remember { mutableStateOf(false) }
        val pwError = ProxyAuth.basicPasswordError(form.basicPassword)
        OutlinedTextField(
            form.basicPassword, { form.basicPassword = it; onEdited() },
            label = { Text("Proxy password") },
            singleLine = true,
            enabled = form.basicUser.isNotBlank() || form.basicPassword.isNotEmpty(),
            isError = pwError != null,
            supportingText = { Text(pwError ?: "Sent as HTTP basic auth (Authorization header)") },
            visualTransformation = if (showPw) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { Eye(showPw) { showPw = !showPw } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Eye(show: Boolean, toggle: () -> Unit) {
    IconButton(onClick = toggle) { Icon(if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (show) "Hide" else "Show") }
}

@Composable
private fun HeaderRow(name: String, value: String, duplicate: Boolean, onName: (String) -> Unit, onValue: (String) -> Unit, onRemove: () -> Unit) {
    var show by remember { mutableStateOf(false) }
    val nameError = if (name.isBlank() && value.isBlank()) null else ProxyAuth.nameError(name.trim()) ?: if (duplicate) "Already listed" else null
    val valueError = ProxyAuth.valueError(value)
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                name, onName,
                label = { Text("Header") },
                placeholder = { Text("X-Custom-Header") },
                singleLine = true,
                isError = nameError != null,
                supportingText = nameError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value, onValue,
                label = { Text("Value") },
                singleLine = true,
                isError = valueError != null,
                supportingText = valueError?.let { { Text(it) } },
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { Eye(show) { show = !show } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        IconButton(onClick = onRemove, modifier = Modifier.padding(top = 8.dp)) { Icon(Icons.Rounded.Close, "Remove header") }
    }
}
