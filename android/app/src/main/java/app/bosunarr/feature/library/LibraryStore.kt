package app.bosunarr.feature.library

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide glue between the Library grid and the detail/add screens:
 * - [items]: the last list we loaded, so a detail screen can paint its header instantly;
 * - [overrides]: changes made elsewhere (monitor toggles, deletes) that the grid applies on top of data fetched
 *   before the change, so going back never shows a stale state;
 * - [version]: bumped when the grid should refetch (something was added).
 */
object LibraryStore {
    val items = ConcurrentHashMap<String, LibItem>()

    data class Override(val monitored: Boolean? = null, val removed: Boolean = false, val at: Long = System.currentTimeMillis())

    val overrides = MutableStateFlow<Map<String, Override>>(emptyMap())
    val version = MutableStateFlow(0)

    fun remember(list: List<LibItem>) {
        items.clear()
        list.forEach { items[it.key] = it }
    }

    fun setMonitored(key: String, monitored: Boolean) {
        items[key]?.let { items[key] = it.copy(monitored = monitored) }
        overrides.update { it + (key to Override(monitored = monitored)) }
    }

    fun removed(key: String) {
        items.remove(key)
        overrides.update { it + (key to Override(removed = true)) }
    }

    fun invalidate() = version.update { it + 1 }

    /** Apply overrides newer than the data (fetched at [fetchedAt]). */
    fun apply(list: List<LibItem>, fetchedAt: Long, ov: Map<String, Override>): List<LibItem> {
        if (ov.isEmpty()) return list
        val fresh = ov.filterValues { it.at > fetchedAt }
        if (fresh.isEmpty()) return list
        return list.mapNotNull { i ->
            val o = fresh[i.key] ?: return@mapNotNull i
            when {
                o.removed -> null
                o.monitored != null && o.monitored != i.monitored -> i.copy(
                    monitored = o.monitored,
                    status = when {
                        i.status == LibStatus.Unmonitored && o.monitored -> if (i.kind == LibKind.Movie || i.have == 0) LibStatus.Missing else LibStatus.Partial
                        !o.monitored && i.status == LibStatus.Missing -> LibStatus.Unmonitored
                        else -> i.status
                    },
                )
                else -> i
            }
        }
    }
}
