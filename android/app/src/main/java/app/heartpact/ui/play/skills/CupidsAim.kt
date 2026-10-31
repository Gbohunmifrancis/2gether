package app.heartpact.ui.play.skills

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import app.heartpact.i18n.tr
import app.heartpact.ui.graphics.HeartGeometry
import app.heartpact.ui.play.PlayInk
import app.heartpact.ui.play.SkillKind
import app.heartpact.ui.play.rememberPlayHaptics
import app.heartpact.ui.play.unitHeart
import app.heartpact.ui.theme.Art
import app.heartpact.ui.theme.Heartpact
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sign
import kotlin.math.sin

/*
 * Cupid's aim: a heart floats across the sky; one tap looses Cupid's arrow straight up from the bow. The arrow takes
 * a moment to fly, so you loose it before the heart gets there. A hit goes right through and sticks with a thunk and
 * a wobble; then a dashed line shows how far from the heart's centre it passed.
 */

internal class CupidPlay(seed: Long, difficulty: Difficulty = Difficulty.Start) : SkillPlay(SkillKind.CupidsAim, seed, difficulty) {
    val flight = CupidFlight(seed, difficulty)
    private val k = difficulty.k(0.3f)

    /** Seconds since the heart took off (the frame clock), and the frame time it took off at. */
    var now by mutableFloatStateOf(0f)
        private set
    private var startMs = Long.MIN_VALUE

    var shot by mutableStateOf<CupidShot?>(null)
        private set

    init { hint = tr("Tap anywhere to loose the arrow.") }

    /** The heart's own time: it stops where it was when the arrow passed. */
    val heartTime: Float get() = shot?.let { minOf(now, it.crossed) } ?: now

    /** How tall the heart is, in units. */
    val heartHeight: Float get() = flight.size * HeartBox.height / HeartBox.width

    /** Where a hit stops: the tip just out of the top of the heart. When it gets there, and where. */
    private fun stickTip(s: CupidShot): Float = s.heartAt.y - (HeartBox.centre.y - (100f - HeartBox.height)) / HeartBox.height * heartHeight - 0.035f
    fun stuckAt(s: CupidShot): Float = s.crossed + (s.heartAt.y - stickTip(s)) / ARROW_SPEED

    /** The arrow's tip now: in flight, stuck in the heart, or gone off the top. */
    fun tip(s: CupidShot): Offset = arrowTip(s.loosed, if (s.hit) minOf(now, stuckAt(s)) else now)

    /** The frame clock at [frameMs]. */
    fun advance(frameMs: Long) {
        if (startMs == Long.MIN_VALUE) startMs = frameMs
        now = maxOf(now, (frameMs - startMs) / 1000f)
    }

    /** A time on the frame clock (a touch's), as seconds since take-off. */
    fun clockAt(ms: Long): Float = if (startMs == Long.MIN_VALUE) 0f else ((ms - startMs) / 1000f).coerceAtLeast(0f)

    /** Looses the arrow at [t] seconds; the score is known at once, and revealed once it has flown. */
    fun loose(t: Float): Boolean {
        if (shot != null) return false
        val s = cupidShot(flight, t)
        shot = s
        hint = ""
        finish(cupidScore(s, flight.size, k), ((stuckAt(s) - t) * 1000).toLong() + 1300)
        return true
    }

    override val hasControls: Boolean get() = true

    @Composable
    override fun Controls(modifier: Modifier) {
        val haptics = rememberPlayHaptics()
        PressPill(tr("Loose the arrow"), shot == null, modifier, onDown = { t -> if (loose(clockAt(t))) haptics.tick() })
    }

