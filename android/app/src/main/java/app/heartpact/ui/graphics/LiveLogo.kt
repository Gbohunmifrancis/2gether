package app.heartpact.ui.graphics

import android.provider.Settings
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import app.heartpact.ui.theme.Art
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * How the mark feels where it appears. It drops in, bounces, claps its halves, dances and somersaults to suit the
 * screen, and pulls a face; the tick, the brand, never changes.
 */
enum class LogoMood(internal val entersOnStage: Boolean) {
    /** Welcome: drops in, then bounces, claps, dances and somersaults. */
    Happy(true),
    /** Sign in: drops in, waves with a wink, hops and claps. */
    Hello(true),
    /** Something went wrong: brows up, shivers and gulps; no tricks. */
    Worried(false),
    /** Waiting for the partner: glances about, leans side to side, hops on the spot. */
    Waiting(true),
    /** Something worth celebrating: hop, hop, somersault, clap, dance. */
    Cheer(true),
    /** Late at night: eyes closed, breathing slowly, nodding off and waking with a start. */
    Sleepy(false),
    /** Out of the way (the navigation rail): breathing and blinking, with a hop or a clap now and then. */
    Idle(false),
}

/**
 * True holds every [LiveLogo] still, at rest: for screenshot tests and previews, which wait for the screen to stop
 * changing.
 */
val LocalStillLogo = staticCompositionLocalOf { false }

/**
 * The mark, alive: [LogoMark] with a face, playing [mood]'s routine. A tap makes it somersault and burst into hearts.
 * [size] is the mark's width and the layout is the mark's own box, as for [LogoMark]; jumps, flips and hearts draw
 * beyond it, so give it a little room above. Still, at rest, when animations are off.
 */
@Composable
fun LiveLogo(size: Dp, mood: LogoMood, night: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val still = LocalStillLogo.current || LocalInspectionMode.current
    val animate = remember { !still && Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f }
    // Read only while drawing, so each frame redraws without recomposing.
    var clock by remember { mutableFloatStateOf(0f) }
    var moodAt by remember { mutableFloatStateOf(0f) }
    var entering by remember { mutableStateOf(animate && mood.entersOnStage) }
    var pokedAt by remember { mutableStateOf<Float?>(null) }
    var shownMood by remember { mutableStateOf(mood) }
    LaunchedEffect(mood) {
        // A new mood starts its routine from the top; only the first one drops in.
        if (mood != shownMood) { shownMood = mood; moodAt = clock; entering = false }
    }
    if (animate) LaunchedEffect(Unit) {
        // Infinite-animation frames, so anything waiting for the screen to settle doesn't wait forever.
        val start = withInfiniteAnimationFrameMillis { it }
        while (true) clock = withInfiniteAnimationFrameMillis { (it - start).toFloat() }
    }
    val tappable = if (animate) modifier.pointerInput(Unit) { detectTapGestures { pokedAt = clock } } else modifier
    LogoCanvas(size, tappable) {
        drawLiveLogo(clock - moodAt, mood, night, entrance = entering, sincePoke = pokedAt?.let { clock - it })
    }
}

/** One frame of [LiveLogo], laid out the same: [t] ms into [mood]'s routine (after the drop-in, with [entrance]). */
@Composable
fun LiveLogoFrame(size: Dp, mood: LogoMood, night: Boolean, t: Float, modifier: Modifier = Modifier, entrance: Boolean = false, sincePoke: Float? = null) =
    LogoCanvas(size, modifier) { drawLiveLogo(t, mood, night, entrance, sincePoke) }

/** The mark's box for layout; the canvas is far bigger, mostly above, and draws over whatever is around. */
@Composable
private fun LogoCanvas(size: Dp, modifier: Modifier, draw: DrawScope.() -> Unit) {
    val unit = size / MARK_W
    Box(modifier.size(size, unit * MARK_H)) {
        Canvas(
            Modifier
                .offset(y = unit * (CANVAS_Y + CANVAS_H / 2 - (MARK_Y + MARK_H / 2)))
                .requiredSize(unit * CANVAS_W, unit * CANVAS_H)
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen),
        ) { inUnits(CANVAS_X, CANVAS_Y, CANVAS_W, CANVAS_H, draw) }
    }
}

