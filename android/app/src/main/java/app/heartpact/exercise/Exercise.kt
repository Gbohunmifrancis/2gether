package app.heartpact.exercise

import app.heartpact.i18n.tr
import app.heartpact.i18n.trn
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Mirrors the server's ExerciseCatalog (docs/couple-goals.md); the server re-checks every completion. */
@Serializable
enum class ExerciseType(val englishName: String, val measure: Measure, val defaultTarget: Int, val minTarget: Int, val maxTarget: Int, val minSecondsPerUnit: Double, val englishCue: String) {
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

    /** The name people see, in their language ([englishName] is the English). */
    val displayName: String get() = when (this) {
        Squat -> tr("Squats")
        PushUp -> tr("Push-ups")
        JumpingJack -> tr("Jumping jacks")
        Plank -> tr("Plank")
        ArmRaise -> tr("Arm raises")
        HighKnees -> tr("High knees")
        SitUp -> tr("Sit-ups")
    }

    /** How to stand for the camera, in the person's language ([englishCue] is the English). */
    val cue: String get() = when (this) {
        Squat -> tr("Side-on to the camera, whole body in frame.")
        PushUp -> tr("Side-on, phone on the floor a couple of metres away.")
        JumpingJack -> tr("Face the camera, arms and feet in frame.")
        Plank -> tr("Side-on, hold a straight line from shoulders to ankles.")
        ArmRaise -> tr("Face the camera, raise both hands above your head.")
        HighKnees -> tr("Face the camera, bring each knee up to hip height.")
        SitUp -> tr("Side-on, lying down, whole body in frame.")
    }

    /**
     * A dare's title for people: the server stores its own English catalog name ("Plank hold") unless someone typed a
     * title, so that name is shown as this exercise's name in the phone's language.
     */
    fun titleOf(title: String): String = if (title == serverName) displayName else title

    private val serverName: String get() = when (this) {
        Squat -> "Squats"
        PushUp -> "Push-ups"
        JumpingJack -> "Jumping jacks"
        Plank -> "Plank hold"
        ArmRaise -> "Overhead arm raises"
        HighKnees -> "High knees"
        SitUp -> "Sit-ups"
    }

    /** "15 squats", "Plank, 30 seconds": one whole phrase per exercise, so every language can make the number agree. */
    fun label(target: Int): String = when (this) {
        Squat -> trn(target, "{count} squat", "{count} squats")
        PushUp -> trn(target, "{count} push-up", "{count} push-ups")
        JumpingJack -> trn(target, "{count} jumping jack", "{count} jumping jacks")
        Plank -> trn(target, "plank, {count} second", "plank, {count} seconds")
        ArmRaise -> trn(target, "{count} arm raise", "{count} arm raises")
        HighKnees -> trn(target, "{count} high knee", "{count} high knees")
        SitUp -> trn(target, "{count} sit-up", "{count} sit-ups")
    }

    /** How far along a dare is: "12 of 15 squats", or "12 of 30 seconds" for a plank. */
    fun progress(achieved: Int, target: Int): String =
        if (measure == Measure.Seconds) trn(target, "{achieved} of {count} second", "{achieved} of {count} seconds", "achieved" to achieved)
        else tr("{achieved} of {total}", "achieved" to achieved, "total" to label(target))
}

@Serializable
enum class Measure { @SerialName("reps") Reps, @SerialName("seconds") Seconds }
