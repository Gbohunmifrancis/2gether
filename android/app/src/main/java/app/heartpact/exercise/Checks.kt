package app.heartpact.exercise

import app.heartpact.exercise.Pose.Companion.L_ANKLE
import app.heartpact.exercise.Pose.Companion.L_HIP
import app.heartpact.exercise.Pose.Companion.L_SHOULDER
import app.heartpact.exercise.Pose.Companion.L_WRIST
import app.heartpact.exercise.Pose.Companion.R_ANKLE
import app.heartpact.exercise.Pose.Companion.R_HIP
import app.heartpact.exercise.Pose.Companion.R_SHOULDER
import app.heartpact.exercise.Pose.Companion.R_WRIST
import app.heartpact.i18n.tr
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
    /** Raise the left hand, or the right, picked at random when it opens: a recording, playing or paused, can't follow the cue. */
    RaiseLeft,
    RaiseRight,
    /** Plank and mountain climbers, where the hands hold the body up: hips up, then back into the line. */
    HipsUp,
    /** Side plank, one arm holding the body up: hips down toward the floor, then back into the line. */
    HipDip,
}

/** A check-in on screen: what to do, how long is left, how far along the hold is, then how it went. */
data class ChallengeUi(val kind: ChallengeKind, val msLeft: Long, val progress: Float, val passed: Boolean? = null) {
    val title: String get() = when {
        passed == true -> tr("Check-in passed")
        passed == false -> tr("Missed the check-in")
        kind == ChallengeKind.RaiseLeft -> tr("Check-in: left hand up!")
        kind == ChallengeKind.RaiseRight -> tr("Check-in: right hand up!")
        kind == ChallengeKind.HipDip -> tr("Check-in: hip dip!")
        else -> tr("Check-in: hips up!")
    }
    val body: String get() = when {
        passed == true -> tr("Thanks. Keep going.")
        passed == false -> tr("Your partner will see that one was missed. Keep going.")
        kind == ChallengeKind.RaiseLeft -> tr("Raise your left hand above your shoulder for a second.")
        kind == ChallengeKind.RaiseRight -> tr("Raise your right hand above your shoulder for a second.")
        kind == ChallengeKind.HipDip -> tr("Lower your hips toward the floor, then lift back into a straight line.")
        else -> tr("Raise your hips, then back into a straight line.")
    }
}

/**
 * Surprise check-ins: at a random moment the screen asks for something a recording can't do on cue.
 * One for a short set, two for a long one, at random points in the middle of the set's expected length.
 * Time is frame time, from when the person first appears in frame.
 */
class ChallengeRunner(private val type: ExerciseType, target: Int, private val random: Random = Random.Default) {
    private var kind = ChallengeKind.HipsUp
    private val schedule: List<Long>
    private var startedAtMs: Long? = null
    private var next = 0
    private var openedAtMs: Long? = null
    private var resultAtMs: Long? = null
    private var lastPassed: Boolean? = null
    // Raise a hand: since when the asked-for hand has been up on its own.
    private var raisedSinceMs: Long? = null
    // Hips up or a hip dip: seen out of the line, then seen back in it.
    private var hipsMoved = false

    var passed = 0; private set
    var missed = 0; private set

    init {
        // A set takes about twice the fastest pace it can be done at; holds take their length.
        val expected = (if (type.measure == Measure.Seconds) target * 1_000L else type.minimumMillis(target) * 2).coerceIn(12_000, 300_000)
        val count = if (expected < 40_000) 1 else 2
        val slot = expected / count
        schedule = List(count) { i -> (slot * i + slot * 3 / 10 + random.nextLong(slot * 4 / 10 + 1)).coerceAtLeast(FIRST_AFTER_MS) }
    }

