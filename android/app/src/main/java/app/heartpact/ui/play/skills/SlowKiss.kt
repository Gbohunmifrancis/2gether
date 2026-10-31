package app.heartpact.ui.play.skills

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.heartpact.i18n.tr
import app.heartpact.ui.play.BlobColour
import app.heartpact.ui.play.HeartBurst
import app.heartpact.ui.play.PlayInk
import app.heartpact.ui.play.SkillKind
import app.heartpact.ui.play.rememberPlayHaptics
import app.heartpact.ui.play.rememberWobble
import app.heartpact.ui.play.unitHeart
import app.heartpact.ui.theme.Art
import app.heartpact.ui.theme.Heartpact
import kotlin.math.PI
import kotlin.math.sin

/*
 * Slow kiss: the time to hold shows big at the top. Press and hold the lips: they pucker, little hearts float up, and
 * a timer counts for a moment, then fades, so the rest is counted in your head. Let go on the time. The reveal lays
 * the kiss out on a line with the target on it.
 */

internal class SlowKissPlay(seed: Long, difficulty: Difficulty = Difficulty.Start) : SkillPlay(SkillKind.SlowKiss, seed, difficulty) {
    /** The time to hold, in seconds, and how long the timer shows once the kiss begins (0: never). */
    val target = slowKissTarget(seed, difficulty)
    val timerFor = slowKissTimerSeconds(difficulty)
    private val k = difficulty.k(0.15f)
    /** Past this the kiss ends on its own: long enough that nobody gets there trying. */
    private val longest = target * 2f + 1f

    enum class Phase { Ready, Holding, Done }

    var phase by mutableStateOf(Phase.Ready)
        private set
    /** When the kiss began and the time now (ms, the frame clock). */
    var downAt by mutableLongStateOf(0L)
        private set
    var now by mutableLongStateOf(0L)
        private set
    /** How long it was held, once let go (seconds). */
    var held by mutableStateOf<Float?>(null)
        private set
    /** Bumps when a press was let go too soon to count, so the lips can say so. */
    var pecks by mutableIntStateOf(0)
        private set

    init { hint = tr("Hold for the time shown.") }

    /** Seconds since the kiss began, still counting once it ended (for the hearts still floating up). */
    val since: Float get() = if (phase == Phase.Ready) 0f else ((now - downAt) / 1000f).coerceAtLeast(0f)

    /** Seconds held so far, or held in all. */
    val holding: Float get() = held ?: since

    fun press(t: Long) {
        if (phase != Phase.Ready) return
        phase = Phase.Holding
        downAt = t
        now = t
        hint = tr("Count it in your head...")
    }

    /** Moves the clock to [t]; a kiss held far too long ends on its own. */
    fun advance(t: Long) {
        if (phase == Phase.Ready) return
        now = maxOf(now, t)
        if (phase == Phase.Holding && since >= longest) release(downAt + (longest * 1000).toLong())
    }

    /** Let go at [t]. A peck (under 0.3 s) doesn't count: the round waits for a real kiss. */
    fun release(t: Long) {
        if (phase != Phase.Holding) return
        val s = (t - downAt) / 1000f
        if (s < 0.3f) {
            phase = Phase.Ready
            pecks++
            hint = tr("That was a peck. Press and hold.")
            return
        }
        held = s
        now = maxOf(now, t)
        phase = Phase.Done
        hint = ""
        finish(slowKissScore(s, target, k), 1200)
    }