// The mark's own box, as in LogoMark, and the canvas around it in heart units: room above for drops, jumps and
// hearts, and to the sides for somersaults.
private const val MARK_Y = 4.4f
private const val MARK_W = 104f
private const val MARK_H = 97.6f
private const val CANVAS_X = -46f
private const val CANVAS_Y = -74f
private const val CANVAS_W = 192f
private const val CANVAS_H = 186f

/**
 * One frame of the live mark at [t] ms into [mood]'s routine, in heart units. With [entrance], the first
 * [ENTRANCE_MS] are the drop-in. [sincePoke] is the time since a tap. Pure, so every frame can be checked.
 */
fun DrawScope.drawLiveLogo(t: Float, mood: LogoMood, night: Boolean, entrance: Boolean = false, sincePoke: Float? = null, faceAlpha: Float = 1f) {
    val poked = sincePoke != null && sincePoke < POKE_MS
    val pose = when {
        poked -> trick(Trick.Flip, sincePoke!! / POKE_MS, sincePoke)
        entrance && t < ENTRANCE_MS -> entrance(t)
        else -> routinePose(if (entrance) t - ENTRANCE_MS else t, mood)
    }
    val left = if (night) Art.MarkNightLeft else Art.Left
    val right = if (night) Art.MarkNightRight else Art.Right
    withTransform({
        translate(pose.dx, pose.dy)
        rotate(pose.tilt, pivot = Offset(50f, 100f))
        rotate(pose.spin, pivot = Offset(50f, 58f))
        scale(pose.sx, pose.sy, pivot = Offset(50f, 100f))
    }) {
        val face = faceAlpha * pose.alpha
        if (pose.split == 0f && pose.splitTilt == 0f) {
            drawMark(left, right, alpha = pose.alpha)
            if (face > 0f) drawLogoFace(t, mood, night, face, eyes = pose.eyes)
        } else {
            // Each half leans out from the point at the bottom, carrying its own eye; the cut tick splits with them.
            for (side in listOf(-1f, 1f)) withTransform({
                translate(side * pose.split, 0f)
                rotate(side * pose.splitTilt, pivot = Offset(50f, 100f))
            }) {
                clipRect(left = if (side < 0) -100f else 50f, top = -100f, right = if (side < 0) 50f else 200f, bottom = 200f) {
                    drawMark(left, right, alpha = pose.alpha)
                    if (face > 0f) drawLogoFace(t, mood, night, face, eyes = pose.eyes)
                }
            }
        }
    }
    if (mood == LogoMood.Sleepy && !poked) drawZs(t)
    heartBursts(t, mood, entrance, sincePoke).forEach { drawHearts(it) }
}

// ---- Motion -------------------------------------------------------------------------------------------------------

internal enum class Eyes { Normal, Happy, Wide, Shut, Wink }

private class LogoPose(
    val dx: Float = 0f, val dy: Float = 0f, val sx: Float = 1f, val sy: Float = 1f,
    /** Degrees, about the point at the bottom: rocking. */
    val tilt: Float = 0f,
    /** Degrees, about the middle: somersaults. */
    val spin: Float = 0f,
    /** How far each half moves out, and leans out (degrees), for the clap. */
    val split: Float = 0f, val splitTilt: Float = 0f,
    val eyes: Eyes? = null, val alpha: Float = 1f,
)

private enum class Trick(val ms: Float, val heartsAt: Float? = null) {
    Rest(0f), Hop(620f), BigHop(780f), DoubleBounce(1150f), Flip(1050f, 0.8f), Clap(1150f, 0.52f), Wiggle(1000f),
    Wave(1100f), Jelly(900f), Shiver(450f), Gulp(1100f), NodOff(1900f), Lean(2400f),
}

private class Step(val trick: Trick, val ms: Float = trick.ms)

private fun rest(ms: Float) = Step(Trick.Rest, ms)

