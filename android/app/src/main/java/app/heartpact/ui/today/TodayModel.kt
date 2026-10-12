package app.heartpact.ui.today

import app.heartpact.data.ExerciseGoal
import app.heartpact.data.ExerciseHistory
import app.heartpact.data.ExerciseOccurrence
import app.heartpact.data.ExerciseToday
import app.heartpact.data.OccurrenceStatus
import app.heartpact.data.utc
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One day on my sticker page this week. */
enum class DaySticker { Heart, Peeled, Missed, Pending, Empty }

/** My next or ringing dare, as Today shows it. */
data class MyDare(val goal: ExerciseGoal, val occurrence: ExerciseOccurrence?, val dueAt: Instant, val setByPartner: Boolean) {
    val ringing get() = occurrence?.status?.ringing == true
}

data class TodayView(
    val mine: MyDare?,
    /** My partner's dare that is ringing right now, if any; carries their call-off request. */
    val partnerRinging: ExerciseOccurrence?,
    val partnerNext: ExerciseGoal?,
    val week: List<Pair<LocalDate, DaySticker>>,
    val hearts: Int,
)

/** Pure: turns the server's view into what Today shows. */
object TodayBuilder {
    fun build(today: ExerciseToday, history: ExerciseHistory?, me: String, now: Instant, zone: ZoneId = ZoneId.systemDefault()): TodayView {
        val myGoals = today.goals.filter { it.assignedUserId == me && it.isActive }
        val ringing = today.occurrences.firstOrNull { it.userId == me && it.status.ringing }
        val mine = if (ringing != null) {
            myGoals.firstOrNull { it.id == ringing.goalId }?.let { MyDare(it, ringing, utc(ringing.scheduledAtUtc), it.createdByUserId != me) }
        } else {
            myGoals.minByOrNull { utc(it.nextDueAtUtc) }?.let { MyDare(it, null, utc(it.nextDueAtUtc), it.createdByUserId != me) }
        }
        val partnerRinging = today.occurrences.firstOrNull { it.userId != me && it.status.ringing }
        val partnerNext = today.goals.filter { it.assignedUserId != me && it.isActive }.minByOrNull { utc(it.nextDueAtUtc) }

        val all = (history?.occurrences.orEmpty() + today.occurrences).filter { it.userId == me }.distinctBy { it.id }
        val todayDate = now.atZone(zone).toLocalDate()
        val week = (6 downTo 0).map { back ->
            val date = todayDate.minusDays(back.toLong())
            val day = all.filter { LocalDate.parse(it.localDate) == date }
            date to when {
                day.any { it.status == OccurrenceStatus.Completed } -> DaySticker.Heart
                day.any { it.status == OccurrenceStatus.Missed } -> DaySticker.Missed
                day.any { it.status == OccurrenceStatus.Cancelled } -> DaySticker.Peeled
                day.any { it.status.ringing } || (back == 0 && mine != null && mine.dueAt.atZone(zone).toLocalDate() == date) -> DaySticker.Pending
                else -> DaySticker.Empty
            }
        }
        val hearts = all.count { it.status == OccurrenceStatus.Completed }
        return TodayView(mine, partnerRinging, partnerNext, week, hearts)
    }
}
