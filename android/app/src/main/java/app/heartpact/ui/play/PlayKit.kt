package app.heartpact.ui.play

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.heartpact.i18n.tr
import app.heartpact.ui.graphics.Character
import app.heartpact.ui.graphics.Mood
import app.heartpact.ui.graphics.Pose
import app.heartpact.ui.graphics.Who
import app.heartpact.ui.theme.Art
import app.heartpact.ui.theme.Heartpact
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * The play kit: what makes a game feel alive, shared by every game so they all move the same way.
 * Sticker blobs to pick from, a pink pen that circles a score, a stamp that slams down, hearts that burst,
 * and Maya and Theo reacting. Everything here is drawn in the app's tokens (Indigo Fold) with one accent:
 * the pen pink of the hearts in the live logo.
 */

/** The pen and confetti pink, the one colour games add to the tokens (the live logo's hearts). */
object PlayInk {
    val Pen = Color(0xFFFF5C8A)
    val PenSoft = Color(0xFFFF8FB1)
    val Blush = Color(0xFFE58AA8)
}

/** Pastel blob fills for the game picker, each with an ink for its icon. Fixed by day and by night, like stickers. */
enum class BlobColour(val fill: Color, val ink: Color) {
    Lilac(Color(0xFFC9CDF6), Color(0xFF3F49A8)),
    Rose(Color(0xFFF9C2D3), Color(0xFFB0305E)),
    Sky(Color(0xFFBFE0F7), Color(0xFF1F6E9E)),
    Mint(Color(0xFFC4ECD6), Color(0xFF237A4F)),
    Butter(Color(0xFFF8E4A6), Color(0xFF8A6512)),
    Peach(Color(0xFFF9CDB0), Color(0xFFA2522A)),
    Lime(Color(0xFFDDEFAE), Color(0xFF5B7414)),
    Teal(Color(0xFFB5E6E3), Color(0xFF1C7470)),
    Indigo(Color(0xFFD5D8F6), Color(0xFF1F2557)),
}

