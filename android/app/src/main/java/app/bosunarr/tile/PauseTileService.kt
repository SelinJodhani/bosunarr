package app.bosunarr.tile

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.bosunarr.R
import app.bosunarr.app
import app.bosunarr.feature.activity.qbit.Qbit
import app.bosunarr.net.fanOut
import app.bosunarr.ui.common.speed
import app.bosunarr.widget.DeepLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Quick Settings "Pause downloads": one tap stops every running torrent on every qBittorrent; the next tap resumes
 * exactly the torrents this tile stopped (it remembers their hashes), so torrents the user had paused on purpose
 * stay paused. Acting without a confirmation is fine here: a QS tap is deliberate, it's fully reversible, and the
 * tile shows the paused state until it's undone. On a locked phone the user has to unlock first.
 */
class PauseTileService : TileService() {
    private var scope: CoroutineScope? = null

    private data class State(val configured: Boolean, val pausedCount: Int, val down: Long? = null, val note: String? = null)

    override fun onStartListening() {
        super.onStartListening()
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
        val paused = TileQbit.paused(applicationContext)
        render(last ?: State(true, paused.values.sumOf { it.size }))
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
        val qbits = TileQbit.services()
        val paused = TileQbit.paused(applicationContext).filterKeys { id -> qbits.any { it.id == id } }
        val count = paused.values.sumOf { it.size }
        if (qbits.isEmpty()) {
            last = State(false, 0)
            return render(last)
        }
        if (count > 0) {
            last = State(true, count)
            return render(last)
        }
        val speeds = fanOut(qbits) { TileQbit.transfer(it).down }
        val ok = speeds.mapNotNull { it.second.getOrNull() }
        last = State(true, 0, if (ok.isEmpty()) null else ok.sum(), if (ok.isEmpty()) "Unreachable" else null)
        render(last)
    }

    private fun toggle() {
        val current = last ?: return
        if (!current.configured) {
            openAndCollapse(DeepLink.intent(this, DeepLink.SETTINGS))
            return
        }
        val resuming = current.pausedCount > 0
        last = current.copy(note = if (resuming) "Resuming…" else "Pausing…")
        render(last)
        val context = applicationContext
        app.scope.launch {
            mutex.withLock {
                val qbits = TileQbit.services()
                last = if (resuming) {
                    val record = TileQbit.paused(context)
                    val results = fanOut(qbits.filter { record[it.id].orEmpty().isNotEmpty() }) { s -> Qbit.start(s, record.getValue(s.id)) }
                    // Keep whatever couldn't be resumed (service unreachable) so the next tap can try again.
                    val left = record.filterKeys { id -> results.any { it.first.id == id && it.second.isFailure } }
                        .filterKeys { id -> qbits.any { it.id == id } }
                    TileQbit.setPaused(context, left)
                    State(true, left.values.sumOf { it.size }, note = if (left.isNotEmpty()) "Couldn't resume all" else null)
                } else {
                    val results = fanOut(qbits) { s ->
                        val running = TileQbit.torrents(s).filterNot { it.state.startsWith("paused") || it.state.startsWith("stopped") }.map { it.hash }
                        if (running.isNotEmpty()) Qbit.stop(s, running)
                        running
                    }
                    val record = results.mapNotNull { (s, r) -> r.getOrNull()?.takeIf { it.isNotEmpty() }?.let { s.id to it } }.toMap()
                    TileQbit.setPaused(context, record)
                    val count = record.values.sumOf { it.size }
                    State(
                        true, count,
                        note = when {
                            results.all { it.second.isFailure } -> "Unreachable"
                            count == 0 -> "Nothing to pause"
                            else -> null
                        },
                    )
                }
            }
            TileQbit.refreshTile(context, PauseTileService::class.java)
        }
    }

    private fun render(st: State?) {
        val tile = qsTile ?: return
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_pause)
        when {
            st == null -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = getString(R.string.tile_pause)
                tile.subtitle = "…"
            }
            !st.configured -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.label = getString(R.string.tile_pause)
                tile.subtitle = "No qBittorrent"
            }
            st.pausedCount > 0 -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "Downloads paused"
                tile.subtitle = st.note ?: "Tap to resume ${st.pausedCount}"
            }
            else -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = getString(R.string.tile_pause)
                tile.subtitle = st.note ?: st.down?.let { "↓ ${speed(it)}" } ?: "…"
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            tile.stateDescription = when {
                st != null && !st.configured -> "Set up qBittorrent in Bosunarr — long-press for settings"
                (st?.pausedCount ?: 0) > 0 -> "${st!!.pausedCount} torrents paused by this tile"
                else -> "Downloads running"
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
        val mutex = Mutex()
    }
}
