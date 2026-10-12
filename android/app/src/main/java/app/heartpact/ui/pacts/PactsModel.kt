package app.heartpact.ui.pacts

import app.heartpact.data.ExerciseGoal
import app.heartpact.data.ExerciseHistory
import app.heartpact.data.ExerciseOccurrence
import app.heartpact.data.OccurrenceStatus
import app.heartpact.data.utc
import app.heartpact.ui.today.DaySticker
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Where a dare stands today, as a word rather than a colour. */
enum class DareStatus(val label: String) { DoneToday("Done today"), Ringing("Ringing"), MissedToday("Missed today"), CalledOff("Called off today"), LaterToday("Later today"), Tomorrow("Tomorrow") }

data class PactCard(val goal: ExerciseGoal, val status: DareStatus, val days: List<Pair<LocalDate, DaySticker>>, val hearts: Int)

data class PactsView(val forMe: List<PactCard>, val bySet: List<PactCard>, val hearts: Int)

/** Pure: goals plus 30 days of history become one card per dare with a 7-day sticker strip. */
object PactsBuilder {
    fun build(goals: List<ExerciseGoal>, history: ExerciseHistory?, me: String, now: Instant, zone: ZoneId = ZoneId.systemDefault()): PactsView {
        val occurrences = history?.occurrences.orEmpty()
        val today = now.atZone(zone).toLocalDate()
        fun card(goal: ExerciseGoal): PactCard {
            val mine = occurrences.filter { it.goalId == goal.id }
            val todays = mine.filter { LocalDate.parse(it.localDate) == today }
            val status = when {
                todays.any { it.status.ringing } -> DareStatus.Ringing
                todays.any { it.status == OccurrenceStatus.Completed } -> DareStatus.DoneToday
                todays.any { it.status == OccurrenceStatus.Missed } -> DareStatus.MissedToday
                todays.any { it.status == OccurrenceStatus.Cancelled } -> DareStatus.CalledOff
                utc(goal.nextDueAtUtc).atZone(zone).toLocalDate() == today -> DareStatus.LaterToday
                else -> DareStatus.Tomorrow
            }
            val days = (6 downTo 0).map { back ->
                val date = today.minusDays(back.toLong())
                date to sticker(mine.filter { LocalDate.parse(it.localDate) == date }, date == today && status == DareStatus.LaterToday)
            }
            return PactCard(goal, status, days, mine.count { it.status == OccurrenceStatus.Completed })
        }
        val active = goals.filter { it.isActive }
        return PactsView(
            forMe = active.filter { it.assignedUserId == me }.map(::card),
            bySet = active.filter { it.assignedUserId != me }.map(::card),
            hearts = occurrences.count { it.userId == me && it.status == OccurrenceStatus.Completed },
        )
    }

    private fun sticker(day: List<ExerciseOccurrence>, pendingToday: Boolean) = when {
        day.any { it.status == OccurrenceStatus.Completed } -> DaySticker.Heart
        day.any { it.status == OccurrenceStatus.Missed } -> DaySticker.Missed
        day.any { it.status == OccurrenceStatus.Cancelled } -> DaySticker.Peeled
        day.any { it.status.ringing } || pendingToday -> DaySticker.Pending
        else -> DaySticker.Empty
    }
}
