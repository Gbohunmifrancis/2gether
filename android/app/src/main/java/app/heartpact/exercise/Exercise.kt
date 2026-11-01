package app.heartpact.exercise

import app.heartpact.i18n.tr
import app.heartpact.i18n.trn
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Mirrors the server's ExerciseCatalog (docs/couple-goals.md); the server re-checks every completion. */
@Serializable
enum class ExerciseType(val englishName: String, val measure: Measure, val defaultTarget: Int, val minTarget: Int, val maxTarget: Int, val minSecondsPerUnit: Double, val englishCue: String) {
    @SerialName("squat") Squat("Squats", Measure.Reps, 15, 1, 200, 0.8, "Whole body in frame, side-on or at an angle."),
    @SerialName("pushUp") PushUp("Push-ups", Measure.Reps, 10, 1, 200, 0.7, "Side-on, phone on the floor a couple of metres away."),
    @SerialName("jumpingJack") JumpingJack("Jumping jacks", Measure.Reps, 20, 1, 300, 0.4, "Face the camera, arms and feet in frame."),
    @SerialName("plank") Plank("Plank", Measure.Seconds, 30, 5, 600, 0.9, "Side-on, hold a straight line from shoulders to ankles."),
    @SerialName("armRaise") ArmRaise("Arm raises", Measure.Reps, 15, 1, 300, 0.5, "Face the camera, raise both hands above your head."),
    @SerialName("highKnees") HighKnees("High knees", Measure.Reps, 30, 1, 400, 0.25, "Face the camera, bring each knee up to hip height."),
    @SerialName("sitUp") SitUp("Sit-ups", Measure.Reps, 10, 1, 200, 0.8, "Side-on, lying down, whole body in frame."),
    @SerialName("lunge") Lunge("Lunges", Measure.Reps, 20, 1, 200, 0.8, "Step forward and lower both knees, then back up. Each leg counts."),
    @SerialName("gluteBridge") GluteBridge("Glute bridges", Measure.Reps, 15, 1, 200, 0.8, "Side-on, lying on your back with your knees bent."),
    @SerialName("mountainClimber") MountainClimber("Mountain climbers", Measure.Reps, 30, 1, 400, 0.25, "Side-on in a high plank, drive each knee toward your chest."),
    @SerialName("burpee") Burpee("Burpees", Measure.Reps, 10, 1, 100, 1.2, "Side-on, whole body in frame: down to a plank, back up to standing."),
    @SerialName("wallSit") WallSit("Wall sit", Measure.Seconds, 30, 5, 600, 0.9, "Back against a wall, thighs level with the floor."),
    @SerialName("sidePlank") SidePlank("Side plank", Measure.Seconds, 20, 5, 300, 0.9, "On your side facing the camera, up on one forearm, body straight."),
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
        Lunge -> tr("Lunges")
        GluteBridge -> tr("Glute bridges")
        MountainClimber -> tr("Mountain climbers")
        Burpee -> tr("Burpees")
        WallSit -> tr("Wall sit")
        SidePlank -> tr("Side plank")
    }

    /** How to stand for the camera, in the person's language ([englishCue] is the English). */
    val cue: String get() = when (this) {
        Squat -> tr("Whole body in frame, side-on or at an angle.")
        PushUp -> tr("Side-on, phone on the floor a couple of metres away.")
        JumpingJack -> tr("Face the camera, arms and feet in frame.")
        Plank -> tr("Side-on, hold a straight line from shoulders to ankles.")
        ArmRaise -> tr("Face the camera, raise both hands above your head.")
        HighKnees -> tr("Face the camera, bring each knee up to hip height.")
        SitUp -> tr("Side-on, lying down, whole body in frame.")
        Lunge -> tr("Step forward and lower both knees, then back up. Each leg counts.")
        GluteBridge -> tr("Side-on, lying on your back with your knees bent.")
        MountainClimber -> tr("Side-on in a high plank, drive each knee toward your chest.")
        Burpee -> tr("Side-on, whole body in frame: down to a plank, back up to standing.")
        WallSit -> tr("Back against a wall, thighs level with the floor.")
        SidePlank -> tr("On your side facing the camera, up on one forearm, body straight.")
    }

    /**
     * A dare's title for people: the server stores its own English catalog name ("Plank hold") unless someone typed a
     * title, so that name is shown as this exercise's name in the phone's language.
     */
    fun titleOf(title: String): String = if (title == serverName) displayName else title

    /** The server catalog's English name (ExerciseCatalog.DisplayName), as it stores it in a dare's title. */
    val serverName: String get() = when (this) {
        Squat -> "Squats"
        PushUp -> "Push-ups"
        JumpingJack -> "Jumping jacks"
        Plank -> "Plank hold"
        ArmRaise -> "Overhead arm raises"
        HighKnees -> "High knees"
        SitUp -> "Sit-ups"
        Lunge -> "Lunges"
        GluteBridge -> "Glute bridges"
        MountainClimber -> "Mountain climbers"
        Burpee -> "Burpees"
        WallSit -> "Wall sit"
        SidePlank -> "Side plank"
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
        Lunge -> trn(target, "{count} lunge", "{count} lunges")
        GluteBridge -> trn(target, "{count} glute bridge", "{count} glute bridges")
        MountainClimber -> trn(target, "{count} mountain climber", "{count} mountain climbers")
        Burpee -> trn(target, "{count} burpee", "{count} burpees")
        WallSit -> trn(target, "wall sit, {count} second", "wall sit, {count} seconds")
        SidePlank -> trn(target, "side plank, {count} second", "side plank, {count} seconds")
    }

    /** How far along a dare is: "12 of 15 squats", or "12 of 30 seconds" for a plank. */
    fun progress(achieved: Int, target: Int): String =
        if (measure == Measure.Seconds) trn(target, "{achieved} of {count} second", "{achieved} of {count} seconds", "achieved" to achieved)
        else tr("{achieved} of {total}", "achieved" to achieved, "total" to label(target))
}

@Serializable
enum class Measure { @SerialName("reps") Reps, @SerialName("seconds") Seconds }

/**
 * Where the camera should see the body from. [SideOn]: the angles that count (an elbow on the floor, a hip lying down)
 * only show from the side. [Facing]: both arms or both legs must be seen apart. [Either]: any angle works when the
 * model measures in space (a knee bends the same seen from the front), side-on when it can only measure the picture.
 */
enum class CameraView { SideOn, Facing, Either }

val ExerciseType.view: CameraView get() = when (this) {
    ExerciseType.Squat, ExerciseType.Lunge, ExerciseType.WallSit -> CameraView.Either
    ExerciseType.PushUp, ExerciseType.Plank, ExerciseType.SitUp, ExerciseType.GluteBridge, ExerciseType.MountainClimber, ExerciseType.Burpee -> CameraView.SideOn
    ExerciseType.ArmRaise, ExerciseType.JumpingJack, ExerciseType.HighKnees, ExerciseType.SidePlank -> CameraView.Facing
}

/** Done on the floor, with the phone propped low. */
val ExerciseType.onFloor: Boolean get() = when (this) {
    ExerciseType.PushUp, ExerciseType.Plank, ExerciseType.SitUp, ExerciseType.GluteBridge, ExerciseType.MountainClimber, ExerciseType.SidePlank -> true
    else -> false
}
