package app.heartpact.exercise

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Mirrors the server's ExerciseCatalog (docs/couple-goals.md); the server re-checks every completion. */
@Serializable
enum class ExerciseType(val displayName: String, val measure: Measure, val defaultTarget: Int, val minTarget: Int, val maxTarget: Int, val minSecondsPerUnit: Double, val cue: String) {
    @SerialName("squat") Squat("Squats", Measure.Reps, 15, 1, 200, 0.8, "Side-on to the camera, whole body in frame."),
    @SerialName("pushUp") PushUp("Push-ups", Measure.Reps, 10, 1, 200, 0.7, "Side-on, phone on the floor a couple of metres away."),
    @SerialName("jumpingJack") JumpingJack("Jumping jacks", Measure.Reps, 20, 1, 300, 0.4, "Face the camera, arms and feet in frame."),
    @SerialName("plank") Plank("Plank", Measure.Seconds, 30, 5, 600, 0.9, "Side-on, hold a straight line from shoulders to ankles."),
    @SerialName("armRaise") ArmRaise("Arm raises", Measure.Reps, 15, 1, 300, 0.5, "Face the camera, raise both hands above your head."),
    @SerialName("highKnees") HighKnees("High knees", Measure.Reps, 30, 1, 400, 0.25, "Face the camera, bring each knee up to hip height."),
    @SerialName("sitUp") SitUp("Sit-ups", Measure.Reps, 10, 1, 200, 0.8, "Side-on, lying down, whole body in frame."),
    ;

    /** How long a completion of [target] must have taken at least, as the server checks it. */
    /** For reps the factor is seconds per rep; for holds it is the fraction of the target that must pass. Either way: factor × target seconds. */
    fun minimumMillis(target: Int): Long = (minSecondsPerUnit * target * 1000).toLong()

    fun label(target: Int): String = if (measure == Measure.Seconds) "${displayName.lowercase()}, ${target} seconds" else "$target ${displayName.lowercase()}"
}

@Serializable
enum class Measure { @SerialName("reps") Reps, @SerialName("seconds") Seconds }
