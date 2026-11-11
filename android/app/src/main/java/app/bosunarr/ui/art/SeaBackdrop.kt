package app.bosunarr.ui.art

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.EnumMap
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * Full-bleed sea scenes: the same 13 metaphors as [SeaArt], drawn as a whole-screen "wallpaper": a sky gradient, the
 * horizon at roughly 55% of the height, the subject (boat, lighthouse, bottle…) sitting on it, and calm water across
 * the bottom third where an empty state's text goes.
 *
 * Layout ([SeaGeometry]): the bottom `calmFraction` of the height (at least 150dp, at most half) is the calm zone. The
 * subject is designed on a 132-unit stage (horizon y = 80, calm zone from y = 104) and scaled by
 * min(width / 132, 88% of the space above the calm zone / 104, 3.4dp per unit), centred horizontally, so it is never
 * cropped or giant from a tall phone (0.45) to a tablet in landscape (1.6+). Sky, sea, wave rows, stars and extra
 * clouds are generated across the full width; wave rows are snapped to absolute stage x so they don't jump when the
 * width changes. Everything size-dependent is built once per size in drawWithCache; a frame only reads the clock and
 * draws cached paths, so animation allocates nothing and never recomposes.
 *
 * Colours: every role of [SeaPalette] (see SeaArt.kt) plus the sky/sea of the scene's [SeaMood]:
 *   light: sky surface→primaryContainer (Day), →tertiaryContainer at the horizon (Dusk), primaryContainer→primary
 *          (Night), surfaceVariant (Fog), outline-grey (Storm); sea primaryContainer→primary 55% at the horizon,
 *          fading to 25% → 10% in the calm zone, so onPrimaryContainer text stays ≥ 7:1 (hint ≥ 5.9:1).
 *   dark:  sky from surface into primaryContainer (tertiaryContainer at dusk); sea primaryContainer→primary 30% at
 *          the horizon, deepening to primaryContainer→surface 20–50% in the calm zone (onPrimaryContainer ≥ 7:1,
 *          onSurfaceVariant ≥ 5.5:1).
 */

/** Weather/time of day of a full-bleed scene: sets the sky and tints the sea. */
internal enum class SeaMood { Day, Dusk, Night, Fog, Storm }

internal class FullSpec(
    val mood: SeaMood,
    /** Stage y of the front water surface (see [Ink.Water]). */
    val water: Float,
    val pivot: Offset,
    val swing: Offset,
    val layers: List<Layer>,
    /** Bigger, tighter wave rows (Storm). */
    val choppy: Boolean = false,
)

/** Text colours that read on the calm water of a [SeaBackdrop], in light and dark. */
@Immutable
class SeaTextColors(val title: Color, val hint: Color)

@Composable
fun rememberSeaTextColors(): SeaTextColors {
    val c = MaterialTheme.colorScheme
    return remember(c) {
        val dark = SeaPalette(c).dark
        SeaTextColors(c.onPrimaryContainer, if (dark) c.onSurfaceVariant else lerp(c.onPrimaryContainer, c.primary, 0.3f))
    }
}

/**
 * Top of the calm zone (where text should start) for a [SeaBackdrop] of this size. [calmFraction] must match the one
 * passed to [SeaBackdrop].
 */
fun seaCalmTop(width: Dp, height: Dp, calmFraction: Float = DEFAULT_CALM): Dp =
    SeaGeometry(width.value, height.value, 1f, calmFraction).calmTop.dp

const val DEFAULT_CALM = 0.36f

/**
 * A whole-screen sea scene filling its bounds. Put the empty state's text over the bottom [calmFraction] (from
 * [seaCalmTop]) in [rememberSeaTextColors]. Decorative (no semantics). Animated unless the system "Remove animations"
 * setting is on or [animate] is false.
 */
