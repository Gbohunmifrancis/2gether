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
import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * What the session noticed, sent with the completion so the partner can see it. Nothing here stops
 * the dare from counting: form faults already didn't count, and the rest is for the partner to judge.
 */
@Serializable
data class ExerciseChecks(
    val formFaults: Int = 0,
    val fastReps: Int = 0,
    val challengesPassed: Int = 0,
    val challengesMissed: Int = 0,
    val phoneMovedReps: Int = 0,
    val outOfFrameSeconds: Int = 0,
)

/**
 * How much the phone moved, from its motion sensors. Samples are intensities where 1 means "clearly
 * moving" (see PhoneMotion). Thread-safe: sensors write on one thread, the pose thread reads.
 */
class MotionLog(private val windowMs: Long = 30_000) {
    private val samples = ArrayDeque<Pair<Long, Float>>()

    @Synchronized fun add(atMs: Long, intensity: Float) {
        samples.addLast(atMs to intensity)
        while (samples.isNotEmpty() && samples.first().first < atMs - windowMs) samples.removeFirst()
    }

    /**
     * Whether the phone kept moving between [startMs] and [endMs]: a quarter or more of the readings
     * over the line. One bump (setting it down, a nudge) is not enough; shaking it to fake reps is.
     */
    @Synchronized fun movedBetween(startMs: Long, endMs: Long): Boolean {
        val inside = samples.filter { it.first in startMs..endMs }
        if (inside.size < MIN_SAMPLES) return false
        return inside.count { it.second >= 1f } * 4 >= inside.size
    }

    private companion object { const val MIN_SAMPLES = 3 }
}

enum class ChallengeKind {
    /** Hold completely still: a recording carries on moving. */
    Freeze,
    /** Plank only, where everyone is already still: hips up, then back into the line. */
    HipsUp,
}

/** A check-in on screen: what to do, how long is left, how far along the hold is, then how it went. */
data class ChallengeUi(val kind: ChallengeKind, val msLeft: Long, val progress: Float, val passed: Boolean? = null) {
    val title: String get() = when {
        passed == true -> "Check-in passed"
        passed == false -> "Missed the check-in"
        kind == ChallengeKind.Freeze -> "Check-in: freeze!"
        else -> "Check-in: hips up!"
    }
    val body: String get() = when {
        passed == true -> "Thanks. Keep going."
        passed == false -> "Your partner will see that one was missed. Keep going."
        kind == ChallengeKind.Freeze -> "Hold completely still for 2 seconds."
        else -> "Raise your hips, then back into a straight line."
    }
}

/**
 * Surprise check-ins: at a random moment the screen asks for something a recording can't do on cue.
 * One for a short set, two for a long one, at random points in the middle of the set's expected length.
 * Time is frame time, from when the person first appears in frame.
 */
class ChallengeRunner(private val type: ExerciseType, target: Int, random: Random = Random.Default) {
    private val kind = if (type == ExerciseType.Plank) ChallengeKind.HipsUp else ChallengeKind.Freeze
    private val schedule: List<Long>
    private var startedAtMs: Long? = null
    private var next = 0
    private var openedAtMs: Long? = null
    private var resultAtMs: Long? = null
    private var lastPassed: Boolean? = null
    // Freeze: where the body was when the current still stretch began.
    private var anchor: Pose? = null
    private var stillSinceMs: Long? = null
    // Hips up: seen high, then seen back in line.
    private var hipsWentUp = false

    var passed = 0; private set
    var missed = 0; private set

    init {
        // A set takes about twice the fastest pace it can be done at; holds take their length.
        val expected = (if (type.measure == Measure.Seconds) target * 1_000L else type.minimumMillis(target) * 2).coerceIn(12_000, 300_000)
        val count = if (expected < 40_000) 1 else 2
        val slot = expected / count
        schedule = List(count) { i -> (slot * i + slot * 3 / 10 + random.nextLong(slot * 4 / 10 + 1)).coerceAtLeast(FIRST_AFTER_MS) }
    }

    /** Feed every frame (null pose: nobody in frame). Returns what to show, if anything. [opening]: false once the set is done. */
    fun update(pose: Pose?, nowMs: Long, opening: Boolean = true): ChallengeUi? {
        if (startedAtMs == null) { if (pose == null) return null; startedAtMs = nowMs }
        val elapsed = nowMs - startedAtMs!!
        val open = openedAtMs
        if (open == null) {
            resultAtMs?.let { if (nowMs - it < RESULT_MS) return ChallengeUi(kind, 0, 1f, lastPassed) else resultAtMs = null }
            if (opening && next < schedule.size && elapsed >= schedule[next]) {
                next++; openedAtMs = nowMs; anchor = null; stillSinceMs = null; hipsWentUp = false
                return ChallengeUi(kind, WINDOW_MS, 0f)
            }
            return null
        }
        val left = WINDOW_MS - (nowMs - open)
        val progress = when (kind) {
            ChallengeKind.Freeze -> freeze(pose, nowMs)
            ChallengeKind.HipsUp -> hipsUp(pose)
        }
        return when {
            progress >= 1f -> close(true, nowMs)
            left <= 0 -> close(false, nowMs)
            else -> ChallengeUi(kind, left, progress)
        }
    }

