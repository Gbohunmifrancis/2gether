package app.heartpact.exercise

import app.heartpact.i18n.tr

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
    /** Not in the exercise's position at all (standing for push-ups, sitting up for sit-ups). Only a tip, not a fault count. */
    Position,
}

/** The most recent fault and when it happened, in frame time. */
data class RepFault(val kind: FaultKind, val atMs: Long)

/** The tip shown when a movement doesn't count: what to do, not what went wrong. */
object FormCues {
    /** How long a tip stays up after the fault. */
    const val SHOW_MS = 3_000L

    fun message(type: ExerciseType, kind: FaultKind): String = when (kind) {
        FaultKind.TooFast -> tr("Slow down: that one was too quick to count.")
        FaultKind.HipsLow -> tr("Lift your hips into a straight line.")
        FaultKind.HipsHigh -> tr("Lower your hips into a straight line.")
        FaultKind.Position -> when (type) {
            ExerciseType.PushUp -> tr("Get down into a push-up: body level, hands under your shoulders.")
            ExerciseType.SitUp -> tr("Lie on your back with your knees bent.")
            ExerciseType.Plank -> tr("Get down into a straight plank.")
            else -> type.cue
        }
        FaultKind.Shallow -> when (type) {
            ExerciseType.Squat -> tr("Go lower: hips down toward knee height.")
            ExerciseType.PushUp -> tr("Go lower: chest toward the floor.")
            ExerciseType.SitUp -> tr("Come all the way up.")
            ExerciseType.ArmRaise -> tr("Both hands all the way above your head.")
            ExerciseType.JumpingJack -> tr("Arms up and feet wide, at the same time.")
            ExerciseType.HighKnees -> tr("Knees up to hip height.")
            ExerciseType.Plank -> tr("Get down into a straight plank.")
        }
        FaultKind.NotAllTheWay -> when (type) {
            ExerciseType.Squat -> tr("Stand all the way up between squats.")
            ExerciseType.PushUp -> tr("Push all the way up between reps.")
            ExerciseType.SitUp -> tr("Lie all the way back between sit-ups.")
            ExerciseType.ArmRaise -> tr("Bring your arms all the way down between raises.")
            ExerciseType.JumpingJack -> tr("Feet together and arms down between jacks.")
            ExerciseType.HighKnees -> tr("Put each foot down before the next knee.")
            ExerciseType.Plank -> tr("Get down into a straight plank.")
        }
        FaultKind.Posture -> when (type) {
            ExerciseType.Squat -> tr("Keep your chest up as you go down.")
            ExerciseType.PushUp -> tr("Keep your body in one straight line.")
            ExerciseType.Plank -> tr("Get down into a straight plank.")
            else -> tr("Keep your body steady.")
        }
    }
}
