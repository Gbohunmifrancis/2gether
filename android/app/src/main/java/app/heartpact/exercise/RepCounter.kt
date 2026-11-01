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
import kotlin.math.abs

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
    /** The latest fault behind [cue], for whoever shows it in another language (the partner's live view). */
    val fault: FaultKind? = null,
)

/** How a pose stands against an exercise's starting position. */
enum class Readiness { OutOfView, OutOfPosition, NotAtStart, Ready }

/** [Readiness] with the fault behind it, when the counter knows one (a plank's hips too low, say). */
data class StartCheck(val readiness: Readiness, val fault: FaultKind? = null)

/**
 * An exercise's resting zone and active zone on its one signal, with the dead band between them. The edges start at
 * defaults and follow the person's own start position once [calibrated]: the rest edge sits [restMargin] inside what
 * their body read standing (or lying) still, the active edge [depth] beyond it, each kept within bounds so that no
 * start position, however odd, makes a shallow rep count or an honest one impossible.
 */
class Zones(
    val rest: Float,
    val active: Float,
    val activeIsLow: Boolean,
    private val restMargin: Float = 0f,
    private val restBounds: ClosedFloatingPointRange<Float> = rest..rest,
    private val depth: Float = 0f,
    private val activeBounds: ClosedFloatingPointRange<Float> = active..active,
) {
    val calibrates: Boolean get() = restBounds.start != restBounds.endInclusive || activeBounds.start != activeBounds.endInclusive

    /** The loosest rest edge the bounds allow: the start position is accepted from there, before calibrating. */
    val startEdge: Float get() = if (activeIsLow) restBounds.start else restBounds.endInclusive

    fun isActive(signal: Float) = if (activeIsLow) signal <= active else signal >= active
    fun isRest(signal: Float) = if (activeIsLow) signal >= rest else signal <= rest
    fun isStart(signal: Float) = if (activeIsLow) signal >= startEdge else signal <= startEdge

    /** At least [share] of the way from the rest edge toward the active edge. */
    fun past(signal: Float, share: Float): Boolean {
        val edge = rest + share * (active - rest)
        return if (activeIsLow) signal <= edge else signal >= edge
    }

    /** At least [share] of the way back from the active edge toward the rest edge. */
    fun back(signal: Float, share: Float): Boolean {
        val edge = active + share * (rest - active)
        return if (activeIsLow) signal >= edge else signal <= edge
    }

    /** The zones for someone whose start position reads [start]. */
    fun calibrated(start: Float): Zones {
        if (!calibrates) return this
        val sign = if (activeIsLow) -1f else 1f
        return Zones(
            rest = (start + sign * restMargin).coerceIn(restBounds), active = (start + sign * depth).coerceIn(activeBounds), activeIsLow = activeIsLow,
            restMargin = restMargin, restBounds = restBounds, depth = depth, activeBounds = activeBounds,
        )
    }

    companion object {
        fun of(type: ExerciseType): Zones = when (type) {
            // Knee angle. Standing reads 165-178 with straight legs; a squat to about parallel, 90-105.
            ExerciseType.Squat -> Zones(160f, 105f, activeIsLow = true, restMargin = 10f, restBounds = 148f..165f, depth = 68f, activeBounds = 100f..108f)
            // Elbow angle. Not everyone locks out at the top.
            ExerciseType.PushUp -> Zones(150f, 100f, activeIsLow = true, restMargin = 12f, restBounds = 140f..160f, depth = 65f, activeBounds = 95f..105f)
            // Hip angle, lying with knees bent (about 125-150) to sitting up.
            ExerciseType.SitUp -> Zones(125f, 80f, activeIsLow = true, restMargin = 10f, restBounds = 112f..135f, depth = 50f, activeBounds = 75f..88f)
            // Wrists above the shoulders, in torso lengths.
            ExerciseType.ArmRaise -> Zones(0.05f, 0.55f, activeIsLow = false)
            ExerciseType.JumpingJack -> Zones(0.2f, 1f, activeIsLow = false)
            // Each knee's height against its hip, in torso lengths: about -0.9 standing, 0 at hip height.
            ExerciseType.HighKnees -> Zones(-0.3f, -0.1f, activeIsLow = false)
            // The straighter knee's angle: both knees bend in a lunge, only one when a leg just swings back.
            ExerciseType.Lunge -> Zones(158f, 118f, activeIsLow = true, restMargin = 10f, restBounds = 148f..165f, depth = 55f, activeBounds = 112f..122f)
            // Hip angle, shoulders on the floor: about 120-140 down, 160-180 with the hips up in line.
            ExerciseType.GluteBridge -> Zones(140f, 160f, activeIsLow = false, restMargin = 10f, restBounds = 128f..150f, depth = 35f, activeBounds = 155f..165f)
            // Each leg's hip angle in a high plank: about 170 extended, 70-110 with the knee driven in.
            ExerciseType.MountainClimber -> Zones(150f, 115f, activeIsLow = true, restMargin = 15f, restBounds = 140f..160f, depth = 60f, activeBounds = 105f..120f)
            // The trunk's slope from level: upright standing (about 90), near level in the plank.
            ExerciseType.Burpee -> Zones(65f, 30f, activeIsLow = true)
            // Holds: 1 in position, 0 out of it.
            ExerciseType.Plank, ExerciseType.WallSit, ExerciseType.SidePlank -> Zones(0f, 1f, activeIsLow = false)
        }
    }
}