private val ROUTINES: Map<LogoMood, List<Step>> = mapOf(
    LogoMood.Happy to listOf(rest(700f), Step(Trick.DoubleBounce), rest(1300f), Step(Trick.Clap), rest(1500f), Step(Trick.Wiggle), rest(1300f), Step(Trick.Flip), rest(1800f)),
    LogoMood.Hello to listOf(rest(600f), Step(Trick.Wave), rest(1300f), Step(Trick.BigHop), rest(1400f), Step(Trick.Clap), rest(1700f), Step(Trick.Flip), rest(1600f)),
    LogoMood.Worried to listOf(Step(Trick.Shiver), rest(1400f), Step(Trick.Gulp), rest(1500f), Step(Trick.Shiver), rest(1800f)),
    LogoMood.Waiting to listOf(rest(900f), Step(Trick.Lean), rest(800f), Step(Trick.Hop), Step(Trick.Hop), rest(1500f), Step(Trick.Jelly), rest(1600f)),
    LogoMood.Cheer to listOf(Step(Trick.Hop, 520f), Step(Trick.Hop, 520f), Step(Trick.Flip), rest(400f), Step(Trick.Clap), Step(Trick.Wiggle), rest(500f)),
    LogoMood.Sleepy to listOf(rest(2600f), Step(Trick.NodOff), rest(2600f)),
    LogoMood.Idle to listOf(rest(6000f), Step(Trick.Hop), rest(7000f), Step(Trick.Clap), rest(7000f), Step(Trick.Jelly)),
)

private fun List<Step>.total() = sumOf { it.ms.toDouble() }.toFloat()

/** The drop-in: falls from above, squashes, bounces twice and lets out a puff of hearts. */
const val ENTRANCE_MS = 1100f
private const val ENTRANCE_HEARTS = 560f
private const val POKE_MS = 1050f

private fun routinePose(t: Float, mood: LogoMood): LogoPose {
    val steps = ROUTINES.getValue(mood)
    var local = t.coerceAtLeast(0f) % steps.total()
    for (step in steps) {
        if (local < step.ms) return if (step.trick == Trick.Rest) breathe(t, mood) else trick(step.trick, local / step.ms, local)
        local -= step.ms
    }
    return breathe(t, mood)
}

private fun breathe(t: Float, mood: LogoMood): LogoPose {
    val b = sin(2f * PI.toFloat() * t / if (mood == LogoMood.Sleepy) 4000f else 2400f)
    return LogoPose(sx = 1f + 0.02f * b, sy = 1f + 0.035f * b)
}

private fun entrance(t: Float): LogoPose = when {
    t < 380f -> { val q = t / 380f; LogoPose(dy = -70f * (1f - q * q), sx = 0.9f, sy = 1.12f, eyes = Eyes.Wide, alpha = seg(t, 0f, 120f)) }
    t < 560f -> squash(seg(t, 380f, 560f), 0.24f, Eyes.Happy)
    t < 840f -> airborne(seg(t, 560f, 840f), 16f)
    t < 940f -> squash(seg(t, 840f, 940f), 0.1f)
    else -> airborne(seg(t, 940f, ENTRANCE_MS), 5f)
}