/** An organic, hand-cut circle: the radius wanders by [wobble] (0 to 1) in a shape [seed] picks. Unit size 1 by 1. */
fun blobPath(seed: Int, size: Size, wobble: Float = 1f, phase: Float = 0f): Path {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val r = minOf(cx, cy)
    val a1 = 0.045f * wobble
    val a2 = 0.03f * wobble
    val o1 = seed * 1.7f + phase
    val o2 = seed * 2.9f - phase * 0.7f
    val steps = 72
    return Path().apply {
        for (i in 0..steps) {
            val t = i / steps.toFloat() * 2f * PI.toFloat()
            val rr = r * (0.94f + a1 * sin(3f * t + o1) + a2 * sin(5f * t + o2))
            val x = cx + rr * cos(t)
            val y = cy + rr * sin(t)
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
}

/**
 * A hand-drawn ellipse around a box, as a pen circles a mark: it starts at the top left, goes round once and runs a
 * little past where it began. [progress] 0 to 1 draws it on.
 */
fun DrawScope.penCircle(progress: Float, colour: Color = PlayInk.Pen, width: Float = 4.dp.toPx(), seed: Int = 0, around: Rect = Rect(Offset.Zero, size)) {
    if (progress <= 0f) return
    val cx = around.center.x
    val cy = around.center.y
    val rx = around.width / 2f
    val ry = around.height / 2f
    val path = Path()
    val turns = 1.12f
    val steps = 90
    val start = -2.3f + seed * 0.4f
    for (i in 0..steps) {
        val p = i / steps.toFloat()
        val t = start + p * turns * 2f * PI.toFloat()
        // A real pen drifts: the loop opens a touch as it goes round.
        val drift = 1f + 0.06f * p + 0.02f * sin(t * 3f + seed)
        val x = cx + rx * drift * cos(t)
        val y = cy + ry * drift * sin(t) - 2.dp.toPx() * p
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    val measure = PathMeasure().apply { setPath(path, false) }
    val part = Path()
    measure.getSegment(0f, measure.length * progress.coerceIn(0f, 1f), part, true)
    drawPath(part, colour, style = Stroke(width, cap = StrokeCap.Round))
}

/** How a score reads, by the line it clears. */
enum class Grade(val from: Int) {
    Perfect(98), Excellent(93), VeryGood(85), Good(75), Average(60), Poor(40), VeryPoor(20), Missed(0);

    val label: String get() = when (this) {
        Perfect -> tr("Perfect!")
        Excellent -> tr("Excellent!")
        VeryGood -> tr("Very good")
        Good -> tr("Good")
        Average -> tr("Not bad")
        Poor -> tr("So close")
        VeryPoor -> tr("Keep trying")
        Missed -> tr("Missed")
    }

    companion object {
        fun of(score: Int): Grade = entries.first { score >= it.from }
    }
}

/**
 * A rubber stamp: rotated, double-ruled, that slams down from above when it appears ([key] changes to replay).
 * Sora caps in pen pink, the way a teacher stamps homework.
 */
@Composable
fun Stamp(text: String, modifier: Modifier = Modifier, colour: Color = PlayInk.Pen, tilt: Float = -8f, key: Any? = text, delayMs: Int = 0) {
    val haptics = LocalHapticFeedback.current
    val slam = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        if (delayMs > 0) kotlinx.coroutines.delay(delayMs.toLong())
        slam.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 520f))
    }
    LaunchedEffect(key) {
        kotlinx.coroutines.delay(delayMs.toLong() + 120)
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    val p = slam.value
    Box(
        modifier
            .graphicsLayer {
                val s = 1f + (1f - p.coerceAtMost(1f)) * 0.9f
                scaleX = s; scaleY = s
                rotationZ = tilt + (1f - p.coerceAtMost(1f)) * 12f
                alpha = (p * 2.5f).coerceIn(0f, 1f)
            }
            .drawBehind {
                val w = 2.5.dp.toPx()
                val r = 10.dp.toPx()
                drawRoundRect(colour, cornerRadius = androidx.compose.ui.geometry.CornerRadius(r), style = Stroke(w))
                val inset = 5.dp.toPx()
                drawRoundRect(colour.copy(alpha = 0.7f), topLeft = Offset(inset, inset), size = Size(size.width - 2 * inset, size.height - 2 * inset),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(r - inset / 2), style = Stroke(1.dp.toPx()))
            }
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(text.uppercase(), style = Heartpact.type.label.copy(fontFamily = app.heartpact.ui.theme.Sora, fontSize = 15.sp, letterSpacing = 0.08.sp), color = colour)
    }
}

/**
 * A score, the way the games give it: the number counts up, the pen circles it, the grade is stamped under it.
 * [key] replays it (a new round). [big] for the result of a round; small for a scoreboard.
 */
@Composable
fun ScoreReveal(score: Int, modifier: Modifier = Modifier, key: Any? = score, big: Boolean = true, stamp: String? = Grade.of(score).label, colour: Color = Heartpact.colors.ink) {
    val haptics = LocalHapticFeedback.current
    val count = remember(key) { Animatable(0f) }
    val pen = remember(key) { Animatable(0f) }
    var shown by remember(key) { mutableIntStateOf(0) }
    LaunchedEffect(key) {
        count.animateTo(score.toFloat(), tween(durationMillis = 650 + score * 4, easing = FastOutSlowInEasing)) {
            val v = value.toInt()
            if (v != shown) { shown = v; if (v % 7 == 0) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
        }
        pen.animateTo(1f, tween(480, easing = LinearEasing))
    }
    val numberSize = if (big) 92.sp else 34.sp
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.drawBehind {
            // A circle a little wider than the number, drawn on once the count has landed.
            val grow = if (big) 26.dp.toPx() else 10.dp.toPx()
            penCircle(pen.value, width = if (big) 4.5.dp.toPx() else 2.5.dp.toPx(), seed = score % 5,
                around = Rect(-grow, -grow / 1.6f, size.width + grow, size.height + grow / 1.6f))
        }) {
            Text("$shown", style = Heartpact.type.display.copy(fontSize = numberSize, lineHeight = numberSize), color = colour, textAlign = TextAlign.Center)
        }
        if (stamp != null && big) Stamp(stamp, Modifier.padding(top = 18.dp), key = key, delayMs = 650 + score * 4 + 380)
    }
}

/**
 * Hearts bursting out of a point and falling, for a point won, a pair found, a round done. Draw it over the thing it
 * celebrates; it replays whenever [trigger] changes (and does nothing for null).
 */
@Composable
fun HeartBurst(trigger: Any?, modifier: Modifier = Modifier, count: Int = 18, spread: Dp = 150.dp) {
    if (trigger == null) return
    val t = remember(trigger) { Animatable(0f) }
    LaunchedEffect(trigger) { t.animateTo(1f, tween(1300, easing = LinearEasing)) }
    val colours = listOf(PlayInk.Pen, PlayInk.PenSoft, Art.Right, Art.Left, PlayInk.Blush)
    Canvas(modifier) {
        val p = t.value
        if (p >= 1f) return@Canvas
        val c = center
        repeat(count) { i ->
            val angle = (i / count.toFloat()) * 2f * PI.toFloat() + sin(i * 12.9898f) * 0.4f
            val speed = spread.toPx() * (0.55f + 0.45f * ((sin(i * 78.233f) + 1f) / 2f))
            val out = 1f - (1f - p) * (1f - p)
            val x = c.x + cos(angle) * speed * out
            val y = c.y + sin(angle) * speed * out * 0.85f + 220.dp.toPx() * p * p
            val s = (7.dp.toPx() + (i % 3) * 3.dp.toPx()) * (if (p < 0.15f) p / 0.15f else 1f)
            withTransform({ translate(x, y); rotate(angle * 40f + p * 180f * (if (i % 2 == 0) 1 else -1), Offset.Zero) }) {
                drawPath(unitHeart(s), colours[i % colours.size], alpha = (1f - p).coerceIn(0f, 1f))
            }
        }
    }
}

