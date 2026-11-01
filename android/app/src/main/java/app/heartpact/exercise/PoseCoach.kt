package app.heartpact.exercise

import app.heartpact.i18n.tr

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
)

/** One frame's coaching: the state to show, and a line to say out loud now, if any. */
data class CoachTick(val state: CoachState, val speak: String? = null)

/**
 * The voice coach (docs/exercise-checks.md, Getting set). Before anything counts it talks the person into the exercise's start
 * position: in the picture, turned the right way for the camera (side-on for squats, push-ups, sit-ups and planks; facing
 * it for the rest) and in the start pose. Held there for a moment, it counts down "3, 2, 1, Go!" and only then does the
 * rep counter start. While counting it says each rep (every ten seconds of a plank), a form tip when one comes up, and
 * asks the person back into the picture if they leave it.
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
    // Counting.
    private var lastCount = 0
    private var lastTip: String? = null
    private var outSinceMs: Long? = null
    private var askedBack = false
    private var finished = false

    val counting: Boolean get() = phase == CoachPhase.Counting

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
        val turn = turn(pose)
        if (type.sideOn && turn > SIDE_ON_MAX) return SetupIssue.TurnSideways
        if (!type.sideOn && turn < FACING_MIN) return SetupIssue.FaceCamera
        return if (check.readiness == Readiness.Ready) null else SetupIssue.StartPosition
    }

    private fun setup(pose: Pose?, nowMs: Long): CoachTick {
        val issue = issue(pose)
        if (issue == null) {
            val since = readySinceMs ?: nowMs.also { readySinceMs = it }
            if (nowMs - since >= readyHoldMs) {
                phase = CoachPhase.Countdown
                countdownStartMs = nowMs
                lastCountdownSaid = -1
                missSinceMs = null
                return CoachTick(CoachState(CoachPhase.Countdown, tr("Perfect. Hold it there."), COUNT_FROM, inFrame = true), say(tr("Perfect. Hold it there."), nowMs))
            }
            return CoachTick(CoachState(CoachPhase.Setup, tr("That's it. Hold still."), inFrame = true))
        }
        readySinceMs = null
        val cue = message(issue, pose)
        return CoachTick(CoachState(CoachPhase.Setup, cue, inFrame = issue.inFrame), settle(cue, nowMs))
    }

    private fun countdown(pose: Pose?, nowMs: Long): CoachTick {
        val issue = issue(pose)
        if (issue != null) {
            val since = missSinceMs ?: nowMs.also { missSinceMs = it }
            if (nowMs - since > GRACE_MS) {
                // Moved out of position before "Go": back to getting set, saying what to fix.
                phase = CoachPhase.Setup
                readySinceMs = null
                val cue = message(issue, pose)
                pendingCue = cue; pendingSinceMs = nowMs
                return CoachTick(CoachState(CoachPhase.Setup, cue, inFrame = issue.inFrame), say(cue, nowMs))
            }
        } else missSinceMs = null
        val elapsed = nowMs - countdownStartMs - LEAD_MS
        if (elapsed >= COUNT_FROM * TICK_MS) {
            phase = CoachPhase.Counting
            return CoachTick(CoachState(CoachPhase.Counting), say(if (type.measure == Measure.Seconds) tr("Go! Hold the line.") else tr("Go!"), nowMs))
        }
        val shown = if (elapsed < 0) COUNT_FROM else COUNT_FROM - (elapsed / TICK_MS).toInt()
        val speak = if (elapsed >= 0 && shown != lastCountdownSaid) { lastCountdownSaid = shown; say("$shown", nowMs) } else null
        return CoachTick(CoachState(CoachPhase.Countdown, tr("Hold it there."), shown, inFrame = issue?.inFrame ?: true), speak)
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
            val say = if (type.measure == Measure.Seconds) {
                (counter.count / 10).takeIf { it > previous / 10 }?.let { "${it * 10}" }
            } else "${counter.count}"
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
                return CoachTick(state, say(message(SetupIssue.StepBack, null), nowMs))
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

    /** The instruction for [issue], in the person's language. */
    fun message(issue: SetupIssue, pose: Pose?): String = when (issue) {
        SetupIssue.NoBody -> if (type.onFloor) tr("Prop the phone on the floor a couple of metres away, side-on, and get in the picture.")
            else tr("Step in front of the camera so it can see you.")
        SetupIssue.StepBack -> if (type.onFloor) tr("Move the phone further away so your whole body is in the picture.")
            else tr("Step back so your whole body is in the picture.")
        SetupIssue.TurnSideways -> tr("Turn side-on to the camera.")
        SetupIssue.FaceCamera -> tr("Turn to face the camera.")
        SetupIssue.StartPosition -> start(pose)
    }

    private fun start(pose: Pose?): String {
        if (type == ExerciseType.Plank) {
            when (pose?.let { probe.readiness(it).fault }) {
                FaultKind.HipsLow -> return tr("Lift your hips into a straight line.")
                FaultKind.HipsHigh -> return tr("Lower your hips into a straight line.")
                else -> Unit
            }
        }
        return when (type) {
            ExerciseType.Squat -> tr("Stand up tall with your feet apart.")
            ExerciseType.PushUp -> tr("Get into the top of a push-up: arms straight, body in one line.")
            ExerciseType.Plank -> tr("Get into a plank on your elbows, body in one straight line.")
            ExerciseType.SitUp -> tr("Lie on your back with your knees bent.")
            ExerciseType.ArmRaise -> tr("Stand tall with your arms down by your sides.")
            ExerciseType.JumpingJack -> tr("Stand tall, feet together and arms by your sides.")
            ExerciseType.HighKnees -> tr("Stand tall with both feet on the floor.")
        }
    }

    /**
     * How square to the camera the body is: shoulder width over torso length. Facing the camera it is about 0.7; side-on
     * the shoulders line up and it falls toward 0.
     */
    private fun turn(pose: Pose): Float {
        val shoulders = pose.distance(Pose.L_SHOULDER, Pose.R_SHOULDER)
        val midShoulder = Landmark((pose[Pose.L_SHOULDER].x + pose[Pose.R_SHOULDER].x) / 2, (pose[Pose.L_SHOULDER].y + pose[Pose.R_SHOULDER].y) / 2)
        val midHip = Landmark((pose[Pose.L_HIP].x + pose[Pose.R_HIP].x) / 2, (pose[Pose.L_HIP].y + pose[Pose.R_HIP].y) / 2)
        val torso = Pose(listOf(midShoulder, midHip), 0, pose.aspect).distance(0, 1).coerceAtLeast(1e-3f)
        return shoulders / torso
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
        /** Side-on exercises: at most this much shoulder width per torso length (about 45 degrees from side-on). */
        const val SIDE_ON_MAX = 0.5f
        /** Facing exercises: at least this much. */
        const val FACING_MIN = 0.35f
    }
}

private val SetupIssue.inFrame: Boolean get() = this != SetupIssue.NoBody && this != SetupIssue.StepBack

/** Whether the camera should see this exercise from the side: its angles are only visible that way. */
val ExerciseType.sideOn: Boolean get() = this == ExerciseType.Squat || this == ExerciseType.PushUp || this == ExerciseType.Plank || this == ExerciseType.SitUp

/** Done on the floor, with the phone propped low. */
val ExerciseType.onFloor: Boolean get() = this == ExerciseType.PushUp || this == ExerciseType.Plank || this == ExerciseType.SitUp
