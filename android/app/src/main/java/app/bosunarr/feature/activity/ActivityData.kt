package app.bosunarr.feature.activity

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.bosunarr.data.ServiceConfig
import app.bosunarr.feature.activity.qbit.Qbit
import app.bosunarr.feature.activity.qbit.QbitSnapshot
import app.bosunarr.feature.activity.qbit.Torrent
import app.bosunarr.feature.activity.queue.ArrQueue
import app.bosunarr.feature.activity.queue.QueueEntry
import app.bosunarr.net.fanOut
import app.bosunarr.net.userMessage
import app.bosunarr.ui.common.ErrorBox
import app.bosunarr.ui.common.Loader
import app.bosunarr.ui.common.LoadingBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Immutable
data class ServiceError(val service: String, val message: String)

@Immutable
data class QueueData(val entries: List<QueueEntry>, val errors: List<ServiceError>)

@Immutable
data class TorrentData(val clients: List<QbitSnapshot>, val errors: List<ServiceError>) {
    val torrents: List<Torrent> = if (clients.size == 1) clients[0].torrents else clients.flatMap { it.torrents }
    val down: Long get() = clients.sumOf { it.server.down }
    val up: Long get() = clients.sumOf { it.server.up }
    fun categoriesOf(serviceId: String): List<String> = clients.firstOrNull { it.serviceId == serviceId }?.categories.orEmpty()
}

suspend fun loadQueue(services: List<ServiceConfig>): QueueData = withContext(Dispatchers.Default) {
    val results = fanOut(services) { ArrQueue.load(it) }
    QueueData(
        entries = results.flatMap { (_, r) -> r.getOrDefault(emptyList()) },
        errors = results.mapNotNull { (s, r) -> r.exceptionOrNull()?.let { ServiceError(s.name, it.userMessage()) } },
    )
}

suspend fun loadTorrents(services: List<ServiceConfig>): TorrentData {
    val results = fanOut(services) { Qbit.snapshot(it) }
    val clients = results.mapNotNull { it.second.getOrNull() }
    return TorrentData(
        clients = clients,
        errors = results.mapNotNull { (s, r) -> r.exceptionOrNull()?.let { ServiceError(s.name, it.userMessage()) } } +
            clients.mapNotNull { c -> c.error?.let { ServiceError(c.serviceName, it) } },
    )
}

/** "Couldn't reach Sonarr, Radarr: <first error>" */
fun errorText(errors: List<ServiceError>): String? = if (errors.isEmpty()) null
else "Couldn't reach ${errors.joinToString(", ") { it.service }}: ${errors.first().message}"

/**
 * Like the core LoadContent, but for screens that poll: the pull-to-refresh spinner only shows for a refresh the
 * user asked for, not for every background poll.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> PolledContent(loader: Loader<T>, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    val s = loader.state
    var userRefresh by remember { mutableStateOf(false) }
    LaunchedEffect(s.loading) { if (!s.loading) userRefresh = false }
    PullToRefreshBox(
        isRefreshing = userRefresh,
        onRefresh = { userRefresh = true; loader.refresh() },
        modifier = modifier.fillMaxSize(),
    ) {
        val value = s.value
        when {
            value != null -> Box(Modifier.fillMaxSize()) { content(value) }
            s.error != null -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { ErrorBox(s.error, onRetry = { loader.refresh() }) }
            else -> LoadingBox()
        }
    }
}
