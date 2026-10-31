package app.heartpact.ui.play.skills

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import app.heartpact.i18n.tr
import app.heartpact.ui.play.PlayInk
import app.heartpact.ui.play.SkillKind
import app.heartpact.ui.play.rememberPlayHaptics
import app.heartpact.ui.theme.Art
import app.heartpact.ui.theme.Heartpact
import kotlinx.coroutines.delay
import kotlin.random.Random

/*
 * Draw a heart: one stroke on the dotted page, as true a heart as you can. Then the heart it was meant to be is drawn
 * on over yours in dashed pink, and your drawing becomes a sticker: indigo on the left, periwinkle on the right.
 * From level 6 a dashed heart shows where and how big to draw it; from level 12 it hides once you start.
 */

/** The board, in units: 1 wide, [DRAW_H] tall. */
private const val DRAW_H = 1.2f

internal class DrawHeartPlay(seed: Long, difficulty: Difficulty = Difficulty.Start) : SkillPlay(SkillKind.DrawAHeart, seed, difficulty) {
    private val rnd = Random(seed)
    /** The dashed heart to draw in (its centre of area and its height, in units), from level 6. */
    val guide: Pair<Offset, Float>? = if (!difficulty.from(6)) null else
        Offset(0.5f + (rnd.nextFloat() - 0.5f) * 0.16f, 0.6f + (rnd.nextFloat() - 0.5f) * 0.14f) to (0.46f + rnd.nextFloat() * 0.14f)
    /** The guide hides as soon as the pen touches down, from level 12. */
    val guideHides = difficulty.from(12)
    private val k = difficulty.k(0.075f)

    /** The stroke being drawn, in units. */
    var stroke by mutableStateOf<List<Offset>>(emptyList())
        private set
    var heart by mutableStateOf<DrawnHeart?>(null)
        private set
    /** Bumps when a stroke wasn't a heart yet, so it fades instead of scoring. */
    var slips by mutableIntStateOf(0)
        private set
    var slipped by mutableStateOf<List<Offset>>(emptyList())
        private set
    /** True once the pen has touched the page this round (the guide may hide). */
    var started by mutableStateOf(false)
        private set

    init { hint = if (guide != null) tr("Draw it inside the dashed heart.") else tr("Anywhere on the page. One go.") }

    fun begin(p: Offset) {
        if (heart != null) return
        stroke = listOf(p)
        started = true
    }

    fun extend(p: Offset) {
        if (heart != null || stroke.isEmpty()) return
        if ((stroke.last() - p).getDistance() < 0.003f) return
        stroke = stroke + p
    }

    fun end() {
        if (heart != null || stroke.isEmpty()) return
        val m = measureHeart(stroke, 0.15f, guide)
        if (m == null) {
            slipped = stroke
            stroke = emptyList()
            slips++
            hint = tr("Draw a whole heart, in one stroke.")
            return
        }
        heart = m
        hint = ""
        finish(heartScore(m, k, guided = guide != null), 1700)
    }