/** [p] is how far through the trick (0 to 1); [ms] the same in milliseconds, for fast wobbles. */
private fun trick(trick: Trick, p: Float, ms: Float): LogoPose = when (trick) {
    Trick.Rest -> LogoPose()
    Trick.Hop -> hop(p, 14f)
    Trick.BigHop -> hop(p, 24f)
    Trick.DoubleBounce -> if (p < 0.55f) hop(p / 0.55f, 20f) else hop((p - 0.55f) / 0.45f, 9f)
    Trick.Flip -> when {
        p < 0.16f -> squash(p / 0.16f, 0.12f)
        p < 0.8f -> {
            val q = seg(p, 0.16f, 0.8f)
            val s = sin(PI.toFloat() * q)
            LogoPose(dy = -30f * s, spin = 360f * easeInOut(seg(q, 0.08f, 0.92f)), sy = 1f + 0.05f * s, sx = 1f - 0.04f * s, eyes = Eyes.Happy)
        }
        else -> squash(seg(p, 0.8f, 1f), 0.2f, Eyes.Happy)
    }
    Trick.Clap -> when {
        // Apart, eyes wide; a beat; snap back together; a jelly squeeze and a puff of hearts.
        p < 0.35f -> { val a = easeOut(p / 0.35f); LogoPose(split = 9f * a, splitTilt = 10f * a, dy = -4f * a, eyes = Eyes.Wide) }
        p < 0.45f -> LogoPose(split = 9f + 0.6f * sin(ms / 12f), splitTilt = 10f, dy = -4f, eyes = Eyes.Wide)
        p < 0.52f -> { val a = 1f - seg(p, 0.45f, 0.52f).pow(2); LogoPose(split = 9f * a, splitTilt = 10f * a, dy = -4f * a, eyes = Eyes.Wide) }
        else -> jelly(seg(p, 0.52f, 1f), 0.14f, Eyes.Happy)
    }
    Trick.Wiggle -> {
        val e = sin(PI.toFloat() * p)
        val w = sin(2f * PI.toFloat() * 3f * p)
        LogoPose(tilt = 12f * w * e, dy = -5f * abs(w) * e, eyes = Eyes.Happy)
    }
    Trick.Wave -> {
        val e = sin(PI.toFloat() * p)
        val w = sin(2f * PI.toFloat() * 2f * p)
        LogoPose(tilt = 14f * w * e, dy = -3f * abs(w) * e, eyes = if (p in 0.3f..0.8f) Eyes.Wink else null)
    }
    Trick.Jelly -> jelly(p, 0.12f)
    Trick.Shiver -> LogoPose(dx = 2.2f * sin(2f * PI.toFloat() * ms / 70f) * (1f - p), sx = 0.97f, sy = 0.97f)
    Trick.Gulp -> when {
        p < 0.3f -> { val a = easeOut(p / 0.3f); LogoPose(sy = 1f - 0.1f * a, sx = 1f + 0.04f * a, eyes = Eyes.Wide) }
        p < 0.6f -> LogoPose(sy = 0.9f, sx = 1.04f, eyes = Eyes.Wide)
        else -> jelly(seg(p, 0.6f, 1f), 0.1f)
    }
    Trick.NodOff -> when {
        p < 0.7f -> LogoPose(tilt = -16f * easeInOut(p / 0.7f))
        p < 0.78f -> { val q = seg(p, 0.7f, 0.78f); LogoPose(tilt = -16f * (1f - q), dy = -6f * sin(PI.toFloat() * q), eyes = Eyes.Wide) }
        else -> jelly(seg(p, 0.78f, 1f), 0.08f, if (p < 0.9f) Eyes.Wide else null)
    }
    Trick.Lean -> LogoPose(tilt = 8f * sin(2f * PI.toFloat() * p))
}

/** Crouch, jump [height], land with a squash. */
private fun hop(p: Float, height: Float): LogoPose = when {
    p < 0.18f -> squash(p / 0.18f, 0.08f)
    p < 0.78f -> airborne(seg(p, 0.18f, 0.78f), height)
    else -> squash(seg(p, 0.78f, 1f), 0.16f)
}

private fun airborne(q: Float, height: Float): LogoPose {
    val s = sin(PI.toFloat() * q)
    return LogoPose(dy = -height * s, sy = 1f + 0.06f * s, sx = 1f - 0.04f * s)
}

private fun squash(q: Float, amount: Float, eyes: Eyes? = null): LogoPose {
    val b = bump(q)
    return LogoPose(sy = 1f - amount * b, sx = 1f + amount * 0.65f * b, eyes = eyes)
}

/** A wobble that dies away. */
private fun jelly(q: Float, amount: Float, eyes: Eyes? = null): LogoPose {
    val w = amount * exp(-3.5f * q) * sin(2f * PI.toFloat() * 3f * q)
    return LogoPose(sx = 1f + w, sy = 1f - 0.8f * w, eyes = eyes)
}

