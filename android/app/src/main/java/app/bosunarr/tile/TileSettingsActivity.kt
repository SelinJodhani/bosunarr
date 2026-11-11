package app.bosunarr.tile

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.bosunarr.app
import app.bosunarr.data.ServiceConfig
import app.bosunarr.ui.theme.BosunarrTheme
import app.bosunarr.widget.DeepLink

/**
 * Long-press target for both tiles (QS_TILE_PREFERENCES). No qBittorrent → Settings, so one can be added.
 * Alt speed tile with several qBittorrents → pick which one the tile controls. Otherwise → the qBittorrent
 * screen (Alt speed) or Activity (Pause downloads).
 */
class TileSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tile = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_COMPONENT_NAME)
        }
        val isAltTile = tile?.className != PauseTileService::class.java.name
        setContent {
            var qbits by remember { mutableStateOf<List<ServiceConfig>?>(null) }
            LaunchedEffect(Unit) {
                val all = TileQbit.services()
                when {
                    all.isEmpty() -> go(DeepLink.SETTINGS)
                    !isAltTile -> go(DeepLink.ACTIVITY)
                    all.size == 1 -> go(DeepLink.service(all[0].id))
                    else -> qbits = all
                }
            }
            val list = qbits ?: return@setContent
            BosunarrTheme(mode = app.state.value?.themeMode ?: "system") {
                var chosen by remember { mutableStateOf(TileQbit.chosenAltService(this@TileSettingsActivity) ?: list[0].id) }
                AlertDialog(
                    onDismissRequest = { finish() },
                    title = { Text("Alt speed tile") },
                    text = {
                        Column {
                            Text("Which qBittorrent should the tile switch?", style = MaterialTheme.typography.bodyMedium)
                            list.forEach { s ->
                                Row(
                                    Modifier.fillMaxWidth().clickable { chosen = s.id }.padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(selected = chosen == s.id, onClick = { chosen = s.id })
                                    Text(s.name)
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            TileQbit.chooseAltService(this@TileSettingsActivity, chosen)
                            TileQbit.refreshTile(this@TileSettingsActivity, AltSpeedTileService::class.java)
                            finish()
                        }) { Text("Done") }
                    },
                    dismissButton = { TextButton(onClick = { go(DeepLink.service(chosen)) }) { Text("Open qBittorrent") } },
                )
            }
        }
    }

    private fun go(target: String) {
        startActivity(DeepLink.intent(this, target))
        finish()
    }
}
