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
    /** Movements that didn't count because of form: too shallow, not all the way back, out of line; plank holds broken. */
    val formFaults: Int = 0,
    /** Reps that didn't count because they were quicker than the exercise can be done. */
    val fastReps: Int = 0,
    /** The tip for the latest fault, while it is fresh. */
    val cue: String? = null,
    /** When the latest counted rep began and ended, in frame time: the phone-motion check looks there. */
    val lastRepStartMs: Long? = null,
    val lastRepEndMs: Long? = null,
)

/**
 * Counts reps (or seconds held) from a stream of poses. Each exercise reduces a pose to one signal
 * with a resting zone and an active zone and a dead band between them, so jitter around a single
 * threshold can never count twice. A rep is counted when the body returns to rest after reaching
 * the active zone. A phase only changes after [confirmFrames] readings in the new zone, so a
 * one-frame spike is ignored; drifting through the dead band keeps a pending change, going back
 * to the old zone cancels it. Counting frames rather than milliseconds keeps this independent of
 * the camera's frame rate. Frames where the key joints are barely visible are ignored rather than guessed.
 *
 * It also judges form, and a movement with a fault doesn't count: going most of the way and coming
 * back ([FaultKind.Shallow]), starting the next rep without getting back ([FaultKind.NotAllTheWay]),
 * reaching the position out of line ([FaultKind.Posture]), or a rep quicker than [fastFactor] of the
 * exercise's minimum pace ([FaultKind.TooFast]). Each fault leaves a tip in [CounterState.cue].
 */
