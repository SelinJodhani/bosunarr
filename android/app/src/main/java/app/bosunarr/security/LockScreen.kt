package app.bosunarr.security

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.bosunarr.ui.common.AppMark

/**
 * BIOMETRIC_STRONG or the device credential. Android 10 doesn't support that combination (only from 11), so it
 * falls back to BIOMETRIC_WEAK or the device credential there.
 */
internal val AUTHENTICATORS: Int
    get() = if (Build.VERSION.SDK_INT >= 30) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_WEAK or DEVICE_CREDENTIAL

internal tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}

/**
 * Ask for the user's biometric or screen lock. [onResult] gets null on success, an error message when it failed,
 * or "" when the user simply cancelled. Errors are the system's own (localised) strings.
 */
internal fun authenticate(activity: FragmentActivity, title: String, subtitle: String?, onResult: (String?) -> Unit) {
    if (activity.isFinishing || activity.isDestroyed) return
    AppLock.authenticating = true
    val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            AppLock.authenticating = false
            onResult(null)
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            AppLock.authenticating = false
            val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED || errorCode == BiometricPrompt.ERROR_CANCELED ||
                errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
            onResult(if (cancelled) "" else errString.toString())
        }
        // onAuthenticationFailed (a finger that didn't match): the prompt stays up and lets the user retry.
    })
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .apply { if (subtitle != null) setSubtitle(subtitle) }
        .setAllowedAuthenticators(AUTHENTICATORS)
        .build()
    runCatching { prompt.authenticate(info) }.onFailure {
        AppLock.authenticating = false
        onResult(it.message ?: "Couldn't show the unlock prompt")
    }
}

/**
 * Wraps the whole app. While locked, the app's UI is taken out of composition — so nothing (dialogs and sheets
 * included, which live in their own windows) can show behind or above the lock screen — and its saveable state
 * (navigation back stack, scroll positions, form fields) is kept by a [rememberSaveableStateHolder] and restored on
 * unlock.
 */
@Composable
fun LockGate(content: @Composable () -> Unit) {
    val locked by AppLock.locked.collectAsState()
    val holder = rememberSaveableStateHolder()
    if (locked) LockScreen() else holder.SaveableStateProvider("app") { content() }
}

@Composable
private fun LockScreen() {
    val ctx = LocalContext.current
    val activity = remember(ctx) { ctx.findFragmentActivity() }
    val focus = LocalFocusManager.current
    var error by remember { mutableStateOf<String?>(null) }
    var autoPrompted by remember { mutableStateOf(false) }

    fun unlock() {
        val a = activity ?: return
        // The screen lock was removed since the app lock was turned on: nothing to check against, and keeping
        // the app locked would lock the user out for good. Turn the app lock off (it needs a secure device).
        if (!AppLock.deviceSecure(a)) {
            AppLock.update(a) { it.copy(enabled = false) }
            AppLock.unlock()
            return
        }
        error = null
        authenticate(a, "Unlock Bosunarr", null) { e ->
            if (e == null) AppLock.unlock() else if (e.isNotEmpty()) error = e
        }
    }

    LaunchedEffect(Unit) { focus.clearFocus(force = true) }
    // Prompt straight away when the lock screen is in front — once per lock, so cancelling doesn't loop.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (!autoPrompted) { autoPrompted = true; unlock() }
    }
    // Back leaves the app instead of reaching the (hidden) screens.
    BackHandler { (ctx as? Activity ?: activity)?.moveTaskToBack(true) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppMark(size = 96.dp)
                Spacer(Modifier.height(16.dp))
                Text("Bosunarr is locked", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Text(
                    "Unlock with your fingerprint, face or screen lock.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(24.dp))
                Button(onClick = { unlock() }, modifier = Modifier.height(52.dp)) {
                    Icon(Icons.Rounded.Lock, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Unlock Bosunarr")
                }
            }
        }
    }
}