/**
 * Counts reps (or seconds held) from a stream of poses. Each exercise reduces a pose to one signal (or one per leg, for
 * exercises where the legs take turns) with a resting zone and an active zone and a dead band between them ([Zones]), so
 * jitter around a single threshold can never count twice. A rep is counted when the body returns to rest after
 * reaching the active zone. A phase only changes after [confirmFrames] readings in the new zone, so a one-frame spike is
 * ignored; drifting through the dead band keeps a pending change, going back to the old zone cancels it. Counting frames
 * rather than milliseconds keeps this independent of the camera's frame rate. Frames where the key joints are barely
 * visible are ignored rather than guessed.
 *
 * Angles are measured in space when the pose has MediaPipe's world landmarks ([Pose.hasDepth]), so a squat seen from the
 * front or at 45 degrees bends as far as one seen side-on; otherwise across the picture. [calibrate] fits the zones to
 * the person's own start position, read while the coach holds them in it.
 *
 * It also judges form, and a movement with a fault doesn't count: going most of the way and coming back
 * ([FaultKind.Shallow]), starting the next rep without getting back ([FaultKind.NotAllTheWay]), reaching the position
 * out of line ([FaultKind.Posture]), or a rep quicker than [fastFactor] of the exercise's minimum pace
 * ([FaultKind.TooFast]). Each fault leaves a tip in [CounterState.cue].
 *
 * The signal alone can be faked by moving one joint, so each exercise also checks the body is where that exercise puts
 * it ([FaultKind.Position]): a squat or a lunge drops the hips, a push-up, a plank and mountain climbers hold the body
 * level with the shoulders up over the hands or elbows, a sit-up or a glute bridge is done lying down. A frame out of
 * position moves nothing. The checks are loose on purpose: real bodies and real camera angles vary, and they only need
 * to rule out the cheats (a chair, standing, lying flat).
 */
