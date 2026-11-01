package app.heartpact.exercise

import app.heartpact.i18n.tr
import kotlin.math.roundToInt

/**
 * Watching live (docs/couple-goals.md): while one partner works out on camera, the other sees a stick figure, never
 * video. These are the only things that leave the exerciser's phone for it: [Pose.FIGURE]'s joints in the picture,
 * rounded, and the numbers around them. Nothing is stored on the way.
 */
enum class LivePhase(val wire: String) {
    Setup("setup"), Countdown("countdown"), Counting("counting"), Done("done");

    companion object {
        fun of(wire: String?): LivePhase? = entries.firstOrNull { it.wire == wire }
    }
}

/** One moment of the workout as the partner sees it. [joints] follows [Pose.FIGURE]; null is a joint the camera can't see. */
data class LiveFrame(
    val count: Int,
    val phase: LivePhase,
    val cue: String?,
    /** The picture's width over its height, so the figure keeps its proportions. */
    val aspect: Float,
    val joints: List<Landmark?>,
) {
    val hasBody: Boolean get() = joints.any { it != null }
}

/** Joints as whole numbers: x and y in thousandths of the picture, -1 for a joint the camera can't see. */
object LiveCodec {
    const val SCALE = 1_000
    const val HIDDEN = -1
    /** A joint below this visibility isn't sent: the partner sees what the camera sees, no guesses. */
    const val MIN_VISIBILITY = 0.5f
    val SIZE = Pose.FIGURE.size * 2

    /** The figure's joints from a pose, in [Pose.FIGURE] order: null for one the camera can't see (or nobody there). */
    fun figure(pose: Pose?): List<Landmark?> {
        if (pose == null || pose.landmarks.size < Pose.COUNT) return List(Pose.FIGURE.size) { null }
        return Pose.FIGURE.map { index ->
            pose[index].takeIf { it.visibility >= MIN_VISIBILITY && it.x in -0.05f..1.05f && it.y in -0.05f..1.05f }
                ?.let { Landmark(it.x.coerceIn(0f, 1f), it.y.coerceIn(0f, 1f)) }
        }
    }

    /** x then y for each joint, [HIDDEN] for one not seen; empty when none is seen. */
    fun encode(joints: List<Landmark?>): IntArray {
        if (joints.size != Pose.FIGURE.size || joints.all { it == null }) return IntArray(0)
        val out = IntArray(SIZE)
        joints.forEachIndexed { i, l ->
            out[i * 2] = l?.let { (it.x.coerceIn(0f, 1f) * SCALE).roundToInt() } ?: HIDDEN
            out[i * 2 + 1] = l?.let { (it.y.coerceIn(0f, 1f) * SCALE).roundToInt() } ?: HIDDEN
        }
        return out
    }

    fun decode(values: IntArray): List<Landmark?> {
        if (values.size != SIZE) return List(Pose.FIGURE.size) { null }
        return List(Pose.FIGURE.size) { i ->
            val x = values[i * 2]; val y = values[i * 2 + 1]
            if (x !in 0..SCALE || y !in 0..SCALE) null else Landmark(x / SCALE.toFloat(), y / SCALE.toFloat())
        }
    }

    /** The picture's shape as a whole number (thousandths), within what a camera can be. */
    fun aspect(aspect: Float): Int = (aspect.coerceIn(0.2f, 5f) * SCALE).roundToInt()
}

/**
 * What the exerciser's screen is telling them, as a short code the partner's phone turns into words in its own
 * language ([text]): "setup.TurnSideways", "count.2", "tip.Shallow", "checkIn.RaiseLeft". Only these codes are sent.
 */
object LiveCues {
    const val READY = "ready"
    const val OUT_OF_FRAME = "out"

    fun setup(issue: SetupIssue, fault: FaultKind? = null) = "setup." + issue.name + (fault?.let { "." + it.name } ?: "")
    fun countdown(shown: Int) = "count.$shown"
    fun tip(kind: FaultKind) = "tip." + kind.name
    fun checkIn(kind: ChallengeKind) = "checkIn." + kind.name

    /** What the exerciser is being told, for the partner to read; null for an unknown or empty code. */
    fun text(type: ExerciseType, code: String?): String? {
        if (code.isNullOrBlank()) return null
        val parts = code.split('.')
        return when (parts[0]) {
            READY -> tr("That's it. Hold still.")
            OUT_OF_FRAME -> tr("Step back so your whole body is in the picture.")
            "count" -> tr("Hold it there.")
            "setup" -> {
                val issue = parts.getOrNull(1)?.let { name -> SetupIssue.entries.firstOrNull { it.name == name } } ?: return null
                val fault = parts.getOrNull(2)?.let { name -> FaultKind.entries.firstOrNull { it.name == name } }
                PoseCoach(type, 1).message(issue, fault)
            }
            "tip" -> parts.getOrNull(1)?.let { name -> FaultKind.entries.firstOrNull { it.name == name } }?.let { FormCues.message(type, it) }
            "checkIn" -> parts.getOrNull(1)?.let { name -> ChallengeKind.entries.firstOrNull { it.name == name } }?.let { ChallengeUi(it, 0, 0f).title }
            else -> null
        }
    }

    /** The countdown number in a "count.N" code. */
    fun countdownOf(code: String?): Int? = code?.takeIf { it.startsWith("count.") }?.substringAfter('.')?.toIntOrNull()

    /**
     * The code for what the camera screen shows right now: the coach's instruction while getting set, then a check-in,
     * a form tip, or being out of the picture.
     */
    fun of(coach: CoachState?, counter: CounterState, challenge: ChallengeUi?): String? {
        if (coach != null && coach.phase != CoachPhase.Counting) return coach.code
        if (challenge != null && challenge.passed == null) return checkIn(challenge.kind)
        counter.fault?.let { return tip(it) }
        return if (!counter.inFrame) OUT_OF_FRAME else null
    }
}

/** What the partner sends to cheer the exerciser on, shown on their camera screen. */
enum class Cheer(val wire: String) {
    Heart("heart"), GotThis("gotThis"), AlmostThere("almostThere");

    /** The words on the exerciser's screen (a heart says nothing). */
    val words: String? get() = when (this) {
        Heart -> null
        GotThis -> tr("You've got this!")
        AlmostThere -> tr("Almost there!")
    }

    companion object {
        fun of(wire: String?): Cheer? = entries.firstOrNull { it.wire == wire }
    }
}

/** A cheer to show on the camera, from [from]: [id] grows with each one, so the same cheer twice still shows twice. */
data class CheerShown(val cheer: Cheer, val from: String, val id: Long)

/** The phase to show the partner, from the coach and the count. */
fun livePhase(coach: CoachState?, count: Int, target: Int): LivePhase = when {
    count >= target -> LivePhase.Done
    coach == null || coach.phase == CoachPhase.Counting -> LivePhase.Counting
    coach.phase == CoachPhase.Countdown -> LivePhase.Countdown
    else -> LivePhase.Setup
}
