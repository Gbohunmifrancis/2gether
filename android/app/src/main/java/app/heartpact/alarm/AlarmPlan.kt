package app.heartpact.alarm

import app.heartpact.data.ExerciseToday
import app.heartpact.data.utc
import app.heartpact.exercise.ExerciseType
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant

/**
 * One alarm the phone should ring: a dare of mine, when it is due and until when it may ring.
 * The server owns the schedule (each goal's next due time) and the state (the day's occurrence);
 * the phone only wakes itself at those times and checks with the server once awake.
 */
@Serializable
data class AlarmSlot(
    val goalId: String,
    val title: String,
    val exerciseType: ExerciseType,
    val target: Int,
    val dueAtEpochMs: Long,
    val deadlineEpochMs: Long,
    /** Who set the dare, for "Theo's dare is ringing". Null when you set it yourself. */
    val setByName: String? = null,
    /** Present once the server has opened the day's occurrence. */
    val occurrenceId: String? = null,
) {
    /** Stable per goal and day, so a re-plan replaces rather than duplicates. */
    val key: String get() = "$goalId@$dueAtEpochMs"
}

object AlarmPlan {
    /**
     * What to ring now and what to wake for next, from the server's view of today. Only my own,
     * active goals count; a partner's dare never rings on my phone.
     */
    fun plan(today: ExerciseToday, myUserId: String, partnerName: String?, now: Instant): List<AlarmSlot> {
        val mine = today.goals.filter { it.assignedUserId == myUserId && it.isActive }
        val slots = mutableListOf<AlarmSlot>()
        for (goal in mine) {
            val setBy = if (goal.createdByUserId != myUserId) partnerName else null
            today.occurrences
                .filter { it.goalId == goal.id && it.userId == myUserId && it.status.ringing }
                .forEach { occurrence ->
                    val deadline = utc(occurrence.completeByUtc ?: occurrence.deadlineUtc)
                    if (deadline.isAfter(now)) {
                        slots += AlarmSlot(goal.id, occurrence.title, occurrence.exerciseType, occurrence.target,
                            utc(occurrence.scheduledAtUtc).toEpochMilli(), deadline.toEpochMilli(), setBy, occurrence.id)
                    }
                }
            val next = utc(goal.nextDueAtUtc)
            if (next.isAfter(now)) {
                slots += AlarmSlot(goal.id, goal.title, goal.exerciseType, goal.target, next.toEpochMilli(),
                    next.plus(Duration.ofMinutes(goal.graceMinutes.toLong())).toEpochMilli(), setBy)
            }
        }
        return slots.distinctBy { it.key }.sortedBy { it.dueAtEpochMs }
    }

    /** Slots that should be ringing at [now]: due, not past their deadline, not silenced on this phone. */
    fun ringingNow(slots: List<AlarmSlot>, silenced: Set<String>, now: Instant): List<AlarmSlot> =
        slots.filter { it.dueAtEpochMs <= now.toEpochMilli() && it.deadlineEpochMs > now.toEpochMilli() && it.key !in silenced }
}
