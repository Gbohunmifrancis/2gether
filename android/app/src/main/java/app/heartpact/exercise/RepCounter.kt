package app.heartpact.exercise

import app.heartpact.exercise.Pose.Companion.L_ANKLE
import app.heartpact.exercise.Pose.Companion.L_ELBOW
import app.heartpact.exercise.Pose.Companion.L_HIP
import app.heartpact.exercise.Pose.Companion.L_KNEE
import app.heartpact.exercise.Pose.Companion.L_SHOULDER
import app.heartpact.exercise.Pose.Companion.L_WRIST
import app.heartpact.exercise.Pose.Companion.R_ANKLE
import app.heartpact.exercise.Pose.Companion.R_ELBOW
import app.heartpact.exercise.Pose.Companion.R_HIP
import app.heartpact.exercise.Pose.Companion.R_KNEE
import app.heartpact.exercise.Pose.Companion.R_SHOULDER
import app.heartpact.exercise.Pose.Companion.R_WRIST

/** What the camera screen shows: the count so far, whether the body is in frame, and how sure we are. */
data class CounterState(
    val count: Int = 0,
    val inFrame: Boolean = false,
    /** Mean visibility of the joints that matter, over the frames that counted. Sent with the completion. */
    val confidence: Double = 0.0,
    val active: Boolean = false,
)

/**
 * Counts reps (or seconds held) from a stream of poses. Each exercise reduces a pose to one signal
 * with a resting zone and an active zone and a dead band between them, so jitter around a single
 * threshold can never count twice. A rep is counted when the body returns to rest after reaching
 * the active zone. A phase only changes after [confirmFrames] readings in the new zone, so a
 * one-frame spike is ignored; drifting through the dead band keeps a pending change, going back
 * to the old zone cancels it. Counting frames rather than milliseconds keeps this independent of
 * the camera's frame rate. Frames where the key joints are barely visible are ignored rather than guessed.
 */
