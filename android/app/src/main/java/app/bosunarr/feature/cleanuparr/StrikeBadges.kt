package app.bosunarr.feature.cleanuparr

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.bosunarr.app
import app.bosunarr.data.ServiceType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Cleanuparr's strikes by torrent hash, shown on torrent rows ("2 of 3 strikes · Stuck on metadata") so trouble is
 * visible before Cleanuparr removes the download. Refreshed by [StrikeBadgesEffect] while a torrent list is on
 * screen; empty (and never fetched) without a Cleanuparr service.
 */
object StrikeBadges {
    private val state = MutableStateFlow<Map<String, StrikeBadge>>(emptyMap())
    val badges: StateFlow<Map<String, StrikeBadge>> = state.asStateFlow()
    private val wake = MutableStateFlow(0)

    /** Re-fetch now (after forgiving a download). */
    fun invalidate() { wake.value++ }

    internal suspend fun refreshLoop() {
        while (true) {
            try {
                state.value = loadStrikeBadges()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep the last known badges: a failed refresh shouldn't make warnings disappear.
            }
            val seen = wake.value
            // Every minute, or right away when invalidated.
            repeat(60) { if (wake.value != seen) return@repeat; delay(1_000) }
        }
    }
}

@Composable
fun StrikeBadgesEffect() {
    val services by app.services.collectAsState()
    val ids = services.filter { it.serviceType == ServiceType.Cleanuparr }.joinToString { it.id + it.url }
    if (ids.isEmpty()) return
    LaunchedEffect(ids) { StrikeBadges.refreshLoop() }
}
