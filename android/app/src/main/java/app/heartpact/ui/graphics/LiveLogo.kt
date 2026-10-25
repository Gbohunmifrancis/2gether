package app.heartpact.ui.graphics

import android.provider.Settings
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import app.heartpact.ui.theme.Art
import kotlin.math.PI
import kotlin.math.sin

/**
 * How the mark feels where it appears. It hops, blinks and pulls a face to suit the screen; the tick, the brand,
 * never changes.
 */
enum class LogoMood {
    /** Welcome: a happy hop now and then, blinking. */
    Happy,
    /** Sign in: a hop with a little tilt, and a wink. */
    Hello,
    /** Something went wrong: brows up, a small shiver, no hopping. */
    Worried,
    /** Waiting for the partner: eyes glancing side to side. */
    Waiting,
    /** Something worth celebrating: quick hops, eyes squeezed happy. */
    Cheer,
    /** Late at night: eyes closed, breathing slowly, a few z's. */
    Sleepy,
    /** Out of the way (the navigation rail): breathing and blinking only. */
    Idle,
}

/**
 * True holds every [LiveLogo] still, at the moment it would start: for screenshot tests and previews, which wait for
 * the screen to stop changing.
 */
val LocalStillLogo = staticCompositionLocalOf { false }

/**
 * The mark, alive: [LogoMark] with eyes, hopping and blinking by [mood]. [size] is the mark's width, as for
 * [LogoMark]; the canvas leaves room above it for hops. Still, with eyes open, when animations are off.
 */
@Composable
fun LiveLogo(size: Dp, mood: LogoMood, night: Boolean, modifier: Modifier = Modifier, frozenAtMs: Float? = null) {
    val context = LocalContext.current
    var time by remember { mutableFloatStateOf(frozenAtMs ?: 0f) }
    val still = LocalStillLogo.current || LocalInspectionMode.current
    if (frozenAtMs == null && !still) LaunchedEffect(mood) {
        if (Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f) return@LaunchedEffect
        // Infinite-animation frames, so tests (and anything else that waits for the screen to settle) don't wait forever.
        val start = withInfiniteAnimationFrameMillis { it }
        while (true) time = withInfiniteAnimationFrameMillis { (it - start).toFloat() }
    }
    Canvas(modifier.size(size, size * (LIVE_H / LIVE_W)).graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
        inUnits(LIVE_X, LIVE_Y, LIVE_W, LIVE_H) { drawLiveLogo(time, mood, night) }
    }
}

// The mark's own box (-2, 4.4, 104 × 97.6) with room above for hops and z's.
private const val LIVE_X = -6f
private const val LIVE_Y = -14f
private const val LIVE_W = 112f
private const val LIVE_H = 118f

/** One frame of the live mark at [t] ms, in heart units. Pure, so every frame can be checked. */
fun DrawScope.drawLiveLogo(t: Float, mood: LogoMood, night: Boolean, faceAlpha: Float = 1f) {
    val motion = motion(t, mood)
    withTransform({
        translate(motion.dx, motion.dy)
        rotate(motion.tilt, pivot = Offset(50f, 100f))
        scale(motion.sx, motion.sy, pivot = Offset(50f, 100f))
    }) {
        drawMark(if (night) Art.MarkNightLeft else Art.Left, if (night) Art.MarkNightRight else Art.Right)
        if (faceAlpha > 0f) drawLogoFace(t, mood, night, faceAlpha)
    }
    if (mood == LogoMood.Sleepy) drawZs(t)
}

private class Motion(val dx: Float = 0f, val dy: Float = 0f, val sx: Float = 1f, val sy: Float = 1f, val tilt: Float = 0f)

/** Hops land with a squash; quiet moods breathe instead. */
private fun motion(t: Float, mood: LogoMood): Motion {
    val (every, height) = when (mood) {
        LogoMood.Happy -> 2600f to 8f
        LogoMood.Hello -> 2200f to 7f
        LogoMood.Waiting -> 3400f to 5f
        LogoMood.Cheer -> 1100f to 12f
        else -> 0f to 0f
    }
    if (every > 0f) {
        val local = t % every
        val crouch = 110f
        val air = 480f
        val land = 170f
        return when {
            local < crouch -> { val p = local / crouch; Motion(sy = 1f - 0.05f * bump(p), sx = 1f + 0.03f * bump(p)) }
            local < crouch + air -> {
                val p = (local - crouch) / air
                val tilt = if (mood == LogoMood.Hello) 7f * sin(2f * PI.toFloat() * p) else 0f
                Motion(dy = -height * sin(PI.toFloat() * p), sy = 1f + 0.04f * sin(PI.toFloat() * p), sx = 1f - 0.03f * sin(PI.toFloat() * p), tilt = tilt)
            }
            local < crouch + air + land -> { val p = (local - crouch - air) / land; Motion(sy = 1f - 0.09f * bump(p), sx = 1f + 0.06f * bump(p)) }
            else -> Motion()
        }
    }
    return when (mood) {
        LogoMood.Worried -> {
            // A short shiver every couple of seconds.
            val local = t % 2400f
            Motion(dx = if (local < 320f) 1.6f * sin(2f * PI.toFloat() * local / 80f) else 0f)
        }
        else -> {
            val breath = sin(2f * PI.toFloat() * t / if (mood == LogoMood.Sleepy) 4200f else 3000f)
            Motion(sx = 1f + 0.015f * breath, sy = 1f + 0.025f * breath)
        }
    }
}

