package app.heartpact

import app.heartpact.data.ExerciseGoal
import app.heartpact.data.ExerciseHistory
import app.heartpact.data.ExerciseOccurrence
import app.heartpact.data.OccurrenceStatus
import app.heartpact.exercise.ExerciseType
import app.heartpact.ui.pacts.DareStatus
import app.heartpact.ui.pacts.PactsBuilder
import app.heartpact.ui.today.DaySticker
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class PactsBuilderTest {
    private val now = Instant.parse("2026-10-11T12:20:00Z")
    private fun goal(id: String, assignee: String, next: String) = ExerciseGoal(id, assignee, "", if (assignee == "maya") "theo" else "maya", ExerciseType.SitUp, "Sit-ups", 20, scheduledTime = "08:00", timeZoneId = "UTC", nextDueAtUtc = next)
    private fun occ(goal: String, user: String, date: String, status: OccurrenceStatus) =
        ExerciseOccurrence("$goal-$date", goal, user, exerciseType = ExerciseType.SitUp, title = "Sit-ups", target = 20, localDate = date, scheduledAtUtc = "${date}T08:00:00Z", deadlineUtc = "${date}T08:30:00Z", status = status)

    @Test fun `dares split by who they are for, each with a week of stickers and a worded status`() {
        val goals = listOf(goal("mine", "maya", "2026-10-12T08:00:00Z"), goal("his", "theo", "2026-10-11T18:30:00Z"), goal("old", "maya", "2026-10-12T08:00:00Z").copy(isActive = false))
        val history = ExerciseHistory(30, listOf(
            occ("mine", "maya", "2026-10-11", OccurrenceStatus.Completed),
            occ("mine", "maya", "2026-10-10", OccurrenceStatus.Cancelled),
            occ("mine", "maya", "2026-10-08", OccurrenceStatus.Missed),
            occ("mine", "maya", "2026-10-07", OccurrenceStatus.Completed),
        ))
        val view = PactsBuilder.build(goals, history, "maya", now, ZoneOffset.UTC)
        assertEquals(listOf("mine"), view.forMe.map { it.goal.id })
        assertEquals(listOf("his"), view.bySet.map { it.goal.id })
        val mine = view.forMe.single()
        assertEquals(DareStatus.DoneToday, mine.status)
        assertEquals(listOf(DaySticker.Empty, DaySticker.Empty, DaySticker.Heart, DaySticker.Missed, DaySticker.Empty, DaySticker.Peeled, DaySticker.Heart), mine.days.map { it.second })
        assertEquals(DareStatus.LaterToday, view.bySet.single().status)
        assertEquals(DaySticker.Pending, view.bySet.single().days.last().second)
        assertEquals(2, view.hearts)
    }
}
