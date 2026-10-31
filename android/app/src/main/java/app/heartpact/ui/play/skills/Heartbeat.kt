package app.heartpact.ui.play.skills

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.heartpact.i18n.tr
import app.heartpact.i18n.trn
import app.heartpact.ui.components.HeartButton
import app.heartpact.ui.graphics.HalfState
import app.heartpact.ui.graphics.HeartSticker
import app.heartpact.ui.play.PlayInk
import app.heartpact.ui.play.SkillKind
import app.heartpact.ui.play.rememberPlayHaptics
import app.heartpact.ui.play.unitHeart
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.Art
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.random.Random

/*
 * Heartbeat: the heart beats a few times at the round's tempo, counting, then goes quiet. Keep counting in your head
 * and tap when the round's beat would land (beat eight at level 1). Up the levels that beat moves later, the tempos
 * spread wider, fewer beats are heard before the quiet, the count stops showing, and then the tempo drifts (a little
 * faster, or a little slower, each beat) and has to be carried on in your head. The reveal lays the beats out on a
 * little timeline with your tap among them.
 */

/**
 * When the beats land: [bpm] at the first beat, [shown] beats felt before the quiet, a tap wanted on beat [target],
 * and a [drift] in the gap from each beat to the next (0.02 makes each gap 2% longer, a slowing heart; negative speeds
 * it up). Pure, so the schedule can be tested.
 */
class BeatPlan(val bpm: Int, val shown: Int, val target: Int, val drift: Float = 0f) {
    /** The gap from beat one to beat two, in ms. */
    val period: Long = 60_000L / bpm

    /** The gap from beat [i] to the next, in ms. */
    fun gap(i: Int): Float = period * Math.pow(1.0 + drift, (i - 1).toDouble()).toFloat()

    /** When beat [i] lands, in ms after beat one. */
    fun offset(i: Int): Long {
        var t = 0f
        for (j in 1 until i) t += gap(j)
        return t.roundToLong()
    }

    companion object {
        /** The level from which the count stops showing on the heart. */
        const val QUIET_COUNT_LEVEL = 7
        /** The level from which the tempo can drift. */
        const val DRIFT_LEVEL = 12

        /**
         * The beats for a round. Level 1 is the game as it always was: 64 to 104 bpm, four or five beats felt, a tap
         * on beat eight. At the top: 50 to 140 bpm, three or four felt, a tap on beat 10 to 12, and mostly a drift.
         */
        fun of(seed: Long, difficulty: Difficulty): BeatPlan {
            val rnd = Random(seed)
            val lo = difficulty.step(64, 50)
            val hi = difficulty.step(104, 140)
            val bpm = lo + rnd.nextInt((hi - lo) / 5 + 1) * 5
            var shown = difficulty.step(4, 3) + rnd.nextInt(2)
            val first = difficulty.step(8, 10)
            val last = difficulty.step(8, 12)
            val target = first + rnd.nextInt(last - first + 1)
            var drift = 0f
            if (difficulty.from(DRIFT_LEVEL) && rnd.nextFloat() < 0.5f + 0.1f * (difficulty.level - DRIFT_LEVEL)) {
                val size = 0.015f + 0.015f * ((difficulty.level - DRIFT_LEVEL) / (Difficulty.RAMP - DRIFT_LEVEL).toFloat()).coerceIn(0f, 1f)
                drift = if (rnd.nextBoolean()) size else -size
                // A drift needs at least three gaps heard to be felt.
                shown = maxOf(shown, 4)
            }
            return BeatPlan(bpm, shown, target, drift)
        }
    }
}

internal class HeartbeatPlay(seed: Long, difficulty: Difficulty = Difficulty.Start) : SkillPlay(SkillKind.Heartbeat, seed, difficulty) {
    val plan = BeatPlan.of(seed, difficulty)
    val bpm get() = plan.bpm
    val period get() = plan.period
    /** Beats felt before it goes quiet. */
    val shown get() = plan.shown
    /** The beat to tap on. */
    val target get() = plan.target
    /** The count shows on the heart as the beats land (until level [BeatPlan.QUIET_COUNT_LEVEL]). */
    val countShows: Boolean = !difficulty.from(BeatPlan.QUIET_COUNT_LEVEL)
    /** How steeply the score falls off, tightening with the level. */
    val k: Float = difficulty.k(256f)

    enum class Phase { Ready, Beating, Quiet, Done }

    var phase by mutableStateOf(Phase.Ready)
        private set
    /** Frame time now, and when beat one lands (ms, the frame clock). */
    var now by mutableLongStateOf(0L)
        private set
    var startAt by mutableLongStateOf(Long.MAX_VALUE)
        private set
    var tapAt by mutableStateOf<Long?>(null)
        private set

