package app.bosunarr.ui.art

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.EnumMap
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Bosunarr's nautical spot illustrations: small, calm sea scenes for empty and error states.
 *
 * Everything is flat vector art drawn on a Canvas from cached Paths, coloured entirely from
 * MaterialTheme.colorScheme so it follows the wallpaper (Material You) and works in light and dark.
 *
 * Palette roles ([Ink]) and where they come from (light / dark):
 *   Backdrop    disc behind the scene      surface→primaryContainer 55%   / surfaceContainerHigh→primaryContainer 35%
 *   Sea         water body                 primaryContainer→primary 38%   / primaryContainer→primary 22%
 *   SeaDeep     lower water band           primaryContainer→primary 55%   / primaryContainer→surface 25%
 *   Wave        the crest line             primary                        / primary
 *   WaveSoft    distant waves, rain        primary→primaryContainer 45%   (both)
 *   Foam        inner waves, glints        surface                        / primary→onSurface 60%
 *   Sail        main sail, gull feathers   surface                        / onSurface→secondary 25%
 *   SailShade   jib, distant sails         secondaryContainer→secondary 32% / secondary→secondaryContainer 35%
 *   Hull        hulls, ironwork, ink       primary→onPrimaryContainer 35% / primary→primaryContainer 50%
 *   HullStripe  trim on Hull               primaryContainer→surface 30%   / primary→onSurface 40%
 *   Sun         sun, lamp, brass           tertiaryContainer→tertiary 30% / tertiary
 *   Glow        halos and light beams      tertiaryContainer              / tertiaryContainer→tertiary 25%
 *   Star/Accent sparkles, pennants, bands  tertiary→tertiaryContainer 20% / tertiary
 *   Bolt        lightning                  tertiaryContainer→tertiary 45% / tertiary
 *   Rock(Shade) rocks, seabed              surfaceVariant→outline 42/62%  / outlineVariant→outline 35%, →surface 25%
 *   Wood(Shade) piers, desk, cork, rope    tertiary family                (both)
 *   Paper(..)   lighthouse, pages, paper   surface + outline family       / onSurface + outline family
 *   Glass       the bottle                 primaryContainer→surface 45%   / primaryContainer→primary 30%
 *   Cloud/Fog   fair-weather cloud, fog    surface                        / primaryContainer→onSurface 30/45%
 *   Cloud2      storm cloud                surfaceVariant→outline 45%     / outline→primaryContainer 35%
 *   Bird        gulls                      onSurfaceVariant→primary 40/30%
 *   Weed        seaweed                    primary→tertiary 25/30%
 *   Shade       translucent shading        onSurface                      / scrim
 * "a→b n%" is an Oklab blend (Compose's lerp), so the art keeps its contrast whatever the seed colour is.
 */

/** Which illustration to show. Each one is a metaphor for a kind of empty or error state. */
enum class SeaScene {
    /** A sailboat on a calm sea: nothing downloading, queue empty. */
    CalmSeas,
    /** A rowboat tied up at an empty pier: no torrents. */
    EmptyDock,
    /** A lighthouse under the stars: nothing on the calendar / nothing scheduled. */
    Horizon,
    /** A message in a bottle: requests all caught up / no requests. */
    MessageBottle,
    /** A spyglass scanning an empty horizon: no search results, nothing to discover, or a search prompt. */
    Spyglass,
    /** An anchor at rest below a buoy: nothing set up yet (no services, empty library). The "set up" invitation. */
    Harbor,
    /** A boat in fog banks with a faint lighthouse: a service can't be reached (offline, timeout). */
    Fog,
    /** A paper boat under a small rain cloud: a generic "couldn't load" error. */
    Storm,
    /** An open ship's log with a quill: empty history. */
    Logbook,
    /** A gull gliding over calm water: nothing wanted, all healthy, all subtitles found. */
    SmoothSailing,
    /** A gangplank to a ship nobody has boarded yet: no users or invitations. */
    Crew,
    /** A compass whose needle won't settle: not found / unsupported. */
    Compass,
    /** A sailboat heading off into the sun: done, ready to go. */
    SetSail,
}

/**
 * A nautical spot illustration, [size] wide (and 120/132 as tall). Decorative: it has no semantics, so put the
 * meaning in nearby text. Gently animated (bobbing, twinkling, a pulsing beam) unless the system "Remove
 * animations" setting is on or [animate] is false, in which case it shows the static mid-pose.
 */
@Composable
fun SeaArt(scene: SeaScene, modifier: Modifier = Modifier, size: Dp = 160.dp, animate: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    val palette = remember(scheme) { SeaPalette(scheme) }
    val art = remember(scene) { compiled(scene) }
    val still = !animate || rememberReducedMotion()
    val clock: State<Float>? = if (still) null else rememberInfiniteTransition(label = "sea").animateFloat(
        initialValue = 0f,
        targetValue = LOOP,
        animationSpec = infiniteRepeatable(tween((LOOP * 1000).toInt(), easing = LinearEasing), RepeatMode.Restart),
        label = "seaClock",
    )
    val pose = remember { Pose() }
    Canvas(modifier.size(size, size * (VIEW_H / VIEW_W))) {
        // Read the clock only here, in the draw phase: animation redraws, it never recomposes.
        val t = clock?.value ?: -1f
        val k = this.size.width / VIEW_W
        withTransform({ scale(k, k, pivot = Offset.Zero) }) {
            drawCircle(palette.backdrop, radius = DISC_R, center = DISC_C)
            val layers = art.spec.layers
            for (i in layers.indices) {
                val layer = layers[i]
                pose.set(layer.motion, t)
                val color = palette[layer.ink]
                val alpha = (layer.alpha * pose.alpha).coerceIn(0f, 1f)
                val pivot = if (pose.swing) art.spec.swing else art.spec.pivot
                if (layer.clip) clipPath(art.disc) { drawPosed(art.paths[i], color, alpha, art.styles[i], pose, pivot) }
                else drawPosed(art.paths[i], color, alpha, art.styles[i], pose, pivot)
            }
        }
    }
}

/**
 * True when the user has asked the system to remove animations (Settings › Accessibility › Remove animations sets
 * the animator duration scale to 0). Previews count as reduced so screenshots are deterministic.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val ctx = LocalContext.current
    val inspection = LocalInspectionMode.current
    return remember(ctx, inspection) {
        inspection || runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
}

/* ================================================================ model */

/** Palette roles. See the table at the top of this file. */
internal enum class Ink {
    Sea, SeaDeep, Wave, WaveSoft, Foam, Sail, SailShade, Hull, HullStripe, Sun, Glow, Star, Accent, Bolt,
    Rock, RockShade, Wood, WoodShade, Paper, PaperShade, PaperLine, Glass, Cloud, Cloud2, Fog, Bird, Weed, Shade,
    /** Full-bleed only: the moon, lighthouse beams, sun glints on the water, a sandbar. */
    Moon, Beam, Glint, Sand,
    /** Full-bleed only: marker for "draw the front water surface here" (see [SeaBackdrop]). */
    Water,
}

/** How a layer moves. Every period divides [LOOP] so the whole scene loops seamlessly. */
internal enum class Motion {
    Still,
    /** ±2.6 up/down and ±2° roll about the scene pivot, 3.2 s: a floating boat or bottle. */
    Bob,
    /** A livelier bob for choppy water, 2.4 s. */
    Toss,
    /** Roll only, about the scene pivot: a boat swinging on its line, a buoy. */
    Rock,
    /** ±4° about the swing hinge, 4.8 s (and the opposite phase). */
    Swing, SwingAlt,
    /** A compass needle that won't settle. */
    Wander,
    /** Waves sliding ±1.6 sideways, 3.2 s, in opposite phases. */
    Wave, WaveAlt,
    /** Clouds and fog drifting ±3 sideways, 9.6 s. */
    Drift, DriftAlt,
    /** A gull riding the air: a slow figure of eight. */
    Glide, GlideAlt,
    /** Opacity 0.35↔1, 2.4 s, in three phases. */
    Twinkle, TwinkleAlt, TwinkleLate,
    /** Slow glow/beam pulse, 4.8 s. */
    Pulse, PulseAlt,
    /** A soft lightning flicker. */
    Flash,
}

internal class Layer(
    val d: String,
    val ink: Ink,
    val stroke: Float = 0f,
    val alpha: Float = 1f,
    val motion: Motion = Motion.Still,
    val clip: Boolean = false,
)

internal class SceneSpec(val pivot: Offset, val swing: Offset, val layers: List<Layer>)

private const val VIEW_W = 132f
private const val VIEW_H = 120f
private const val DISC_R = 52f
private val DISC_C = Offset(66f, 60f)
private const val LOOP = 9.6f
private const val TAU = (2 * PI).toFloat()

/** A scene with its paths parsed once per process (paths are immutable once built and only ever drawn). */
private class Compiled(val spec: SceneSpec, val paths: List<Path>, val styles: List<DrawStyle>, val disc: Path)

private val cache = EnumMap<SeaScene, Compiled>(SeaScene::class.java)

private fun compiled(scene: SeaScene): Compiled = cache.getOrPut(scene) {
    val spec = sceneSpec(scene)
    Compiled(
        spec = spec,
        paths = spec.layers.map { PathParser().parsePathString(it.d).toPath() },
        styles = spec.layers.map { if (it.stroke > 0f) Stroke(width = it.stroke, cap = StrokeCap.Round, join = StrokeJoin.Round) else Fill },
        disc = Path().apply { addOval(Rect(DISC_C, DISC_R)) },
    )
}

/* ================================================================ motion */

/** Per-frame transform of one layer; reused so drawing a frame allocates nothing. */
internal class Pose {
    var dx = 0f; var dy = 0f; var rot = 0f; var alpha = 1f; var swing = false

    /** [t] in seconds within the loop; negative means "no motion": the static mid-pose. */
    fun set(m: Motion, t: Float) {
        dx = 0f; dy = 0f; rot = 0f; alpha = 1f; swing = false
        if (t < 0f) {
            alpha = when (m) { Motion.Pulse, Motion.PulseAlt -> 0.8f; Motion.Flash -> 0.9f; else -> 1f }
            return
        }
        fun s(period: Float, phase: Float = 0f) = sin(TAU * t / period + phase)
        when (m) {
            Motion.Still -> Unit
            Motion.Bob -> { dy = 2.6f * s(3.2f); rot = 2f * s(3.2f, TAU / 4) }
            Motion.Toss -> { dy = 2.8f * s(2.4f); rot = 5f * s(2.4f, TAU / 4) }
            Motion.Rock -> rot = 2.2f * s(3.2f)
            Motion.Swing -> { rot = 4f * s(4.8f); swing = true }
            Motion.SwingAlt -> { rot = -4f * s(4.8f, 1f); swing = true }
            Motion.Wander -> { rot = 24f * s(9.6f) + 9f * s(2.4f); swing = true }
            Motion.Wave -> dx = 1.6f * s(3.2f)
            Motion.WaveAlt -> dx = -1.6f * s(3.2f)
            Motion.Drift -> dx = 3f * s(9.6f)
            Motion.DriftAlt -> dx = -3f * s(9.6f)
            Motion.Glide -> { dx = 1.6f * s(9.6f); dy = 2.2f * s(4.8f) }
            Motion.GlideAlt -> { dx = 1.6f * s(9.6f, TAU / 2); dy = 2.2f * s(4.8f, TAU / 2) }
            Motion.Twinkle -> alpha = 0.675f + 0.325f * s(2.4f)
            Motion.TwinkleAlt -> alpha = 0.675f + 0.325f * s(2.4f, TAU / 3)
            Motion.TwinkleLate -> alpha = 0.675f + 0.325f * s(2.4f, 2 * TAU / 3)
            Motion.Pulse -> alpha = 0.75f + 0.25f * s(4.8f)
            Motion.PulseAlt -> alpha = 0.75f + 0.25f * s(4.8f, TAU / 2)
            Motion.Flash -> alpha = 0.55f + 0.45f * sqrt(max(0f, s(2.4f)))
        }
    }
}

internal fun DrawScope.drawPosed(path: Path, color: Color, alpha: Float, style: DrawStyle, pose: Pose, pivot: Offset) {
    if (pose.dx == 0f && pose.dy == 0f && pose.rot == 0f) {
        drawPath(path, color, alpha, style)
    } else {
        withTransform({
            translate(pose.dx, pose.dy)
            rotate(pose.rot, pivot)
        }) { drawPath(path, color, alpha, style) }
    }
}

/* ================================================================ palette */

/** Resolves every [Ink] role from a colour scheme once (see the table at the top of the file). */
internal class SeaPalette(c: ColorScheme, fullBleed: Boolean = false) {
    val dark = c.surface.luminance() < 0.5f
    val backdrop: Color = if (dark) lerp(c.surfaceContainerHigh, c.primaryContainer, 0.35f) else lerp(c.surface, c.primaryContainer, 0.55f)
    private val inks = Array(Ink.entries.size) { i -> resolve(Ink.entries[i], c) }.also {
        // On a full-bleed sea the water is darker than the disc backdrop: lift hulls so they don't sink into it.
        if (fullBleed && dark) {
            it[Ink.Hull.ordinal] = lerp(c.primary, c.onSurface, 0.1f)
            it[Ink.HullStripe.ordinal] = lerp(c.primaryContainer, c.surface, 0.2f)
        }
    }

    operator fun get(ink: Ink): Color = inks[ink.ordinal]

    private fun resolve(ink: Ink, c: ColorScheme): Color = if (dark) when (ink) {
        Ink.Sea -> lerp(c.primaryContainer, c.primary, 0.22f)
        Ink.SeaDeep -> lerp(c.primaryContainer, c.surface, 0.25f)
        Ink.Wave -> c.primary
        Ink.WaveSoft -> lerp(c.primary, c.primaryContainer, 0.45f)
        Ink.Foam -> lerp(c.primary, c.onSurface, 0.6f)
        Ink.Sail -> lerp(c.onSurface, c.secondary, 0.25f)
        Ink.SailShade -> lerp(c.secondary, c.secondaryContainer, 0.35f)
        Ink.Hull -> lerp(c.primary, c.primaryContainer, 0.5f)
        Ink.HullStripe -> lerp(c.primary, c.onSurface, 0.4f)
        Ink.Sun -> c.tertiary
        Ink.Glow -> lerp(c.tertiaryContainer, c.tertiary, 0.25f)
        Ink.Star, Ink.Accent, Ink.Bolt -> c.tertiary
        Ink.Rock -> lerp(c.outlineVariant, c.outline, 0.35f)
        Ink.RockShade -> lerp(c.outlineVariant, c.surface, 0.25f)
        Ink.Wood -> lerp(c.tertiaryContainer, c.tertiary, 0.35f)
        Ink.WoodShade -> lerp(c.tertiaryContainer, c.surface, 0.3f)
        Ink.Paper -> lerp(c.onSurface, c.surfaceVariant, 0.12f)
        Ink.PaperShade -> lerp(c.outline, c.onSurface, 0.35f)
        Ink.PaperLine -> c.outline
        Ink.Glass -> lerp(c.primaryContainer, c.primary, 0.3f)
        Ink.Cloud -> lerp(c.primaryContainer, c.onSurface, 0.3f)
        Ink.Cloud2 -> lerp(c.outline, c.primaryContainer, 0.35f)
        Ink.Fog -> lerp(c.primaryContainer, c.onSurface, 0.45f)
        Ink.Bird -> lerp(c.onSurfaceVariant, c.primary, 0.3f)
        Ink.Weed -> lerp(c.primary, c.tertiary, 0.3f)
        Ink.Shade -> c.scrim
        Ink.Moon -> lerp(c.tertiary, c.onSurface, 0.4f)
        Ink.Beam -> lerp(c.tertiaryContainer, c.tertiary, 0.3f)
        Ink.Glint -> lerp(c.tertiary, c.onSurface, 0.3f)
        Ink.Sand -> lerp(c.tertiaryContainer, c.outline, 0.25f)
        Ink.Water -> c.primaryContainer
    } else when (ink) {
        Ink.Sea -> lerp(c.primaryContainer, c.primary, 0.38f)
        Ink.SeaDeep -> lerp(c.primaryContainer, c.primary, 0.55f)
        Ink.Wave -> c.primary
        Ink.WaveSoft -> lerp(c.primary, c.primaryContainer, 0.45f)
        Ink.Foam, Ink.Sail, Ink.Paper, Ink.Cloud, Ink.Fog -> c.surface
        Ink.SailShade -> lerp(c.secondaryContainer, c.secondary, 0.32f)
        Ink.Hull -> lerp(c.primary, c.onPrimaryContainer, 0.35f)
        Ink.HullStripe -> lerp(c.primaryContainer, c.surface, 0.3f)
        Ink.Sun -> lerp(c.tertiaryContainer, c.tertiary, 0.3f)
        Ink.Glow -> c.tertiaryContainer
        Ink.Star, Ink.Accent -> lerp(c.tertiary, c.tertiaryContainer, 0.2f)
        Ink.Bolt -> lerp(c.tertiaryContainer, c.tertiary, 0.45f)
        Ink.Rock -> lerp(c.surfaceVariant, c.outline, 0.42f)
        Ink.RockShade -> lerp(c.surfaceVariant, c.outline, 0.62f)
        Ink.Wood -> lerp(c.tertiary, c.tertiaryContainer, 0.42f)
        Ink.WoodShade -> lerp(c.tertiary, c.onTertiaryContainer, 0.25f)
        Ink.PaperShade -> lerp(c.surfaceVariant, c.outlineVariant, 0.5f)
        Ink.PaperLine -> lerp(c.outlineVariant, c.outline, 0.35f)
        Ink.Glass -> lerp(c.primaryContainer, c.surface, 0.45f)
        Ink.Cloud2 -> lerp(c.surfaceVariant, c.outline, 0.45f)
        Ink.Bird -> lerp(c.onSurfaceVariant, c.primary, 0.4f)
        Ink.Weed -> lerp(c.primary, c.tertiary, 0.25f)
        Ink.Shade -> c.onSurface
        Ink.Moon -> lerp(c.tertiaryContainer, c.surface, 0.3f)
        Ink.Beam -> c.tertiaryContainer
        Ink.Glint -> c.surface
        Ink.Sand -> lerp(c.tertiaryContainer, c.surfaceVariant, 0.5f)
        Ink.Water -> c.primaryContainer
    }
}

/* ================================================================ previews */

@Composable
private fun SeaGallery(scheme: ColorScheme) {
    MaterialTheme(colorScheme = scheme) {
        Surface {
            FlowRow(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SeaScene.entries.forEach { s ->
                    Column {
                        SeaArt(s, size = 132.dp, animate = false)
                        Text(s.name, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Preview(name = "Sea scenes · light", widthDp = 600)
@Composable
private fun SeaGalleryLight() = SeaGallery(lightColorScheme())

@Preview(name = "Sea scenes · dark", widthDp = 600)
@Composable
private fun SeaGalleryDark() = SeaGallery(darkColorScheme())
