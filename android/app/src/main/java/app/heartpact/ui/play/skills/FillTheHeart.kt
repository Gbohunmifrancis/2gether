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
import androidx.compose.ui.geometry.Rect
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
import kotlinx.coroutines.delay

/*
 * Fill the heart: hold to pour pink love from a tap into a jar, and let go at the line. The line hides as soon as the
 * love runs, and what's still falling when you let go lands too, so you have to stop a moment early. The jars
 * (Jars.kt) fill by area, so the love races up a narrow neck and crawls across a wide belly. Up the levels the pour
 * gets faster, the line sits in less obvious places and then only shows for a moment before the pour, and the
 * score gets stricter.
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

internal class FillPlay(seed: Long, difficulty: Difficulty = Difficulty.Start) : SkillPlay(SkillKind.FillTheHeart, seed, difficulty) {
    private val rnd = Random(seed)
    /** The jar this round pours into. */
    val jarKind: JarKind = Jar.pick(rnd, difficulty)
    val jar: Jar = Jar.of(jarKind)
    val table = JarTable(jar.outline)
    /** Where to stop, as a level of the jar. */
    val target: Float = pickTarget()
    /** How long full flow takes to fill the whole jar: a little over three seconds at level 1, two at the top. */
    val fillSeconds: Float = difficulty.lerp(3.3f, 2.0f)
    val maxRate = table.total / fillSeconds
    /**
     * From level [PEEK_LEVEL] the line only shows for a moment, before the pour can start: this long, in ms (0 below
     * that level, where it shows until the love runs).
     */
    val peekMs: Long = if (difficulty.from(PEEK_LEVEL)) (2200L - 100L * (difficulty.level - PEEK_LEVEL)).coerceAtLeast(1000L) else 0L
    /** How steeply the score falls off with the gap, tightening with the level. */
    val k: Float = difficulty.k(14f)

    /** Where the line is put: anywhere from a third to three quarters up at level 1, then wider, off the middle, and often where it's narrow. */
    private fun pickTarget(): Float {
        val lo = difficulty.lerp(0.34f, 0.2f)
        val hi = difficulty.lerp(0.78f, 0.86f)
        fun one(): Float {
            var t = lo + rnd.nextFloat() * (hi - lo)
            // From level 5, not just halfway up, where the eye finds it easily.
            var tries = 0
            while (difficulty.from(5) && abs(t - 0.5f) < 0.05f && tries++ < 8) t = lo + rnd.nextFloat() * (hi - lo)
            return t
        }
        if (!difficulty.from(10)) return one()
        // Higher up, half the time it sits where the jar is narrowest of a few tries, where the love moves fastest.
        val tries = List(3) { one() }
        return if (rnd.nextBoolean()) tries.minBy { JarTable.widthAt(jar.outline, table.yOf(it)) } else tries[0]
    }

    private class Drop(val born: Float, val volume: Float, val rate: Float)
    private class Bubble(val x: Float, var y: Float, val r: Float, val heart: Boolean, val speed: Float)

    enum class Phase { Ready, Pouring, Settling, Done }

    /** The line's moment on show, when it only shows for a moment (Off when it shows until the love runs). */
    enum class Peek { Off, Waiting, Showing, Gone }

    var phase by mutableStateOf(Phase.Ready)
        private set
    var peek by mutableStateOf(if (peekMs > 0) Peek.Waiting else Peek.Off)
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

    /** The pour can start: the round is ready, and the line has had its moment if it only gets one. */
    val canPour: Boolean get() = phase == Phase.Ready && (peek == Peek.Off || peek == Peek.Gone)

    /** Whether the line shows now. */
    val lineShows: Boolean get() = scored != null || (phase == Phase.Ready && (peek == Peek.Off || peek == Peek.Showing))

    init { hint = readyHint() }

    /** What to do, and every twist of this round: the line's moment, a faster pour, a jar that rises unevenly. */
    private fun readyHint(): String = listOfNotNull(
        if (peekMs > 0) tr("Remember the line. It hides before you can pour.") else tr("Stop right at the dashed line.") + " " + tr("It hides once the love runs."),
        if (fillSeconds <= 3.0f) tr("The pour runs faster.") else null,
        if (jarKind.odd) tr("It rises faster where the jar is narrow.") else null,
    ).joinToString(" ")

    /** Shows the line for its moment (from level [PEEK_LEVEL]). */
    fun showLine() {
        if (phase != Phase.Ready || peek != Peek.Waiting) return
        peek = Peek.Showing
    }

    /** The line's moment is over: hide it, and let the pour start. */
    fun hideLine() {
        if (peek != Peek.Showing) return
        peek = Peek.Gone
        hint = tr("Now pour to where the line was.")
    }

    fun press() {
        if (!canPour) return
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
            finish(fillScore(level, target, off, k), 250)
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
        val lineAlpha by animateFloatAsState(if (lineShows) 1f else 0f, moving(tween(if (phase == Phase.Pouring) 260 else 500)), label = "line")
        LaunchedEffect(peek) {
            if (peek != Peek.Showing) return@LaunchedEffect
            delay(peekMs)
            hideLine()
            haptics.tick()
        }
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
        val described = tr("A jar to fill: {jar}. Touch and hold to pour, let go to stop at the line.", "jar" to jarKind.label)
        Canvas(
            modifier
                .onSizeChanged { size = it.toSize() }
                .semantics { contentDescription = described }
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
            if (peek == Peek.Waiting) showLine() else press()
            waitForUpOrCancellation()
            release()
        }
    }

    override val hasControls: Boolean get() = true

    @Composable
    override fun Controls(modifier: Modifier) {
        val label = when {
            phase == Phase.Ready && peek == Peek.Waiting -> tr("Show the line")
            phase == Phase.Ready && peek == Peek.Showing -> tr("Remember it...")
            phase == Phase.Ready -> tr("Hold to pour")
            phase == Phase.Pouring -> tr("Let go at the line")
            else -> tr("Settling...")
        }
        val enabled = phase == Phase.Pouring || canPour || peek == Peek.Waiting
        PressPill(label, enabled, modifier, onDown = { if (peek == Peek.Waiting) showLine() else press() }, onUp = { release() })
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

    /** A part drawn in the sticker's way: a white keyline, then the fill. */
    private fun DrawScope.part(path: Path, fill: Color) {
        drawPath(path, Art.Keyline, style = Stroke(5.dp.toPx(), join = StrokeJoin.Round))
        drawPath(path, fill)
    }

    private fun DrawScope.drawJar(f: Fit) {
        val path = f.path(jar.outline)
        drawBehind(f)
        // What it stands on: a little cradle under a point or a round bottom, a shelf under a flat one.
        when {
            jarKind == JarKind.Balloon || jarKind == JarKind.Hourglass -> Unit
            jarKind.cradle -> {
                val base = f.px(Offset(50f, jar.bottom + 1f))
                drawRoundRect(Art.Keyline, Offset(base.x - f.px(14f), base.y - f.px(6f)), Size(f.px(28f), f.px(10f)), CornerRadius(f.px(4f)))
                drawRoundRect(Art.Right, Offset(base.x - f.px(12f), base.y - f.px(4.5f)), Size(f.px(24f), f.px(7f)), CornerRadius(f.px(3f)))
            }
            else -> {
                val (lo, hi) = Jar.spanAt(jar.outline, jar.bottom - 0.5f)
                val a = f.px(Offset(lo - 8f, jar.bottom + 1f))
                val w = f.px(hi - lo + 16f)
                drawRoundRect(Art.Keyline, Offset(a.x - 3.dp.toPx(), a.y - f.px(2.5f)), Size(w + 6.dp.toPx(), f.px(9f)), CornerRadius(f.px(4f)))
                drawRoundRect(Art.Right, Offset(a.x, a.y - f.px(1f)), Size(w, f.px(6f)), CornerRadius(f.px(3f)))
            }
        }
        // The die-cut keyline and a soft lift, then the glass.
        translate(0f, 4.dp.toPx()) { drawPath(path, Art.Shadow, alpha = 0.12f, style = Stroke(12.dp.toPx(), join = StrokeJoin.Round)) }
        drawPath(path, Art.Keyline, style = Stroke(10.dp.toPx(), join = StrokeJoin.Round))
        drawPath(path, Color(0xFFF7F8FD))
        clipPath(path) { drawLove(f) }
        drawFront(f)
        drawPath(path, Art.Right.copy(alpha = 0.55f), style = Stroke(2.5.dp.toPx(), join = StrokeJoin.Round))
        drawGlint(f)
    }

    /** What sits behind the glass: a mug's handle, a chimney, the posts of an hourglass, a balloon's string. */
    private fun DrawScope.drawBehind(f: Fit) {
        when (jarKind) {
            JarKind.Mug -> {
                val c = f.px(Offset(80f, 58f))
                val r = f.px(15f)
                val box = Rect(c, r)
                drawArc(Art.Keyline, -105f, 210f, false, box.topLeft, box.size, style = Stroke(f.px(7f) + 6.dp.toPx(), cap = StrokeCap.Round))
                drawArc(Art.Right, -105f, 210f, false, box.topLeft, box.size, style = Stroke(f.px(7f), cap = StrokeCap.Round))
            }
            JarKind.House -> part(f.path(listOf(Offset(63f, 16f), Offset(74f, 16f), Offset(74f, 44f), Offset(63f, 44f))), Art.Right)
            JarKind.Hourglass -> {
                part(f.path(listOf(Offset(9f, 6f), Offset(14f, 6f), Offset(14f, 104f), Offset(9f, 104f))), Art.Right)
                part(f.path(listOf(Offset(86f, 6f), Offset(91f, 6f), Offset(91f, 104f), Offset(86f, 104f))), Art.Right)
            }
            JarKind.Balloon -> {
                val string = Path().apply {
                    val a = f.px(Offset(50f, 93f)); moveTo(a.x, a.y)
                    val b = f.px(Offset(57f, 98f)); val c = f.px(Offset(46f, 102f)); quadraticTo(b.x, b.y, c.x, c.y)
                    val d = f.px(Offset(41f, 105f)); val e = f.px(Offset(49f, 109f)); quadraticTo(d.x, d.y, e.x, e.y)
                }
                drawPath(string, Art.Keyline, style = Stroke(f.px(1.8f) + 4.dp.toPx(), cap = StrokeCap.Round))
                drawPath(string, Art.Right, style = Stroke(f.px(1.8f), cap = StrokeCap.Round))
            }
            else -> Unit
        }
    }

    /** What's printed on the glass, over the love: a ribbon and a bow, an envelope's flap and seal, a door, a label. */
    private fun DrawScope.drawFront(f: Fit) {
        val line = Art.Right.copy(alpha = 0.55f)
        val thin = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun seal(at: Offset, size: Float) = translate(f.px(at).x, f.px(at).y) {
            val h = unitHeart(f.px(size))
            drawPath(h, Art.Keyline, style = Stroke(3.dp.toPx(), join = StrokeJoin.Round))
            drawPath(h, PlayInk.Pen)
        }
        when (jarKind) {
            JarKind.Gift -> {
                drawPath(f.path(listOf(Offset(29f, 24f), Offset(37f, 24f), Offset(37f, 100f), Offset(29f, 100f))), PlayInk.Pen.copy(alpha = 0.5f))
                drawPath(f.path(listOf(Offset(12f, 38f), Offset(88f, 38f)), close = false), line, style = thin)
                // The bow, on the ribbon.
                val l = f.path(listOf(Offset(33f, 24f), Offset(19f, 11f), Offset(17f, 25f)))
                val r = f.path(listOf(Offset(33f, 24f), Offset(47f, 11f), Offset(49f, 25f)))
                drawPath(l, Art.Keyline, style = Stroke(4.dp.toPx(), join = StrokeJoin.Round)); drawPath(r, Art.Keyline, style = Stroke(4.dp.toPx(), join = StrokeJoin.Round))
                drawPath(l, PlayInk.Pen); drawPath(r, PlayInk.Pen)
                drawCircle(Art.Keyline, f.px(4.2f) + 1.5.dp.toPx(), f.px(Offset(33f, 23f)))
                drawCircle(PlayInk.Pen, f.px(4.2f), f.px(Offset(33f, 23f)))
            }
            JarKind.Envelope -> {
                drawPath(f.path(listOf(Offset(7f, 43f), Offset(50f, 72f), Offset(93f, 43f)), close = false), line, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(f.path(listOf(Offset(7f, 99f), Offset(39f, 76f)), close = false), Art.Right.copy(alpha = 0.35f), style = thin)
                drawPath(f.path(listOf(Offset(93f, 99f), Offset(61f, 76f)), close = false), Art.Right.copy(alpha = 0.35f), style = thin)
                seal(Offset(50f, 72f), 13f)
            }
            JarKind.House -> {
                val door = Path().apply {
                    val a = f.px(Offset(43f, 100f)); moveTo(a.x, a.y)
                    val b = f.px(Offset(43f, 87f)); lineTo(b.x, b.y)
                    val c = f.px(Offset(50f, 80f)); val d = f.px(Offset(57f, 87f))
                    quadraticTo(f.px(Offset(43f, 80f)).x, c.y, c.x, c.y)
                    quadraticTo(f.px(Offset(57f, 80f)).x, c.y, d.x, d.y)
                    val e = f.px(Offset(57f, 100f)); lineTo(e.x, e.y)
                }
                drawPath(door, line, style = thin)
                translate(f.px(Offset(50f, 40f)).x, f.px(Offset(50f, 40f)).y) { drawPath(unitHeart(f.px(12f)), line, style = thin) }
            }
            JarKind.Bottle -> {
                val label = f.px(Offset(33f, 56f))
                drawRoundRect(Color.White.copy(alpha = 0.5f), label, Size(f.px(34f), f.px(26f)), CornerRadius(f.px(4f)))
                drawRoundRect(line, label, Size(f.px(34f), f.px(26f)), CornerRadius(f.px(4f)), style = thin)
                seal(Offset(50f, 69f), 11f)
            }
            JarKind.Hourglass -> {
                for (y in listOf(3f, 100f)) {
                    part(f.path(Jar.rounded(listOf(Offset(5f, y) to 2f, Offset(44f, y) to 2f, Offset(44f, y + 7f) to 2f, Offset(5f, y + 7f) to 2f))), Art.Right)
                    part(f.path(Jar.rounded(listOf(Offset(56f, y) to 2f, Offset(95f, y) to 2f, Offset(95f, y + 7f) to 2f, Offset(56f, y + 7f) to 2f))), Art.Right)
                }
            }
            else -> Unit
        }
    }

    /** A white glint on the glass, where light would catch it. */
    private fun DrawScope.drawGlint(f: Fit) {
        val glint = Color.White.copy(alpha = 0.9f)
        val stroke = Stroke(f.px(3f), cap = StrokeCap.Round)
        fun arc(c: Offset, r: Float, start: Float, sweep: Float) {
            val p = Path().apply { addArc(Rect(f.px(c), f.px(r)), start, sweep) }
            drawPath(p, glint, style = stroke)
        }
        fun line(a: Offset, b: Offset) = drawLine(glint, f.px(a), f.px(b), f.px(3f), cap = StrokeCap.Round)
        when (jarKind) {
            JarKind.Heart -> arc(Offset(29f, 37f), 21f, 195f, 60f)
            JarKind.Mug -> line(Offset(31f, 32f), Offset(31f, 58f))
            JarKind.Tumbler -> line(Offset(24.5f, 26f), Offset(29f, 62f))
            JarKind.Gift -> line(Offset(24f, 46f), Offset(24f, 68f))
            JarKind.Envelope -> line(Offset(13f, 52f), Offset(13f, 66f))
            JarKind.House -> line(Offset(26f, 62f), Offset(26f, 84f))
            JarKind.Bottle -> line(Offset(31f, 40f), Offset(31f, 50f))
            JarKind.Star -> arc(Offset(50f, 58f), 14f, 200f, 50f)
            JarKind.Drop -> arc(Offset(50f, 66f), 24f, 190f, 55f)
            JarKind.Balloon -> arc(Offset(50f, 45f), 28f, 195f, 55f)
            JarKind.Cloud -> arc(Offset(42f, 61f), 14f, 200f, 60f)
            JarKind.Flask -> arc(Offset(50f, 68f), 23f, 190f, 55f)
            JarKind.WaistCup -> line(Offset(22f, 16f), Offset(29f, 31f))
            JarKind.Hourglass -> line(Offset(26f, 74f), Offset(24f, 90f))
            JarKind.Vase -> arc(Offset(50f, 62f), 24f, 190f, 50f)
        }
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
            val br = f.px(Offset(102f, 110f)); val bl = f.px(Offset(-2f, 110f))
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
        val (lo, hi) = Jar.spanAt(jar.outline, y)
        val a = f.px(Offset(lo - 9f, y))
        val b = f.px(Offset(hi + 9f, y))
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

    companion object {
        /** The level from which the line shows only for a moment, before the pour. */
        const val PEEK_LEVEL = 8
    }
}
