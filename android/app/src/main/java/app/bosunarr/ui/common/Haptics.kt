package app.bosunarr.ui.common

import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role

/** The system's on/off tick for a toggle (monitor, switches), given the new value. */
@Composable
fun rememberToggleHaptic(): (Boolean) -> Unit {
    val haptics = LocalHapticFeedback.current
    return { on -> haptics.performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff) }
}

/**
 * A whole row that toggles (pair it with a `Switch(onCheckedChange = null)` / `Checkbox(onCheckedChange = null)`):
 * one touch target and one TalkBack item, with the toggle haptic.
 */
@Composable
fun Modifier.toggleRow(value: Boolean, enabled: Boolean = true, role: Role = Role.Switch, onValueChange: (Boolean) -> Unit): Modifier {
    val haptic = rememberToggleHaptic()
    return toggleable(value, enabled = enabled, role = role) { haptic(it); onValueChange(it) }
}
