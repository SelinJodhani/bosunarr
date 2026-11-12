package app.bosunarr.feature.plex

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.bosunarr.ui.common.friendlyMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

private sealed interface SignIn {
    data object Idle : SignIn
    data object Starting : SignIn
    data class Waiting(val pin: PlexPin) : SignIn
    data class Choose(val authToken: String, val servers: List<PlexServerChoice>) : SignIn
    data class Done(val server: String?) : SignIn
    data class Failed(val message: String) : SignIn
}

/**
 * "Sign in with Plex" on the setup form: gets a token through the plex.tv PIN flow so nobody has to dig it out of
 * Plex Web's XML view. [onSignedIn] gets the server's token and, when the account has a server, its address.
 */
@Composable
fun PlexSignIn(onSignedIn: (token: String, url: String?) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val clientId = remember { PlexHeaders.installId(context) }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<SignIn>(SignIn.Idle) }

    fun pick(authToken: String, server: PlexServerChoice?) {
        onSignedIn(server?.token ?: authToken, server?.url)
        state = SignIn.Done(server?.name)
    }

    fun start() {
        state = SignIn.Starting
        scope.launch {
            state = try {
                val pin = PlexTv.createPin(clientId)
                runCatching { uri.openUri(plexAuthUrl(clientId, pin)) }
                SignIn.Waiting(pin)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SignIn.Failed(e.friendlyMessage())
            }
        }
    }

    // Poll while waiting for approval; stops when the form closes (the pin just expires on plex.tv).
    val waiting = state as? SignIn.Waiting
    LaunchedEffect(waiting?.pin?.id) {
        val pin = waiting?.pin ?: return@LaunchedEffect
        val until = pin.expiresAt ?: Instant.now().plusSeconds(15 * 60)
        while (Instant.now().isBefore(until)) {
            delay(2_000)
            val now = try {
                PlexTv.checkPin(clientId, pin.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                continue // a flaky network mid-wait shouldn't end the sign-in
            } ?: break
            val token = now.authToken ?: continue
            val servers = try {
                PlexTv.servers(clientId, token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList() // signed in but couldn't list servers: the account token still works on an owned server
            }
            if (servers.size > 1) state = SignIn.Choose(token, servers) else pick(token, servers.firstOrNull())
            return@LaunchedEffect
        }
        state = SignIn.Failed("The sign-in code expired — try again")
    }

    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(16.dp), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (val st = state) {
                SignIn.Idle, is SignIn.Failed -> {
                    Text("No token handy? Sign in with your Plex account and Bosunarr fills it in.", style = MaterialTheme.typography.bodyMedium)
                    if (st is SignIn.Failed) Text(st.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Button(onClick = { start() }) {
                        Icon(Icons.AutoMirrored.Rounded.Login, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (st is SignIn.Failed) "Try again" else "Sign in with Plex")
                    }
                }
                SignIn.Starting -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Contacting plex.tv…", style = MaterialTheme.typography.bodyMedium)
                }
                is SignIn.Waiting -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Waiting for you to approve Bosunarr in the browser…", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                    Text("Or on any device, go to plex.tv/link and enter", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(st.pin.code, style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace, letterSpacing = 4.sp), fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { runCatching { uri.openUri(plexAuthUrl(clientId, st.pin)) } }) {
                            Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Open sign-in")
                        }
                        TextButton(onClick = { state = SignIn.Idle }) { Text("Cancel") }
                    }
                }
                is SignIn.Choose -> {
                    Text("Which server?", style = MaterialTheme.typography.titleSmall)
                    st.servers.forEach { srv ->
                        OutlinedButton(onClick = { pick(st.authToken, srv) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.Dns, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(srv.name + if (srv.owned) "" else " (shared)", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        }
                    }
                }
                is SignIn.Done -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        st.server?.let { "Signed in — token for $it filled in" } ?: "Signed in — token filled in",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { start() }) { Text("Redo") }
                }
            }
        }
    }
}
