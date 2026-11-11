package app.bosunarr.tile

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.bosunarr.R
import app.bosunarr.app
import app.bosunarr.feature.activity.qbit.Qbit
import app.bosunarr.ui.common.speed
import app.bosunarr.widget.DeepLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Quick Settings "Alt speed": turns qBittorrent's alternative speed limits on/off for the chosen (or first)
 * qBittorrent. Active = alt limits on; the subtitle shows the download limit now in force. Long-press opens
 * [TileSettingsActivity] (pick which qBittorrent, or set one up when there's none).
 */
class AltSpeedTileService : TileService() {
    private var scope: CoroutineScope? = null

    /** Last known state, shown immediately when the shade opens (then confirmed with the server). */
    private data class State(val serviceId: String?, val enabled: Boolean? = null, val downLimit: Long? = null, val error: String? = null)

    override fun onStartListening() {
        super.onStartListening()
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
        render(last)
        sc.launch { load() }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { toggle() } else toggle()
    }

    private suspend fun load() {
        val s = TileQbit.altTarget(applicationContext)
        if (s == null) {
            last = State(null)
            render(last)
            return
        }
        // Throttle: the shade can open many times a minute; a few seconds of staleness is fine.
        val fresh = last?.takeIf { it.serviceId == s.id && it.error == null && System.currentTimeMillis() - loadedAt < 5_000 }
        if (fresh != null) return render(fresh)
        last = try {
            val enabled = TileQbit.altEnabled(s)
            val info = TileQbit.transfer(s)
            loadedAt = System.currentTimeMillis()
            State(s.id, enabled, info.downLimit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            State(s.id, last?.takeIf { it.serviceId == s.id }?.enabled, null, "Unreachable")
        }
        render(last)
    }

    private fun toggle() {
        val current = last ?: return // still loading
        if (current.serviceId == null) {
            // No qBittorrent yet: take the user to where they can add one.
            openAndCollapse(DeepLink.intent(this, DeepLink.SETTINGS))
            return
        }
        val target = !(current.enabled ?: false)
        last = current.copy(enabled = target, downLimit = null, error = null)
        render(last)
        val context = applicationContext
        // App-wide scope: the change must complete even if the shade closes and the service unbinds.
        app.scope.launch {
            mutex.withLock {
                val s = TileQbit.altTarget(context) ?: return@withLock
                last = try {
                    Qbit.setAltSpeed(s, target)
                    val enabled = TileQbit.altEnabled(s)
                    val info = TileQbit.transfer(s)
                    loadedAt = System.currentTimeMillis()
                    State(s.id, enabled, info.downLimit)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    loadedAt = 0
                    State(s.id, current.enabled, current.downLimit, "Couldn't switch")
                }
            }
            TileQbit.refreshTile(context, AltSpeedTileService::class.java)
        }
    }

    private fun render(st: State?) {
        val tile = qsTile ?: return
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_speed)
        tile.label = getString(R.string.tile_alt_speed)
        when {
            st == null -> {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = "…"
            }
            st.serviceId == null -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.subtitle = "No qBittorrent"
            }
            else -> {
                tile.state = if (st.enabled == true) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                tile.subtitle = st.error ?: when (val limit = st.downLimit) {
                    null -> if (st.enabled == true) "On" else if (st.enabled == false) "Off" else "…"
                    0L -> "↓ No limit"
                    else -> "↓ ${speed(limit)}"
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            tile.stateDescription = when {
                st?.serviceId == null && st != null -> "Set up qBittorrent in Bosunarr — long-press for settings"
                st?.enabled == true -> "Alternative speed limits on"
                else -> "Alternative speed limits off"
            }
        }
        tile.updateTile()
    }

    override fun onDestroy() {
        scope?.cancel()
        super.onDestroy()
    }

    private companion object {
        @Volatile var last: State? = null
        @Volatile var loadedAt = 0L
        val mutex = Mutex()
    }
}