    @Composable
    override fun Board(modifier: Modifier) {
        val colors = Heartpact.colors
        val haptics = rememberPlayHaptics()
        val still = rememberMotionOff()
        var box by remember { mutableStateOf(Size.Zero) }
        val fit = Fit.of(box, 1f, CUPID_H, with(LocalDensity.current) { 12.dp.toPx() })
        val wobble = remember { Animatable(0f) }
        val burst = remember { Animatable(0f) }
        val mark = remember { Animatable(0f) }
        val s = shot
        // The heart flies until the arrow has passed it and gone (or stuck), then the clock rests.
        LaunchedEffect(Unit) {
            while (true) {
                withFrameMillis { advance(it) }
                val done = shot
                if (done != null && now > stuckAt(done) + 2.5f) break
            }
        }
        val markIn = moving(tween<Float>(480, delayMillis = 350, easing = LinearOutSlowInEasing))
        val impact = s != null && now >= stuckAt(s)
        LaunchedEffect(impact) {
            if (!impact || s == null) return@LaunchedEffect
            if (s.hit) haptics.thud() else haptics.tick()
            if (!still) {
                wobble.snapTo(1f)
                launch { wobble.animateTo(0f, spring(dampingRatio = 0.22f, stiffness = 260f)) }
                if (s.hit) launch { burst.snapTo(0f); burst.animateTo(1f, tween(720, easing = LinearEasing)) }
            }
            mark.animateTo(1f, markIn)
        }
        val heartPath = remember { HeartGeometry.whole() }
        val leftPath = remember { HeartGeometry.left() }
        val rightPath = remember { HeartGeometry.right() }
        Canvas(
            modifier
                .clipToBounds()
                .onSizeChanged { box = it.toSize() }
                .semantics { contentDescription = tr("A heart floats across the sky above a bow. Tap to loose the arrow straight up.") }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        if (loose(clockAt(down.uptimeMillis))) haptics.tick()
                    }
                },
        ) {
            val clouds = if (still) 0f else now
            drawCloud(fit, Offset(((0.15f + clouds * 0.018f) % 1.5f) - 0.25f, 0.16f), 0.16f)
            drawCloud(fit, Offset(((0.95f + clouds * 0.012f) % 1.5f) - 0.25f, 0.84f), 0.12f)
            if (flight.aimLine && s == null) {
                drawLine(colors.ink4, fit.px(Offset(BOW.x, 0.04f)), fit.px(Offset(BOW.x, BOW.y - 0.1f)), 2.dp.toPx(), cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 9.dp.toPx())))
            }
            val t = heartTime
            if (flight.decoy) drawDecoy(fit, flight.decoyAt(t), heartPath, colors.ink4, colors.sticker)
            val c = flight.at(t)
            val w = wobble.value
            val dir = if (s != null && s.dx != 0f) sign(s.dx) else 1f
            // A hit swings the heart (and the arrow in it) round its centre; a miss makes it shiver.
            val swing = if (s?.hit == true) 13f * w * dir * cos(w * 9f) else 0f
            val shiver = if (s != null && !s.hit) fit.px(0.012f) * w * sin(w * 30f) else 0f
            val centre = fit.px(c) + Offset(shiver, 0f)
            rotate(swing, centre) {
                // Behind the heart, so a hit reads as an arrow through it.
                if (s != null) drawArrow(fit, tip(s), colors.ink2)
                drawHeart(centre, fit.px(flight.size), heartPath, leftPath, rightPath, squash = 0.06f * w * cos(w * 12f))
            }
            if (s != null && s.hit && burst.value in 0.001f..0.999f) drawBurst(fit.px(Offset(BOW.x, s.heartAt.y)), burst.value, fit.px(flight.size))
            drawBow(fit, s, now, still, colors.ink2)
            if (s == null) drawArrow(fit, arrowTip(0f, 0f), colors.ink2)
            if (s != null && mark.value > 0f) {
                // How far off the centre it passed, drawn from the arrow's line to the centre.
                val from = fit.px(Offset(BOW.x, s.heartAt.y))
                rotate(swing, centre) {
                    drawLine(PlayInk.Pen, from, from + (centre - from) * mark.value, 2.5.dp.toPx(), cap = StrokeCap.Round,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 5.dp.toPx())))
                    drawCircle(Art.Keyline, 5.dp.toPx() * mark.value, from)
                    drawCircle(PlayInk.Pen, 3.5.dp.toPx() * mark.value, from)
                }
            }
        }
    }
}

