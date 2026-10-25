package app.heartpact

import app.heartpact.data.CycleDayLog
import app.heartpact.data.CyclePrediction
import app.heartpact.data.FlowLevel
import app.heartpact.ui.cycle.CyclePlan
import app.heartpact.ui.cycle.DayKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CyclePlanTest {
    private val today = LocalDate.of(2026, 10, 11)
    private val prediction = CyclePrediction("2026-10-28", "2026-10-09", "2026-11-01", "2026-10-14", "2026-09-30", 12)

    @Test fun `the strip is the cycle so far and to come, then the predicted period`() {
        val strip = CyclePlan.strip(prediction, emptyList(), 5, today)
        assertEquals(28 + 5, strip.size)
        assertEquals(List(5) { DayKind.Period }, strip.take(5).map { it.kind })
        assertEquals(DayKind.Fertile, strip.first { it.date == LocalDate.of(2026, 10, 9) }.kind)
        assertEquals(DayKind.Plain, strip.first { it.date == LocalDate.of(2026, 10, 15) }.kind)
        assertTrue(strip.single { it.today }.date == today)
        assertEquals(List(5) { DayKind.Predicted }, strip.takeLast(5).map { it.kind })
    }

    @Test fun `logged flow replaces the assumed period`() {
        val logs = listOf("2026-09-30", "2026-10-01", "2026-10-02").map { CycleDayLog(it, FlowLevel.Medium) }
        val strip = CyclePlan.strip(prediction, logs, 5, today)
        assertEquals(3, strip.count { it.kind == DayKind.Period })
    }

    @Test fun `a rolled-forward cycle draws its assumed period as a prediction, not as one that happened`() {
        // The server rolled 2 Sep forward to 30 Sep: nothing says the period on 30 Sep came.
        val rolled = prediction.copy(lastPeriodStart = "2026-09-02")
        assertEquals(List(5) { DayKind.Predicted }, CyclePlan.strip(rolled, emptyList(), 5, today).take(5).map { it.kind })
        // The real last start (logged, or set by the owner) is drawn as a period.
        assertEquals(List(5) { DayKind.Period }, CyclePlan.strip(prediction.copy(lastPeriodStart = "2026-09-30"), emptyList(), 5, today).take(5).map { it.kind })
    }

    @Test fun `a late period runs the strip to today with no period drawn where it was due`() {
        val lateToday = LocalDate.of(2026, 10, 31)
        val late = prediction.copy(lastPeriodStart = "2026-09-30", cycleDay = 32, daysLate = 3)
        val logs = listOf("2026-09-30", "2026-10-01", "2026-10-02").map { CycleDayLog(it, FlowLevel.Medium) }
        val strip = CyclePlan.strip(late, logs, 5, lateToday)
        assertEquals(lateToday, strip.last().date)
        assertTrue(strip.last().today)
        assertTrue(strip.none { it.kind == DayKind.Predicted })
        assertEquals(DayKind.Plain, strip.first { it.date == LocalDate.of(2026, 10, 28) }.kind)
        assertEquals("Period late by 3 days.", CyclePlan.summary(late, 28))
        assertEquals("Period due today.", CyclePlan.summary(late.copy(daysLate = 0), 28))
    }

    @Test fun `past days can be logged for two months`() {
        assertTrue(CyclePlan.canLog(today, today))
        assertTrue(CyclePlan.canLog(today.minusDays(60), today))
        assertFalse(CyclePlan.canLog(today.minusDays(61), today))
        assertFalse(CyclePlan.canLog(today.plusDays(1), today))
        assertEquals("Today", CyclePlan.dayTitle(today, today))
        assertEquals("Yesterday", CyclePlan.dayTitle(today.minusDays(1), today))
        assertEquals("Thursday 8 October", CyclePlan.dayTitle(LocalDate.of(2026, 10, 8), today))
    }

    @Test fun `settings open on the start the forecast counts from`() {
        assertEquals("2026-10-02", CyclePlan.lastStartForEditing(prediction.copy(lastPeriodStart = "2026-10-02"), "2026-09-01"))
        assertEquals("2026-09-01", CyclePlan.lastStartForEditing(CyclePrediction(), "2026-09-01"))
    }

    @Test fun `wording`() {
        assertEquals("Of about 28 days. Next period likely 27–29 October.", CyclePlan.summary(prediction, 28))
        assertEquals("31 October–2 November", CyclePlan.likely(LocalDate.of(2026, 11, 1)))
        assertEquals("Day 12 · Theo sees predictions", CyclePlan.usRow(true, 12, app.heartpact.data.CycleShare.PredictionsOnly, "Theo", null))
        assertEquals("Day 12 of Maya's cycle", CyclePlan.usRow(false, null, null, "Maya", 12))
    }
}
