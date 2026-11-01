package app.heartpact.exercise

import app.heartpact.i18n.tr
import kotlin.math.hypot

/** Where a workout is: getting into the start position, counting down, or counting. */
enum class CoachPhase { Setup, Countdown, Counting }

/** What stands between the person and the start position, first thing first. */
enum class SetupIssue { NoBody, StepBack, TurnSideways, FaceCamera, StartPosition }

/** What the camera screen shows from the coach: the phase, the instruction while getting set, and the countdown number. */
data class CoachState(
    val phase: CoachPhase = CoachPhase.Setup,
    val cue: String? = null,
    /** 3, 2, 1 during the countdown; null otherwise. */
    val countdown: Int? = null,
    /** While getting set: whether the whole body is in the picture. */
    val inFrame: Boolean = false,
    /** [cue] as a [LiveCues] code, for the partner's live view to say in their own language. */
    val code: String? = null,
)

/** One frame's coaching: the state to show, and a line to say out loud now, if any. */
data class CoachTick(val state: CoachState, val speak: String? = null)

/**
 * The voice coach (docs/exercise-checks.md, Getting set). Before anything counts it talks the person into the exercise's start
 * position: in the picture, turned the right way for the camera ([ExerciseType.view]: side-on for push-ups, sit-ups and
 * planks; facing it for arm raises and jumping jacks; any angle for squats and lunges when the model measures in space)
 * and in the start pose. Held there for a moment, it counts down "3, 2, 1, Go!" and only then does the rep counter
 * start. While the person holds still for the countdown it reads their own start position ([calibration]), which the
 * counter's zones are fitted to. While counting it says each rep (every five of fast ones, every ten seconds of a hold),
 * a form tip when one comes up, and asks the person back into the picture if they leave it.
 *
 * Pure and driven by frame time, so it can be played through in tests; the screen speaks [CoachTick.speak].
 */
