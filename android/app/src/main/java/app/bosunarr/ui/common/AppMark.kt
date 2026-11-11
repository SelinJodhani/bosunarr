package app.bosunarr.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.bosunarr.R

private val MarkGradient = listOf(Color(0xFF5B45A8), Color(0xFF3B2A7A), Color(0xFF22184D))

/**
 * The app icon drawn in-app (welcome, About, empty dashboard): the launcher's violet background and sailboat
 * foreground in a rounded square, so the brand looks the same everywhere.
 */
@Composable
fun AppMark(modifier: Modifier = Modifier, size: Dp = 72.dp) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.3f))
            .background(Brush.linearGradient(MarkGradient, start = Offset(0f, 0f), end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY))),
        contentAlignment = Alignment.Center,
    ) {
        // The foreground is a 108dp adaptive-icon canvas whose art fills the middle ~45%: scale it up to fill.
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.requiredSize(size * 1.5f))
    }
}