class RepCounter(
    val type: ExerciseType,
    private val confirmFrames: Int = 2,
    private val minVisibility: Float = 0.5f,
    private val fastFactor: Double = 0.6,
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
    private var heldSinceMs: Long? = null
    // Form: how far each movement got and what went wrong on the way.
    private var nearFrames = 0
    private var reachedNear = false
    private var retreated = false
    private var postureFrames = 0
    private var restSeenMs: Long? = null
    private var repStartMs: Long? = null
    private var lastRepStartMs: Long? = null
    private var lastRepEndMs: Long? = null
    private var formFaults = 0
    private var fastReps = 0
    private var fault: RepFault? = null
    private var lastFrameMs = 0L

    private val minRepMs = (type.minSecondsPerUnit * 1_000 * fastFactor).toLong()

    fun reset() {
        count = 0; active = false; pending = 0
        visibilitySum = 0.0; visibleFrames = 0; inFrame = false; holdMs = 0; lastHoldFrameMs = null; heldSinceMs = null
        nearFrames = 0; reachedNear = false; retreated = false; postureFrames = 0; restSeenMs = null; repStartMs = null
        lastRepStartMs = null; lastRepEndMs = null; formFaults = 0; fastReps = 0; fault = null; lastFrameMs = 0
    }

    val state: CounterState get() = CounterState(
        count = if (type.measure == Measure.Seconds) (holdMs / 1000).toInt() else count,
        inFrame = inFrame,
        confidence = if (visibleFrames == 0) 0.0 else visibilitySum / visibleFrames,
        active = active,
        formFaults = formFaults,
        fastReps = fastReps,
        cue = fault?.takeIf { lastFrameMs - it.atMs in 0..FormCues.SHOW_MS }?.let { FormCues.message(type, it.kind) },
        lastRepStartMs = lastRepStartMs,
        lastRepEndMs = lastRepEndMs,
    )

    /** Feed one frame; null means no person detected. */
    fun update(pose: Pose?): CounterState {
        if (pose == null || pose.landmarks.size < Pose.COUNT) {
            inFrame = false
            lastHoldFrameMs = null
            heldSinceMs = null
            return state
        }
        val now = pose.timestampMs
        lastFrameMs = now
        val reading = read(pose)
        inFrame = reading != null
        if (reading == null) {
            lastHoldFrameMs = null
            heldSinceMs = null
            return state
        }
        visibilitySum += reading.visibility
        visibleFrames++
        if (type == ExerciseType.Plank) {
            val last = lastHoldFrameMs
            if (reading.signal >= 1f) {
                if (last != null) holdMs += (now - last).coerceIn(0, MAX_HOLD_GAP_MS)
                lastHoldFrameMs = now
                if (heldSinceMs == null) heldSinceMs = now
                active = true
            } else {
                // A hold of a second or more that breaks while still in frame is a fault; a moment's wobble isn't.
                val since = heldSinceMs
                if (since != null && now - since >= PLANK_BREAK_MS) formFaults++
                reading.fault?.let { fault = RepFault(it, now) }
                lastHoldFrameMs = null
                heldSinceMs = null
                active = false
            }
            return state
        }
        val inActive = if (reading.activeIsLow) reading.signal <= reading.activeEdge else reading.signal >= reading.activeEdge
        val inRest = if (reading.activeIsLow) reading.signal >= reading.restEdge else reading.signal <= reading.restEdge
        // Halfway between the zones, measured from wherever the body is heading back from.
        val near = reading.restEdge + NEAR * (reading.activeEdge - reading.restEdge)
        val retreat = reading.activeEdge + NEAR * (reading.restEdge - reading.activeEdge)
        val pastNear = if (reading.activeIsLow) reading.signal <= near else reading.signal >= near
        val pastRetreat = if (reading.activeIsLow) reading.signal >= retreat else reading.signal <= retreat

        if (!active) {
            if (inRest) restSeenMs = now
            nearFrames = if (pastNear && !inActive) nearFrames + 1 else 0
            if (nearFrames >= confirmFrames) reachedNear = true
        } else {
            if (!reading.postureOk) postureFrames++
            if (pastRetreat && !inRest) retreated = true
            // Back down into the next rep without reaching the start: that rep can't count.
            if (retreated && inActive) { retreated = false; formFaults++; fault = RepFault(FaultKind.NotAllTheWay, now) }
        }

        val toward = if (active) inRest else inActive
        val back = if (active) inActive else inRest
        when {
            toward -> if (++pending >= confirmFrames) {
                pending = 0
                if (active) finishRep(now) else { repStartMs = restSeenMs ?: now; postureFrames = 0; retreated = false; reachedNear = false }
                active = !active
            }
            back -> {
                pending = 0
                // Came back to rest from most of the way there.
                if (!active && reachedNear) { reachedNear = false; formFaults++; fault = RepFault(FaultKind.Shallow, now) }
            }
        }
        return state
    }

    private fun finishRep(now: Long) {
        val start = repStartMs ?: now
        when {
            postureFrames >= confirmFrames -> { formFaults++; fault = RepFault(FaultKind.Posture, now) }
            now - start < minRepMs -> { fastReps++; fault = RepFault(FaultKind.TooFast, now) }
            else -> { count++; lastRepStartMs = start; lastRepEndMs = now }
        }
        postureFrames = 0
        retreated = false
        restSeenMs = now
    }

    /** One frame reduced to a signal and its zones, or null when the joints that matter are not visible. */
    private class Reading(
        val signal: Float,
        val restEdge: Float,
        val activeEdge: Float,
        val activeIsLow: Boolean,
        val visibility: Float,
        /** False on frames where the body is out of line for this exercise. */
        val postureOk: Boolean = true,
        /** Plank: why the line is broken, when it is. */
        val fault: FaultKind? = null,
    )

    private fun read(p: Pose): Reading? {
        fun seen(vararg idx: Int): Float? = p.visibility(*idx).takeIf { it >= minVisibility }
        return when (type) {
            ExerciseType.Squat -> {
                val s = p.betterSide(intArrayOf(L_HIP, L_KNEE, L_ANKLE), intArrayOf(R_HIP, R_KNEE, R_ANKLE))
                seen(*s)?.let {
                    // Side-on, the trunk leaning more than [SQUAT_LEAN] degrees from upright means the chest has dropped.
                    val shoulder = if (s[0] == L_HIP) L_SHOULDER else R_SHOULDER
                    val upright = p.visibility(shoulder) < minVisibility || p.lean(s[0], shoulder) <= SQUAT_LEAN
                    Reading(p.angle(s[0], s[1], s[2]), restEdge = 160f, activeEdge = 105f, activeIsLow = true, visibility = it, postureOk = upright)
                }
            }
            ExerciseType.PushUp -> {
                val s = p.betterSide(intArrayOf(L_SHOULDER, L_ELBOW, L_WRIST), intArrayOf(R_SHOULDER, R_ELBOW, R_WRIST))
                seen(*s)?.let {
                    // Shoulders, hips and ankles in a line; checked only when the camera sees them.
                    val (hip, ankle) = if (s[0] == L_SHOULDER) L_HIP to L_ANKLE else R_HIP to R_ANKLE
                    val straight = p.visibility(hip, ankle) < minVisibility || p.angle(s[0], hip, ankle) >= PUSH_UP_LINE
                    Reading(p.angle(s[0], s[1], s[2]), restEdge = 150f, activeEdge = 100f, activeIsLow = true, visibility = it, postureOk = straight)
                }
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
                    val fault = when {
                        straight && flat -> null
                        !flat -> FaultKind.Posture
                        // y grows downward: a hip below the shoulder-to-ankle line is sagging.
                        p[s[1]].y > p.lineY(s[0], s[2], p[s[1]].x) -> FaultKind.HipsLow
                        else -> FaultKind.HipsHigh
                    }
                    Reading(if (fault == null) 1f else 0f, restEdge = 0f, activeEdge = 1f, activeIsLow = false, visibility = it, fault = fault)
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
        /** A plank held at least this long and then broken counts as a form fault. */
        const val PLANK_BREAK_MS = 1_000L
        /** How far toward the other zone counts as "most of the way". */
        const val NEAR = 0.45f
        const val SQUAT_LEAN = 60f
        const val PUSH_UP_LINE = 150f
    }
}