/** The app's two-tone heart as a sticker, [width] px wide, centred on its centre of area, with a target at the centre. */
private fun DrawScope.drawHeart(centre: Offset, width: Float, whole: Path, left: Path, right: Path, squash: Float = 0f) {
    val k = width / HeartBox.width
    val drop = 10.dp.toPx()
    // A soft shadow on the page below: it's floating.
    withTransform({
        translate(centre.x, centre.y + drop)
        scale(k, k, Offset.Zero)
        translate(-HeartBox.centre.x, -HeartBox.centre.y)
    }) { drawPath(whole, Art.Shadow, alpha = 0.10f) }
    withTransform({
        translate(centre.x, centre.y)
        scale(k * (1f + squash), k * (1f - squash), Offset.Zero)
        translate(-HeartBox.centre.x, -HeartBox.centre.y)
    }) {
        drawPath(whole, Art.Keyline, style = Stroke(8.dp.toPx() / k, join = StrokeJoin.Round))
        drawPath(left, Art.Left)
        drawPath(right, Art.Right)
        // A glint on the left lobe, and the target: a white ring and dot on the fold, at the centre.
        drawArc(Color.White.copy(alpha = 0.55f), 200f, 55f, false, HeartGeometry.leftLobe - Offset(20f, 20f), Size(40f, 40f),
            style = Stroke(4.5f, cap = StrokeCap.Round))
        drawCircle(Color.White.copy(alpha = 0.85f), 9f, HeartBox.centre, style = Stroke(2.6f))
        drawCircle(Color.White, 3.2f, HeartBox.centre)
    }
}

/** The decoy: a heart's outline only, dashed, like a slot still to fill. */
private fun DrawScope.drawDecoy(fit: Fit, at: Offset, whole: Path, ink: Color, fill: Color) {
    val k = fit.px(0.24f) / HeartBox.width
    withTransform({
        translate(fit.px(at).x, fit.px(at).y)
        scale(k, k, Offset.Zero)
        translate(-HeartBox.centre.x, -HeartBox.centre.y)
    }) {
        drawPath(whole, fill, alpha = 0.55f)
        drawPath(whole, ink, style = Stroke(3.dp.toPx() / k, pathEffect = PathEffect.dashPathEffect(floatArrayOf(9.dp.toPx() / k, 8.dp.toPx() / k))))
    }
}

/** Cupid's arrow, point up, its tip at [tip] (units): a heart for a head, a shaft, pink feathers. */
private fun DrawScope.drawArrow(fit: Fit, tip: Offset, shaft: Color) {
    val top = fit.px(tip)
    val len = fit.px(ARROW_LENGTH)
    val tail = top + Offset(0f, len)
    val head = 15.dp.toPx()
    drawLine(Art.Keyline, top + Offset(0f, head * 0.5f), tail, 6.dp.toPx(), cap = StrokeCap.Round)
    drawLine(shaft, top + Offset(0f, head * 0.5f), tail, 2.6.dp.toPx(), cap = StrokeCap.Round)
    // Feathers: two leaves each side of the tail.
    val f = 9.dp.toPx()
    for (side in listOf(-1f, 1f)) for (step in 0..1) {
        val y = tail.y - f * 0.4f - step * f * 0.75f
        val feather = Path().apply {
            moveTo(tail.x, y)
            quadraticTo(tail.x + side * f * 1.1f, y + f * 0.1f, tail.x + side * f * 0.9f, y + f * 0.95f)
            quadraticTo(tail.x + side * f * 0.35f, y + f * 0.75f, tail.x, y + f * 0.35f)
            close()
        }
        drawPath(feather, Art.Keyline, style = Stroke(2.5.dp.toPx(), join = StrokeJoin.Round))
        drawPath(feather, if (step == 0) PlayInk.Pen else PlayInk.PenSoft)
    }
    // The head: a heart turned point up.
    withTransform({ translate(top.x, top.y + head * 0.4f); rotate(180f, Offset.Zero) }) {
        val h = unitHeart(head)
        drawPath(h, Art.Keyline, style = Stroke(3.dp.toPx(), join = StrokeJoin.Round))
        drawPath(h, PlayInk.Pen)
    }
}

