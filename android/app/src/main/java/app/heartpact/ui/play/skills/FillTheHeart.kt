package app.heartpact.ui.play.skills

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import app.heartpact.i18n.tr
import app.heartpact.ui.play.PlayInk
import app.heartpact.ui.play.SkillKind
import app.heartpact.ui.play.rememberPlayHaptics
import app.heartpact.ui.play.unitHeart
import app.heartpact.ui.theme.Art
import app.heartpact.ui.theme.Heartpact
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/*
 * Fill the heart: hold to pour pink love from a tap into a heart-shaped jar, and let go at the line. The line hides
 * as soon as the love runs, and what's still falling when you let go lands too, so you have to stop a moment early.
 * The jar is narrow at the tip and wide at the lobes, so the love rises fast, then slow.
 */

/** The jar's insides: how much it holds below a level, and the level a volume reaches. Levels are 0 (tip) to 1 (top). */
internal class JarTable(outline: List<Offset>, rows: Int = 480) {
    val top = outline.minOf { it.y }
    val bottom = outline.maxOf { it.y }
    private val cumulative = FloatArray(rows + 1)
    private val step = (bottom - top) / rows

    init {
        // Width of the jar at each height, summed from the tip up.
        for (i in 1..rows) {
            val y = bottom - (i - 0.5f) * step
            cumulative[i] = cumulative[i - 1] + widthAt(outline, y) * step
        }
    }

    val total: Float get() = cumulative.last()

    fun volumeBelow(level: Float): Float {
        val x = level.coerceIn(0f, 1f) * (cumulative.size - 1)
        val i = x.toInt().coerceAtMost(cumulative.size - 2)
        return cumulative[i] + (cumulative[i + 1] - cumulative[i]) * (x - i)
    }

    fun levelFor(volume: Float): Float {
        if (volume <= 0f) return 0f
        if (volume >= total) return 1f
        var lo = 0
        var hi = cumulative.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (cumulative[mid] < volume) lo = mid else hi = mid
        }
        val f = (volume - cumulative[lo]) / (cumulative[hi] - cumulative[lo]).coerceAtLeast(1e-6f)
        return (lo + f) / (cumulative.size - 1)
    }

    /** The y (in jar units) of a level. */
    fun yOf(level: Float): Float = bottom - level * (bottom - top)

    companion object {
        /** The total width inside [poly] along the horizontal line at [y]. */
        fun widthAt(poly: List<Offset>, y: Float): Float {
            val xs = ArrayList<Float>()
            for (i in poly.indices) {
                val a = poly[i]
                val b = poly[(i + 1) % poly.size]
                if ((a.y > y) != (b.y > y)) xs += a.x + (y - a.y) / (b.y - a.y) * (b.x - a.x)
            }
            xs.sort()
            var w = 0f
            var i = 0
            while (i + 1 < xs.size) { w += xs[i + 1] - xs[i]; i += 2 }
            return w
        }
    }
}

internal class FillPlay(seed: Long) : SkillPlay(SkillKind.FillTheHeart, seed) {
    val jar = heartOutline(240)
    val table = JarTable(jar)
    private val rnd = Random(seed)
    /** Where to stop, as a level of the jar. */
    val target = 0.34f + rnd.nextFloat() * 0.44f
    /** Full flow fills the jar in a little over three seconds. */
    val maxRate = table.total / 3.3f

    private class Drop(val born: Float, val volume: Float, val rate: Float)
    private class Bubble(val x: Float, var y: Float, val r: Float, val heart: Boolean, val speed: Float)

    enum class Phase { Ready, Pouring, Settling, Done }

    var phase by mutableStateOf(Phase.Ready)
        private set
    /** Bumped every frame the love moves, so the board redraws. */
    var tick by mutableLongStateOf(0L)
        private set
    var volume by mutableFloatStateOf(0f)
        private set
    private var rate = 0f
    private var clock = 0f
    private var stoppedAt = 0f
    private val falling = ArrayList<Drop>()
    private val bubbles = ArrayList<Bubble>()
    /** How much the surface is sloshing. */
    private var slosh = 0f

