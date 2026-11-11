package app.bosunarr.feature.activity

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import app.bosunarr.app
import app.bosunarr.feature.activity.qbit.Qbit
import app.bosunarr.data.ServiceConfig
import app.bosunarr.feature.activity.qbit.Torrent
import app.bosunarr.feature.activity.qbit.TorrentGroup
import app.bosunarr.feature.activity.qbit.categoryLabel
import app.bosunarr.ui.common.friendlyMessage
import app.bosunarr.ui.common.LocalSnackbar
import app.bosunarr.ui.common.plural
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Runs torrent actions with optimistic UI: the change shows at once (a paused torrent looks paused, a deleted one
 * disappears) and is rolled back with a snackbar if qBittorrent refuses. The override stays until a poll confirms
 * it or it times out, so a poll that was already in flight can't make the row flicker back.
 */
@Stable
class TorrentController internal constructor(
    private val snackbar: SnackbarHostState,
    private val onChanged: () -> Unit,
) {
    private enum class Kind { Stop, Start, Recheck, Delete, Category }

    private data class Pending(val kind: Kind, val from: String, val at: Long, val category: String? = null)

    private val pending = mutableStateMapOf<String, Pending>()

    private fun ttl(k: Kind) = when (k) {
        Kind.Delete -> 20_000L
        Kind.Recheck -> 5_000L
        else -> 10_000L
    }

    /** The list as the user should see it right now (pending actions applied). */
    fun apply(list: List<Torrent>): List<Torrent> {
        if (pending.isEmpty()) return list
        val now = System.currentTimeMillis()
        return list.mapNotNull { t ->
            val p = pending[t.key] ?: return@mapNotNull t
            if (now - p.at > ttl(p.kind)) return@mapNotNull t
            when (p.kind) {
                Kind.Delete -> null
                Kind.Stop -> if (t.state == p.from) t.copy(state = if (t.isDone) "stoppedUP" else "stoppedDL", dlspeed = 0, upspeed = 0) else t
                Kind.Start -> if (t.state == p.from) t.copy(state = if (t.isDone) "uploading" else "downloading") else t
                Kind.Recheck -> if (t.state == p.from) t.copy(state = if (t.isDone) "checkingUP" else "checkingDL", dlspeed = 0, upspeed = 0) else t
                Kind.Category -> t.copy(category = p.category.orEmpty())
            }
        }
    }

    /** Drop overrides that the latest data confirms (or that timed out). Call after each refresh. */
    fun prune(list: List<Torrent>) {
        if (pending.isEmpty()) return
        val now = System.currentTimeMillis()
        val byKey = list.associateBy { it.key }
        pending.entries.filter { (key, p) ->
            val t = byKey[key]
            now - p.at > ttl(p.kind) || when (p.kind) {
                Kind.Delete -> t == null
                Kind.Category -> t == null || t.category == p.category
                else -> t == null || t.state != p.from
            }
        }.map { it.key }.forEach { pending.remove(it) }
    }

    fun isBusy(t: Torrent): Boolean = pending.containsKey(t.key)

    fun stop(ts: List<Torrent>) = run(ts, Kind.Stop, { s, h -> Qbit.stop(s, h) }) { n ->
        if (ts.all { it.isDone }) "Stopped seeding" + count(n) else "Paused" + count(n)
    }

    fun start(ts: List<Torrent>) = run(ts, Kind.Start, { s, h -> Qbit.start(s, h) }) { n ->
        if (ts.all { it.isDone }) "Seeding started" + count(n) else "Resumed" + count(n)
    }

    fun recheck(ts: List<Torrent>) = run(ts, Kind.Recheck, { s, h -> Qbit.recheck(s, h) }) { n -> "Checking files" + count(n) }

    fun reannounce(ts: List<Torrent>) = run(ts, null, { s, h -> Qbit.reannounce(s, h) }) { n -> "Reannounced" + count(n) }

    fun delete(ts: List<Torrent>, deleteFiles: Boolean) = run(ts, Kind.Delete, { s, h -> Qbit.delete(s, h, deleteFiles) }) { n ->
        val what = if (n == 1) "torrent" else plural(n, "torrent")
        if (deleteFiles) "Deleted $what and files" else "Deleted $what"
    }

    fun setCategory(ts: List<Torrent>, category: String) = run(ts, Kind.Category, { s, h -> Qbit.setCategory(s, h, category) }, category) {
        if (category.isEmpty()) "Category removed" else "Category set to ${categoryLabel(category)}"
    }

    /** Pause every torrent of the clients in [ts] (`hashes=all`, so torrents added meanwhile are included). */
    fun stopAll(ts: List<Torrent>) {
        val running = ts.filter { !it.isStopped }
        if (running.isEmpty()) return show("Nothing to pause")
        run(running, Kind.Stop, { s, _ -> Qbit.stopAll(s) }) { "Paused all torrents" }
    }

    fun startAll(ts: List<Torrent>) {
        val stopped = ts.filter { it.isStopped || it.group == TorrentGroup.Errored }
        if (stopped.isEmpty()) return show("Nothing to resume")
        run(stopped, Kind.Start, { s, _ -> Qbit.startAll(s) }) { "Resumed all torrents" }
    }

    private fun count(n: Int) = if (n > 1) " $n torrents" else ""

    private fun run(
        ts: List<Torrent>,
        kind: Kind?,
        call: suspend (ServiceConfig, List<String>) -> Unit,
        category: String? = null,
        done: (Int) -> String,
    ) {
        if (ts.isEmpty()) return
        val now = System.currentTimeMillis()
        if (kind != null) ts.forEach { pending[it.key] = Pending(kind, it.state, now, category) }
        // app.scope: the request finishes even if the screen is left meanwhile.
        app.scope.launch(Dispatchers.Main) {
            val failures = mutableListOf<String>()
            ts.groupBy { it.serviceId }.forEach { (serviceId, group) ->
                val s = app.service(serviceId)
                if (s == null) {
                    failures += "Service no longer exists"
                    group.forEach { pending.remove(it.key) }
                    return@forEach
                }
                try {
                    call(s, group.map { it.hash })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures += if (ts.size > group.size) "${s.name}: ${e.friendlyMessage()}" else e.friendlyMessage()
                    group.forEach { pending.remove(it.key) }
                }
            }
            onChanged()
            show(if (failures.isEmpty()) done(ts.size) else "Couldn't do that — ${failures.first()}")
        }
    }

    fun show(message: String) {
        app.scope.launch(Dispatchers.Main) {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(message)
        }
    }
}

/** A [TorrentController] whose confirmed changes trigger [onChanged] (usually `loader.refresh()`). */
@Composable
fun rememberTorrentController(onChanged: () -> Unit): TorrentController {
    val snackbar = LocalSnackbar.current
    val latest = rememberUpdatedState(onChanged)
    return remember(snackbar) { TorrentController(snackbar) { latest.value() } }
}
