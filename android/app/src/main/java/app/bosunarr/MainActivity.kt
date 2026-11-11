package app.bosunarr

import android.app.UiModeManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import app.bosunarr.notify.NotifyLinks
import app.bosunarr.ui.AppRoot
import app.bosunarr.ui.common.CrashReporter
import app.bosunarr.update.Updater
import app.bosunarr.ui.theme.BosunarrTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        CrashReporter.install(this)
        val splash = installSplashScreen()
        // Hold the splash until the encrypted store is read (usually a few ms) so the first frame is the real
        // screen, never an empty one; capped so a slow disk can't keep the app on the splash.
        val started = SystemClock.uptimeMillis()
        splash.setKeepOnScreenCondition { app.state.value == null && SystemClock.uptimeMillis() - started < 1_500 }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val state by app.state.collectAsState()
            val mode = state?.themeMode
            LaunchedEffect(mode) { if (mode != null) applyNightMode(mode) }
            BosunarrTheme(mode = mode ?: "system") { AppRoot() }
        }
    }

    /**
     * Android 12+: tell the system the app's own Light/Dark choice, so the next launch's splash screen (drawn by
     * the system before any app code runs) uses the same theme as the app. The activity handles `uiMode` changes
     * itself (manifest), so this never recreates it.
     */
    private fun applyNightMode(mode: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val ui = getSystemService(UiModeManager::class.java) ?: return
        val night = when (mode) {
            "dark" -> UiModeManager.MODE_NIGHT_YES
            "light" -> UiModeManager.MODE_NIGHT_NO
            else -> UiModeManager.MODE_NIGHT_AUTO
        }
        runCatching { ui.setApplicationNightMode(night) }
    }

    override fun onResume() {
        super.onResume()
        // Back from "Install unknown apps": finish the update the user started.
        Updater.resumePendingInstall(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Magnet links (VIEW) and shared text containing a magnet/torrent URL (SEND) open the Add torrent sheet. */
    private fun handleIntent(intent: Intent?) {
        if (NotifyLinks.handle(intent)) return
        val link = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }?.trim() ?: return
        val found = Regex("""magnet:\?\S+""").find(link)?.value
            ?: Regex("""https?://\S+\.torrent\b\S*""").find(link)?.value
            ?: link.takeIf { it.startsWith("http") }
        if (found != null) app.pendingTorrent.value = found
    }
}
