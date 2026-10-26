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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.heartpact.i18n.tr
import app.heartpact.ui.play.PlayInk
import app.heartpact.ui.play.SkillKind
import app.heartpact.ui.play.rememberPlayHaptics
import app.heartpact.ui.play.unitHeart
import app.heartpact.ui.theme.Art
import app.heartpact.ui.theme.Heartpact
import kotlinx.coroutines.delay

/*
 * Promise ring: one stroke, as round as you can, on the dotted page. The overlap past a full turn is trimmed (it
 * isn't scored); then the ideal circle is drawn under yours, a heart set on top, and the note names the main flaw.
 */

internal class RingPlay(seed: Long) : SkillPlay(SkillKind.PromiseRing, seed) {
    /** The stroke being drawn, in dp. */
    var stroke by mutableStateOf<List<Offset>>(emptyList())
        private set
    var ring by mutableStateOf<Ring?>(null)
        private set
    /** Bumps when a stroke wasn't a ring yet, so it fades out instead of scoring. */
    var slips by mutableIntStateOf(0)
        private set
    var slipped by mutableStateOf<List<Offset>>(emptyList())
        private set
    private var closed = false

    init { hint = tr("Anywhere on the page. One go.") }

    fun begin(p: Offset) {
        if (ring != null) return
        stroke = listOf(p)
        closed = false
    }

    /** Adds a point; true the moment the line comes back round to its start (a little click). */
    fun extend(p: Offset): Boolean {
        if (ring != null || stroke.isEmpty()) return false
        if ((stroke.last() - p).getDistance() < 1f) return false
        stroke = stroke + p
        if (!closed && stroke.size > 30 && length(stroke) > 200f && (p - stroke.first()).getDistance() < 16f) {
            closed = true
            return true
        }
        return false
    }

    fun end() {
        if (ring != null || stroke.isEmpty()) return
        val r = measureRing(stroke)
        if (r == null) {
            slipped = stroke
            stroke = emptyList()
            slips++
            hint = tr("Go all the way round, in one stroke.")
            return
        }
        ring = r
        hint = ""
        finish(ringScore(r), 1500)
    }

    @Composable
    override fun Board(modifier: Modifier) {
        val colors = Heartpact.colors
        val mine = Heartpact.actor.action
        val haptics = rememberPlayHaptics()
        val density = LocalDensity.current.density
        val ideal = remember { Animatable(0f) }
        val gem = remember { Animatable(0f) }
        val fade = remember { Animatable(0f) }
        val draw = moving(tween<Float>(700, easing = FastOutSlowInEasing))
        val pop = moving(spring<Float>(dampingRatio = 0.4f, stiffness = 380f))
        val fadeOut = moving(tween<Float>(450))
        val r = ring
        LaunchedEffect(r) {
            if (r == null) return@LaunchedEffect
            delay(150)
            ideal.animateTo(1f, draw)
            haptics.tick()
            gem.animateTo(1f, pop)
        }
        LaunchedEffect(slips) {
            if (slips == 0) return@LaunchedEffect
            haptics.tick()
            fade.snapTo(1f)
            fade.animateTo(0f, fadeOut)
        }
        val width = 6.dp
        Canvas(
            modifier
                .semantics { contentDescription = tr("A dotted page. Draw a circle in one stroke.") }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        if (ring != null) return@awaitEachGesture
                        haptics.tick()
                        begin(down.position / density)
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            if (extend(ch.position / density)) haptics.tick()
                            ch.consume()
                        }
                        end()
                    }
                },
        ) {
            fun line(points: List<Offset>): Path = Path().apply {
                points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x * density, p.y * density) else lineTo(p.x * density, p.y * density) }
            }
            val pen = Stroke(width.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            if (r == null) {
                if (fade.value > 0f && slipped.size > 1) drawPath(line(slipped), mine.copy(alpha = 0.5f * fade.value), style = pen)
                if (stroke.size > 1) drawPath(line(stroke), mine, style = pen)
                else if (stroke.size == 1) drawCircle(mine, width.toPx() / 2, stroke[0] * density)
                return@Canvas
            }
            val c = r.centre * density
            val rad = r.radius * density
            // The ideal circle, drawn on from where the stroke began, under the stroke.
            val start = Math.toDegrees(angleAround(r.centre, r.kept.first()).toDouble()).toFloat()
            drawArc(PlayInk.Pen.copy(alpha = 0.8f), start, 360f * ideal.value * r.turn, false, Offset(c.x - rad, c.y - rad), Size(rad * 2, rad * 2),
                style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 8.dp.toPx()))))
            drawCircle(colors.ink3, 3.dp.toPx() * ideal.value, c)
            // Past a full turn, faded: it doesn't count. Then the ring itself.
            if (r.overlap.size > 1) drawPath(line(r.overlap), mine.copy(alpha = 0.28f), style = pen)
            drawPath(line(r.kept), mine, style = pen)
            // The gap, if it was left open, marked in pink.
            if (r.gapDegrees > 4f && ideal.value >= 1f) {
                val end = Math.toDegrees(angleAround(r.centre, r.kept.last()).toDouble()).toFloat()
                drawArc(PlayInk.Pen, end, r.gapDegrees * r.turn, false, Rect(c, rad).topLeft, Size(rad * 2, rad * 2),
                    style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
            }
            // A heart set on top, like the stone of a ring.
            if (gem.value > 0f) {
                val top = Offset(c.x, c.y - rad)
                translate(top.x, top.y - 4.dp.toPx()) {
                    val h = unitHeart(26.dp.toPx() * gem.value)
                    drawPath(h, Art.Keyline, style = Stroke(5.dp.toPx(), join = StrokeJoin.Round))
                    drawPath(h, PlayInk.Pen)
                    drawCircle(Color.White.copy(alpha = 0.7f), 2.dp.toPx() * gem.value, Offset(-5.dp.toPx(), -3.dp.toPx()) * gem.value)
                }
            }
        }
    }
}