private fun bump(p: Float) = sin(PI.toFloat() * p.coerceIn(0f, 1f))

// High in each lobe, mirrored about the heart's middle (x 50), above the tick's end cap (74, 38; radius 4.75).
private val LEFT_EYE = Offset(30f, 25f)
private val RIGHT_EYE = Offset(70f, 25f)
private val INK = Color(0xFF161A3D)
private val BLUSH = Color(0xFFE58AA8)

/**
 * Eyes clear of the tick (which ends at 74, 38): white on the indigo half, ink on the periwinkle one. [open] is how
 * open the eyes are (1 open, 0 shut); the splash opens them itself.
 */
internal fun DrawScope.drawLogoFace(t: Float, mood: LogoMood, night: Boolean, alpha: Float, open: Float = blinkOpen(t, mood)) {
    val leftInk = if (night) INK else Color.White
    val rightInk = INK
    val line = Stroke(3.2f, cap = StrokeCap.Round)
    val eyes = listOf(LEFT_EYE to leftInk, RIGHT_EYE to rightInk)
    when (mood) {
        LogoMood.Cheer -> eyes.forEach { (at, ink) -> drawPath(arc(at, up = true), ink, alpha = alpha, style = line) }
        LogoMood.Sleepy -> eyes.forEach { (at, ink) -> drawPath(arc(at, up = false), ink, alpha = alpha, style = line) }
        else -> {
            val glance = if (mood == LogoMood.Waiting) 2.6f * sin(2f * PI.toFloat() * t / 3400f).let { if (it > 0.5f) 1f else if (it < -0.5f) -1f else it * 2f } else 0f
            val wink = mood == LogoMood.Hello && (t % 3600f) in 2600f..3050f
            eyes.forEachIndexed { index, (at, ink) ->
                if (wink && index == 1) drawPath(arc(at, up = true), ink, alpha = alpha, style = line)
                else drawEye(at.copy(x = at.x + glance), ink, open, small = mood == LogoMood.Worried, catchlight = if (index == 0 && !night) Art.Left else Color.White, alpha = alpha)
            }
            if (mood == LogoMood.Worried) {
                drawPath(Path().apply { moveTo(LEFT_EYE.x - 6f, LEFT_EYE.y - 8f); lineTo(LEFT_EYE.x + 2.5f, LEFT_EYE.y - 10.5f) }, leftInk, alpha = alpha, style = Stroke(2.8f, cap = StrokeCap.Round))
                drawPath(Path().apply { moveTo(RIGHT_EYE.x + 6f, RIGHT_EYE.y - 8f); lineTo(RIGHT_EYE.x - 2.5f, RIGHT_EYE.y - 10.5f) }, rightInk, alpha = alpha, style = Stroke(2.8f, cap = StrokeCap.Round))
            }
        }
    }
    if (mood != LogoMood.Worried && mood != LogoMood.Idle) {
        // Outside each eye, the right one kept off the tick's end.
        listOf(Offset(LEFT_EYE.x - 16f, LEFT_EYE.y + 5f), Offset(RIGHT_EYE.x + 8f, RIGHT_EYE.y + 5f))
            .forEach { drawOval(BLUSH, it, Size(8f, 4.5f), alpha = 0.6f * alpha) }
    }
}

/** 1 open, 0 shut: a quick blink every few seconds, and a double blink now and then. */
private fun blinkOpen(t: Float, mood: LogoMood): Float {
    val every = if (mood == LogoMood.Idle) 4600f else 3800f
    val local = (t + 1700f) % every
    val second = (t + 1700f) % (every * 3) < every
    fun shut(at: Float) = if (local in at..at + 140f) 1f - bump((local - at) / 140f) else 1f
    return minOf(shut(0f), if (second) shut(260f) else 1f)
}

private fun DrawScope.drawEye(at: Offset, ink: Color, open: Float, small: Boolean, catchlight: Color, alpha: Float) {
    val w = if (small) 6.6f else 7.8f
    val h = (if (small) 8.6f else 10.4f) * open.coerceAtLeast(0.12f)
    drawOval(ink, Offset(at.x - w / 2, at.y - h / 2), Size(w, h), alpha = alpha)
    if (open > 0.6f) drawCircle(catchlight, 1.5f, Offset(at.x + 1.3f, at.y - 2f), alpha = alpha)
}

/** A happy "^" (up) or a sleeping "‿" (down). */
private fun arc(at: Offset, up: Boolean) = Path().apply {
    moveTo(at.x - 5.5f, at.y + if (up) 2.5f else -1f)
    quadraticTo(at.x, at.y + if (up) -6f else 6f, at.x + 5.5f, at.y + if (up) 2.5f else -1f)
}

/** Three z's drifting up from the right lobe, fading as they rise. */
private fun DrawScope.drawZs(t: Float) {
    repeat(3) { i ->
        val p = ((t + i * 1000f) % 3000f) / 3000f
        val size = 5f + 3f * p
        val x = 86f + 10f * p + i * 2f
        val y = 4f - 16f * p
        val z = Path().apply { moveTo(x, y); lineTo(x + size, y); lineTo(x, y + size); lineTo(x + size, y + size) }
        drawPath(z, Art.Right, alpha = (1f - p) * 0.9f, style = Stroke(1.8f, cap = StrokeCap.Round))
    }
}