private fun bump(p: Float) = sin(PI.toFloat() * p.coerceIn(0f, 1f))
private fun seg(x: Float, a: Float, b: Float) = ((x - a) / (b - a)).coerceIn(0f, 1f)
private fun easeOut(p: Float) = 1f - (1f - p).pow(3)
private fun easeInOut(p: Float) = if (p < 0.5f) 4f * p * p * p else 1f - (-2f * p + 2f).pow(3) / 2f

// ---- Face ---------------------------------------------------------------------------------------------------------

// High in each lobe, mirrored about the heart's middle (x 50), above the tick's end cap (74, 38; radius 4.75).
private val LEFT_EYE = Offset(30f, 25f)
private val RIGHT_EYE = Offset(70f, 25f)
private val INK = Color(0xFF161A3D)
private val BLUSH = Color(0xFFE58AA8)

/**
 * Eyes clear of the tick: white on the indigo half, ink on the periwinkle one. [open] is how open the eyes are
 * (1 open, 0 shut); the splash opens them itself. [eyes] overrides the mood's own eyes for a trick.
 */
internal fun DrawScope.drawLogoFace(t: Float, mood: LogoMood, night: Boolean, alpha: Float, open: Float = blinkOpen(t, mood), eyes: Eyes? = null) {
    val leftInk = if (night) INK else Color.White
    val rightInk = INK
    val line = Stroke(3.2f, cap = StrokeCap.Round)
    val both = listOf(LEFT_EYE to leftInk, RIGHT_EYE to rightInk)
    val style = eyes ?: when (mood) {
        LogoMood.Cheer -> Eyes.Happy
        LogoMood.Sleepy -> Eyes.Shut
        else -> Eyes.Normal
    }
    when (style) {
        Eyes.Happy -> both.forEach { (at, ink) -> drawPath(arc(at, up = true), ink, alpha = alpha, style = line) }
        Eyes.Shut -> both.forEach { (at, ink) -> drawPath(arc(at, up = false), ink, alpha = alpha, style = line) }
        Eyes.Wide -> both.forEachIndexed { index, (at, ink) -> drawEye(at, ink, 1f, scale = 1.25f, catchlight = catchlight(index, night), alpha = alpha) }
        Eyes.Normal, Eyes.Wink -> {
            val glance = if (mood == LogoMood.Waiting) 2.6f * sin(2f * PI.toFloat() * t / 3400f).let { if (it > 0.5f) 1f else if (it < -0.5f) -1f else it * 2f } else 0f
            both.forEachIndexed { index, (at, ink) ->
                if (style == Eyes.Wink && index == 1) drawPath(arc(at, up = true), ink, alpha = alpha, style = line)
                else drawEye(at.copy(x = at.x + glance), ink, open, scale = if (mood == LogoMood.Worried) 0.85f else 1f, catchlight = catchlight(index, night), alpha = alpha)
            }
        }
    }
    if (mood == LogoMood.Worried && eyes == null) {
        drawPath(Path().apply { moveTo(LEFT_EYE.x - 6f, LEFT_EYE.y - 8f); lineTo(LEFT_EYE.x + 2.5f, LEFT_EYE.y - 10.5f) }, leftInk, alpha = alpha, style = Stroke(2.8f, cap = StrokeCap.Round))
        drawPath(Path().apply { moveTo(RIGHT_EYE.x + 6f, RIGHT_EYE.y - 8f); lineTo(RIGHT_EYE.x - 2.5f, RIGHT_EYE.y - 10.5f) }, rightInk, alpha = alpha, style = Stroke(2.8f, cap = StrokeCap.Round))
    }
    if (mood != LogoMood.Worried && (mood != LogoMood.Idle || style == Eyes.Happy)) {
        // Outside each eye, the right one kept off the tick's end.
        listOf(Offset(LEFT_EYE.x - 16f, LEFT_EYE.y + 5f), Offset(RIGHT_EYE.x + 8f, RIGHT_EYE.y + 5f))
            .forEach { drawOval(BLUSH, it, Size(8f, 4.5f), alpha = 0.6f * alpha) }
    }
}