class RepCounter(
    val type: ExerciseType,
    private val confirmFrames: Int = 2,
    private val minVisibility: Float = 0.5f,
    private val fastFactor: Double = 0.6,
) {
    private val defaults = Zones.of(type)
    /** The zones in use: the defaults, or fitted to the person by [calibrate]. */
    var zones: Zones = defaults
        private set

    private var count = 0
    private var active = false
    private var pending = 0
    private var visibilitySum = 0.0
    private var visibleFrames = 0
    private var inFrame = false
    // Holds: time accumulated in position, tolerating short drop-outs.
    private var holdMs = 0L
    private var lastHoldFrameMs: Long? = null
    private var heldSinceMs: Long? = null
    // Form: how far each movement got and what went wrong on the way.
    private var nearFrames = 0
    private var reachedNear = false
    // Out of position on the way (a lunge's feet side by side): coming back keeps that tip rather than "go lower".
    private var strayed = false
    private var reachedDeep = false
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
    // Squat and lunge: where the hip was in the picture the last time the legs were straight, and the leg's length
    // then, to tell a squat from a knee bent in a chair. The picture is the fixed frame here: the camera doesn't move.
    private var standingHipY: Float? = null
    private var standingLeg: Float? = null
    // Exercises where the legs take turns: each leg's own rep.
    private val legs = arrayOf(Leg(), Leg())

    private val minRepMs = (type.minSecondsPerUnit * 1_000 * fastFactor).toLong()

    private class Leg {
        var active = false
        var pending = 0
        var startMs: Long? = null
        var restSeenMs: Long? = null
        var nearFrames = 0
        var reachedNear = false
        fun reset() { active = false; pending = 0; startMs = null; restSeenMs = null; nearFrames = 0; reachedNear = false }
    }

    fun reset() {
        count = 0; active = false; pending = 0
        visibilitySum = 0.0; visibleFrames = 0; inFrame = false; holdMs = 0; lastHoldFrameMs = null; heldSinceMs = null
        nearFrames = 0; reachedNear = false; strayed = false; reachedDeep = false; retreated = false; postureFrames = 0; restSeenMs = null; repStartMs = null
        lastRepStartMs = null; lastRepEndMs = null; formFaults = 0; fastReps = 0; fault = null; lastFrameMs = 0; standingHipY = null; standingLeg = null
        legs.forEach { it.reset() }
        zones = defaults
    }

    /**
     * Fits the zones to the person: [start] is what their body read in the start position (see [startReading]). Null,
     * or an exercise whose zones don't move, keeps the defaults.
     */
    fun calibrate(start: Float?) {
        zones = if (start == null) defaults else defaults.calibrated(start)
    }

    val state: CounterState get() {
        val fresh = fault?.takeIf { lastFrameMs - it.atMs in 0..FormCues.SHOW_MS }
        return CounterState(
            count = if (type.measure == Measure.Seconds) (holdMs / 1000).toInt() else count,
            inFrame = inFrame,
            confidence = if (visibleFrames == 0) 0.0 else visibilitySum / visibleFrames,
            active = active,
            formFaults = formFaults,
            fastReps = fastReps,
            cue = fresh?.let { FormCues.message(type, it.kind) },
            lastRepStartMs = lastRepStartMs,
            lastRepEndMs = lastRepEndMs,
            fault = fresh?.kind,
        )
    }

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
        if (!reading.inPosition) {
            // In view but not in this exercise's position (standing for push-ups, a knee bent in a chair): nothing moves.
            pending = 0
            legs.forEach { it.pending = 0 }
            if (!active && reachedNear) strayed = true
            reading.fault?.let { fault = RepFault(it, now) }
            return state
        }
        visibilitySum += reading.visibility
        visibleFrames++
        if (type.measure == Measure.Seconds) {
            hold(reading, now)
            return state
        }
        if (reading.legs != null) {
            updateLegs(reading.legs, now)
            return state
        }
        val z = zones
        val inActive = z.isActive(reading.signal)
        val inRest = z.isRest(reading.signal)
        // Most of the way toward the active zone, and most of the way back from it.
        val pastNear = z.past(reading.signal, NEAR)
        val pastRetreat = z.back(reading.signal, NEAR)

        if (!active) {
            if (inRest) { restSeenMs = now; if (!reachedNear) strayed = false }
            nearFrames = if (pastNear && !inActive) nearFrames + 1 else 0
            if (nearFrames >= confirmFrames) reachedNear = true
        } else {
            if (!reading.postureOk) postureFrames++
            if (reading.deep == true) reachedDeep = true
            if (pastRetreat && !inRest) retreated = true
            // Back down into the next rep without reaching the start: that rep can't count.
            if (retreated && inActive) { retreated = false; formFaults++; fault = RepFault(FaultKind.NotAllTheWay, now) }
        }

        val toward = if (active) inRest else inActive
        val back = if (active) inActive else inRest
        when {
            toward -> if (++pending >= confirmFrames) {
                pending = 0
                if (active) finishRep(now, needsDeep = reading.deep != null) else {
                    repStartMs = restSeenMs ?: now; postureFrames = 0; retreated = false; reachedNear = false; reachedDeep = reading.deep == true
                }
                active = !active
            }
            back -> {
                pending = 0
                // Came back to rest from most of the way there; if the body left the position on the way, that was why.
                if (!active && reachedNear) {
                    reachedNear = false
                    if (strayed) fault = RepFault(FaultKind.Position, now) else { formFaults++; fault = RepFault(FaultKind.Shallow, now) }
                    strayed = false
                }
            }
        }
        return state
    }

    /** A hold: the clock runs while in position, stops (and says why) when the line breaks. */
    private fun hold(reading: Reading, now: Long) {
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
    }

    /** Each leg is its own rep: one knee coming down while the other goes up can't hide either. */
    private fun updateLegs(signals: FloatArray, now: Long) {
        val z = zones
        signals.forEachIndexed { i, signal ->
            val leg = legs[i]
            val inActive = z.isActive(signal)
            val inRest = z.isRest(signal)
            if (!leg.active) {
                if (inRest) leg.restSeenMs = now
                leg.nearFrames = if (z.past(signal, NEAR) && !inActive) leg.nearFrames + 1 else 0
                if (leg.nearFrames >= confirmFrames) leg.reachedNear = true
            }
            val toward = if (leg.active) inRest else inActive
            val back = if (leg.active) inActive else inRest
            when {
                toward -> if (++leg.pending >= confirmFrames) {
                    leg.pending = 0
                    if (leg.active) {
                        val start = leg.startMs ?: now
                        if (now - start < minRepMs) { fastReps++; fault = RepFault(FaultKind.TooFast, now) }
                        else { count++; lastRepStartMs = start; lastRepEndMs = now }
                        leg.restSeenMs = now
                    } else { leg.startMs = leg.restSeenMs ?: now; leg.reachedNear = false }
                    leg.active = !leg.active
                }
                back -> {
                    leg.pending = 0
                    if (!leg.active && leg.reachedNear) { leg.reachedNear = false; formFaults++; fault = RepFault(FaultKind.Shallow, now) }
                }
            }
        }
        active = legs.any { it.active }
    }

    private fun finishRep(now: Long, needsDeep: Boolean) {
        val start = repStartMs ?: now
        when {
            // Down and back up without reaching the whole position (a burpee without the plank).
            needsDeep && !reachedDeep -> { formFaults++; fault = RepFault(FaultKind.Shallow, now) }
            postureFrames >= confirmFrames -> { formFaults++; fault = RepFault(FaultKind.Posture, now) }
            now - start < minRepMs -> { fastReps++; fault = RepFault(FaultKind.TooFast, now) }
            else -> { count++; lastRepStartMs = start; lastRepEndMs = now }
        }
        postureFrames = 0
        retreated = false
        reachedDeep = false
        restSeenMs = now
    }

    /**
     * Whether [pose] is this exercise's starting position, for the coach before counting begins (docs/exercise-checks.md, Getting
     * set). The rest edge here is the loosest the calibration allows, so someone whose legs never read quite straight can
     * still start. Use a separate counter for this: reading a squat remembers the standing hip height.
     */
    fun readiness(pose: Pose): StartCheck {
        if (pose.landmarks.size < Pose.COUNT) return StartCheck(Readiness.OutOfView)
        val reading = read(pose) ?: return StartCheck(Readiness.OutOfView)
        if (!reading.inPosition) return StartCheck(Readiness.OutOfPosition, reading.fault)
        if (type.measure == Measure.Seconds) return if (reading.signal >= 1f) StartCheck(Readiness.Ready) else StartCheck(Readiness.NotAtStart, reading.fault)
        val atStart = (reading.legs ?: floatArrayOf(reading.signal)).all { defaults.isStart(it) }
        return if (atStart && reading.postureOk) StartCheck(Readiness.Ready) else StartCheck(Readiness.NotAtStart)
    }

    /**
     * What [pose] reads in the start position, for [calibrate]: the signal, or for legs taking turns the leg nearer the
     * active zone. Null when the exercise doesn't calibrate or the pose can't be read.
     */
    fun startReading(pose: Pose): Float? {
        if (!defaults.calibrates || pose.landmarks.size < Pose.COUNT) return null
        val reading = read(pose)?.takeIf { it.inPosition } ?: return null
        val signals = reading.legs ?: return reading.signal
        return if (defaults.activeIsLow) signals.min() else signals.max()
    }

    /** One frame reduced to a signal, or null when the joints that matter are not visible. */
    private class Reading(
        val signal: Float,
        val visibility: Float,
        /** False on frames where the body is out of line for this exercise. */
        val postureOk: Boolean = true,
        /** Holds: why the position is broken, when it is. Out of position: the tip to show, if any. */
        val fault: FaultKind? = null,
        /** False when the body isn't where this exercise puts it; such a frame moves nothing. */
        val inPosition: Boolean = true,
        /** One signal per leg (left, right) for exercises where the legs take turns; [signal] is then the busier one. */
        val legs: FloatArray? = null,
        /** Whether this frame reaches the whole active position, for exercises that need more than the signal (a burpee's plank). */
        val deep: Boolean? = null,
    )

    private fun read(p: Pose): Reading? {
        fun seen(vararg idx: Int): Float? = p.visibility(*idx).takeIf { it >= minVisibility }
        val z = zones
        return when (type) {
            ExerciseType.Squat -> {
                val s = p.betterSide(intArrayOf(L_HIP, L_KNEE, L_ANKLE), intArrayOf(R_HIP, R_KNEE, R_ANKLE))
                seen(*s)?.let {
                    // The trunk leaning more than [SQUAT_LEAN] degrees from upright means the chest has dropped.
                    val shoulder = if (s[0] == L_HIP) L_SHOULDER else R_SHOULDER
                    val upright = p.visibility(shoulder) < minVisibility || p.lean(s[0], shoulder) <= SQUAT_LEAN
                    val knee = p.angle(s[0], s[1], s[2])
                    // The hips must come down, not only the knee bend: sitting in a chair and swinging a leg moves the knee alone.
                    val dropped = hipsDropped(p, s[0], s[1], s[2], straight = !active && knee >= z.rest)
                    Reading(knee, visibility = it, postureOk = upright, fault = FaultKind.Position, inPosition = knee > z.active || dropped)
                }
            }
            ExerciseType.Lunge -> {
                val s = p.betterSide(intArrayOf(L_HIP, L_KNEE, L_ANKLE), intArrayOf(R_HIP, R_KNEE, R_ANKLE))
                val far = if (s[0] == L_HIP) intArrayOf(R_HIP, R_KNEE, R_ANKLE) else intArrayOf(L_HIP, L_KNEE, L_ANKLE)
                // Both legs are needed (they bend together), the far one at least roughly.
                if (p.visibility(*far) < FAR_VISIBILITY) return null
                seen(*s)?.let {
                    val near = p.angle(s[0], s[1], s[2])
                    val other = p.angle(far[0], far[1], far[2])
                    val knee = maxOf(near, other)
                    val leg = p.distance(s[0], s[1]) + p.distance(s[1], s[2])
                    // One foot forward, one back: the feet apart along the floor, not side by side as in a squat.
                    val split = p.spread(L_ANKLE, R_ANKLE) >= LUNGE_SPLIT * leg
                    val dropped = hipsDropped(p, s[0], s[1], s[2], straight = !active && knee >= z.rest)
                    val shoulder = if (s[0] == L_HIP) L_SHOULDER else R_SHOULDER
                    val upright = p.visibility(shoulder) < minVisibility || p.lean(s[0], shoulder) <= LUNGE_LEAN
                    Reading(knee, visibility = it, postureOk = upright, fault = FaultKind.Position, inPosition = knee > z.active || (split && dropped))
                }
            }
            ExerciseType.PushUp -> {
                val s = p.betterSide(intArrayOf(L_SHOULDER, L_ELBOW, L_WRIST), intArrayOf(R_SHOULDER, R_ELBOW, R_WRIST))
                val (hip, ankle) = if (s[0] == L_SHOULDER) L_HIP to L_ANKLE else R_HIP to R_ANKLE
                // The hip is needed to see that the body is level; without it the frame can't be judged.
                if (p.visibility(hip) < minVisibility) return null
                seen(*s)?.let {
                    // Shoulders, hips and ankles in a line; checked only when the camera sees them.
                    val straight = p.visibility(hip, ankle) < minVisibility || p.angle(s[0], hip, ankle) >= PUSH_UP_LINE
                    val elbow = p.angle(s[0], s[1], s[2])
                    // Level (not standing side-on bending the elbows), and at the top the hands are under the
                    // raised shoulders (not lying flat, not on the back pushing the arms up).
                    val level = p.tilt(s[0], if (p.visibility(ankle) >= minVisibility) ankle else hip) <= BODY_TILT
                    val shouldersUp = p.below(s[0], s[2]) >= ARMS_UNDER * (p.distance(s[0], s[1]) + p.distance(s[1], s[2]))
                    Reading(elbow, visibility = it, postureOk = straight, fault = FaultKind.Position, inPosition = level && (elbow < z.rest || shouldersUp))
                }
            }
            ExerciseType.SitUp -> {
                val s = p.betterSide(intArrayOf(L_SHOULDER, L_HIP, L_KNEE), intArrayOf(R_SHOULDER, R_HIP, R_KNEE))
                seen(*s)?.let {
                    // Lying down the knee is level with the hip or above it; standing and bending forward, it hangs below.
                    val lying = p.below(s[1], s[2]) <= SIT_UP_KNEE_DROP * p.distance(s[1], s[2])
                    Reading(p.angle(s[0], s[1], s[2]), visibility = it, fault = FaultKind.Position, inPosition = lying)
                }
            }
            ExerciseType.GluteBridge -> {
                val s = p.betterSide(intArrayOf(L_SHOULDER, L_HIP, L_KNEE), intArrayOf(R_SHOULDER, R_HIP, R_KNEE))
                seen(*s)?.let {
                    val ankle = if (s[0] == L_SHOULDER) L_ANKLE else R_ANKLE
                    // On the back: the trunk far from upright, and the shoulders not raised above the hips (a sit-up).
                    val lying = p.lean(s[1], s[0]) >= BRIDGE_LYING && p.below(s[1], s[0]) >= -BRIDGE_SHOULDERS * p.distance(s[0], s[1])
                    // Knees bent, feet on the floor: lying flat with straight legs is already "in line".
                    val bent = p.visibility(ankle) < minVisibility || p.angle(s[1], s[2], ankle) <= BRIDGE_KNEE
                    Reading(p.angle(s[0], s[1], s[2]), visibility = it, fault = FaultKind.Position, inPosition = lying && bent)
                }
            }
            ExerciseType.MountainClimber -> {
                val s = p.betterSide(intArrayOf(L_SHOULDER, L_HIP, L_WRIST), intArrayOf(R_SHOULDER, R_HIP, R_WRIST))
                if (p.visibility(L_KNEE) < FAR_VISIBILITY || p.visibility(R_KNEE) < FAR_VISIBILITY) return null
                seen(*s)?.let {
                    val (shoulder, hip, wrist) = Triple(s[0], s[1], s[2])
                    val elbow = if (shoulder == L_SHOULDER) L_ELBOW else R_ELBOW
                    // A high plank: the trunk near level, the hands down under the shoulders (not running on the spot).
                    val level = p.tilt(shoulder, hip) <= CLIMBER_TILT
                    val handsDown = p.below(shoulder, wrist) >= ARMS_UNDER * (p.distance(shoulder, elbow) + p.distance(elbow, wrist))
                    val legs = floatArrayOf(p.angle(shoulder, hip, L_KNEE), p.angle(shoulder, hip, R_KNEE))
                    Reading(legs.min(), visibility = it, fault = FaultKind.Position, inPosition = level && handsDown, legs = legs)
                }
            }
            ExerciseType.Burpee -> {
                val s = p.betterSide(intArrayOf(L_SHOULDER, L_HIP, L_KNEE, L_ANKLE), intArrayOf(R_SHOULDER, R_HIP, R_KNEE, R_ANKLE))
                seen(*s)?.let {
                    val trunk = p.tilt(s[1], s[0])
                    val knee = p.angle(s[1], s[2], s[3])
                    // Standing tall is upright with straight legs; crouched with the trunk upright isn't the top yet.
                    val signal = if (knee >= BURPEE_STAND_KNEE) trunk else minOf(trunk, BURPEE_CROUCH)
                    // The bottom only counts as a plank: the body straight from shoulders to ankles, and near level.
                    val plank = p.angle(s[0], s[1], s[3]) >= BURPEE_PLANK_LINE && p.tilt(s[0], s[3]) <= BURPEE_PLANK_TILT
                    Reading(signal, visibility = it, deep = plank)
                }
            }
            ExerciseType.ArmRaise -> {
                val idx = intArrayOf(L_SHOULDER, R_SHOULDER, L_WRIST, R_WRIST, L_HIP, R_HIP)
                seen(*idx)?.let {
                    // Both wrists' height above the shoulders, in torso lengths (the lower wrist decides).
                    val lift = minOf(p.below(L_WRIST, L_SHOULDER), p.below(R_WRIST, R_SHOULDER)) / torso(p)
                    Reading(lift, visibility = it)
                }
            }
            ExerciseType.JumpingJack -> {
                val idx = intArrayOf(L_SHOULDER, R_SHOULDER, L_WRIST, R_WRIST, L_HIP, R_HIP, L_ANKLE, R_ANKLE)
                seen(*idx)?.let {
                    val lift = minOf(p.below(L_WRIST, L_SHOULDER), p.below(R_WRIST, R_SHOULDER)) / torso(p)
                    val feet = p.spread(L_ANKLE, R_ANKLE) / p.spread(L_HIP, R_HIP).coerceAtLeast(1e-3f)
                    // Arms up and feet apart together make the open position.
                    Reading(minOf(lift / 0.35f, (feet - 1f) / 0.6f), visibility = it)
                }
            }
            ExerciseType.HighKnees -> {
                val idx = intArrayOf(L_HIP, R_HIP, L_KNEE, R_KNEE, L_SHOULDER, R_SHOULDER)
                seen(*idx)?.let {
                    val torso = torso(p)
                    // Each knee's height relative to its hip, in torso lengths: about -0.9 standing, near 0 at hip height.
                    val legs = floatArrayOf(p.below(L_KNEE, L_HIP) / torso, p.below(R_KNEE, R_HIP) / torso)
                    Reading(legs.max(), visibility = it, legs = legs)
                }
            }
            ExerciseType.Plank -> {
                val s = p.betterSide(intArrayOf(L_SHOULDER, L_HIP, L_ANKLE), intArrayOf(R_SHOULDER, R_HIP, R_ANKLE))
                seen(*s)?.let {
                    val straight = p.angle(s[0], s[1], s[2]) >= PLANK_LINE
                    // Horizontal-ish: the body's rise is small compared with its length.
                    val flat = abs(p.below(s[0], s[2])) <= 0.45f * p.distance(s[0], s[2])
                    // Held up on the elbows or hands: lying flat, face down or on the back, is also straight and level.
                    val (elbow, wrist) = if (s[0] == L_SHOULDER) L_ELBOW to L_WRIST else R_ELBOW to R_WRIST
                    val prop = listOf(elbow, wrist).filter { p[it].visibility >= minVisibility }.maxOfOrNull { p.below(s[0], it) }
                    val propped = prop != null && prop >= PLANK_PROP * p.distance(s[0], elbow)
                    val fault = when {
                        straight && flat && propped -> null
                        !flat || !propped -> FaultKind.Posture
                        p.sag(s[0], s[1], s[2]) > 0 -> FaultKind.HipsLow
                        else -> FaultKind.HipsHigh
                    }
                    Reading(if (fault == null) 1f else 0f, visibility = it, fault = fault)
                }
            }
            ExerciseType.SidePlank -> {
                val idx = intArrayOf(L_SHOULDER, R_SHOULDER, L_HIP, R_HIP, L_ANKLE, R_ANKLE)
                seen(*idx)?.let {
                    val shoulder = p.mid(L_SHOULDER, R_SHOULDER); val hip = p.mid(L_HIP, R_HIP); val ankle = p.mid(L_ANKLE, R_ANKLE)
                    val straight = angleAt(shoulder, hip, ankle) >= PLANK_LINE
                    // On the side, not standing and not lying flat: the body slopes gently up from the feet, shoulders stacked one above the other.
                    val slope = tiltOf(shoulder, ankle)
                    val inclined = slope in SIDE_PLANK_RISE..SIDE_PLANK_TILT
                    val across = p.at(R_SHOULDER) - p.at(L_SHOULDER)
                    val stacked = abs(across.y) >= SIDE_PLANK_STACK * across.length
                    // Up on the lower forearm (or hand): its elbow or wrist under the lower shoulder.
                    val lower = if (p.at(L_SHOULDER).y >= p.at(R_SHOULDER).y) L_SHOULDER else R_SHOULDER
                    val (elbow, wrist) = if (lower == L_SHOULDER) L_ELBOW to L_WRIST else R_ELBOW to R_WRIST
                    val prop = listOf(elbow, wrist).filter { p[it].visibility >= SIDE_PLANK_ARM_VISIBILITY }.maxOfOrNull { p.below(lower, it) }
                    val propped = prop != null && prop >= PLANK_PROP * p.distance(lower, elbow)
                    val fault = when {
                        !inclined || !stacked || !propped -> FaultKind.Posture
                        straight -> null
                        sagOf(shoulder, hip, ankle) > 0 -> FaultKind.HipsLow
                        else -> FaultKind.HipsHigh
                    }
                    Reading(if (fault == null) 1f else 0f, visibility = it, fault = fault)
                }
            }
            ExerciseType.WallSit -> {
                val s = p.betterSide(intArrayOf(L_HIP, L_KNEE, L_ANKLE), intArrayOf(R_HIP, R_KNEE, R_ANKLE))
                seen(*s)?.let {
                    val shoulder = if (s[0] == L_HIP) L_SHOULDER else R_SHOULDER
                    val knee = p.angle(s[0], s[1], s[2])
                    // How far the hip sits above the knee, in thigh lengths: about 1 standing, 0 with the thigh level.
                    val raised = p.below(s[0], s[1]) / p.distance(s[0], s[1]).coerceAtLeast(1e-3f)
                    val upright = p.visibility(shoulder) < minVisibility || p.lean(s[0], shoulder) <= WALL_SIT_LEAN
                    val fault = when {
                        raised > WALL_SIT_HIGH || knee > WALL_SIT_KNEE_MAX -> FaultKind.HipsHigh
                        raised < -WALL_SIT_LOW || knee < WALL_SIT_KNEE_MIN -> FaultKind.HipsLow
                        !upright -> FaultKind.Posture
                        else -> null
                    }
                    Reading(if (fault == null) 1f else 0f, visibility = it, fault = fault)
                }
            }
        }
    }

    /**
     * Whether the hips have sunk in the picture since the legs were last straight, by at least [SQUAT_DROP] of the leg.
     * When [straight], the current height is remembered as standing. Vertical movement reads the same from any side.
     */
    private fun hipsDropped(p: Pose, hip: Int, knee: Int, ankle: Int, straight: Boolean): Boolean {
        val hipY = p[hip].y
        if (straight) {
            standingHipY = hipY
            standingLeg = p.pictureDistance(hip, knee) + p.pictureDistance(knee, ankle)
        }
        val leg = standingLeg ?: (p.pictureDistance(hip, knee) + p.pictureDistance(knee, ankle))
        return standingHipY?.let { hipY - it >= SQUAT_DROP * leg } == true
    }

    private fun torso(p: Pose): Float = (p.mid(L_HIP, R_HIP).y - p.mid(L_SHOULDER, R_SHOULDER).y).coerceAtLeast(0.05f)

    companion object {
        /** A brief drop-out (a blurred frame) keeps a hold going; a longer one stops the clock. */
        const val MAX_HOLD_GAP_MS = 400L
        /** A hold kept at least this long and then broken counts as a form fault. */
        const val PLANK_BREAK_MS = 1_000L
        /** How far toward the other zone counts as "most of the way". */
        const val NEAR = 0.45f
        /** The far leg, partly hidden side-on, still has to be roughly seen to be measured. */
        const val FAR_VISIBILITY = 0.25f
        const val SQUAT_LEAN = 60f
        const val PUSH_UP_LINE = 150f
        /** Squat and lunge: how far the hips must sink below standing at the bottom, in leg lengths. A real squat to 105 degrees sinks about 0.2. */
        const val SQUAT_DROP = 0.1f
        /** Lunge: the feet at least this many leg lengths apart along the floor (side by side in a squat they are about 0.3). */
        const val LUNGE_SPLIT = 0.45f
        /** Lunge: the trunk stays within this many degrees of upright. */
        const val LUNGE_LEAN = 45f
        /** Push-up: the most the shoulder-to-feet line may slope, in degrees (standing is about 90, an incline on a sofa about 40). */
        const val BODY_TILT = 50f
        /** Push-up and mountain climbers: at the top the hands are below the shoulders by at least this share of the arm (straight down is 1). */
        const val ARMS_UNDER = 0.5f
        /** Plank and side plank: the elbow or hand is below the shoulder by at least this share of the upper arm (a forearm plank is about 1). */
        const val PLANK_PROP = 0.5f
        /** Plank and side plank: shoulders, hips and ankles in a line to within this angle. */
        const val PLANK_LINE = 155f
        /** Sit-up: the knee may sit at most this share of the thigh below the hip (standing it hangs a whole thigh below). */
        const val SIT_UP_KNEE_DROP = 0.5f
        /** Glute bridge: the trunk at least this many degrees from upright (lying is about 90). */
        const val BRIDGE_LYING = 50f
        /** Glute bridge: the shoulders at most this share of the trunk above the hips (sitting up raises them a whole trunk). */
        const val BRIDGE_SHOULDERS = 0.35f
        /** Glute bridge: the knees bent to at most this angle (the feet planted near the hips). */
        const val BRIDGE_KNEE = 135f
        /** Mountain climbers: the trunk's slope from level, at most (a high plank is about 15-25 degrees). */
        const val CLIMBER_TILT = 40f
        /** Burpee: the knee angle that counts as standing tall. */
        const val BURPEE_STAND_KNEE = 150f
        /** Burpee: crouched with straight-ish trunk, the signal is held here, short of the rest zone. */
        const val BURPEE_CROUCH = 50f
        /** Burpee: the plank's line from shoulders to ankles, and its slope from level. */
        const val BURPEE_PLANK_LINE = 150f
        const val BURPEE_PLANK_TILT = 35f
        /** Side plank: the shoulder-to-ankle line slopes at most this much (standing is 90)... */
        const val SIDE_PLANK_TILT = 45f
        /** ...and at least this much: up on a forearm it is about 15, lying flat on the side about 4. */
        const val SIDE_PLANK_RISE = 10f
        /** Side plank: the line between the shoulders is at least this share vertical (one above the other). */
        const val SIDE_PLANK_STACK = 0.6f
        /** Side plank: the lower arm is partly hidden by the body; it still has to be roughly seen. */
        const val SIDE_PLANK_ARM_VISIBILITY = 0.3f
        /** Wall sit: the hip at most this share of the thigh above the knee (level is 0, standing about 1). */
        const val WALL_SIT_HIGH = 0.45f
        /** Wall sit: and at most this share below it. */
        const val WALL_SIT_LOW = 0.35f
        const val WALL_SIT_KNEE_MIN = 60f
        const val WALL_SIT_KNEE_MAX = 125f
        /** Wall sit: the back within this many degrees of upright, against the wall. */
        const val WALL_SIT_LEAN = 30f
    }
}
