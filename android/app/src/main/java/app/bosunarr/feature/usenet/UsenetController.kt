package app.bosunarr.feature.usenet

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import app.bosunarr.app
import app.bosunarr.data.ServiceConfig
import app.bosunarr.feature.services.ActionRunner
import app.bosunarr.feature.services.rememberActionRunner
import app.bosunarr.net.ServiceException
import app.bosunarr.ui.common.plural

/**
 * Runs queue actions with optimistic UI: a paused job looks paused and a deleted one disappears at once. The
 * override stays until a poll confirms it (or times out), so a poll already in flight can't make the row flicker
 * back. Failures surface in the app snackbar and the next refresh shows the truth.
 */
@Stable
class UsenetController internal constructor(
    private val runner: ActionRunner,
    private val onChanged: () -> Unit,
) {
    private enum class Kind { Pause, Resume, Delete }

    private data class Pending(val kind: Kind, val at: Long)

    private val pending = mutableStateMapOf<String, Pending>()

    /** Keys (`serviceId:id`) of the selected queue jobs. Non-empty = selection mode. */
    var selection by mutableStateOf(emptySet<String>())
    val selecting: Boolean get() = selection.isNotEmpty()

    fun toggle(item: NzbItem) {
        selection = if (item.key in selection) selection - item.key else selection + item.key
    }

    fun clearSelection() { selection = emptySet() }

    private fun ttl(k: Kind) = if (k == Kind.Delete) 20_000L else 10_000L

    /** The list as the user should see it right now (pending actions applied). */
    fun apply(list: List<NzbItem>): List<NzbItem> {
        if (pending.isEmpty()) return list
        val now = System.currentTimeMillis()
        return list.mapNotNull { t ->
            val p = pending[t.key]?.takeIf { now - it.at <= ttl(it.kind) } ?: return@mapNotNull t
            when (p.kind) {
                Kind.Delete -> null
                Kind.Pause -> if (t.state == NzbState.Paused) t else t.copy(state = NzbState.Paused, stateLabel = "Paused", eta = null)
                Kind.Resume -> if (t.state != NzbState.Paused) t else t.copy(state = NzbState.Queued, stateLabel = "Queued")
            }
        }
    }

    /** Drop overrides the latest data confirms (or that timed out). Call after each refresh. */
    fun prune(list: List<NzbItem>) {
        if (pending.isEmpty()) return
        val now = System.currentTimeMillis()
        val byKey = list.associateBy { it.key }
        pending.entries.filter { (key, p) ->
            val t = byKey[key]
            now - p.at > ttl(p.kind) || t == null || when (p.kind) {
                Kind.Delete -> false
                Kind.Pause -> t.state == NzbState.Paused
                Kind.Resume -> t.state != NzbState.Paused
            }
        }.map { it.key }.forEach { pending.remove(it) }
        if (selection.isNotEmpty()) {
            val keep = selection.filterTo(HashSet()) { it in byKey && pending[it]?.kind != Kind.Delete }
            if (keep.size != selection.size) selection = keep
        }
    }

    fun isBusy(item: NzbItem): Boolean = pending.containsKey(item.key)

    /** Run [block] once per client, with that client's jobs (in queue order). */
    private fun perClient(
        items: List<NzbItem>,
        kind: Kind?,
        key: String,
        success: String,
        block: suspend (ServiceConfig, List<NzbItem>) -> Unit,
    ) {
        if (items.isEmpty()) return
        val now = System.currentTimeMillis()
        if (kind != null) items.forEach { pending[it.key] = Pending(kind, now) }
        runner.run(key + ":" + items.joinToString(",") { it.key }, success = success, after = onChanged) {
            try {
                items.groupBy { it.serviceId }.forEach { (sid, list) ->
                    val s = app.service(sid) ?: throw ServiceException("${list.first().serviceName} was removed")
                    block(s, list.sortedBy { it.index })
                }
                null
            } catch (e: Exception) {
                if (kind != null) items.forEach { pending.remove(it.key) }
                throw e
            }
        }
    }

    private fun count(items: List<NzbItem>) = if (items.size == 1) "" else " " + plural(items.size, "job")

    fun pause(items: List<NzbItem>) = perClient(items, Kind.Pause, "pause", "Paused" + count(items)) { s, l -> UsenetApi.pause(s, l.map { it.id }) }
    fun resume(items: List<NzbItem>) = perClient(items, Kind.Resume, "resume", "Resumed" + count(items)) { s, l -> UsenetApi.resume(s, l.map { it.id }) }
    fun delete(items: List<NzbItem>, purge: Boolean) =
        perClient(items, Kind.Delete, "delete", "Deleted" + count(items)) { s, l -> UsenetApi.delete(s, l.map { it.id }, purge) }
    fun moveTop(items: List<NzbItem>) = perClient(items, null, "top", "Moved to the top") { s, l -> UsenetApi.moveTop(s, l.map { it.id }) }
    fun setPriority(items: List<NzbItem>, p: NzbPriority) =
        perClient(items, null, "prio", "Priority set to ${p.label}") { s, l -> UsenetApi.setPriority(s, l.map { it.id }, p) }
    fun setCategory(items: List<NzbItem>, category: String) =
        perClient(items, null, "cat", if (category.isEmpty()) "Category removed" else "Category set to $category") { s, l ->
            UsenetApi.setCategory(s, l.map { it.id }, category)
        }

    /** One-off client action (pause all, speed limit…) with a snackbar message. */
    fun run(key: String, success: String, block: suspend () -> Unit) {
        runner.run(key, success = success, after = onChanged) { block(); null }
    }

    fun isRunning(key: String) = runner.isBusy(key)

    fun toast(message: String) = runner.toast(message)
}

@Composable
fun rememberUsenetController(onChanged: () -> Unit): UsenetController {
    val runner = rememberActionRunner()
    val latest by rememberUpdatedState(onChanged)
    return remember(runner) { UsenetController(runner) { latest() } }
}