    @Composable
    override fun Board(modifier: Modifier) {
        val colors = Heartpact.colors
        val mine = Heartpact.actor.action
        val haptics = rememberPlayHaptics()
        var box by remember { mutableStateOf(Size.Zero) }
        val fit = Fit.of(box, 1f, DRAW_H, with(LocalDensity.current) { 16.dp.toPx() })
        val fitNow by rememberUpdatedState(fit)
        val ideal = remember { Animatable(0f) }
        val fill = remember { Animatable(0f) }
        val fade = remember { Animatable(0f) }
        val guideAlpha = remember { Animatable(1f) }
        val draw = moving(tween<Float>(800, easing = FastOutSlowInEasing))
        val pop = moving(spring<Float>(dampingRatio = 0.42f, stiffness = 340f))
        val fadeOut = moving(tween<Float>(450))
        val m = heart
        LaunchedEffect(m) {
            if (m == null) return@LaunchedEffect
            guideAlpha.snapTo(0f)
            delay(150)
            ideal.animateTo(1f, draw)
            haptics.tick()
            fill.animateTo(1f, pop)
        }
        LaunchedEffect(slips) {
            if (slips == 0) return@LaunchedEffect
            haptics.tick()
            fade.snapTo(1f)
            fade.animateTo(0f, fadeOut)
        }
        LaunchedEffect(started, slips) {
            if (guideHides && started && heart == null) guideAlpha.animateTo(if (stroke.isEmpty()) 1f else 0f, fadeOut)
        }
        Canvas(
            modifier
                .onSizeChanged { box = it.toSize() }
                .semantics { contentDescription = tr("A dotted page. Draw a heart in one stroke.") }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        if (heart != null) return@awaitEachGesture
                        haptics.tick()
                        begin(fitNow.unit(down.position))
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            extend(fitNow.unit(ch.position))
                            ch.consume()
                        }
                        end()
                    }
                },
        ) {
            fun line(points: List<Offset>, close: Boolean = false): Path = fit.path(points, close)
            val pen = Stroke(6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            val dashed = PathEffect.dashPathEffect(floatArrayOf(9.dp.toPx(), 8.dp.toPx()))
            val g = guide
            val guidePath = g?.let { (c, h) -> line(HEART_FRAME.map { c + it * h }, close = true) }
            if (m == null) {
                if (guidePath != null && guideAlpha.value > 0.01f) {
                    drawPath(guidePath, colors.sticker.copy(alpha = 0.5f * guideAlpha.value))
                    drawPath(guidePath, colors.ink4.copy(alpha = guideAlpha.value), style = Stroke(3.dp.toPx(), pathEffect = dashed))
                }
                if (fade.value > 0f && slipped.size > 1) drawPath(line(slipped), mine.copy(alpha = 0.5f * fade.value), style = pen)
                if (stroke.size > 1) drawPath(line(stroke), mine, style = pen)
                else if (stroke.size == 1) drawCircle(mine, 3.dp.toPx(), fit.px(stroke[0]))
                return@Canvas
            }
            // Your heart becomes a sticker: a white keyline, then indigo left of its centre line and periwinkle right.
            val yours = line(stroke, close = true)
            val f = fill.value
            if (f > 0f) {
                val c = fit.px(m.centre)
                withTransform({ scale(0.94f + 0.06f * f, 0.94f + 0.06f * f, c) }) {
                    drawPath(yours, Art.Keyline, alpha = f.coerceIn(0f, 1f), style = Stroke(12.dp.toPx(), join = StrokeJoin.Round))
                    clipPath(yours) {
                        // Split down the fitted heart's centre line, leaning with it.
                        withTransform({ rotate(Math.toDegrees(m.turn.toDouble()).toFloat(), c) }) {
                            val big = size.maxDimension * 2f
                            drawRect(Art.Left, Offset(c.x - big, c.y - big), Size(big, big * 2), alpha = f.coerceIn(0f, 1f))
                            drawRect(Art.Right, Offset(c.x, c.y - big), Size(big, big * 2), alpha = f.coerceIn(0f, 1f))
                        }
                    }
                }
            }
            drawPath(line(stroke), if (f > 0f) Art.Keyline.copy(alpha = 0.55f * f) else mine, style = Stroke(if (f > 0f) 2.dp.toPx() else 6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            // The heart it was meant to be (the guide, or the best fit), drawn on in dashed pink.
            val meant = guidePath ?: line(HEART_FRAME.map { m.toBoard(it) }, close = true)
            val measure = PathMeasure().apply { setPath(meant, false) }
            val part = Path()
            measure.getSegment(0f, measure.length * ideal.value, part, true)
            drawPath(part, Art.Keyline, style = Stroke(6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = dashed))
            drawPath(part, PlayInk.Pen, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = dashed))
            // An open end, marked in pink.
            if (m.gap > 0.06f && ideal.value >= 1f) {
                val a = fit.px(stroke.first())
                val b = fit.px(stroke.last())
                drawLine(PlayInk.Pen, a, b, 3.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 6.dp.toPx())))
                for (end in listOf(a, b)) {
                    drawCircle(Art.Keyline, 7.dp.toPx(), end)
                    drawCircle(PlayInk.Pen, 5.dp.toPx(), end)
                }
            }
        }
    }
}