@Composable
fun SeaBackdrop(scene: SeaScene, modifier: Modifier = Modifier, calmFraction: Float = DEFAULT_CALM, animate: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    val art = remember(scene) { fullCompiled(scene) }
    val colors = remember(scheme, art.spec.mood) { MoodColors(scheme, art.spec.mood) }
    val still = !animate || rememberReducedMotion()
    val clock: State<Float>? = if (still) null else rememberInfiniteTransition(label = "seaBackdrop").animateFloat(
        initialValue = 0f,
        targetValue = 9.6f,
        animationSpec = infiniteRepeatable(tween(9600, easing = LinearEasing), RepeatMode.Restart),
        label = "seaBackdropClock",
    )
    val pose = remember { Pose() }
    Box(
        modifier.drawWithCache {
            val w = size.width
            val h = size.height
            val g = SeaGeometry(w, h, density, calmFraction)
            val k = g.k
            val spec = art.spec
            // Sea gradient stops: far at the horizon, mid by stage y 96, calm from the calm zone down.
            val seaH = max(1f, h - g.horizon)
            val fMid = ((g.horizon + (96f - HORIZON) * k - g.horizon) / seaH).coerceIn(0.01f, 0.97f)
            val fCalm = ((g.calmTop - g.horizon) / seaH).coerceIn(fMid + 0.01f, 0.99f)
            val sky = Brush.verticalGradient(listOf(colors.skyTop, colors.skyHorizon), startY = 0f, endY = max(1f, g.horizon))
            val sea = Brush.verticalGradient(
                0f to colors.far, fMid to colors.mid, fCalm to colors.calm, 1f to colors.calm2,
                startY = g.horizon, endY = h,
            )
            // The same gradient in stage units, for the front water surface drawn inside the stage transform.
            val stageBottom = (h - g.oy) / k
            val seaStage = Brush.verticalGradient(
                0f to colors.far, fMid to colors.mid, fCalm to colors.calm, 1f to colors.calm2,
                startY = HORIZON, endY = max(HORIZON + 1f, stageBottom),
            )
            val x0 = -g.ox / k - 20f
            val x1 = (w - g.ox) / k + 20f
            val rows = Array(ROWS.size) { i -> rowPath(i, ROWS[i], x0, x1, spec.choppy) }
            val rowStyles = Array(ROWS.size) { i -> Stroke(width = ROWS[i].stroke, cap = StrokeCap.Round, join = StrokeJoin.Round) }
            val water = waterPath(spec.water, x0 - 10f, x1 + 10f, CALM + 8f)
            val night = spec.mood == SeaMood.Night
            val starR = 1.dp.toPx()
            val cloudy = spec.mood != SeaMood.Night && spec.mood != SeaMood.Fog && g.oy > 70.dp.toPx()
            val cloudColor = if (spec.mood == SeaMood.Storm) colors.palette[Ink.Cloud2] else colors.palette[Ink.Cloud]
            onDrawBehind {
                val t = clock?.value ?: -1f
                drawRect(sky, size = Size(w, g.horizon + 1f))
                drawRect(sea, topLeft = Offset(0f, g.horizon), size = Size(w, h - g.horizon))
                if (night) {
                    var i = 0
                    while (i < STARS.size) {
                        pose.set(TWINKLES[(i / 3) % 3], t)
                        val s = STARS[i + 2]
                        drawCircle(colors.star, radius = starR * (0.8f + 1.2f * s), center = Offset(STARS[i] * w, STARS[i + 1] * g.horizon * 0.85f), alpha = pose.alpha * (0.5f + 0.5f * s))
                        i += 3
                    }
                }
                if (cloudy) {
                    var i = 0
                    while (i < SKY_CLOUDS.size) {
                        pose.set(if (i % 2 == 0) Motion.Drift else Motion.DriftAlt, t)
                        val sc = k * SKY_CLOUDS[i + 2]
                        withTransform({
                            translate(SKY_CLOUDS[i] * w + pose.dx * k, SKY_CLOUDS[i + 1] * g.oy)
                            scale(sc, sc, Offset.Zero)
                        }) { drawPath(art.skyCloud, cloudColor, alpha = 0.7f) }
                        i += 3
                    }
                }
                withTransform({
                    translate(g.ox, g.oy)
                    scale(k, k, Offset.Zero)
                }) {
                    for (i in rows.indices) {
                        pose.set(ROWS[i].motion, t)
                        translate(pose.dx, 0f) { drawPath(rows[i], colors.palette[ROWS[i].ink], ROWS[i].alpha, rowStyles[i]) }
                    }
                    val layers = spec.layers
                    for (i in layers.indices) {
                        val layer = layers[i]
                        pose.set(layer.motion, t)
                        if (layer.ink == Ink.Water) {
                            translate(pose.dx, 0f) { drawPath(water, seaStage) }
                            continue
                        }
                        val color = if (night && layer.ink == Ink.Star) colors.star else colors.palette[layer.ink]
                        val alpha = (layer.alpha * pose.alpha).coerceIn(0f, 1f)
                        val pivot = if (pose.swing) spec.swing else spec.pivot
                        if (layer.clip) clipRect(-1e4f, -1e4f, 1e4f, HORIZON) { drawPosed(art.paths[i], color, alpha, art.styles[i], pose, pivot) }
                        else drawPosed(art.paths[i], color, alpha, art.styles[i], pose, pivot)
                    }
                }
            }
        },
    )
}

/* ================================================================ geometry */

internal const val HORIZON = 80f
internal const val CALM = 104f

