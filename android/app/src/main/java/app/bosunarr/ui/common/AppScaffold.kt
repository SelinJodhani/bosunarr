package app.bosunarr.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigation.NavController

/**
 * State of the collapsing (large) top app bar of the enclosing [AppScaffold], or null when it uses the small bar.
 * Pull-to-refresh containers read it (see [expandTopBarFirst]) so a downward drag re-expands the bar before it
 * starts pulling the refresh indicator.
 */
@OptIn(ExperimentalMaterial3Api::class)
val LocalCollapsingTopBar = compositionLocalOf<TopAppBarState?> { null }

/** State of the enclosing [AppScaffold]'s top bar (small or large), e.g. to tint tabs with it on scroll. */
@OptIn(ExperimentalMaterial3Api::class)
val LocalTopBarState = compositionLocalOf<TopAppBarState?> { null }

/**
 * Standard screen frame: top app bar (back arrow when [onBack] is set) that tints when content scrolls under it.
 * With [large], a large title that collapses into the small bar as content scrolls (top-level screens).
 * Window insets are handled by the outer app scaffold + this one; [content] gets the padding to apply.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    large: Boolean = false,
    /** Optional icon before the title of the small bar (e.g. a service logo). */
    titleIcon: (@Composable () -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    val barState = rememberTopAppBarState()
    val scroll = if (large) TopAppBarDefaults.exitUntilCollapsedScrollBehavior(barState) else TopAppBarDefaults.pinnedScrollBehavior(barState)
    val navIcon = @Composable {
        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
    }
    Scaffold(
        modifier = modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            if (large) {
                LargeTopAppBar(
                    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = navIcon,
                    actions = actions,
                    scrollBehavior = scroll,
                )
            } else {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (titleIcon != null) {
                                titleIcon()
                                Spacer(Modifier.width(12.dp))
                            }
                            Column {
                                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    },
                    navigationIcon = navIcon,
                    actions = actions,
                    scrollBehavior = scroll,
                )
            }
        },
        floatingActionButton = floatingActionButton,
    ) { padding ->
        CompositionLocalProvider(LocalCollapsingTopBar provides if (large) barState else null, LocalTopBarState provides barState) { content(padding) }
    }
}

/**
 * For content inside a pull-to-refresh container under a large top bar: a downward drag that reaches the top
 * of the list first re-expands the bar, and only then pulls the refresh indicator. Apply it *inside* the
 * pull-to-refresh box (between it and the scrolling content). No-op under a small bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Modifier.expandTopBarFirst(): Modifier {
    val state = LocalCollapsingTopBar.current ?: return this
    val connection = remember(state) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y <= 0f || state.heightOffset >= 0f) return Offset.Zero
                val old = state.heightOffset
                state.heightOffset = (old + available.y).coerceAtMost(0f)
                return Offset(0f, state.heightOffset - old)
            }
        }
    }
    return this.nestedScroll(connection)
}

/** Back that never pops the last screen (avoids a blank NavHost on double taps). */
fun NavController.back() {
    if (previousBackStackEntry != null) popBackStack()
}