    private fun close(ok: Boolean, nowMs: Long): ChallengeUi {
        if (ok) passed++ else missed++
        openedAtMs = null; lastPassed = ok; resultAtMs = nowMs
        return ChallengeUi(kind, 0, 1f, ok)
    }

    private fun freeze(pose: Pose?, nowMs: Long): Float {
        if (pose == null) { anchor = null; stillSinceMs = null; return 0f }
        val base = anchor
        if (base == null || drift(base, pose) > STILL) { anchor = pose; stillSinceMs = nowMs; return 0f }
        return ((nowMs - stillSinceMs!!).toFloat() / HOLD_MS).coerceIn(0f, 1f)
    }

    private fun hipsUp(pose: Pose?): Float {
        if (pose == null) return if (hipsWentUp) 0.5f else 0f
        val s = pose.betterSide(intArrayOf(L_SHOULDER, L_HIP, L_ANKLE), intArrayOf(R_SHOULDER, R_HIP, R_ANKLE))
        if (pose.visibility(*s) < 0.5f) return if (hipsWentUp) 0.5f else 0f
        val angle = pose.angle(s[0], s[1], s[2])
        val above = pose[s[1]].y < pose.lineY(s[0], s[2], pose[s[1]].x)
        if (above && angle <= 150f) hipsWentUp = true
        if (hipsWentUp && angle >= 160f) return 1f
        return if (hipsWentUp) 0.5f else 0f
    }

    /** The largest joint movement since [from], in units of the person's own trunk length. */
    private fun drift(from: Pose, to: Pose): Float {
        val scale = maxOf(to.distance(L_SHOULDER, L_HIP), to.distance(R_SHOULDER, R_HIP), 0.05f)
        return JOINTS.maxOf { j ->
            val dx = (to[j].x - from[j].x) * to.aspect; val dy = to[j].y - from[j].y
            kotlin.math.hypot(dx, dy)
        } / scale
    }

    companion object {
        const val WINDOW_MS = 6_000L
        const val HOLD_MS = 2_000L
        const val RESULT_MS = 1_800L
        const val FIRST_AFTER_MS = 5_000L
        /** Joint movement under this share of the trunk length counts as still. */
        const val STILL = 0.2f
        private val JOINTS = intArrayOf(L_SHOULDER, R_SHOULDER, L_ELBOW, R_ELBOW, L_WRIST, R_WRIST, L_HIP, R_HIP, L_KNEE, R_KNEE, L_ANKLE, R_ANKLE)
    }
}

/**
 * The session's integrity checks around the counter: surprise check-ins, reps counted while the phone
 * was moving, and time out of frame once the person has appeared. Feed it every frame after the counter.
 */
class SessionChecks(type: ExerciseType, private val target: Int, private val motion: MotionLog? = null, random: Random = Random.Default) {
    private val challenges = ChallengeRunner(type, target, random)
    private var lastCount = 0
    private var phoneMovedReps = 0
    private var outOfFrameMs = 0L
    private var seen = false
    private var lastFrameMs: Long? = null

    fun update(pose: Pose?, state: CounterState, nowMs: Long): ChallengeUi? {
        val last = lastFrameMs
        if (seen && !state.inFrame && last != null) outOfFrameMs += (nowMs - last).coerceIn(0, MAX_GAP_MS)
        if (state.inFrame) seen = true
        lastFrameMs = nowMs
        if (state.count > lastCount) {
            val start = state.lastRepStartMs; val end = state.lastRepEndMs
            if (motion != null && start != null && end != null && motion.movedBetween(start, end)) phoneMovedReps += state.count - lastCount
            lastCount = state.count
        }
        return challenges.update(pose?.takeIf { state.inFrame }, nowMs, opening = state.count < target)
    }

    fun report(state: CounterState) = ExerciseChecks(
        formFaults = state.formFaults,
        fastReps = state.fastReps,
        challengesPassed = challenges.passed,
        challengesMissed = challenges.missed,
        phoneMovedReps = phoneMovedReps,
        outOfFrameSeconds = (outOfFrameMs / 1_000).toInt(),
    )

    private companion object { const val MAX_GAP_MS = 1_000L }
}