class RepCounter(
    val type: ExerciseType,
    private val confirmFrames: Int = 2,
    private val minVisibility: Float = 0.5f,
) {
    private var count = 0
    private var active = false
    private var pending = 0
    private var visibilitySum = 0.0
    private var visibleFrames = 0
    private var inFrame = false
    // Plank: time accumulated in a straight line, tolerating short drop-outs.
    private var holdMs = 0L
    private var lastHoldFrameMs: Long? = null

    fun reset() {
        count = 0; active = false; pending = 0
        visibilitySum = 0.0; visibleFrames = 0; inFrame = false; holdMs = 0; lastHoldFrameMs = null
    }

    val state: CounterState get() = CounterState(
        count = if (type.measure == Measure.Seconds) (holdMs / 1000).toInt() else count,
        inFrame = inFrame,
        confidence = if (visibleFrames == 0) 0.0 else visibilitySum / visibleFrames,
        active = active,
    )

    /** Feed one frame; null means no person detected. */
    fun update(pose: Pose?): CounterState {
        if (pose == null || pose.landmarks.size < Pose.COUNT) {
            inFrame = false
            lastHoldFrameMs = null
            return state
        }
        val reading = read(pose)
        inFrame = reading != null
        if (reading == null) {
            lastHoldFrameMs = null
            return state
        }
        visibilitySum += reading.visibility
        visibleFrames++
        if (type == ExerciseType.Plank) {
            val last = lastHoldFrameMs
            if (reading.signal >= 1f) {
                if (last != null) holdMs += (pose.timestampMs - last).coerceIn(0, MAX_HOLD_GAP_MS)
                lastHoldFrameMs = pose.timestampMs
                active = true
            } else {
                lastHoldFrameMs = null
                active = false
            }
            return state
        }
        val inActive = if (reading.activeIsLow) reading.signal <= reading.activeEdge else reading.signal >= reading.activeEdge
        val inRest = if (reading.activeIsLow) reading.signal >= reading.restEdge else reading.signal <= reading.restEdge
        val toward = if (active) inRest else inActive
        val back = if (active) inActive else inRest
        when {
            toward -> if (++pending >= confirmFrames) {
                pending = 0
                if (active) count++
                active = !active
            }
            back -> pending = 0
        }
        return state
    }

    /** One frame reduced to a signal and its zones, or null when the joints that matter are not visible. */
    private class Reading(val signal: Float, val restEdge: Float, val activeEdge: Float, val activeIsLow: Boolean, val visibility: Float)

    private fun read(p: Pose): Reading? {
        fun seen(vararg idx: Int): Float? = p.visibility(*idx).takeIf { it >= minVisibility }
        return when (type) {
            ExerciseType.Squat -> {
                val s = p.betterSide(intArrayOf(L_HIP, L_KNEE, L_ANKLE), intArrayOf(R_HIP, R_KNEE, R_ANKLE))
                seen(*s)?.let { Reading(p.angle(s[0], s[1], s[2]), restEdge = 160f, activeEdge = 105f, activeIsLow = true, visibility = it) }
            }
            ExerciseType.PushUp -> {
                val s = p.betterSide(intArrayOf(L_SHOULDER, L_ELBOW, L_WRIST), intArrayOf(R_SHOULDER, R_ELBOW, R_WRIST))
                seen(*s)?.let { Reading(p.angle(s[0], s[1], s[2]), restEdge = 150f, activeEdge = 100f, activeIsLow = true, visibility = it) }
            }
            ExerciseType.SitUp -> {
                val s = p.betterSide(intArrayOf(L_SHOULDER, L_HIP, L_KNEE), intArrayOf(R_SHOULDER, R_HIP, R_KNEE))
                seen(*s)?.let { Reading(p.angle(s[0], s[1], s[2]), restEdge = 125f, activeEdge = 80f, activeIsLow = true, visibility = it) }
            }
            ExerciseType.ArmRaise -> {
                val idx = intArrayOf(L_SHOULDER, R_SHOULDER, L_WRIST, R_WRIST, L_HIP, R_HIP)
                seen(*idx)?.let {
                    // Both wrists' height above the shoulders, in torso lengths (the lower wrist decides).
                    val torso = torso(p)
                    val lift = minOf(p[L_SHOULDER].y - p[L_WRIST].y, p[R_SHOULDER].y - p[R_WRIST].y) / torso
                    Reading(lift, restEdge = 0.05f, activeEdge = 0.55f, activeIsLow = false, visibility = it)
                }
            }
            ExerciseType.JumpingJack -> {
                val idx = intArrayOf(L_SHOULDER, R_SHOULDER, L_WRIST, R_WRIST, L_HIP, R_HIP, L_ANKLE, R_ANKLE)
                seen(*idx)?.let {
                    val torso = torso(p)
                    val lift = minOf(p[L_SHOULDER].y - p[L_WRIST].y, p[R_SHOULDER].y - p[R_WRIST].y) / torso
                    val feet = p.horizontalSpread(L_ANKLE, R_ANKLE) / p.horizontalSpread(L_HIP, R_HIP).coerceAtLeast(1e-3f)
                    // Arms up and feet apart together make the open position.
                    Reading(minOf(lift / 0.35f, (feet - 1f) / 0.6f), restEdge = 0.2f, activeEdge = 1f, activeIsLow = false, visibility = it)
                }
            }
            ExerciseType.HighKnees -> {
                val idx = intArrayOf(L_HIP, R_HIP, L_KNEE, R_KNEE, L_SHOULDER, R_SHOULDER)
                seen(*idx)?.let {
                    val torso = torso(p)
                    // Height of the higher knee relative to its hip, in torso lengths: about -0.5 standing, near 0 at hip height.
                    val knee = maxOf(p[L_HIP].y - p[L_KNEE].y, p[R_HIP].y - p[R_KNEE].y) / torso
                    Reading(knee, restEdge = -0.3f, activeEdge = -0.1f, activeIsLow = false, visibility = it)
                }
            }
            ExerciseType.Plank -> {
                val s = p.betterSide(intArrayOf(L_SHOULDER, L_HIP, L_ANKLE), intArrayOf(R_SHOULDER, R_HIP, R_ANKLE))
                seen(*s)?.let {
                    val straight = p.angle(s[0], s[1], s[2]) >= 155f
                    // Horizontal-ish: the body's rise is small compared with its length.
                    val flat = kotlin.math.abs(p[s[0]].y - p[s[2]].y) <= 0.45f * p.distance(s[0], s[2])
                    Reading(if (straight && flat) 1f else 0f, restEdge = 0f, activeEdge = 1f, activeIsLow = false, visibility = it)
                }
            }
        }
    }

    private fun torso(p: Pose): Float {
        val shoulderY = (p[L_SHOULDER].y + p[R_SHOULDER].y) / 2
        val hipY = (p[L_HIP].y + p[R_HIP].y) / 2
        return (hipY - shoulderY).coerceAtLeast(0.05f)
    }

    companion object {
        /** A brief drop-out (a blurred frame) keeps the plank going; a longer one stops the clock. */
        const val MAX_HOLD_GAP_MS = 400L
    }
}
