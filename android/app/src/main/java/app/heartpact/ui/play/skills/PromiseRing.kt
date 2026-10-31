package app.heartpact.ui.play.skills

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.layout.onSizeChanged
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
import kotlin.random.Random

/*
 * Promise ring: one stroke on the dotted page. At level 1 it's a ring, as round as you can, anywhere; up the levels
 * other shapes join (DrawShapes.kt), shown as a faint dashed guide that later hides once you start, then a size to
 * match too, and the scoring gets stricter. The overlap past the start is trimmed (it isn't scored); then the ideal
 * shape is drawn under yours, a heart set on top, and the note names the main flaw.
 */

internal class RingPlay(seed: Long, difficulty: Difficulty = Difficulty.Start) : SkillPlay(SkillKind.PromiseRing, seed, difficulty) {
    private val rnd = Random(seed)
    /** What to draw this round. */
    val shape: DrawShape = DrawShape.pick(rnd, difficulty)
    /** An oval stood on its end. */
    val turned: Boolean = shape == DrawShape.Oval && rnd.nextBoolean()
    val template: ShapeTemplate = ShapeTemplate.of(shape, turned)
    /** From level [SIZE_LEVEL], the drawing should match the guide's size too. */
    val sizeCounts: Boolean = difficulty.from(SIZE_LEVEL)
    /** From level [HIDE_LEVEL], the guide hides once you start drawing. */
    val guideHides: Boolean = difficulty.from(HIDE_LEVEL)
    /** A guide to draw by: every shape but the ring, and the ring too once its size counts. */
    val hasGuide: Boolean = shape != DrawShape.Circle || sizeCounts
    /** How big the guide is: its longer side as a share of the board's shorter side. */
    val guideShare: Float = 0.5f + rnd.nextFloat() * 0.22f
    /** How steeply the score falls off, tightening with the level. */
    val k: Float = difficulty.k(1f)

    /** The board's size, in dp, where the guide is centred. */
    var board by mutableStateOf(Size.Zero)
    /** The stroke being drawn, in dp. */
    var stroke by mutableStateOf<List<Offset>>(emptyList())
        private set
    var ring by mutableStateOf<Ring?>(null)
        private set
    var fit by mutableStateOf<ShapeFit?>(null)
        private set
    /** Bumps when a stroke wasn't a whole attempt yet, so it fades out instead of scoring. */
    var slips by mutableIntStateOf(0)
        private set
    var slipped by mutableStateOf<List<Offset>>(emptyList())
        private set
    private var closed = false

    private val drawn: Boolean get() = ring != null || fit != null

    /** The guide's spread (dp): the template is 1 across its spread, so this is its scale. */
    val guideScale: Float get() = guideShare * minOf(board.width, board.height) / maxOf(template.width, template.height)

    /** The guide in dp, centred on the board. */
    fun guide(): List<Offset> {
        val c = Offset(board.width / 2, board.height / 2)
        return template.line.map { c + it * guideScale }
    }

    init {
        hint = listOfNotNull(
            shape.prompt,
            when {
                !hasGuide -> tr("Anywhere on the page. One go.")
                guideHides -> tr("The guide hides once you start. One stroke.")
                else -> tr("Over the guide or anywhere, in one stroke.")
            },
            if (sizeCounts) tr("Match the guide's size too.") else null,
        ).joinToString(" ")
    }

    fun begin(p: Offset) {
        if (drawn) return
        stroke = listOf(p)
        closed = false
    }

    /** Adds a point; true the moment the line comes back round to its start (a little click). */
    fun extend(p: Offset): Boolean {
        if (drawn || stroke.isEmpty()) return false
        if ((stroke.last() - p).getDistance() < 1f) return false
        stroke = stroke + p
        if (shape.closed && !closed && stroke.size > 30 && length(stroke) > 200f && (p - stroke.first()).getDistance() < 16f) {
            closed = true
            return true
        }
        return false
    }

