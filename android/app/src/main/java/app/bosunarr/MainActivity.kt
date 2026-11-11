package app.bosunarr

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.bosunarr.ui.AppRoot
import app.bosunarr.ui.theme.BosunarrTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val state by app.state.collectAsState()
            BosunarrTheme(mode = state?.themeMode ?: "system") { AppRoot() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Magnet links (VIEW) and shared text containing a magnet/torrent URL (SEND) open the Add torrent sheet. */
    private fun handleIntent(intent: Intent?) {
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