/** Where everything goes for a [w]×[h] canvas; [unit] is pixels per dp (1 for dp maths). */
internal class SeaGeometry(w: Float, h: Float, unit: Float, calmFraction: Float) {
    private val zone = min(max(h * calmFraction, 150f * unit), h * 0.5f)
    val calmTop = h - zone
    /** Pixels per stage unit. */
    val k = min(min(w / 132f, calmTop * 0.88f / CALM), 3.4f * unit)
    val horizon = calmTop - (CALM - HORIZON) * k
    val ox = w / 2f - 66f * k
    val oy = horizon - HORIZON * k
}

/* ================================================================ wave rows */

internal class Row(val y: Float, val amp: Float, val half: Float, val stroke: Float, val alpha: Float, val ink: Ink, val segLen: Float, val gap: Float, val motion: Motion)

/** Rows of broken wave lines between the horizon and the calm zone, far (thin, faint) to near. */
private val ROWS = arrayOf(
    Row(83f, 0.5f, 5f, 1.1f, 0.55f, Ink.WaveSoft, 14f, 22f, Motion.Wave),
    Row(86.5f, 0.8f, 7f, 1.5f, 0.7f, Ink.WaveSoft, 21f, 30f, Motion.WaveAlt),
    Row(91f, 1.1f, 9f, 2f, 0.6f, Ink.Wave, 27f, 46f, Motion.Wave),
    Row(97.5f, 1.2f, 11f, 2.2f, 0.55f, Ink.Foam, 33f, 70f, Motion.WaveAlt),
)

private val TWINKLES = arrayOf(Motion.Twinkle, Motion.TwinkleAlt, Motion.TwinkleLate)

private fun rnd(n: Int): Double {
    val x = sin(n * 12.9898 + 78.233) * 43758.5453
    return x - floor(x)
}

/** Deterministic broken wave line across [x0]..[x1], snapped to the half-wave grid in absolute stage x. */
private fun rowPath(index: Int, r: Row, x0: Float, x1: Float, choppy: Boolean): Path {
    val amp = if (choppy) r.amp * 1.9f else r.amp
    val half = if (choppy) r.half * 0.75f else r.half
    val p = Path()
    var x = -2000.0
    var n = 0
    while (x < x1) {
        val len = r.segLen * (0.6 + 0.8 * rnd(index * 1000 + n))
        val gap = r.gap * (0.5 + rnd(index * 1000 + n + 500))
        val a = floor(x / half) * half
        val b = a + max(2, (len / half).roundToInt()) * half
        if (b > x0 - 20) {
            p.moveTo(a.toFloat(), r.y)
            var xx = a
            while (xx < b - 0.01) {
                val up = ((xx / half).roundToInt() % 2 == 0)
                val kk = 1.33f * amp * (if (up) -1f else 1f)
                p.cubicTo((xx + half / 3).toFloat(), r.y + kk, (xx + 2 * half / 3).toFloat(), r.y + kk, (xx + half).toFloat(), r.y)
                xx += half
            }
        }
        x = b + gap
        n++
    }
    return p
}

/** The front water surface: a gentle wave at stage [y], filled down to [bottom]. */
private fun waterPath(y: Float, x0: Float, x1: Float, bottom: Float): Path {
    val half = 10f
    val a = floor(x0 / half) * half
    val p = Path()
    p.moveTo(a, y)
    var xx = a
    while (xx < x1) {
        val up = ((xx / half).roundToInt() % 2 == 0)
        val kk = 1.33f * (if (up) -1f else 1f)
        p.cubicTo(xx + half / 3, y + kk, xx + 2 * half / 3, y + kk, xx + half, y)
        xx += half
    }
    p.lineTo(xx, bottom)
    p.lineTo(a, bottom)
    p.close()
    return p
}

/* ================================================================ colours */

private class MoodColors(c: ColorScheme, mood: SeaMood) {
    val palette = SeaPalette(c, fullBleed = true)
    private val dark = palette.dark
    val skyTop: Color
    val skyHorizon: Color
    val far: Color
    val mid: Color
    val calm: Color
    val calm2: Color
    val star: Color = if (dark) c.tertiary else c.surface