/** The bow, lying across the bottom: drawn back with the arrow nocked, then twanging once it's loosed. */
private fun DrawScope.drawBow(fit: Fit, shot: CupidShot?, now: Float, still: Boolean, string: Color) {
    val l = fit.px(Offset(BOW.x - 0.17f, BOW.y + 0.07f))
    val r = fit.px(Offset(BOW.x + 0.17f, BOW.y + 0.07f))
    val bend = fit.px(Offset(BOW.x, BOW.y - 0.11f))
    val nock = if (shot == null) fit.px(Offset(BOW.x, BOW.y - 0.06f + ARROW_LENGTH)) else {
        // Snaps back and shivers.
        val since = (now - shot.loosed).coerceAtLeast(0f)
        val shake = if (still) 0f else fit.px(0.03f) * exp(-since * 9f) * cos(since * 70f)
        Offset((l.x + r.x) / 2f, l.y + shake)
    }
    val stringPath = Path().apply { moveTo(l.x, l.y); lineTo(nock.x, nock.y); lineTo(r.x, r.y) }
    drawPath(stringPath, string, style = Stroke(1.8.dp.toPx(), join = StrokeJoin.Round))
    val limb = Path().apply { moveTo(l.x, l.y); quadraticTo(bend.x, bend.y, r.x, r.y) }
    drawPath(limb, Art.Keyline, style = Stroke(11.dp.toPx(), cap = StrokeCap.Round))
    drawPath(limb, Art.Left, style = Stroke(6.dp.toPx(), cap = StrokeCap.Round))
    for (end in listOf(l, r)) {
        drawCircle(Art.Keyline, 6.dp.toPx(), end)
        drawCircle(Art.Right, 4.dp.toPx(), end)
    }
    // The grip: a little pink heart where the hand would hold it.
    val grip = Offset(bend.x, (l.y + bend.y) / 2f + 2.dp.toPx())
    translate(grip.x, grip.y) {
        val h = unitHeart(17.dp.toPx())
        drawPath(h, Art.Keyline, style = Stroke(3.dp.toPx(), join = StrokeJoin.Round))
        drawPath(h, PlayInk.Pen)
    }
}

/** A white sticker cloud, [w] units wide, its middle at [at]. */
private fun DrawScope.drawCloud(fit: Fit, at: Offset, w: Float) {
    val c = fit.px(at)
    val u = fit.px(w)
    val puffs = listOf(Offset(-0.3f, 0.06f) to 0.2f, Offset(0f, -0.06f) to 0.28f, Offset(0.3f, 0.06f) to 0.2f)
    for ((o, r) in puffs) drawCircle(Art.Shadow.copy(alpha = 0.07f), u * r, c + o * u + Offset(0f, 4.dp.toPx()))
    for ((o, r) in puffs) drawCircle(Color.White.copy(alpha = 0.9f), u * r, c + o * u)
    drawRect(Color.White.copy(alpha = 0.9f), c + Offset(-0.3f * u, 0.02f * u), Size(0.6f * u, 0.24f * u))
}

/** Little hearts flying out of a hit, [p] 0 to 1. */
private fun DrawScope.drawBurst(at: Offset, p: Float, spread: Float) {
    val colours = listOf(PlayInk.Pen, PlayInk.PenSoft, Art.Right, PlayInk.Blush)
    val out = 1f - (1f - p) * (1f - p)
    repeat(9) { i ->
        val a = i / 9f * 2f * PI.toFloat() + 0.3f
        val d = spread * (0.45f + 0.25f * ((i * 37) % 5) / 4f) * out
        val pos = at + Offset(cos(a), sin(a)) * d + Offset(0f, 30.dp.toPx() * p * p)
        translate(pos.x, pos.y) { drawPath(unitHeart(9.dp.toPx() + (i % 3) * 3.dp.toPx()), colours[i % colours.size], alpha = (1f - p).coerceIn(0f, 1f)) }
    }
}