    val level: Float get() = table.levelFor(volume)

    init { hint = tr("Stop right at the dashed line.") }

    fun press() {
        if (phase != Phase.Ready) return
        phase = Phase.Pouring
        hint = tr("Pouring...")
    }

    fun release() {
        if (phase != Phase.Pouring) return
        phase = Phase.Settling
        stoppedAt = clock
        hint = ""
    }

    /** Spout tip, in jar units. */
    val spoutY = -6f
    private val g = 1800f

    /** One step of [dt] seconds: flow, falling love, the slosh and the bubbles. */
    fun step(dt: Float) {
        if (phase == Phase.Ready || phase == Phase.Done) return
        clock += dt
        if (phase == Phase.Pouring) {
            rate += (maxRate - rate) * (dt / 0.12f).coerceAtMost(1f)
            falling += Drop(clock, rate * dt, rate)
        }
        val surface = table.yOf(level)
        val it = falling.iterator()
        while (it.hasNext()) {
            val d = it.next()
            val t = clock - d.born
            if (spoutY + 0.5f * g * t * t >= surface) {
                volume = (volume + d.volume).coerceAtMost(table.total)
                slosh += d.rate / maxRate * dt * 3.2f
                it.remove()
                if (rnd.nextFloat() < dt * 14f && bubbles.size < 16) {
                    bubbles += Bubble(50f + (rnd.nextFloat() - 0.5f) * 18f, surface + 6f + rnd.nextFloat() * 16f, 0.8f + rnd.nextFloat() * 1.3f, rnd.nextInt(5) == 0, 10f + rnd.nextFloat() * 10f)
                }
            }
        }
        slosh = (slosh * exp(-dt / 0.55f)).coerceAtMost(1.6f)
        val top = table.yOf(level)
        bubbles.removeAll { b -> b.y -= b.speed * dt; b.y < top + 1f }
        if (phase == Phase.Settling && falling.isEmpty() && slosh < 0.04f && clock - stoppedAt > 0.5f) {
            phase = Phase.Done
            val off = abs(volume - table.volumeBelow(target)) / maxRate
            finish(fillScore(level, target, off), 250)
        }
        tick++
    }

