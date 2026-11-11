package app.bosunarr

import android.content.Context
import app.bosunarr.data.AppState
import app.bosunarr.data.SecureStore
import app.bosunarr.data.ServiceConfig
import app.bosunarr.data.ServiceType
import app.bosunarr.net.ServiceClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.UUID
import java.util.concurrent.TimeUnit

class AppContainer(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = SecureStore(context)

    private val baseHttp = OkHttpClient.Builder().retryOnConnectionFailure(true).build()
    val client = ServiceClient(baseHttp)
    // No redirects: image requests carry API-key headers, which OkHttp would forward to another host.
    val imageHttp: OkHttpClient = baseHttp.newBuilder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    /** null until the encrypted store has been read once (so the UI can tell "loading" from "no services"). */
    private val _state = MutableStateFlow<AppState?>(null)
    private val _services = MutableStateFlow<List<ServiceConfig>>(emptyList())
    val state: StateFlow<AppState?> = _state
    val services: StateFlow<List<ServiceConfig>> = _services

    init {
        // One collector, services first: anything that sees `state != null` also sees the loaded services
        // (no "no services yet" flash, and a magnet shared into a cold app isn't dropped).
        scope.launch {
            store.state.collect { s ->
                _services.value = s.services.sortedWith(compareBy({ it.serviceType?.ordinal ?: 99 }, { it.sort }, { it.name.lowercase() }))
                _state.value = s
            }
        }
    }

    fun servicesOf(vararg types: ServiceType): List<ServiceConfig> = services.value.filter { it.serviceType in types }
    fun service(id: String): ServiceConfig? = services.value.firstOrNull { it.id == id }

    /** A magnet link or URL shared into the app, waiting for the Add torrent sheet. */
    val pendingTorrent = MutableStateFlow<String?>(null)

    suspend fun saveService(s: ServiceConfig): ServiceConfig {
        val saved = if (s.id.isBlank()) s.copy(id = UUID.randomUUID().toString()) else s
        store.update { st ->
            val list = st.services.toMutableList()
            val i = list.indexOfFirst { it.id == saved.id }
            if (i >= 0) list[i] = saved else list += saved
            st.copy(services = list)
        }
        client.reset(saved.id)
        return saved
    }

    suspend fun deleteService(id: String) {
        store.update { st -> st.copy(services = st.services.filterNot { it.id == id }) }
        client.reset(id)
    }

    fun updateState(transform: (AppState) -> AppState) {
        scope.launch { store.update(transform) }
    }
}