    fun beatTime(i: Int): Long = startAt + plan.offset(i)

    /** The last beat felt so far (0 before the first). */
    val lastBeat: Int get() {
        if (now < startAt) return 0
        var i = 1
        while (i < shown && beatTime(i + 1) <= now) i++
        return i
    }

    init {
        hint = listOfNotNull(
            tr("Tap on beat {n}.", "n" to target),
            if (shown < 4) trn(shown, "Just {count} beat before the quiet.", "Just {count} beats before the quiet.") else null,
            if (!countShows) tr("No numbers this time. Count for yourself.") else null,
            if (plan.drift != 0f) tr("The tempo drifts. Keep it drifting in your head.") else null,
            tr("Ready when you are."),
        ).joinToString(" ")
    }

    fun start() { if (phase == Phase.Ready) { phase = Phase.Beating; hint = tr("Count along...") } }

    /** Moves the clock to [t]: beats land, the heart goes quiet, and the round ends if the tap never came. */
    fun advance(t: Long, onBeat: () -> Unit = {}) {
        if (phase == Phase.Ready || phase == Phase.Done) return
        if (startAt == Long.MAX_VALUE) startAt = t + period
        val before = lastBeat
        now = t
        if (lastBeat > before) onBeat()
        if (phase == Phase.Beating && now >= beatTime(shown) + (plan.gap(shown) / 2).toLong()) {
            phase = Phase.Quiet
            hint = tr("Keep counting in your head...")
        }
        if (phase == Phase.Quiet && now > beatTime(target) + maxOf(1200L, (plan.gap(target) * 2).toLong())) {
            phase = Phase.Done
            hint = ""
            finish(heartbeatMissed(), 400)
        }
    }

    /** A tap at [t] (same clock as frames). Ignored until it goes quiet. */
    fun tap(t: Long): Boolean {
        if (phase != Phase.Quiet) {
            if (phase == Phase.Beating) hint = tr("Not yet. Wait for the quiet.")
            return false
        }
        tapAt = t
        now = maxOf(now, t)
        phase = Phase.Done
        hint = ""
        finish(heartbeatScore((t - beatTime(target)).toFloat(), k), 1000)
        return true
    }

    /** How hard the heart is pulsing at [now]: 0 at rest, 1 at the peak of a beat (or of the tap). */
    private fun pulse(): Float {
        val beats = (1..lastBeat).map { beatTime(it) } + listOfNotNull(tapAt)
        val last = beats.filter { it <= now }.maxOrNull() ?: return 0f
        val since = (now - last).toFloat()
        return if (since < 60f) since / 60f else exp(-(since - 60f) / 150f)
    }

    override val hasControls: Boolean get() = true

    @Composable
    override fun Controls(modifier: Modifier) {
        val haptics = rememberPlayHaptics()
        if (phase == Phase.Ready) HeartButton(tr("Start"), { haptics.tick(); start() }, modifier)
        else PressPill(if (phase == Phase.Quiet) tr("Tap on beat {n}", "n" to target) else tr("Listen..."), phase == Phase.Quiet, modifier, onDown = { t -> if (tap(t)) haptics.thud() })
    }