    init {
        if (!dark) {
            when (mood) {
                SeaMood.Day -> { skyTop = lerp(c.surface, c.primaryContainer, 0.2f); skyHorizon = lerp(c.surface, c.primaryContainer, 0.75f) }
                SeaMood.Dusk -> { skyTop = lerp(c.surface, c.primaryContainer, 0.55f); skyHorizon = lerp(c.surface, c.tertiaryContainer, 0.95f) }
                SeaMood.Night -> { skyTop = lerp(c.primaryContainer, c.primary, 0.62f); skyHorizon = lerp(c.primaryContainer, c.surface, 0.15f) }
                SeaMood.Fog -> { skyTop = lerp(c.surface, c.surfaceVariant, 0.7f); skyHorizon = lerp(c.surface, c.primaryContainer, 0.3f) }
                SeaMood.Storm -> { skyTop = lerp(c.surfaceVariant, c.outline, 0.4f); skyHorizon = lerp(c.surface, c.surfaceVariant, 0.8f) }
            }
            var f = lerp(c.primaryContainer, c.primary, 0.55f)
            var m = lerp(c.primaryContainer, c.primary, 0.36f)
            calm = lerp(c.primaryContainer, c.primary, 0.25f)
            calm2 = lerp(c.primaryContainer, c.primary, 0.1f)
            when (mood) {
                SeaMood.Fog -> { f = lerp(f, palette[Ink.Fog], 0.45f); m = lerp(m, palette[Ink.Fog], 0.3f) }
                SeaMood.Storm -> f = lerp(f, palette[Ink.Cloud2], 0.3f)
                SeaMood.Dusk -> f = lerp(f, c.tertiaryContainer, 0.3f)
                else -> Unit
            }
            far = f; mid = m
        } else {
            when (mood) {
                SeaMood.Day -> { skyTop = lerp(c.surface, c.primaryContainer, 0.25f); skyHorizon = lerp(c.surfaceContainerHigh, c.primaryContainer, 0.7f) }
                SeaMood.Dusk -> { skyTop = lerp(c.surface, c.primaryContainer, 0.35f); skyHorizon = lerp(c.primaryContainer, c.tertiaryContainer, 0.75f) }
                SeaMood.Night -> { skyTop = lerp(c.surface, c.primaryContainer, 0.08f); skyHorizon = lerp(c.surface, c.primaryContainer, 0.6f) }
                SeaMood.Fog -> { skyTop = lerp(c.surface, c.onSurface, 0.12f); skyHorizon = lerp(c.primaryContainer, c.onSurface, 0.28f) }
                SeaMood.Storm -> { skyTop = lerp(c.surface, c.outline, 0.15f); skyHorizon = lerp(c.surfaceContainerHigh, c.outline, 0.35f) }
            }
            var f = lerp(c.primaryContainer, c.primary, 0.3f)
            var m = lerp(c.primaryContainer, c.primary, 0.06f)
            calm = lerp(c.primaryContainer, c.surface, 0.2f)
            calm2 = lerp(c.primaryContainer, c.surface, 0.5f)
            when (mood) {
                SeaMood.Fog -> { f = lerp(f, palette[Ink.Fog], 0.45f); m = lerp(m, palette[Ink.Fog], 0.3f) }
                SeaMood.Storm -> f = lerp(f, palette[Ink.Cloud2], 0.3f)
                SeaMood.Dusk -> f = lerp(f, c.tertiaryContainer, 0.3f)
                else -> Unit
            }
            far = f; mid = m
        }
    }
}

/* ================================================================ cache */

private class FullCompiled(val spec: FullSpec, val paths: List<Path>, val styles: List<DrawStyle>, val skyCloud: Path)

private val fullCache = EnumMap<SeaScene, FullCompiled>(SeaScene::class.java)

private fun fullCompiled(scene: SeaScene): FullCompiled = fullCache.getOrPut(scene) {
    val spec = fullSceneSpec(scene)
    FullCompiled(
        spec = spec,
        paths = spec.layers.map { if (it.d.isEmpty()) Path() else PathParser().parsePathString(it.d).toPath() },
        styles = spec.layers.map { if (it.stroke > 0f) Stroke(width = it.stroke, cap = StrokeCap.Round, join = StrokeJoin.Round) else Fill },
        skyCloud = PathParser().parsePathString(SKY_CLOUD).toPath(),
    )
}

/* ================================================================ previews */

@Composable
private fun BackdropPreview(scheme: ColorScheme, scene: SeaScene) {
    MaterialTheme(colorScheme = scheme) {
        Box(Modifier.fillMaxSize()) {
            SeaBackdrop(scene, Modifier.fillMaxSize(), animate = false)
            Text(scene.name, Modifier.align(Alignment.BottomCenter), color = rememberSeaTextColors().title)
        }
    }
}

@Preview(name = "Backdrop · phone light", widthDp = 412, heightDp = 700)
@Composable
private fun BackdropPhoneLight() = BackdropPreview(lightColorScheme(), SeaScene.CalmSeas)

@Preview(name = "Backdrop · phone dark", widthDp = 412, heightDp = 700)
@Composable
private fun BackdropPhoneDark() = BackdropPreview(darkColorScheme(), SeaScene.Horizon)

@Preview(name = "Backdrop · landscape", widthDp = 892, heightDp = 330)
@Composable
private fun BackdropLandscape() = BackdropPreview(lightColorScheme(), SeaScene.Crew)
