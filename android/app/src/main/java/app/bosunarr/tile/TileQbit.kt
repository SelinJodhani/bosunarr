package app.bosunarr.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import androidx.core.content.edit
import app.bosunarr.app
import app.bosunarr.data.ServiceConfig
import app.bosunarr.data.ServiceType
import app.bosunarr.net.AppJson
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/** qBittorrent access and small persisted settings shared by the Quick Settings tiles. */
internal object TileQbit {
    private const val PREFS = "bosunarr_tiles"
    private const val KEY_ALT_SERVICE = "alt_service"
    private const val KEY_PAUSED = "paused"

    /** Every configured qBittorrent (waits briefly for the encrypted store on a cold start). */
    suspend fun services(): List<ServiceConfig> {
        withTimeoutOrNull(5_000) { app.state.filterNotNull().first() }
        return app.services.value.filter { it.serviceType == ServiceType.QBittorrent }
    }

    /** The qBittorrent the Alt speed tile controls: the one picked on long-press, else the first. */
    suspend fun altTarget(context: Context): ServiceConfig? {
        val all = services()
        val chosen = prefs(context).getString(KEY_ALT_SERVICE, null)
        return all.firstOrNull { it.id == chosen } ?: all.firstOrNull()
    }

    fun chosenAltService(context: Context): String? = prefs(context).getString(KEY_ALT_SERVICE, null)

    fun chooseAltService(context: Context, id: String) {
        prefs(context).edit { putString(KEY_ALT_SERVICE, id) }
    }

    @Serializable
    data class TransferInfo(
        @SerialName("dl_info_speed") val down: Long = 0,
        @SerialName("dl_rate_limit") val downLimit: Long = 0,
    )

    @Serializable
    data class LightTorrent(val hash: String = "", val state: String = "")

    suspend fun transfer(s: ServiceConfig): TransferInfo = app.client.get(s, "/api/v2/transfer/info")

    /** "1" when alternative speed limits are on. */
    suspend fun altEnabled(s: ServiceConfig): Boolean = app.client.text(s, "/api/v2/transfer/speedLimitsMode").trim() == "1"

    suspend fun torrents(s: ServiceConfig): List<LightTorrent> = app.client.get(s, "/api/v2/torrents/info")

    // ------------------------------------------------------------ what the Pause tile paused (service id → hashes)

    private val pausedSer = MapSerializer(String.serializer(), ListSerializer(String.serializer()))

    fun paused(context: Context): Map<String, List<String>> =
        prefs(context).getString(KEY_PAUSED, null)?.let { runCatching { AppJson.decodeFromString(pausedSer, it) }.getOrNull() }.orEmpty()

    fun setPaused(context: Context, value: Map<String, List<String>>) {
        prefs(context).edit {
            if (value.isEmpty()) remove(KEY_PAUSED) else putString(KEY_PAUSED, AppJson.encodeToString(pausedSer, value))
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Ask the system to redraw a tile (it binds the service and calls onStartListening). */
    fun refreshTile(context: Context, cls: Class<out TileService>) {
        runCatching { TileService.requestListeningState(context, ComponentName(context, cls)) }
    }
}

/** Open an app screen from a tile and collapse the shade (PendingIntent form required from Android 14). */
@SuppressLint("StartActivityAndCollapseDeprecated") // the Intent overload only runs below Android 14
internal fun TileService.openAndCollapse(intent: Intent) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
    } else {
        @Suppress("DEPRECATION")
        startActivityAndCollapse(intent)
    }
}
