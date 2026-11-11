package app.bosunarr.demo

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.currentBackStackEntryAsState
import app.bosunarr.app
import app.bosunarr.nav.DashboardRoute
import app.bosunarr.nav.OnboardingRoute
import kotlinx.coroutines.launch

private const val CREDITS_URL = "https://github.com/SelinJodhani/bosunarr/blob/main/android/app/src/main/assets/demo/CREDITS.md"

/** Leave the demo: back to the user's own services, or to the welcome screen when they have none. */
private suspend fun exitDemo(nav: NavController) {
    val hadReal = Demo.exit()
    if (hadReal) {
        if (!nav.popBackStack(DashboardRoute, inclusive = false)) nav.navigate(DashboardRoute)
    } else {
        nav.navigate(OnboardingRoute) {
            popUpTo(DashboardRoute) { inclusive = false }
            launchSingleTop = true
        }
    }
}

/** "Try the demo" on the welcome screen; [onEntered] runs once the demo services are in place. */
@Composable
fun TryDemoButton(onEntered: () -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = {
            busy = true
            scope.launch {
                Demo.enter()
                onEntered()
            }
        },
        enabled = !busy,
        modifier = modifier.fillMaxWidth().height(56.dp),
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else Icon(Icons.Rounded.PlayCircle, null, Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text("Try the demo", style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * The "Demo mode · Exit" pill, floating above the bottom bar on the dashboard while the demo is on.
 * Draw it over the NavHost (it fills the area but only the pill takes touches).
 */
@Composable
fun DemoBanner(nav: NavController, modifier: Modifier = Modifier) {
    val demo = app.state.collectAsState().value?.demo == true
    val entry by nav.currentBackStackEntryAsState()
    val onDashboard = entry?.destination?.hasRoute(DashboardRoute::class) == true
    val scope = rememberCoroutineScope()
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = demo && onDashboard,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shadowElevation = 6.dp,
                // Above the tab bar (80dp, drawn by each tab screen) and the gesture bar.
                modifier = Modifier.navigationBarsPadding().padding(bottom = 92.dp),
            ) {
                Row(Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Science, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Demo mode", style = MaterialTheme.typography.labelLarge)
                    Text(" · ", style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = { scope.launch { exitDemo(nav) } }) {
                        Text("Exit", color = MaterialTheme.colorScheme.inversePrimary, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/** Settings card shown while the demo is on, with the way out. */
@Composable
fun DemoSettingsCard(nav: NavController) {
    val scope = rememberCoroutineScope()
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Science, null, Modifier.size(28.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("You're exploring the demo", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Everything here is sample data. Your own services come back when you exit.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.width(12.dp))
            FilledTonalButton(onClick = { scope.launch { exitDemo(nav) } }) { Text("Exit demo") }
        }
    }
}

/** One line for Settings → About: where the demo's films and images come from (opens CREDITS.md). */
@Composable
fun DemoCreditsLine() {
    val uri = LocalUriHandler.current
    val link = MaterialTheme.colorScheme.primary
    Text(
        buildAnnotatedString {
            append("Demo mode shows Blender open movies (CC BY, CC BY-SA) and public-domain films and cartoons. ")
            withStyle(SpanStyle(color = link, textDecoration = TextDecoration.Underline)) { append("Demo content credits") }
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clickable { runCatching { uri.openUri(CREDITS_URL) } },
    )
}
