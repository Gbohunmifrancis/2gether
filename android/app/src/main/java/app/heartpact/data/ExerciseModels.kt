package app.heartpact.data

import app.heartpact.exercise.ExerciseType
import app.heartpact.exercise.Measure
import app.heartpact.i18n.tr
import app.heartpact.i18n.trn
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

/** Wire contracts of api/exercise (docs/couple-goals.md). Timestamps stay strings and are parsed with [utc]. */

@Serializable
enum class OccurrenceStatus {
    @SerialName("alarming") Alarming,
    @SerialName("inProgress") InProgress,
    @SerialName("completed") Completed,
    @SerialName("missed") Missed,
    @SerialName("cancelled") Cancelled;

    val ringing get() = this == Alarming || this == InProgress
}

@Serializable
data class ExerciseGoal(
    val id: String,
    val assignedUserId: String,
    val assignedUserName: String = "",
    val createdByUserId: String,
    val exerciseType: ExerciseType,
    val title: String,
    val target: Int,
    val measure: Measure = Measure.Reps,
    /** Local wall clock, "HH:mm", in [timeZoneId]. */
    val scheduledTime: String,
    val timeZoneId: String,
    val graceMinutes: Int = 30,
    val isActive: Boolean = true,
    val nextDueAtUtc: String,
)

@Serializable
data class ExerciseOccurrence(
    val id: String,
    val goalId: String,
    val userId: String,
    val userName: String = "",
    val exerciseType: ExerciseType,
    val title: String,
    val target: Int,
    val measure: Measure = Measure.Reps,
    val localDate: String,
    val scheduledAtUtc: String,
    val deadlineUtc: String,
    val completeByUtc: String? = null,
    val status: OccurrenceStatus,
    val startedAtUtc: String? = null,
    val completedAtUtc: String? = null,
    val achievedCount: Int = 0,
    val confidence: Double = 0.0,
    val stateVersion: Long = 0,
    val callOffRequestedAtUtc: String? = null,
    val callOffNote: String? = null,
    /** What the camera noticed in the session that completed it. */
    val checks: OccurrenceChecks? = null,
)

/**
 * The server's view of a completed session's checks. Flags and the verdict stay strings so a flag added
 * on the server later doesn't break decoding here.
 */
@Serializable
data class OccurrenceChecks(
    val formFaults: Int = 0,
    val fastReps: Int = 0,
    val challengesPassed: Int = 0,
    val challengesMissed: Int = 0,
    val phoneMovedReps: Int = 0,
    val outOfFrameSeconds: Int = 0,
    val integrity: String = "notChecked",
    val flags: List<String> = emptyList(),
) {
    /** What the partner reads on Today: the flags first, or reps that didn't count, or null for a clean set. */
    fun partnerLine(): String? {
        val parts = flags.mapNotNull { flag ->
            when (flag) {
                "missedCheckIn" -> trn(challengesMissed, "missed a camera check-in", "missed {count} camera check-ins")
                "phoneMoved" -> trn(phoneMovedReps, "the phone was moving during {count} reps", "the phone was moving during {count} reps")
                "leftFrame" -> trn(outOfFrameSeconds, "out of view for {count} seconds", "out of view for {count} seconds")
                "unverifiedApp" -> tr("the app couldn't be verified")
                else -> null
            }
        }
        if (parts.isNotEmpty()) return tr("Worth a look: {problems}.", "problems" to parts.joinToString(", "))
        val notCounted = formFaults + fastReps
        return when {
            notCounted > 0 -> trn(notCounted, "{count} rep didn't count", "{count} reps didn't count")
            challengesPassed > 0 -> tr("Every camera check-in passed")
            else -> null
        }
    }

    val flagged: Boolean get() = flags.isNotEmpty()
}

@Serializable
data class ExerciseToday(
    val generatedAtUtc: String,
    val goals: List<ExerciseGoal> = emptyList(),
    val occurrences: List<ExerciseOccurrence> = emptyList(),
    val nextDueAtUtc: String? = null,
    val nextDueGoalId: String? = null,
)

@Serializable
data class ExercisePartnerStats(val userId: String, val userName: String = "", val currentStreakDays: Int = 0, val completedCount: Int = 0, val missedCount: Int = 0)

@Serializable
data class ExerciseHistory(val days: Int, val occurrences: List<ExerciseOccurrence> = emptyList(), val stats: List<ExercisePartnerStats> = emptyList())

@Serializable
data class CreateGoalRequest(val assignedUserId: String, val exerciseType: ExerciseType, val title: String?, val target: Int, val scheduledTime: String, val timeZoneId: String, val graceMinutes: Int = 30)

@Serializable
data class CompleteRequest(
    val achievedCount: Int,
    val confidence: Double,
    val checks: app.heartpact.exercise.ExerciseChecks? = null,
    /** A Play Integrity token for [PlayIntegrity.requestHash] of this occurrence and count. */
    val integrityToken: String? = null,
)

@Serializable
data class AskOffRequest(val note: String?)

/** The API writes UTC instants, with or without a trailing Z depending on how they were stored. */
fun utc(value: String): Instant = Instant.parse(if (value.endsWith("Z") || value.contains('+')) value else "${value}Z")
