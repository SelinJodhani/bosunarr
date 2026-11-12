package app.bosunarr.ui.common

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable

/**
 * Android's predictive back look: while the user swipes back, the leaving screen becomes a card with rounded
 * corners (the NavHost's pop transitions scale and shift it; the gesture seeks them), so it reads as a page being
 * lifted off, like the system Settings app — not a square box.
 */
@Composable
fun AnimatedContentScope.PredictiveBackCard(content: @Composable () -> Unit) {
    val corner by transition.animateDp(transitionSpec = { tween(400, easing = LinearEasing) }, label = "backCorner") {
        if (it == EnterExitState.PostExit) 34.dp else 0.dp
    }
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(corner))) { content() }
}

/** `composable<T>` with the predictive back card. Use for every pushed (non-tab) screen. */
inline fun <reified T : Any> NavGraphBuilder.screen(noinline content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit) {
    composable<T> { entry -> PredictiveBackCard { content(entry) } }
}