    fun end() {
        if (drawn || stroke.isEmpty()) return
        val size = if (sizeCounts && board != Size.Zero) guideScale else null
        if (shape == DrawShape.Circle) {
            val r = measureRing(stroke) ?: return slip(tr("Go all the way round, in one stroke."))
            ring = r
            hint = ""
            finish(ringScore(r, k, size), 1500)
        } else {
            val f = measureShape(stroke, shape, turned) ?: return slip(tr("Draw the whole shape, in one stroke."))
            fit = f
            hint = ""
            finish(shapeScore(f, k, size), 1500)
        }
    }

    private fun slip(why: String) {
        slipped = stroke
        stroke = emptyList()
        slips++
        hint = why
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
        val sf = fit
        val done = r != null || sf != null
        LaunchedEffect(done) {
            if (!done) return@LaunchedEffect
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
        // The guide: there before you draw, gone once you start from the level it hides at, back faintly at the end when size counted.
        val guideAlpha by animateFloatAsState(
            when {
                !hasGuide -> 0f
                done -> if (sizeCounts) 0.6f else 0f
                guideHides && stroke.isNotEmpty() -> 0f
                else -> 1f
            },
            moving(tween(if (stroke.isNotEmpty()) 200 else 400)), label = "guide",
        )
        val width = 6.dp
        val described = if (hasGuide) tr("A dotted page with a faint guide. Draw the shape in one stroke.") else tr("A dotted page. Draw a circle in one stroke.")
        Canvas(
            modifier
                .onSizeChanged { board = Size(it.width / density, it.height / density) }
                .semantics { contentDescription = described }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        if (drawn) return@awaitEachGesture
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
            fun line(points: List<Offset>, close: Boolean = false): Path = Path().apply {
                points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x * density, p.y * density) else lineTo(p.x * density, p.y * density) }
                if (close) close()
            }
            val pen = Stroke(width.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            if (guideAlpha > 0.01f && board != Size.Zero) {
                drawPath(line(guide()), colors.ink4.copy(alpha = guideAlpha), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 7.dp.toPx()))))
            }
            if (!done) {
                if (fade.value > 0f && slipped.size > 1) drawPath(line(slipped), mine.copy(alpha = 0.5f * fade.value), style = pen)
                if (stroke.size > 1) drawPath(line(stroke), mine, style = pen)
                else if (stroke.size == 1) drawCircle(mine, width.toPx() / 2, stroke[0] * density)
                return@Canvas
            }
            val gemAt: Offset
            if (r != null) {
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
                gemAt = Offset(c.x, c.y - rad)
            } else {
                val f = sf!!
                // The shape itself, laid over the drawing where it matched best, fading in under the stroke.
                val shapeLine = f.ideal(template)
                drawPath(line(shapeLine), PlayInk.Pen.copy(alpha = 0.8f * ideal.value),
                    style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 8.dp.toPx()))))
                drawCircle(colors.ink3, 3.dp.toPx() * ideal.value, f.centre * density)
                if (f.overlap.size > 1) drawPath(line(f.overlap), mine.copy(alpha = 0.28f), style = pen)
                drawPath(line(f.kept), mine, style = pen)
                if (shape.closed && f.gap > 0.02f && ideal.value >= 1f) {
                    drawLine(PlayInk.Pen, f.kept.last() * density, f.kept.first() * density, 4.dp.toPx(), cap = StrokeCap.Round)
                }
                gemAt = shapeLine.minBy { it.y } * density
            }
            // A heart set on top, like the stone of a ring.
            if (gem.value > 0f) {
                translate(gemAt.x, gemAt.y - 4.dp.toPx()) {
                    val h = unitHeart(26.dp.toPx() * gem.value)
                    drawPath(h, Art.Keyline, style = Stroke(5.dp.toPx(), join = StrokeJoin.Round))
                    drawPath(h, PlayInk.Pen)
                    drawCircle(Color.White.copy(alpha = 0.7f), 2.dp.toPx() * gem.value, Offset(-5.dp.toPx(), -3.dp.toPx()) * gem.value)
                }
            }
        }
    }

    companion object {
        /** The level from which the guide hides once you start drawing. */
        const val HIDE_LEVEL = 6
        /** The level from which the guide's size counts too. */
        const val SIZE_LEVEL = 10
    }
}
