package app.bosunarr.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.bosunarr.net.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Data loaded from services: first load, success (maybe refreshing, maybe with a stale-refresh error), or failure. */
data class Load<out T>(
    val value: T? = null,
    val error: String? = null,
    val loading: Boolean = true,
    /** A refresh the user asked for (pull to refresh) — the only kind that shows the refresh spinner. */
    val userRefreshing: Boolean = false,
) {
    val isInitialLoading get() = loading && value == null
}

class LoaderViewModel : ViewModel() {
    val state = MutableStateFlow(Load<Any?>())
    private var job: Job? = null
    var lastFetch: (suspend () -> Any?)? = null

    fun load(fetch: suspend () -> Any?, user: Boolean = false) {
        lastFetch = fetch
        job?.cancel()
        state.value = state.value.copy(loading = true, userRefreshing = user)
        job = viewModelScope.launch {
            state.value = try {
                Load(fetch(), null, false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep showing the last good data; surface the error alongside it.
                state.value.copy(error = e.userMessage(), loading = false, userRefreshing = false)
            }
        }
    }

    /** Apply a local change immediately (optimistic update) without refetching. */
    @Suppress("UNCHECKED_CAST")
    fun <T> mutate(transform: (T) -> T) {
        val v = state.value.value as? T ?: return
        state.value = state.value.copy(value = transform(v))
    }
}

class Loader<T>(private val vm: LoaderViewModel, private val stateRef: State<Load<Any?>>, private val fetch: () -> (suspend () -> T)) {
    @Suppress("UNCHECKED_CAST")
    val state: Load<T> get() = stateRef.value as Load<T>
    val value: T? get() = state.value
    /** Reload quietly (after an action, or a poll): old data stays on screen, no spinner. */
    fun refresh() = vm.load(fetch())
    /** Pull to refresh: same, but shows the refresh indicator. */
    fun pullRefresh() = vm.load(fetch(), user = true)
    fun mutate(transform: (T) -> T) = vm.mutate(transform)
}

/**
 * Load data for a screen. Survives navigation (scoped to the back-stack entry), keeps old data while refreshing,
 * and with [refreshMs] polls while the screen is visible (stops in the background).
 * Changing [key] reloads.
 */
@Composable
fun <T> rememberLoader(key: String, refreshMs: Long? = null, fetch: suspend () -> T): Loader<T> {
    val vm: LoaderViewModel = viewModel(key = "loader:$key")
    val latest by rememberUpdatedState(fetch)
    val state = vm.state.collectAsState()
    val loader = Loader(vm, state) { suspend { latest() } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(vm, refreshMs) {
        if (vm.lastFetch == null) loader.refresh()
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (refreshMs == null) return@repeatOnLifecycle
            while (true) {
                delay(refreshMs)
                loader.refresh()
            }
        }
    }
    return loader
}
