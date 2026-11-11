package app.bosunarr.ui.common

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Re-tapping the bottom-bar tab you're already on (at its root) scrolls that screen back to the top, like every
 * Google app. The nav bar emits; whichever tab root is on screen listens with one of the helpers below.
 */
object TabReselect {
    private val _events = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val events: SharedFlow<Unit> = _events

    fun emit() {
        _events.tryEmit(Unit)
    }
}

/**
 * Runs [onReselect] each time the current tab is re-tapped. A collapsed large top bar (Home) is expanded again,
 * since a programmatic scroll doesn't move it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnTabReselected(onReselect: suspend () -> Unit) {
    val latest = rememberUpdatedState(onReselect)
    val bar = LocalTopBarState.current
    LaunchedEffect(bar) {
        TabReselect.events.collect {
            bar?.let { it.heightOffset = 0f; it.contentOffset = 0f }
            latest.value()
        }
    }
}

/** Short lists animate; long jumps snap first so the animation never scrolls through hundreds of rows. */
@Composable
fun ScrollToTopOnReselect(state: LazyListState) = OnTabReselected {
    if (state.firstVisibleItemIndex > 30) state.scrollToItem(0) else state.animateScrollToItem(0)
}

@Composable
fun ScrollToTopOnReselect(state: LazyGridState) = OnTabReselected {
    if (state.firstVisibleItemIndex > 60) state.scrollToItem(0) else state.animateScrollToItem(0)
}

@Composable
fun ScrollToTopOnReselect(state: ScrollState) = OnTabReselected { state.animateScrollTo(0) }
