package app.heartpact.ui.play.skills

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import app.heartpact.i18n.tr
import app.heartpact.ui.play.BlobColour
import app.heartpact.ui.play.PlayInk
import app.heartpact.ui.play.SkillKind
import app.heartpact.ui.play.rememberPlayHaptics
import app.heartpact.ui.theme.Art
import app.heartpact.ui.theme.Heartpact
import kotlinx.coroutines.delay
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/*
 * Kiss the center: an odd shape, and one tap where you think its middle is (its centre of area, where it would
 * balance on a fingertip). The kiss drops in and squashes as it lands; then the true centre shows, with a line
 * between the two. Up the levels the shapes get odder, then turn while you aim, then drift too, and at the top they
 * fade from sight a moment after they appear, so you kiss from memory.
 */

/**
 * How a shape moves while you aim, by level: still up to 7, turning slowly about its centre from 8 (faster as the
 * levels rise), drifting a little too from 13, and from 15 fading out [fadeAfterMs] after it appears. Once it has
 * faded it stops where it was last seen, so there is a fair place to kiss.
 */
internal class KissMotion(
    /** Radians a second (the sign is the way round), 0 for still. */
    val turn: Float,
    /** How far it wanders from where it started, in units of the board; 0 for not at all. */
    val drift: Float,
    /** When it starts to fade, after appearing; null when it stays. */
    val fadeAfterMs: Long?,
    private val phase: Float,
) {
    val moves: Boolean get() = turn != 0f || drift > 0f || fadeAfterMs != null

    /** How far it has turned [ms] after appearing, in radians. */
    fun angle(ms: Long): Float = turn * clock(ms) / 1000f

    /** How far it has drifted [ms] after appearing, in units. Starts at zero. */
    fun offset(ms: Long): Offset {
        if (drift == 0f) return Offset.Zero
        val t = clock(ms) / 1000f
        return Offset(drift * (sin(1.7f * t + phase) - sin(phase)), drift * 0.8f * (sin(1.15f * t + 2 * phase) - sin(2 * phase)))
    }

    /** How visible it is [ms] after appearing: 1, then down to 0 over [FADE_MS]. */
    fun alpha(ms: Long): Float = fadeAfterMs?.let { 1f - ((ms - it) / FADE_MS.toFloat()).coerceIn(0f, 1f) } ?: 1f

    /** True once it has faded right out. */
    fun gone(ms: Long): Boolean = fadeAfterMs != null && ms >= fadeAfterMs + FADE_MS

    /** The clock it moves by: it stops when it has faded out, so it stays where it was last seen. */
    private fun clock(ms: Long): Long = fadeAfterMs?.let { minOf(ms, it + FADE_MS) } ?: ms

    companion object {
        const val FADE_MS = 450L

        fun of(seed: Long, d: Difficulty): KissMotion {
            val rnd = Random(seed xor 0x2F6B1A5L)
            val way = if (rnd.nextBoolean()) 1f else -1f
            val turn = if (d.from(8)) way * (0.22f + 0.38f * ramp(d.level, 8)) else 0f
            val drift = if (d.from(13)) 0.03f + 0.03f * ramp(d.level, 13) else 0f
            val fade = if (d.from(15)) 1500L else null
            return KissMotion(turn, drift, fade, rnd.nextFloat() * TAU)
        }

        /** 0 at [from], 1 at the top of the ramp. */
        private fun ramp(level: Int, from: Int) = ((level - from) / (Difficulty.RAMP - from).toFloat()).coerceIn(0f, 1f)
    }
}

internal class KissPlay(seed: Long, difficulty: Difficulty = Difficulty.Start) : SkillPlay(SkillKind.KissTheCenter, seed, difficulty) {
    // Its own shapes, even from the same seed as a Fair share round: lopsided ones, with no obvious middle.
    val treat = Treat.of(seed * 31 + 7, odd = true, difficulty = difficulty)
    val centre = centroid(treat.outline)
    val size = sqrt(area(treat.outline))
    val motion = KissMotion.of(seed, difficulty)
    /** Time since the shape appeared, in ms, while you aim; the board's clock runs it. */
    var clock by mutableLongStateOf(0L)
        private set
    var kiss by mutableStateOf<Offset?>(null)
        private set
    /** When the kiss landed. */
    var kissedAt = 0L
        private set

    /** Where the true centre is at [ms]: the shape turns about it, so only drifting moves it. */
    fun centreAt(ms: Long): Offset = centre + motion.offset(ms)

    init {
        hint = when {
            motion.fadeAfterMs != null -> tr("It moves, then fades. Look closely.")
            motion.drift > 0f -> tr("It turns and drifts while you aim.")
            motion.turn != 0f -> tr("It turns while you aim.")
            else -> tr("Where would it balance on a fingertip?")
        }
    }

    /** Moves the clock on to [ms] after the shape appeared. */
    fun tick(ms: Long) {
        if (kiss != null) return
        clock = ms
        if (motion.gone(ms)) hint = tr("Remember where it was.")
    }