    @Composable
    override fun Board(modifier: Modifier) {
        val colors = Heartpact.colors
        val haptics = rememberPlayHaptics()
        val still = rememberMotionOff()
        val measurer = rememberTextMeasurer()
        val labelStyle = Heartpact.type.labelSmall
        val timerStyle = Heartpact.type.display.copy(fontSize = 34.sp, lineHeight = 34.sp)
        val targetLabel = tr("Target")
        val letGoLabel = tr("You let go")
        val noteColour = noteInk()
        val pucker = remember { Animatable(0f) }
        val breath = rememberWobble(2600)
        val p = phase
        LaunchedEffect(p) {
            if (p == Phase.Ready) return@LaunchedEffect
            while (phase == Phase.Holding || now - downAt - ((held ?: 0f) * 1000).toLong() < 2400) {
                withFrameMillis { advance(it) }
            }
        }
        LaunchedEffect(p) {
            val to = if (p == Phase.Holding) 1f else 0f
            if (still) pucker.snapTo(to)
            else pucker.animateTo(to, if (p == Phase.Holding) spring(dampingRatio = 0.5f, stiffness = 300f) else spring(dampingRatio = 0.28f, stiffness = 420f))
        }
        LaunchedEffect(pecks) { if (pecks > 0) haptics.tick() }
        Column(
            modifier
                .semantics {
                    role = Role.Button
                    contentDescription = tr("Lips to kiss. Press and hold for {s} seconds, then let go.", "s" to twoDecimals(target))
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        if (phase != Phase.Ready) return@awaitEachGesture
                        press(down.uptimeMillis)
                        haptics.tick()
                        val up = waitForUpOrCancellation()
                        val before = phase
                        release(up?.uptimeMillis ?: now)
                        if (before == Phase.Holding && phase == Phase.Done) haptics.thud()
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The time to hold, big; once it's over, the time it was held.
            val done = held
            Text(
                if (done == null) tr("Hold for") else tr("You held"),
                style = Heartpact.type.label, color = colors.ink2, modifier = Modifier.padding(top = 10.dp),
            )
            Text(
                tr("{s} s", "s" to twoDecimals(done ?: target)),
                style = Heartpact.type.display.copy(fontSize = 52.sp, lineHeight = 52.sp),
                color = colors.ink, textAlign = TextAlign.Center,
            )
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val c = center
                    val w = minOf(size.width * 0.56f, 210.dp.toPx())
                    val t = since
                    // Little hearts floating up while the kiss lasts, and drifting on a moment after.
                    if (!still && phase != Phase.Ready) {
                        val colours = listOf(PlayInk.Pen, PlayInk.PenSoft, Art.Right, PlayInk.Blush)
                        val made = (holding / 0.2f).toInt()
                        for (i in 0..made) {
                            val age = t - i * 0.2f
                            if (age !in 0f..1.9f) continue
                            val sway = sin(age * 3.2f + i * 1.7f) * 18.dp.toPx() * (0.3f + age)
                            val start = ((i * 53) % 7 - 3) / 3f * w * 0.18f
                            val at = Offset(c.x + start + sway, c.y - w * 0.12f - age * 150.dp.toPx())
                            val grow = (age / 0.18f).coerceAtMost(1f)
                            val s = (11.dp.toPx() + (i % 3) * 4.dp.toPx()) * grow
                            withTransform({ translate(at.x, at.y); rotate(sin(age * 4f + i) * 18f, Offset.Zero) }) {
                                drawPath(unitHeart(s), colours[i % colours.size], alpha = (1f - age / 1.9f).coerceIn(0f, 1f))
                            }
                        }
                    }
                    // The lips: puckered while held, with a little kiss-pulse; resting, they breathe.
                    val pk = pucker.value
                    val pulse = if (still) 0f else if (phase == Phase.Holding) 0.025f * sin(t * 2f * PI.toFloat() * 1.4f) else 0.018f * sin(breath * 2f * PI.toFloat())
                    withTransform({
                        translate(c.x, c.y)
                        scale(1f - 0.24f * pk + pulse, 1f + 0.18f * pk + pulse, Offset.Zero)
                    }) {
                        translate(0f, 6.dp.toPx()) { drawPath(lipsPath(w), Art.Shadow, alpha = 0.12f) }
                        drawPath(lipsPath(w), Art.Keyline, style = Stroke(10.dp.toPx(), join = StrokeJoin.Round))
                        drawPath(lipsPath(w), PlayInk.Pen)
                        drawPath(lipsSeam(w), BlobColour.Rose.ink, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                        drawCircle(Color.White.copy(alpha = 0.6f), w * 0.045f, Offset(-w * 0.2f, -w * 0.19f))
                        drawCircle(Color.White.copy(alpha = 0.45f), w * 0.03f, Offset(w * 0.12f, w * 0.12f))
                    }
                    // The timer, for a moment: then it fades and the counting is yours. It comes back once it's over.
                    val shown = when {
                        phase == Phase.Done -> 0f
                        phase == Phase.Holding -> if (t < timerFor) 1f else (1f - (t - timerFor) / 0.35f).coerceIn(0f, 1f)
                        else -> 0f
                    }
                    if (shown > 0f) {
                        val text = measurer.measure(twoDecimals(t), timerStyle)
                        drawText(text, colors.ink2, Offset(c.x - text.size.width / 2f, c.y + w * 0.3f), alpha = shown)
                    }
                }
                if (!still) HeartBurst(held, Modifier.fillMaxSize(), count = 14, spread = 120.dp)
            }
            // The kiss on a line, once it's over: how long it lasted, and where the target was.
            Canvas(Modifier.fillMaxWidth().height(86.dp).padding(horizontal = 32.dp).graphicsLayer { alpha = if (done == null) 0f else 1f }) {
                val h = done ?: return@Canvas
                val y = 40.dp.toPx()
                val span = maxOf(h, target) * 1.12f + 0.2f
                fun xAt(s: Float) = s / span * size.width
                drawLine(colors.rule2, Offset(0f, y), Offset(size.width, y), 2.dp.toPx(), cap = StrokeCap.Round)
                var second = 1
                while (second < span) {
                    drawLine(colors.ink4, Offset(xAt(second.toFloat()), y - 4.dp.toPx()), Offset(xAt(second.toFloat()), y + 4.dp.toPx()), 1.5.dp.toPx())
                    second++
                }
                // The kiss itself, a soft pink bar from the start to the moment it ended.
                drawLine(PlayInk.PenSoft, Offset(0f, y), Offset(xAt(h), y), 7.dp.toPx(), cap = StrokeCap.Round)
                translate(xAt(target), y) {
                    drawPath(unitHeart(26.dp.toPx()), PlayInk.Pen)
                    drawPath(unitHeart(26.dp.toPx()), Art.Keyline, style = Stroke(1.5.dp.toPx()))
                }
                val tl = measurer.measure(targetLabel, labelStyle)
                drawText(tl, noteColour, Offset((xAt(target) - tl.size.width / 2f).coerceIn(0f, size.width - tl.size.width), y - 18.dp.toPx() - tl.size.height))
                val hx = xAt(h)
                drawLine(colors.ink, Offset(hx, y - 12.dp.toPx()), Offset(hx, y + 12.dp.toPx()), 2.5.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(colors.ink, 4.dp.toPx(), Offset(hx, y + 12.dp.toPx()))
                val ll = measurer.measure(letGoLabel, labelStyle)
                drawText(ll, colors.ink2, Offset((hx - ll.size.width / 2f).coerceIn(0f, size.width - ll.size.width), y + 19.dp.toPx()))
            }
        }
    }
}
