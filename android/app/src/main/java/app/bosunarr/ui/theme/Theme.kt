package app.bosunarr.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightFallback = lightColorScheme(
    primary = Color(0xFF6750A4), onPrimary = Color.White, primaryContainer = Color(0xFFEADDFF), onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF625B71), secondaryContainer = Color(0xFFE8DEF8), tertiary = Color(0xFF7D5260), tertiaryContainer = Color(0xFFFFD8E4),
    background = Color(0xFFFEF7FF), surface = Color(0xFFFEF7FF),
)
private val DarkFallback = darkColorScheme(
    primary = Color(0xFFD0BCFF), onPrimary = Color(0xFF381E72), primaryContainer = Color(0xFF4F378B), onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFCCC2DC), secondaryContainer = Color(0xFF4A4458), tertiary = Color(0xFFEFB8C8), tertiaryContainer = Color(0xFF633B48),
    background = Color(0xFF141218), surface = Color(0xFF141218),
)

/** Material You: wallpaper colours on Android 12+, a purple fallback before that. */
@Composable
fun BosunarrTheme(mode: String = "system", content: @Composable () -> Unit) {
    val dark = when (mode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> DarkFallback
        else -> LightFallback
    }
    // Status/navigation bar icons follow the app's own Light/Dark choice, not just the system's.
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