    fun kissAt(u: Offset) {
        if (kiss != null) return
        kissedAt = clock
        kiss = u
        hint = ""
        finish(kissScore(u, centreAt(kissedAt), size, difficulty), 1500)
    }

    @Composable
    override fun Board(modifier: Modifier) {
        val colors = Heartpact.colors
        val haptics = rememberPlayHaptics()
        var box by remember { mutableStateOf(Size.Zero) }
        val fit = Fit.of(box, 1f, 1f, with(LocalDensity.current) { 20.dp.toPx() })
        val fitNow by rememberUpdatedState(fit)
        val drop = remember { Animatable(0f) }
        val land = remember { Animatable(1f) }
        val mark = remember { Animatable(0f) }
        val line = remember { Animatable(0f) }
        // Brings a faded shape back once the kiss is down, to show where it really was.
        val back = remember { Animatable(0f) }
        val fall = moving(tween<Float>(190, easing = FastOutSlowInEasing))
        val squash = moving(spring<Float>(dampingRatio = 0.3f, stiffness = 520f))
        val pop = moving(spring<Float>(dampingRatio = 0.45f, stiffness = 400f))
        val draw = moving(tween<Float>(420, easing = LinearOutSlowInEasing))
        val reappear = moving(tween<Float>(360))
        val placed = kiss
        if (motion.moves) {
            LaunchedEffect(Unit) {
                val start = withFrameMillis { it }
                while (kiss == null) withFrameMillis { tick(it - start) }
            }
        }
        LaunchedEffect(placed) {
            if (placed == null) return@LaunchedEffect
            drop.animateTo(1f, fall)
            haptics.thud()
            land.snapTo(0f)
            land.animateTo(1f, squash)
        }
        LaunchedEffect(placed) {
            if (placed == null) return@LaunchedEffect
            delay(560)
            haptics.tick()
            back.animateTo(1f, reappear)
            mark.animateTo(1f, pop)
            line.animateTo(1f, draw)
        }
        val ms = if (placed != null) kissedAt else clock
        Box(modifier) {
            // The shape on its own layer, so it can fade without the kiss fading with it.
            Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = maxOf(motion.alpha(ms), back.value) }) {
                val shift = motion.offset(ms) * fit.scale
                val turn = Math.toDegrees(motion.angle(ms).toDouble()).toFloat()
                withTransform({
                    translate(shift.x, shift.y)
                    rotate(turn, fit.px(centre))
                }) { drawTreat(treat, fit) }
            }
            Canvas(
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { box = it.toSize() }
                    .semantics { contentDescription = tr("An odd shape. Tap once where you think its center is.") }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            kissAt(fitNow.unit(down.position))
                        }
                    },
            ) {
                val k = placed ?: return@Canvas
                val at = fit.px(k)
                val c = fit.px(centreAt(kissedAt))
                drawKiss(at, drop.value, land.value)
                // The dashed line from the kiss to the true centre, drawn on.
                if (line.value > 0f) {
                    drawLine(colors.ink.copy(alpha = 0.85f), at, at + (c - at) * line.value, 2.5.dp.toPx(), cap = StrokeCap.Round,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 5.dp.toPx())))
                }
                if (mark.value > 0f) {
                    val r = 10.dp.toPx() * mark.value
                    drawCircle(Art.Keyline, r + 3.dp.toPx(), c)
                    drawCircle(colors.left.art.takeIf { !colors.isNight } ?: Art.Left, r, c, style = Stroke(2.5.dp.toPx()))
                    drawCircle(colors.left.art.takeIf { !colors.isNight } ?: Art.Left, 3.dp.toPx() * mark.value, c)
                }
            }
        }
    }

    /** The kiss at [at]: drops in from a little above, big and faint ([d] 0 to 1), then squashes flat and springs back ([l]). */
    private fun DrawScope.drawKiss(at: Offset, d: Float, l: Float) {
        val w = 44.dp.toPx()
        val sx = (1.7f - 0.7f * d) * (1f + 0.3f * (1f - l))
        val sy = (1.7f - 0.7f * d) * (1f - 0.3f * (1f - l))
        val lift = (1f - d) * 36.dp.toPx()
        withTransform({
            translate(at.x, at.y - lift)
            scale(sx, sy, Offset.Zero)
            rotate(-8f, Offset.Zero)
        }) {
            val lips = lipsPath(w)
            drawPath(lips, Art.Keyline.copy(alpha = d), style = Stroke(5.dp.toPx() / sx, join = StrokeJoin.Round))
            drawPath(lips, PlayInk.Pen.copy(alpha = d))
            drawPath(lipsSeam(w), BlobColour.Rose.ink.copy(alpha = d), style = Stroke(2.dp.toPx() / sx, cap = StrokeCap.Round))
            drawCircle(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.6f * d), w * 0.05f, Offset(-w * 0.2f, -w * 0.2f))
        }
    }
}