class PoseCoach(
    val type: ExerciseType,
    private val target: Int,
    private val readyHoldMs: Long = 600,
) {
    private val probe = RepCounter(type)
    private var phase = CoachPhase.Setup
    private var readySinceMs: Long? = null
    private var countdownStartMs = 0L
    private var lastCountdownSaid = -1
    private var missSinceMs: Long? = null
    // What was last said and when, and the instruction waiting to settle before it is said.
    private var lastSaid: String? = null
    private var lastSaidAtMs = Long.MIN_VALUE / 2
    private var pendingCue: String? = null
    private var pendingSinceMs = 0L
    // The start position as the person holds it, for calibrating the counter.
    private val starts = ArrayList<Float>()
    // Counting.
    private var lastCount = 0
    private var lastTip: String? = null
    private var outSinceMs: Long? = null
    private var askedBack = false
    private var finished = false

    val counting: Boolean get() = phase == CoachPhase.Counting

    /**
     * What this person's body read in the start position, held still through the countdown (the median, so a blurred
     * frame doesn't move it). Null until "Go!", and for exercises whose zones don't move. Hand it to [RepCounter.calibrate].
     */
    var calibration: Float? = null
        private set

    /** Straight to counting, as if "Go!" had been said: for tests of what it says while counting. */
    internal fun skipToCounting() { phase = CoachPhase.Counting }

    /** Feed one frame, before the counter. [counter] is the counter's latest state, used once counting. */
    fun update(pose: Pose?, counter: CounterState, nowMs: Long): CoachTick = when (phase) {
        CoachPhase.Setup -> setup(pose, nowMs)
        CoachPhase.Countdown -> countdown(pose, nowMs)
        CoachPhase.Counting -> count(counter, nowMs)
    }

    /** What, if anything, keeps [pose] from being the start position. */
    fun issue(pose: Pose?): SetupIssue? {
        if (pose == null) return SetupIssue.NoBody
        val check = probe.readiness(pose)
        if (check.readiness == Readiness.OutOfView) return SetupIssue.StepBack
        turnIssue(pose)?.let { return it }
        return if (check.readiness == Readiness.Ready) null else SetupIssue.StartPosition
    }

    private fun turnIssue(pose: Pose): SetupIssue? = when (type.view) {
        CameraView.SideOn -> if (sideOn(pose)) null else SetupIssue.TurnSideways
        CameraView.Facing -> if (facing(pose)) null else SetupIssue.FaceCamera
        // Measured in space, any angle shows the knees bend; across the picture only side-on does.
        CameraView.Either -> if (pose.hasDepth || sideOn(pose)) null else SetupIssue.TurnSideways
    }

    private fun setup(pose: Pose?, nowMs: Long): CoachTick {
        val issue = issue(pose)
        if (issue == null) {
            val since = readySinceMs ?: nowMs.also { readySinceMs = it; starts.clear() }
            remember(pose)
            if (nowMs - since >= readyHoldMs) {
                phase = CoachPhase.Countdown
                countdownStartMs = nowMs
                lastCountdownSaid = -1
                missSinceMs = null
                val cue = tr("Perfect. Hold it there.")
                return CoachTick(CoachState(CoachPhase.Countdown, cue, COUNT_FROM, inFrame = true, code = LiveCues.countdown(COUNT_FROM)), say(cue, nowMs))
            }
            return CoachTick(CoachState(CoachPhase.Setup, tr("That's it. Hold still."), inFrame = true, code = LiveCues.READY))
        }
        readySinceMs = null
        val fault = startFault(issue, pose)
        val cue = message(issue, fault)
        return CoachTick(CoachState(CoachPhase.Setup, cue, inFrame = issue.inFrame, code = LiveCues.setup(issue, fault)), settle(cue, nowMs))
    }

    private fun countdown(pose: Pose?, nowMs: Long): CoachTick {
        val issue = issue(pose)
        if (issue != null) {
            val since = missSinceMs ?: nowMs.also { missSinceMs = it }
            if (nowMs - since > GRACE_MS) {
                // Moved out of position before "Go": back to getting set, saying what to fix.
                phase = CoachPhase.Setup
                readySinceMs = null
                starts.clear()
                val fault = startFault(issue, pose)
                val cue = message(issue, fault)
                pendingCue = cue; pendingSinceMs = nowMs
                return CoachTick(CoachState(CoachPhase.Setup, cue, inFrame = issue.inFrame, code = LiveCues.setup(issue, fault)), say(cue, nowMs))
            }
        } else {
            missSinceMs = null
            remember(pose)
        }
        val elapsed = nowMs - countdownStartMs - LEAD_MS
        if (elapsed >= COUNT_FROM * TICK_MS) {
            phase = CoachPhase.Counting
            calibration = starts.takeIf { it.isNotEmpty() }?.sorted()?.let { it[it.size / 2] }
            return CoachTick(CoachState(CoachPhase.Counting), say(go(), nowMs))
        }
        val shown = if (elapsed < 0) COUNT_FROM else COUNT_FROM - (elapsed / TICK_MS).toInt()
        val speak = if (elapsed >= 0 && shown != lastCountdownSaid) { lastCountdownSaid = shown; say("$shown", nowMs) } else null
        return CoachTick(CoachState(CoachPhase.Countdown, tr("Hold it there."), shown, inFrame = issue?.inFrame ?: true, code = LiveCues.countdown(shown)), speak)
    }

    private fun remember(pose: Pose?) {
        if (pose == null || starts.size >= MAX_STARTS) return
        probe.startReading(pose)?.let { starts += it }
    }

    private fun go(): String = when (type) {
        ExerciseType.Plank, ExerciseType.SidePlank -> tr("Go! Hold the line.")
        ExerciseType.WallSit -> tr("Go! Hold it.")
        else -> tr("Go!")
    }

    private fun count(counter: CounterState, nowMs: Long): CoachTick {
        val state = CoachState(CoachPhase.Counting)
        if (finished) return CoachTick(state)
        if (counter.count >= target) {
            finished = true
            return CoachTick(state, say(tr("Done! Well done."), nowMs))
        }
        if (counter.count > lastCount) {
            val previous = lastCount
            lastCount = counter.count
            // Out loud: every rep; every few of the quick ones (high knees, mountain climbers), which a voice can't keep up with; every ten seconds of a hold.
            val every = if (type.measure == Measure.Seconds) 10 else if (type.minSecondsPerUnit < FAST_PACE) 5 else 1
            val say = (counter.count / every).takeIf { it > previous / every }?.let { "${it * every}" }
            if (say != null) return CoachTick(state, say(say, nowMs))
        }
        val tip = counter.cue
        if (tip == null) lastTip = null
        else if (tip != lastTip && nowMs - lastSaidAtMs >= TIP_GAP_MS) {
            lastTip = tip
            return CoachTick(state, say(tip, nowMs))
        }
        if (counter.inFrame) { outSinceMs = null; askedBack = false }
        else {
            val since = outSinceMs ?: nowMs.also { outSinceMs = it }
            if (!askedBack && nowMs - since >= OUT_MS) {
                askedBack = true
                return CoachTick(state, say(message(SetupIssue.StepBack), nowMs))
            }
        }
        return CoachTick(state)
    }

    /** Says a new instruction once it has held for a moment (no chatter while someone moves), and again every few seconds. */
    private fun settle(cue: String, nowMs: Long): String? {
        if (cue != pendingCue) { pendingCue = cue; pendingSinceMs = nowMs }
        return when {
            cue != lastSaid && nowMs - pendingSinceMs >= SETTLE_MS -> say(cue, nowMs)
            cue == lastSaid && nowMs - lastSaidAtMs >= REPEAT_MS -> say(cue, nowMs)
            else -> null
        }
    }

    private fun say(text: String, nowMs: Long): String { lastSaid = text; lastSaidAtMs = nowMs; return text }

    /** The fault behind a start-position instruction (a plank's hips too low), when there is one. */
    private fun startFault(issue: SetupIssue, pose: Pose?): FaultKind? =
        if (issue == SetupIssue.StartPosition && pose != null) probe.readiness(pose).fault else null

    /** The instruction for [issue], in the person's language; [fault] says what's off about the start position. */
    fun message(issue: SetupIssue, fault: FaultKind? = null): String = when (issue) {
        SetupIssue.NoBody -> if (type.onFloor) tr("Prop the phone on the floor a couple of metres away, side-on, and get in the picture.")
            else tr("Step in front of the camera so it can see you.")
        SetupIssue.StepBack -> if (type.onFloor) tr("Move the phone further away so your whole body is in the picture.")
            else tr("Step back so your whole body is in the picture.")
        SetupIssue.TurnSideways -> tr("Turn side-on to the camera.")
        SetupIssue.FaceCamera -> tr("Turn to face the camera.")
        SetupIssue.StartPosition -> start(fault)
    }

    private fun start(fault: FaultKind?): String {
        // A hold's start is the hold itself: say which way to move (hips up or down, down to level, back to the wall).
        if (fault == FaultKind.HipsLow || fault == FaultKind.HipsHigh || (type == ExerciseType.WallSit && fault == FaultKind.Posture)) return FormCues.message(type, fault)
        return when (type) {
            ExerciseType.Squat -> tr("Stand up tall with your feet apart.")
            ExerciseType.PushUp -> tr("Get into the top of a push-up: arms straight, body in one line.")
            ExerciseType.Plank -> tr("Get into a plank on your elbows, body in one straight line.")
            ExerciseType.SitUp -> tr("Lie on your back with your knees bent.")
            ExerciseType.ArmRaise -> tr("Stand tall with your arms down by your sides.")
            ExerciseType.JumpingJack -> tr("Stand tall, feet together and arms by your sides.")
            ExerciseType.HighKnees -> tr("Stand tall with both feet on the floor.")
            ExerciseType.Lunge -> tr("Stand tall with your feet together.")
            ExerciseType.GluteBridge -> tr("Lie on your back, knees bent and feet flat on the floor.")
            ExerciseType.MountainClimber -> tr("Get into a high plank: arms straight, body in one line.")
            ExerciseType.Burpee -> tr("Stand up tall, feet apart.")
            ExerciseType.WallSit -> tr("Back against a wall, then slide down until your thighs are level.")
            ExerciseType.SidePlank -> tr("Lie on your side facing the camera, up on one forearm, hips lifted.")
        }
    }

    companion object {
        const val COUNT_FROM = 3
        /** From "Perfect. Hold it there." to "3". */
        const val LEAD_MS = 1_400L
        const val TICK_MS = 1_000L
        /** Out of position this long during the countdown starts getting set again. */
        const val GRACE_MS = 700L
        /** An instruction must hold this long before it is said. */
        const val SETTLE_MS = 700L
        /** An instruction still not followed is said again after this long. */
        const val REPEAT_MS = 6_000L
        /** At least this long after the last thing said before a form tip. */
        const val TIP_GAP_MS = 1_200L
        /** Out of the picture this long while counting, the coach asks the person back. */
        const val OUT_MS = 2_500L
        /** Exercises quicker than this many seconds a rep are counted aloud every five. */
        const val FAST_PACE = 0.3
        /** Readings of the start position kept for the median. */
        const val MAX_STARTS = 120
        /** Measured across the picture, side-on: at most this much shoulder width per torso length (about 45 degrees from side-on). */
        const val SIDE_ON_MAX = 0.5f
        /** Facing exercises: at least this much. */
        const val FACING_MIN = 0.35f
        /** Measured in space, side-on: at most this share of the shoulder line lies across the picture (45 degrees). */
        const val SIDE_ON_SQUARE_MAX = 0.71f
        /** Facing: at least this share (60 degrees). */
        const val FACING_SQUARE_MIN = 0.5f
        /** Facing, in space: at least this share of the trunk lies across the picture. */
        const val TRUNK_ACROSS_MIN = 0.6f

        /**
         * How square to the camera the body is. In space: the share of the line between the shoulders that lies across the
         * picture rather than toward the camera (1 facing it, 0 side-on), whatever the body's height or whether it lies
         * down. Across the picture: shoulder width over torso length (facing about 0.7, falling toward 0 side-on).
         */
        fun squareness(pose: Pose): Float {
            if (pose.hasDepth) {
                val d = pose.at(Pose.R_SHOULDER) - pose.at(Pose.L_SHOULDER)
                return if (d.length < 1e-6f) 0f else hypot(d.x, d.y) / d.length
            }
            val shoulders = pose.pictureDistance(Pose.L_SHOULDER, Pose.R_SHOULDER)
            val mx = (pose[Pose.L_SHOULDER].x + pose[Pose.R_SHOULDER].x - pose[Pose.L_HIP].x - pose[Pose.R_HIP].x) / 2 * pose.aspect
            val my = (pose[Pose.L_SHOULDER].y + pose[Pose.R_SHOULDER].y - pose[Pose.L_HIP].y - pose[Pose.R_HIP].y) / 2
            return shoulders / hypot(mx, my).coerceAtLeast(1e-3f)
        }

        fun sideOn(pose: Pose) = squareness(pose) <= if (pose.hasDepth) SIDE_ON_SQUARE_MAX else SIDE_ON_MAX

        /**
         * Square to the camera, and (measured in space) with the trunk across the picture rather than pointing at the
         * camera: a side plank lying head-first toward the phone has its shoulders stacked like one facing it.
         */
        fun facing(pose: Pose): Boolean {
            if (!pose.hasDepth) return squareness(pose) >= FACING_MIN
            val trunk = pose.mid(Pose.L_SHOULDER, Pose.R_SHOULDER) - pose.mid(Pose.L_HIP, Pose.R_HIP)
            return squareness(pose) >= FACING_SQUARE_MIN && (trunk.length < 1e-6f || hypot(trunk.x, trunk.y) / trunk.length >= TRUNK_ACROSS_MIN)
        }
    }
}

private val SetupIssue.inFrame: Boolean get() = this != SetupIssue.NoBody && this != SetupIssue.StepBack