    @Composable
    override fun Board(modifier: Modifier) {
        val colors = Heartpact.colors
        val haptic = LocalHapticFeedback.current
        val haptics = rememberPlayHaptics()
        var size by remember { mutableStateOf(Size.Zero) }
        val pad = with(LocalDensity.current) { 16.dp.toPx() }
        // The jar's units: x 0 to 100 with a margin either side, from the tap above (y -34) to the stand (y 108).
        val fit = Fit.of(size, 128f, 142f, pad)
        val unitFit = Fit(fit.scale, fit.origin + Offset(14f, 34f) * fit.scale)
        val lineAlpha by animateFloatAsState(if (phase == Phase.Ready || scored != null) 1f else 0f, moving(tween(if (phase == Phase.Pouring) 260 else 500)), label = "line")
        LaunchedEffect(phase == Phase.Pouring || phase == Phase.Settling) {
            if (phase != Phase.Pouring && phase != Phase.Settling) return@LaunchedEffect
            var last = withFrameMillis { it }
            var glug = 0L
            while (phase == Phase.Pouring || phase == Phase.Settling) {
                withFrameMillis { now ->
                    step(((now - last) / 1000f).coerceIn(0f, 0.05f))
                    if (phase == Phase.Pouring && now - glug > 95) { glug = now; haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick) }
                    last = now
                }
            }
            haptics.thud()
        }
        Canvas(
            modifier
                .onSizeChanged { size = it.toSize() }
                .semantics { contentDescription = tr("A heart jar with a line to fill it to. Touch and hold to pour, let go to stop.") }
                .pointerInput(Unit) { holdToPour() },
        ) {
            tick // redraw on every step
            drawTap(unitFit, phase == Phase.Pouring)
            drawJar(unitFit)
            drawStream(unitFit)
            drawTarget(unitFit, lineAlpha, colors.ink)
        }
    }

    private suspend fun PointerInputScope.holdToPour() {
        awaitEachGesture {
            awaitFirstDown()
            press()
            waitForUpOrCancellation()
            release()
        }
    }

    override val hasControls: Boolean get() = true

    @Composable
    override fun Controls(modifier: Modifier) {
        val label = when (phase) {
            Phase.Ready -> tr("Hold to pour")
            Phase.Pouring -> tr("Let go at the line")
            else -> tr("Settling...")
        }
        PressPill(label, phase == Phase.Ready || phase == Phase.Pouring, modifier, onDown = { press() }, onUp = { release() })
    }

    private fun DrawScope.drawTap(f: Fit, open: Boolean) {
        val pipe = Art.Left
        val x = f.px(Offset(50f, 0f)).x
        val top = f.px(Offset(0f, -40f)).y
        val tip = f.px(Offset(0f, spoutY)).y
        val w = f.px(7f)
        // A pipe from above, a nozzle, and a heart for a handle that turns when it's open.
        drawRoundRect(Art.Keyline, Offset(x - w / 2 - 3.dp.toPx(), top), Size(w + 6.dp.toPx(), tip - top + 3.dp.toPx()), CornerRadius(w))
        drawRoundRect(pipe, Offset(x - w / 2, top), Size(w, tip - top), CornerRadius(w / 2))
        val nozzle = f.px(11f)
        drawRoundRect(Art.Keyline, Offset(x - nozzle / 2 - 3.dp.toPx(), tip - f.px(7f) - 3.dp.toPx()), Size(nozzle + 6.dp.toPx(), f.px(7f) + 6.dp.toPx()), CornerRadius(f.px(3f)))
        drawRoundRect(pipe, Offset(x - nozzle / 2, tip - f.px(7f)), Size(nozzle, f.px(7f)), CornerRadius(f.px(2.5f)))
        val handle = Offset(x + f.px(10f), tip - f.px(16f))
        drawLine(pipe, Offset(x, handle.y), handle, f.px(2.5f), cap = StrokeCap.Round)
        rotate(if (open) 35f else 0f, handle) {
            translate(handle.x, handle.y) {
                val h = unitHeart(f.px(13f))
                drawPath(h, Art.Keyline, style = Stroke(3.dp.toPx(), join = StrokeJoin.Round))
                drawPath(h, PlayInk.Pen)
            }
        }
    }

    private fun DrawScope.drawJar(f: Fit) {
        val path = f.path(jar)
        // A little stand for the tip to sit in.
        val base = f.px(Offset(50f, 101f))
        drawRoundRect(Art.Keyline, Offset(base.x - f.px(14f), base.y - f.px(6f)), Size(f.px(28f), f.px(10f)), CornerRadius(f.px(4f)))
        drawRoundRect(Art.Right, Offset(base.x - f.px(12f), base.y - f.px(4.5f)), Size(f.px(24f), f.px(7f)), CornerRadius(f.px(3f)))
        // The die-cut keyline and a soft lift, then the glass.
        translate(0f, 4.dp.toPx()) { drawPath(path, Art.Shadow, alpha = 0.12f, style = Stroke(12.dp.toPx(), join = StrokeJoin.Round)) }
        drawPath(path, Art.Keyline, style = Stroke(10.dp.toPx(), join = StrokeJoin.Round))
        drawPath(path, Color(0xFFF7F8FD))
        clipPath(path) { drawLove(f) }
        drawPath(path, Art.Right.copy(alpha = 0.55f), style = Stroke(2.5.dp.toPx(), join = StrokeJoin.Round))
        // A glint on the left lobe.
        val glint = Path().apply {
            val c = f.px(Offset(29f, 37f))
            val r = f.px(21f)
            addArc(androidx.compose.ui.geometry.Rect(c, r), 195f, 60f)
        }
        drawPath(glint, Color.White.copy(alpha = 0.9f), style = Stroke(f.px(3f), cap = StrokeCap.Round))
    }

    /** The love in the jar: a wobbling surface, a deeper pink band at the top, bubbles rising. */
    private fun DrawScope.drawLove(f: Fit) {
        if (volume <= 0f) return
        val y = table.yOf(level)
        val t = clock
        val amp = slosh * 1.8f
        val surface = Path()
        val n = 40
        for (i in 0..n) {
            val x = -2f + 104f * i / n
            val yy = y + amp * sin(x * 0.17f + t * 9f) + amp * 0.5f * sin(x * 0.31f - t * 13f)
            val p = f.px(Offset(x, yy))
            if (i == 0) surface.moveTo(p.x, p.y) else surface.lineTo(p.x, p.y)
        }
        val body = Path().apply {
            addPath(surface)
            val br = f.px(Offset(102f, 104f)); val bl = f.px(Offset(-2f, 104f))
            lineTo(br.x, br.y); lineTo(bl.x, bl.y); close()
        }
        drawPath(body, PlayInk.PenSoft)
        drawPath(surface, PlayInk.Pen, style = Stroke(f.px(2.4f), cap = StrokeCap.Round))
        for (b in bubbles) {
            val p = f.px(Offset(b.x, b.y))
            if (b.heart) translate(p.x, p.y) { drawPath(unitHeart(f.px(b.r * 2.6f)), Color.White.copy(alpha = 0.75f)) }
            else drawCircle(Color.White.copy(alpha = 0.8f), f.px(b.r), p, style = Stroke(1.2.dp.toPx()))
        }
    }

    /** The falling stream: thick at the spout, thinning as it falls. */
    private fun DrawScope.drawStream(f: Fit) {
        if (falling.isEmpty()) return
        val surface = table.yOf(level)
        var prev: Pair<Offset, Float>? = null
        for (d in falling) {
            val t = clock - d.born
            val y = (spoutY + 0.5f * g * t * t).coerceAtMost(surface)
            val w = (2.2f + 2.6f * sqrt(d.rate / maxRate)) * (1f - 0.4f * ((y - spoutY) / (surface - spoutY)).coerceIn(0f, 1f))
            val p = f.px(Offset(50f + 0.5f * sin(d.born * 40f), y))
            prev?.let { (q, qw) -> drawLine(PlayInk.Pen, q, p, f.px((w + qw) / 2), cap = StrokeCap.Round) }
            prev = p to w
        }
        prev?.let { (p, w) -> drawCircle(PlayInk.Pen, f.px(w / 2), p) }
    }

    private fun DrawScope.drawTarget(f: Fit, alpha: Float, ink: Color) {
        if (alpha <= 0.01f) return
        val y = table.yOf(target)
        val w = JarTable.widthAt(jar, y) / 2 + 9f
        val a = f.px(Offset(50f - w, y))
        val b = f.px(Offset(50f + w, y))
        drawLine(PlayInk.Pen.copy(alpha = alpha), a, b, 2.5.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx())))
        // Little ticks at both ends, like a mark on a measuring jug.
        drawCircle(PlayInk.Pen.copy(alpha = alpha), 4.dp.toPx(), a)
        drawCircle(PlayInk.Pen.copy(alpha = alpha), 4.dp.toPx(), b)
        val ly = f.px(Offset(0f, table.yOf(level))).y
        if (scored != null && volume > 0f && abs(ly - a.y) > 4.dp.toPx()) {
            // The gap between the love and the line, bracketed on the right.
            val x = b.x + 10.dp.toPx()
            drawLine(ink.copy(alpha = alpha * 0.8f), Offset(x, ly), Offset(x, a.y), 2.dp.toPx(), cap = StrokeCap.Round)
            drawLine(ink.copy(alpha = alpha * 0.8f), Offset(x - 5.dp.toPx(), ly), Offset(x + 5.dp.toPx(), ly), 2.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}
