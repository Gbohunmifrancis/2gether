package app.heartpact

import app.heartpact.alarm.AlarmPlan
import app.heartpact.data.ExerciseGoal
import app.heartpact.data.ExerciseOccurrence
import app.heartpact.data.ExerciseToday
import app.heartpact.data.OccurrenceStatus
import app.heartpact.exercise.ExerciseType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AlarmPlanTest {
    private val now = Instant.parse("2026-10-11T07:48:00Z")
    private val me = "maya"
    private val theo = "theo"

    private fun goal(id: String, assignee: String, setBy: String, next: String, grace: Int = 30, active: Boolean = true) =
        ExerciseGoal(id, assignee, "", setBy, ExerciseType.SitUp, "Sit-ups", 20, scheduledTime = "08:00", timeZoneId = "UTC", graceMinutes = grace, isActive = active, nextDueAtUtc = next)

    private fun occurrence(goal: String, user: String, status: OccurrenceStatus, scheduled: String, deadline: String) =
        ExerciseOccurrence("o-$goal", goal, user, exerciseType = ExerciseType.SitUp, title = "Sit-ups", target = 20, localDate = "2026-10-11", scheduledAtUtc = scheduled, deadlineUtc = deadline, status = status)

    @Test fun `only my own active dares ring on my phone, at their next due time`() {
        val today = ExerciseToday("2026-10-11T07:48:00Z", listOf(
            goal("mine", me, theo, "2026-10-11T08:00:00Z"),
            goal("his", theo, me, "2026-10-11T18:30:00Z"),
            goal("paused", me, theo, "2026-10-11T09:00:00Z", active = false),
        ))
        val plan = AlarmPlan.plan(today, me, "Theo", now)
        assertEquals(listOf("mine"), plan.map { it.goalId })
        val slot = plan.single()
        assertEquals(Instant.parse("2026-10-11T08:00:00Z").toEpochMilli(), slot.dueAtEpochMs)
        assertEquals(Instant.parse("2026-10-11T08:30:00Z").toEpochMilli(), slot.deadlineEpochMs)
        assertEquals("Theo", slot.setByName)
        assertNull(slot.occurrenceId)
    }

    @Test fun `a dare already ringing is planned now with the server's deadline, and the next one after`() {
        val today = ExerciseToday("x",
            listOf(goal("mine", me, me, "2026-10-12T07:40:00Z")),
            listOf(occurrence("mine", me, OccurrenceStatus.Alarming, "2026-10-11T07:40:00Z", "2026-10-11T08:10:00Z")))
        val plan = AlarmPlan.plan(today, me, "Theo", now)
        assertEquals(2, plan.size)
        assertEquals("o-mine", plan[0].occurrenceId)
        assertNull("set by me: no partner name", plan[0].setByName)
        assertEquals(listOf(plan[0]), AlarmPlan.ringingNow(plan, emptySet(), now))
        assertTrue(AlarmPlan.ringingNow(plan, setOf(plan[0].key), now).isEmpty())
    }

    @Test fun `finished or expired dares never ring`() {
        val today = ExerciseToday("x",
            listOf(goal("mine", me, theo, "2026-10-12T07:40:00Z")),
            listOf(
                occurrence("mine", me, OccurrenceStatus.Completed, "2026-10-11T07:00:00Z", "2026-10-11T07:30:00Z"),
                occurrence("mine", me, OccurrenceStatus.Alarming, "2026-10-11T07:00:00Z", "2026-10-11T07:30:00Z"),
            ))
        val plan = AlarmPlan.plan(today, me, "Theo", now)
        assertEquals(1, plan.size)
        assertTrue(AlarmPlan.ringingNow(plan, emptySet(), now).isEmpty())
    }
}