/** A heart [size] across, centred on the origin. */
fun unitHeart(size: Float): Path = Path().apply {
    val s = size
    moveTo(0f, 0.4f * s)
    cubicTo(-0.65f * s, -0.05f * s, -0.5f * s, -0.62f * s, 0f, -0.26f * s)
    cubicTo(0.5f * s, -0.62f * s, 0.65f * s, -0.05f * s, 0f, 0.4f * s)
    close()
}

/** What a character is doing on a game screen. */
enum class Reaction { Idle, Cheer, Sad, Thinking, Waiting }

/**
 * Maya or Theo on a game screen, alive: idle breathing, a hop and a cheer for a point, a slump for a miss, a sway
 * while thinking. [key] replays a one-off reaction (a point scored twice in a row still hops twice).
 */
@Composable
fun ReactingCharacter(who: Who, reaction: Reaction, size: Dp, modifier: Modifier = Modifier, key: Any? = reaction) {
    // Still (the reaction's pose, no breathing) when animations are off, and in screenshot tests, which wait for the
    // screen to settle.
    val context = androidx.compose.ui.platform.LocalContext.current
    val still = app.heartpact.ui.graphics.LocalStillLogo.current ||
        remember { android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    if (still) {
        Character(who, stillPose(who, reaction), size, modifier, mood = moodOf(reaction))
        return
    }
    val hop = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        if (reaction == Reaction.Cheer || reaction == Reaction.Sad) {
            hop.snapTo(0f)
            hop.animateTo(1f, tween(if (reaction == Reaction.Cheer) 560 else 700, easing = LinearEasing))
        }
    }
    val loop = rememberInfiniteTransition(label = "char")
    val breathe by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "breathe")
    val b = sin(breathe * 2f * PI.toFloat())
    val h = hop.value
    val pose = stillPose(who, reaction)
    val mood = moodOf(reaction)
    Character(who, pose, size, modifier.graphicsLayer {
        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
        when (reaction) {
            Reaction.Cheer -> {
                translationY = -size.toPx() * 0.22f * sin(PI.toFloat() * h)
                val squash = if (h > 0.85f) sin((h - 0.85f) / 0.15f * PI.toFloat()) * 0.12f else 0f
                scaleY = 1f - squash + 0.04f * sin(PI.toFloat() * h); scaleX = 1f + squash
            }
            Reaction.Sad -> { scaleY = 1f - 0.08f * sin(PI.toFloat() * h.coerceAtMost(0.5f) * 2f).coerceAtLeast(0f) * (1f - h) - 0.03f; rotationZ = 0f }
            Reaction.Thinking -> rotationZ = 4f * b
            else -> { scaleY = 1f + 0.02f * b; scaleX = 1f - 0.01f * b }
        }
    }, mood = mood)
}

private fun stillPose(who: Who, reaction: Reaction) = when (reaction) {
    Reaction.Cheer -> Pose.Cheer
    Reaction.Waiting -> Pose.Stand
    Reaction.Sad -> Pose.Rest
    else -> if (reaction == Reaction.Idle && who != Who.Both) Pose.Wave else Pose.Stand
}

private fun moodOf(reaction: Reaction) = when (reaction) {
    Reaction.Cheer -> Mood.Cheer
    Reaction.Sad -> Mood.Worried
    else -> Mood.Smile
}

/** A light tap for a placed piece, a firm one for a win. */
@Composable
fun rememberPlayHaptics(): PlayHaptics {
    val haptics = LocalHapticFeedback.current
    return remember(haptics) { PlayHaptics({ haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }, { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }) }
}

class PlayHaptics(val tick: () -> Unit, val thud: () -> Unit)

/** A faint dotted page, the notebook the skill games are played on. */
fun Modifier.dottedPage(colour: Color, step: Dp = 22.dp): Modifier = drawBehind {
    val s = step.toPx()
    var y = s / 2
    while (y < size.height) {
        var x = s / 2
        while (x < size.width) { drawCircle(colour, 1.1.dp.toPx(), Offset(x, y)); x += s }
        y += s
    }
}

/** A gentle, endless wobble for things waiting to be picked (0 to 1, looping). */
@Composable
fun rememberWobble(periodMs: Int = 3200): Float {
    // Still when animations are off, and in screenshot tests, which wait for the screen to settle.
    val context = androidx.compose.ui.platform.LocalContext.current
    val still = app.heartpact.ui.graphics.LocalStillLogo.current ||
        remember { android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    if (still) return 0f
    val loop = rememberInfiniteTransition(label = "wobble")
    val p by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(periodMs, easing = LinearEasing)), label = "p")
    return p
}

/** A spring for things that pop into place: a piece, a card, a blob. */
fun <T> popSpring() = spring<T>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)

/** Shared mutable float, for gesture-driven games. */
@Composable
fun rememberFloat(initial: Float = 0f) = remember { mutableFloatStateOf(initial) }
