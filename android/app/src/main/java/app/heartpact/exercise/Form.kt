package app.heartpact.exercise

/** Why a movement didn't count, or why a hold stopped. */
enum class FaultKind {
    /** Went part of the way toward the active position and came back. */
    Shallow,
    /** Started the next rep without getting back to the start position. */
    NotAllTheWay,
    /** Reached the position with the body out of line: chest dropping in a squat, hips sagging in a push-up. */
    Posture,
    /** Faster than the exercise can be done properly. */
    TooFast,
    /** Plank: hips below the line from shoulders to ankles. */
    HipsLow,
    /** Plank: hips above it. */
    HipsHigh,
}

/** The most recent fault and when it happened, in frame time. */
data class RepFault(val kind: FaultKind, val atMs: Long)

/** The tip shown when a movement doesn't count: what to do, not what went wrong. */
object FormCues {
    /** How long a tip stays up after the fault. */
    const val SHOW_MS = 3_000L

    fun message(type: ExerciseType, kind: FaultKind): String = when (kind) {
        FaultKind.TooFast -> "Slow down: that one was too quick to count."
        FaultKind.HipsLow -> "Lift your hips into a straight line."
        FaultKind.HipsHigh -> "Lower your hips into a straight line."
        FaultKind.Shallow -> when (type) {
            ExerciseType.Squat -> "Go lower: hips down toward knee height."
            ExerciseType.PushUp -> "Go lower: chest toward the floor."
            ExerciseType.SitUp -> "Come all the way up."
            ExerciseType.ArmRaise -> "Both hands all the way above your head."
            ExerciseType.JumpingJack -> "Arms up and feet wide, at the same time."
            ExerciseType.HighKnees -> "Knees up to hip height."
            ExerciseType.Plank -> "Get down into a straight plank."
        }
        FaultKind.NotAllTheWay -> when (type) {
            ExerciseType.Squat -> "Stand all the way up between squats."
            ExerciseType.PushUp -> "Push all the way up between reps."
            ExerciseType.SitUp -> "Lie all the way back between sit-ups."
            ExerciseType.ArmRaise -> "Bring your arms all the way down between raises."
            ExerciseType.JumpingJack -> "Feet together and arms down between jacks."
            ExerciseType.HighKnees -> "Put each foot down before the next knee."
            ExerciseType.Plank -> "Get down into a straight plank."
        }
        FaultKind.Posture -> when (type) {
            ExerciseType.Squat -> "Keep your chest up as you go down."
            ExerciseType.PushUp -> "Keep your body in one straight line."
            ExerciseType.Plank -> "Get down into a straight plank."
            else -> "Keep your body steady."
        }
    }
}