    @Composable
    override fun Board(modifier: Modifier) {
        val colors = Heartpact.colors
        val haptic = LocalHapticFeedback.current
        val haptics = rememberPlayHaptics()
        val measurer = rememberTextMeasurer()
        val labelStyle = Heartpact.type.labelSmall
        val yourTap = tr("Your tap")
        val described = tr("A beating heart. It beats a few times, then goes quiet. Tap when beat {n} would land.", "n" to target)
        val beatLabel = tr("Beat {n}", "n" to target)
        val noteColour = noteInk()
        val running = phase == Phase.Beating || phase == Phase.Quiet
        LaunchedEffect(running) {
            while (phase == Phase.Beating || phase == Phase.Quiet) {
                withFrameMillis { t -> advance(t) { haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) } }
            }
        }
        // At rest the heart breathes a little, so it looks alive before it starts.
        val breath by rememberInfiniteTransition(label = "breath").animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart), label = "b")
        val still = rememberMotionOff()
        val p = pulse()
        val rest = if (phase == Phase.Ready && !still) 0.02f * sin(breath * 2f * PI.toFloat()) else 0f
        Column(
            modifier
                .semantics { contentDescription = described }
                .pointerInput(Unit) { awaitEachGesture { val d = awaitFirstDown(); if (tap(d.uptimeMillis)) haptics.thud() } },
        ) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                // Faint rings going out on each beat.
                Canvas(Modifier.fillMaxSize()) {
                    val r0 = 70.dp.toPx()
                    (1..lastBeat).forEach { i ->
                        val since = (now - beatTime(i)).toFloat()
                        if (since in 0f..800f) {
                            val out = since / 800f
                            drawCircle(PlayInk.Pen.copy(alpha = 0.4f * (1f - out)), r0 * (1f + 0.9f * out), center, style = Stroke(2.5.dp.toPx() * (1f - out * 0.6f)))
                        }
                    }
                }
                HeartSticker(HalfState.Stuck, HalfState.Stuck, 150.dp, Modifier.graphicsLayer {
                    scaleX = 1f + 0.16f * p + rest
                    scaleY = 1f + 0.12f * p + rest
                })
                val count = when {
                    !countShows -> null
                    phase == Phase.Beating && lastBeat > 0 -> "$lastBeat"
                    tapAt != null -> "$target"
                    else -> null
                }
                if (count != null) {
                    Text(
                        count,
                        style = Heartpact.type.display.copy(fontSize = 44.sp, lineHeight = 44.sp),
                        color = colors.ink,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.offset(y = (-128).dp).graphicsLayer { scaleX = 1f + 0.25f * p; scaleY = 1f + 0.25f * p },
                    )
                }
            }
            // A mark for each beat up to the one to tap on, the felt ones filling in (while the count shows). At the end, a
            // close-up of that beat and your tap.
            Canvas(Modifier.fillMaxWidth().height(86.dp).padding(horizontal = 36.dp)) {
                val y = 40.dp.toPx()
                val tap = tapAt
                if (scored == null || tap == null) {
                    val step = size.width / (target - 0.5f)
                    for (i in 1..target) {
                        val c = Offset(step * 0.25f + (i - 1) * step, y)
                        when {
                            i == target -> translate(c.x, c.y) { drawPath(unitHeart(26.dp.toPx()), PlayInk.Pen, style = Stroke(2.5.dp.toPx())) }
                            i <= lastBeat && i <= shown && countShows -> drawCircle(if (colors.isNight) colors.ink else colors.left.art, 6.dp.toPx(), c)
                            else -> drawCircle(colors.ink4, 6.dp.toPx(), c, style = Stroke(2.dp.toPx(),
                                pathEffect = if (i > shown) PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())) else null))
                        }
                    }
                    return@Canvas
                }
                val err = (tap - beatTime(target)).toFloat()
                // Wide enough to show the miss, with the beats either side at the edges when they fit.
                val before = plan.gap(target - 1)
                val after = plan.gap(target)
                val span = maxOf(maxOf(before, after) * 1.1f, abs(err) * 1.4f + 80f)
                fun xAt(ms: Float) = size.width / 2 + ms / span * (size.width / 2)
                drawLine(colors.rule2, Offset(0f, y), Offset(size.width, y), 2.dp.toPx(), cap = StrokeCap.Round)
                var tick = -(span / 100f).toInt() * 100f
                while (tick <= span) {
                    drawLine(colors.ink4, Offset(xAt(tick), y - 4.dp.toPx()), Offset(xAt(tick), y + 4.dp.toPx()), 1.5.dp.toPx())
                    tick += 100f
                }
                for (side in listOf(-before, after)) if (abs(side) < span) drawCircle(colors.ink4, 5.dp.toPx(), Offset(xAt(side), y), style = Stroke(2.dp.toPx()))
                translate(xAt(0f), y) {
                    drawPath(unitHeart(26.dp.toPx()), PlayInk.Pen)
                    drawPath(unitHeart(26.dp.toPx()), Art.Keyline, style = Stroke(1.5.dp.toPx()))
                }
                val beatText = measurer.measure(beatLabel, labelStyle)
                drawText(beatText, noteColour, Offset((xAt(0f) - beatText.size.width / 2f), y - 18.dp.toPx() - beatText.size.height))
                val tx = xAt(err).coerceIn(0f, size.width)
                drawLine(colors.ink, Offset(tx, y - 12.dp.toPx()), Offset(tx, y + 12.dp.toPx()), 2.5.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(colors.ink, 4.dp.toPx(), Offset(tx, y + 12.dp.toPx()))
                val label = measurer.measure(yourTap, labelStyle)
                val lx = (tx - label.size.width / 2f).coerceIn(0f, size.width - label.size.width)
                drawText(label, colors.ink2, Offset(lx, y + 19.dp.toPx()))
            }
        }
    }
}