private fun catchlight(index: Int, night: Boolean) = if (index == 0 && !night) Art.Left else Color.White

/** 1 open, 0 shut: a quick blink every few seconds, and a double blink now and then. */
private fun blinkOpen(t: Float, mood: LogoMood): Float {
    val every = if (mood == LogoMood.Idle) 4600f else 3800f
    val local = (t + 1700f) % every
    val second = (t + 1700f) % (every * 3) < every
    fun shut(at: Float) = if (local in at..at + 140f) 1f - bump((local - at) / 140f) else 1f
    return minOf(shut(0f), if (second) shut(260f) else 1f)
}

private fun DrawScope.drawEye(at: Offset, ink: Color, open: Float, scale: Float, catchlight: Color, alpha: Float) {
    val w = 7.8f * scale
    val h = 10.4f * scale * open.coerceAtLeast(0.12f)
    drawOval(ink, Offset(at.x - w / 2, at.y - h / 2), Size(w, h), alpha = alpha)
    if (open > 0.6f) drawCircle(catchlight, 1.5f * scale, Offset(at.x + 1.3f * scale, at.y - 2f * scale), alpha = alpha)
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

// ---- Hearts -------------------------------------------------------------------------------------------------------

private const val HEART_LIFE = 1200f
private val HEART_COLOURS = listOf(Color(0xFFFF8FB1), Art.Right, Color(0xFFFF5C8A), BLUSH, Color(0xFFFF8FB1))

/** The ages (ms) of every puff of hearts still in the air at [t]. */
private fun heartBursts(t: Float, mood: LogoMood, entrance: Boolean, sincePoke: Float?): List<Float> {
    val ages = mutableListOf<Float>()
    fun add(age: Float) { if (age in 0f..HEART_LIFE + 200f) ages += age }
    if (sincePoke != null) add(sincePoke)
    if (entrance) add(t - ENTRANCE_HEARTS)
    val start = if (entrance) ENTRANCE_MS else 0f
    if (t >= start) {
        val steps = ROUTINES.getValue(mood)
        val total = steps.total()
        val local = t - start
        val cycle = local - local % total
        // This time round and the last, for hearts still floating across the loop.
        for (round in listOf(cycle, cycle - total)) {
            if (round < 0f) continue
            var at = round
            for (step in steps) {
                step.trick.heartsAt?.let { add(local - (at + it * step.ms)) }
                at += step.ms
            }
        }
    }
    return ages
}

/** Five hearts popping out of the top of the mark, drifting up and apart, fading. */
private fun DrawScope.drawHearts(age: Float) {
    repeat(5) { i ->
        val q = (age - i * 40f) / HEART_LIFE
        if (q !in 0f..1f) return@repeat
        val spread = i - 2f
        val x = 50f + spread * 9f + spread * 18f * q + 5f * sin(2f * PI.toFloat() * q * 1.5f + i)
        // From just above the lobes, clear of the face, up past the mark.
        val y = 4f - 58f * easeOut(q)
        val pop = when {
            q < 0.12f -> 1.25f * q / 0.12f
            q < 0.22f -> 1.25f - 0.25f * seg(q, 0.12f, 0.22f)
            else -> 1f - 0.3f * seg(q, 0.22f, 1f)
        }
        val size = (if (i % 2 == 0) 17f else 13f) * pop
        withTransform({
            translate(x, y)
            rotate(spread * 12f, pivot = Offset.Zero)
            scale(size, size, pivot = Offset.Zero)
        }) { drawPath(HEART, HEART_COLOURS[i], alpha = 1f - q.pow(3)) }
    }
}

/** A unit heart around the origin. */
private val HEART = Path().apply {
    moveTo(0f, 0.4f)
    cubicTo(-0.65f, -0.05f, -0.5f, -0.62f, 0f, -0.26f)
    cubicTo(0.5f, -0.62f, 0.65f, -0.05f, 0f, 0.4f)
    close()
}