    /**
     * Feed every frame (null pose: nobody in frame). Returns what to show, if anything. [opening]: false once the set is done.
     * [mirrored]: the picture was flipped before detection (the front camera), which swaps the model's left and right.
     */
    fun update(pose: Pose?, nowMs: Long, opening: Boolean = true, mirrored: Boolean = false): ChallengeUi? {
        if (startedAtMs == null) { if (pose == null) return null; startedAtMs = nowMs }
        val elapsed = nowMs - startedAtMs!!
        val open = openedAtMs
        if (open == null) {
            resultAtMs?.let { if (nowMs - it < RESULT_MS) return ChallengeUi(kind, 0, 1f, lastPassed) else resultAtMs = null }
            if (opening && next < schedule.size && elapsed >= schedule[next]) {
                next++; openedAtMs = nowMs; raisedSinceMs = null; hipsMoved = false
                kind = when {
                    type == ExerciseType.Plank || type == ExerciseType.MountainClimber -> ChallengeKind.HipsUp
                    type == ExerciseType.SidePlank -> ChallengeKind.HipDip
                    random.nextBoolean() -> ChallengeKind.RaiseLeft
                    else -> ChallengeKind.RaiseRight
                }
                return ChallengeUi(kind, WINDOW_MS, 0f)
            }
            return null
        }
        val left = WINDOW_MS - (nowMs - open)
        val progress = when (kind) {
            ChallengeKind.RaiseLeft -> raise(pose, nowMs, left = !mirrored)
            ChallengeKind.RaiseRight -> raise(pose, nowMs, left = mirrored)
            ChallengeKind.HipsUp -> hips(pose, up = true)
            ChallengeKind.HipDip -> hips(pose, up = false)
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

    /**
     * The asked-for hand (by the model's [left] landmarks) above its shoulder, held for [RAISE_HOLD_MS], with the
     * other hand not up too: a clip of arm raises or jumping jacks lifts both. A hand the camera can't see isn't up.
     */
    private fun raise(pose: Pose?, nowMs: Long, left: Boolean): Float {
        fun up(wrist: Int, shoulder: Int) = pose != null && pose[wrist].visibility >= MIN_VISIBILITY && pose[shoulder].visibility >= MIN_VISIBILITY && pose[wrist].y < pose[shoulder].y
        val asked = if (left) up(L_WRIST, L_SHOULDER) else up(R_WRIST, R_SHOULDER)
        val other = if (left) up(R_WRIST, R_SHOULDER) else up(L_WRIST, L_SHOULDER)
        if (!asked || other) { raisedSinceMs = null; return 0f }
        val since = raisedSinceMs ?: nowMs.also { raisedSinceMs = it }
        return ((nowMs - since).toFloat() / RAISE_HOLD_MS).coerceIn(0f, 1f)
    }

    /**
     * Hips out of the line and back: [up] above the line from shoulders to ankles (a plank), or below it (a side plank's
     * hip dip). Half way once they have left the line, done when the body is straight again.
     */
    private fun hips(pose: Pose?, up: Boolean): Float {
        val line = pose?.let { bodyLine(it) } ?: return if (hipsMoved) 0.5f else 0f
        val (shoulder, hip, ankle) = line
        val angle = angleAt(shoulder, hip, ankle)
        val sag = sagOf(shoulder, hip, ankle)
        if (angle <= 150f && (if (up) sag < 0 else sag > 0)) hipsMoved = true
        if (hipsMoved && angle >= 160f) return 1f
        return if (hipsMoved) 0.5f else 0f
    }

    /** Shoulder, hip and ankle: the side the camera sees for a plank; the middle of the body for a side plank, which faces it. */
    private fun bodyLine(pose: Pose): Triple<Point3, Point3, Point3>? {
        if (type == ExerciseType.SidePlank) {
            val all = intArrayOf(L_SHOULDER, R_SHOULDER, L_HIP, R_HIP, L_ANKLE, R_ANKLE)
            if (pose.visibility(*all) < MIN_VISIBILITY) return null
            return Triple(pose.mid(L_SHOULDER, R_SHOULDER), pose.mid(L_HIP, R_HIP), pose.mid(L_ANKLE, R_ANKLE))
        }
        val s = pose.betterSide(intArrayOf(L_SHOULDER, L_HIP, L_ANKLE), intArrayOf(R_SHOULDER, R_HIP, R_ANKLE))
        if (pose.visibility(*s) < MIN_VISIBILITY) return null
        return Triple(pose.at(s[0]), pose.at(s[1]), pose.at(s[2]))
    }

    companion object {
        const val WINDOW_MS = 6_000L
        /** How long the asked-for hand stays up: long enough that one stray frame can't pass. */
        const val RAISE_HOLD_MS = 1_000L
        const val RESULT_MS = 1_800L
        const val FIRST_AFTER_MS = 5_000L
        private const val MIN_VISIBILITY = 0.5f
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

    /** [mirrored]: the front camera's picture, flipped before detection (see [ChallengeRunner.update]). */
    fun update(pose: Pose?, state: CounterState, nowMs: Long, mirrored: Boolean = false): ChallengeUi? {
        val last = lastFrameMs
        if (seen && !state.inFrame && last != null) outOfFrameMs += (nowMs - last).coerceIn(0, MAX_GAP_MS)
        if (state.inFrame) seen = true
        lastFrameMs = nowMs
        if (state.count > lastCount) {
            val start = state.lastRepStartMs; val end = state.lastRepEndMs
            if (motion != null && start != null && end != null && motion.movedBetween(start, end)) phoneMovedReps += state.count - lastCount
            lastCount = state.count
        }
        return challenges.update(pose?.takeIf { state.inFrame }, nowMs, opening = state.count < target, mirrored = mirrored)
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
