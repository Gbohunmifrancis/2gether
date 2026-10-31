package app.heartpact.ui.play.skills

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import app.heartpact.i18n.tr
import app.heartpact.ui.components.ButtonKind
import app.heartpact.ui.components.HeartButton
import app.heartpact.ui.play.BlobColour
import app.heartpact.ui.play.PlayInk
import app.heartpact.ui.play.SkillKind
import app.heartpact.ui.play.rememberPlayHaptics
import app.heartpact.ui.theme.Heartpact
import kotlinx.coroutines.delay

/*
 * Other half: half a shape sits left of a dashed fold; draw its mirror on the right, in one stroke or a few, then
 * submit. The reveal draws the true half under yours and folds your drawing back over the given half, ghosted, so
 * you can see where they part. Up the levels the shapes get more intricate, and from level 10 the given half shows
 * only for a moment before it fades to a faint ghost, so the rest is drawn from memory.
 */

internal class OtherHalfPlay(seed: Long, difficulty: Difficulty = Difficulty.Start) : SkillPlay(SkillKind.OtherHalf, seed, difficulty) {
    val shape = HalfShape.of(seed, difficulty)
    /** Finished strokes and the one being drawn, in shape units (1 tall, fold at x = 0). */
    val strokes = mutableStateListOf<List<Offset>>()
    var drawing by mutableStateOf<List<Offset>>(emptyList())
        private set
    var submitted by mutableStateOf(false)
        private set

    /** From level 10 the given half shows for [showMs], then fades to a ghost at [ghostAlpha] (both shorter and fainter higher up). */
    val fades = difficulty.from(10)
    val showMs: Long = if (fades) (2000 - 800 * up(difficulty)).toLong() else 0L
    val ghostAlpha: Float = if (fades) 0.2f - 0.1f * up(difficulty) else 1f
    /** True once the given half has faded to its ghost. */
    var faded by mutableStateOf(false)
        private set

    private fun up(d: Difficulty) = ((d.level - 10) / (Difficulty.RAMP - 10f)).coerceIn(0f, 1f)

    /** What to say while nothing is drawn. */
    private fun idle(): String = when {
        faded -> tr("Only a ghost is left. Draw it from memory.")
        fades -> tr("Look closely. It fades to a ghost.")
        else -> tr("Start anywhere on the right.")
    }

    init { hint = idle() }

    /** The given half has faded to its ghost. */
    fun fade() {
        if (faded) return
        faded = true
        if (strokes.isEmpty() && drawing.isEmpty() && !submitted) hint = idle()
    }

    fun begin(p: Offset) { drawing = listOf(p) }

    fun extend(p: Offset) {
        if (drawing.isEmpty()) return
        if ((drawing.last() - p).getDistance() < 0.003f) return
        drawing = drawing + p
    }

    fun end() {
        if (drawing.size > 1) strokes += drawing
        drawing = emptyList()
        hint = if (strokes.isEmpty()) idle() else tr("Done? Submit it.")
    }

    fun clear() {
        strokes.clear()
        drawing = emptyList()
        hint = idle()
    }

    fun submit() {
        if (strokes.isEmpty() || submitted) return
        submitted = true
        hint = ""
        finish(otherHalfScore(strokes.toList(), shape.half, difficulty), 1300)
    }

    override val hasControls: Boolean get() = true

    @Composable
    override fun Controls(modifier: Modifier) {
        val haptics = rememberPlayHaptics()
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HeartButton(tr("Clear"), { haptics.tick(); clear() }, Modifier.weight(1f), kind = ButtonKind.Outline)
            HeartButton(tr("Submit"), { haptics.thud(); submit() }, Modifier.weight(1f), enabled = strokes.isNotEmpty())
        }
    }

    @Composable
    override fun Board(modifier: Modifier) {
        val colors = Heartpact.colors
        val mine = Heartpact.actor.action
        val haptics = rememberPlayHaptics()
        var size by remember { mutableStateOf(Size.Zero) }
        val pad = with(LocalDensity.current) { 18.dp.toPx() }
        // Frame units: the fold at x 0.62, the shape from y 0.04 to 1.04.
        val frame = Fit.of(size, 1.24f, 1.08f, pad)
        val fit = Fit(frame.scale, frame.origin + Offset(0.62f, 0.04f) * frame.scale)
        val fitNow by rememberUpdatedState(fit)
        val reveal = remember { Animatable(0f) }
        val ghost = remember { Animatable(0f) }
        val draw = moving(tween<Float>(900, easing = FastOutSlowInEasing))
        val fade = moving(tween<Float>(500))
        // The given half, fully there, or (high up) a fading ghost of it until the reveal brings it back.
        val shown = remember { Animatable(1f) }
        val toGhost = moving(tween<Float>(700, easing = FastOutSlowInEasing))
        val backIn = moving(tween<Float>(400))
        LaunchedEffect(Unit) {
            if (!fades) return@LaunchedEffect
            delay(showMs)
            if (!submitted) shown.animateTo(ghostAlpha, toGhost)
            fade()
        }
        LaunchedEffect(submitted) {
            if (!submitted) return@LaunchedEffect
            if (shown.value < 1f) shown.animateTo(1f, backIn)
            reveal.animateTo(1f, draw)
            ghost.animateTo(1f, fade)
        }
        val tint = BlobColour.Mint
        Canvas(
            modifier
                .onSizeChanged { size = it.toSize() }
                .semantics { contentDescription = tr("Half a shape left of a dashed fold. Draw its other half on the right.") }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        if (submitted) return@awaitEachGesture
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
            // The given half, as a pastel sticker cut along the fold.
            val given = fit.path(shape.given)
            val a = shown.value
            drawPath(given, Color.White, alpha = a, style = Stroke(9.dp.toPx(), join = StrokeJoin.Round))
            drawPath(given, tint.fill, alpha = a)
            drawPath(fit.path(shape.given, close = false), tint.ink, alpha = a, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            // The fold.
            drawLine(colors.ink3, fit.px(Offset(0f, -0.03f)), fit.px(Offset(0f, 1.03f)), 2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 6.dp.toPx())), cap = StrokeCap.Round)
            // At the reveal: the true half drawn on, under the player's strokes.
            if (reveal.value > 0f) {
                val truth = fit.path(shape.half, close = false)
                val measure = PathMeasure().apply { setPath(truth, false) }
                val part = Path()
                measure.getSegment(0f, measure.length * reveal.value, part, true)
                drawPath(part, PlayInk.Pen.copy(alpha = 0.85f), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 7.dp.toPx()))))
            }
            val width = 5.dp.toPx()
            (strokes + listOf(drawing)).forEach { s ->
                if (s.size > 1) drawPath(fit.path(s, close = false), mine, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
                else if (s.size == 1) drawCircle(mine, width / 2, fit.px(s[0]))
                // Folded back over the given half, ghosted.
                if (ghost.value > 0f && s.size > 1) {
                    drawPath(fit.path(s.map { Offset(-it.x, it.y) }, close = false), PlayInk.Pen.copy(alpha = 0.55f * ghost.value),
                        style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        }
    }
}
