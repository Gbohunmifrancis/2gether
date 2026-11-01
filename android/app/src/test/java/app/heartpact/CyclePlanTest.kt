package app.heartpact

import app.heartpact.data.CycleDayLog
import app.heartpact.data.CyclePrediction
import app.heartpact.data.FlowLevel
import app.heartpact.ui.cycle.CyclePlan
import app.heartpact.ui.cycle.CycleMark
import app.heartpact.ui.cycle.DayKind
import app.heartpact.ui.cycle.DialText
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

    @Test fun `a partner sees a late period by the owner's name, and only when the server says it is late`() {
        val late = prediction.copy(lastPeriodStart = "2026-09-30", cycleDay = 32, daysLate = 3)
        assertEquals("Maya's period is 3 days late.", CyclePlan.partnerSummary(late, 28, "Maya"))
        assertEquals("Maya's period is 1 day late.", CyclePlan.partnerSummary(late.copy(daysLate = 1), 28, "Maya"))
        assertEquals("Maya's period is due today.", CyclePlan.partnerSummary(late.copy(daysLate = 0), 28, "Maya"))
        // On time (or rolled forward, as for a private pregnancy): the owner's own wording, with no hint.
        assertEquals("Of about 28 days. Next period likely 27–29 October.", CyclePlan.partnerSummary(prediction, 28, "Maya"))
        assertEquals(null, CyclePlan.partnerSummary(CyclePrediction(), 28, "Maya"))
        // The Us row says the same instead of a day count that keeps climbing.
        assertEquals("Maya's period is 3 days late", CyclePlan.usRow(false, null, null, "Maya", 32, 3))
        assertEquals("Maya's period is due today", CyclePlan.usRow(false, null, null, "Maya", 29, 0))
        assertEquals("Day 12 of Maya's cycle", CyclePlan.usRow(false, null, null, "Maya", 12, null))
        // The owner's own row is unchanged by a partner value.
        assertEquals("Day 32 · private", CyclePlan.usRow(true, 32, app.heartpact.data.CycleShare.None, "Theo", null, 3))
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

    @Test fun `the dial gives each day of the cycle an equal share of the circle, day 1 at the top`() {
        val ring = CyclePlan.ring(prediction, emptyList(), 5, today)
        assertEquals(28, ring.size)
        assertEquals((1..28).toList(), ring.map { it.day })
        val step = 360f / 28
        // Segments and the gaps between them make the whole circle, and each segment starts a step after the last.
        assertEquals(360f, ring.sumOf { it.sweepDegrees.toDouble() }.toFloat() + 28 * (step - ring[0].sweepDegrees), 0.01f)
        assertEquals(-90f, ring[0].startDegrees - (step - ring[0].sweepDegrees) / 2f, 0.01f)
        ring.zipWithNext().forEach { (a, b) -> assertEquals(step, b.startDegrees - a.startDegrees, 0.01f) }
        // Today (day 12) sits where its segment's middle is: 11.5 steps round from the top.
        val now = ring.single { it.today }
        assertEquals(12, now.day)
        assertEquals(-90f + 11.5f * step, now.midDegrees, 0.01f)
    }

    @Test fun `the dial marks the period, the predicted fertile window and ovulation`() {
        val ring = CyclePlan.ring(prediction, emptyList(), 5, today)
        assertEquals(List(5) { CycleMark.Period }, ring.take(5).map { it.mark })
        // Fertile 9-14 October is days 10 to 15; ovulation is its last day.
        assertEquals(listOf(10, 11, 12, 13, 14), ring.filter { it.mark == CycleMark.Fertile }.map { it.day })
        assertEquals(15, ring.single { it.mark == CycleMark.Ovulation }.day)
        assertEquals(LocalDate.of(2026, 10, 14), ring.single { it.mark == CycleMark.Ovulation }.date)
        // A rolled-forward cycle's period is a prediction, not one that happened.
        val rolled = CyclePlan.ring(prediction.copy(lastPeriodStart = "2026-09-02"), emptyList(), 5, today)
        assertEquals(List(5) { CycleMark.PredictedPeriod }, rolled.take(5).map { it.mark })
    }

    @Test fun `right to left, the dial runs the other way round`() {
        val ltr = CyclePlan.ring(prediction, emptyList(), 5, today)
        val rtl = CyclePlan.ring(prediction, emptyList(), 5, today, clockwise = false)
        ltr.zip(rtl).forEach { (l, r) ->
            // Mirrored about the vertical through the top: -90 + a becomes -90 - a.
            assertEquals(-180f - l.midDegrees, r.midDegrees, 0.01f)
            assertEquals(l.sweepDegrees, r.sweepDegrees, 0.001f)
        }
    }

    @Test fun `a late period runs the dial on to today`() {
        val lateToday = LocalDate.of(2026, 10, 31)
        val late = prediction.copy(lastPeriodStart = "2026-09-30", cycleDay = 32, daysLate = 3)
        val ring = CyclePlan.ring(late, emptyList(), 5, lateToday)
        assertEquals(32, ring.size)
        assertTrue(ring.last().today)
        assertEquals(DialText("Day 32", "Period may be late", "3 days past the predicted start"), CyclePlan.dial(late, ring, 5, lateToday))
        assertEquals("Period due today", CyclePlan.dial(late.copy(daysLate = 0), ring, 5, lateToday)?.line)
    }

    @Test fun `the middle of the dial says where you are`() {
        val ring = CyclePlan.ring(prediction, emptyList(), 5, today)
        // 11 October is in the predicted fertile window; the next period is 28 October.
        assertEquals(DialText("Day 12", "Period in 17 days", "Fertile window, predicted"), CyclePlan.dial(prediction, ring, 5, today))
        val second = LocalDate.of(2026, 10, 1)
        val inPeriod = prediction.copy(cycleDay = 2)
        assertEquals(DialText("Period day 2", "Usually about 5 days", null, period = true), CyclePlan.dial(inPeriod, CyclePlan.ring(inPeriod, emptyList(), 5, second), 5, second))
        val ovulating = LocalDate.of(2026, 10, 14)
        assertEquals("Ovulation, predicted", CyclePlan.dial(prediction.copy(cycleDay = 15), CyclePlan.ring(prediction, emptyList(), 5, ovulating), 5, ovulating)?.note)
        val eve = LocalDate.of(2026, 10, 27)
        assertEquals("Period in 1 day", CyclePlan.dial(prediction.copy(cycleDay = 28), CyclePlan.ring(prediction, emptyList(), 5, eve), 5, eve)?.line)
        assertEquals(null, CyclePlan.dial(CyclePrediction(), emptyList(), 5, today))
    }

    @Test fun `the calendar carries the same marks, earlier logged periods and the predicted next one`() {
        val earlier = listOf("2026-09-02", "2026-09-03").map { CycleDayLog(it, FlowLevel.Light) }
        val marks = CyclePlan.marks(prediction, earlier, 5, today)
        assertEquals(CycleMark.Period, marks[LocalDate.of(2026, 9, 2)])
        assertEquals(CycleMark.Period, marks[LocalDate.of(2026, 9, 30)])
        assertEquals(CycleMark.Ovulation, marks[LocalDate.of(2026, 10, 14)])
        assertEquals(CycleMark.Fertile, marks[LocalDate.of(2026, 10, 9)])
        assertEquals(CycleMark.PredictedPeriod, marks[LocalDate.of(2026, 10, 28)])
        assertEquals(CycleMark.PredictedPeriod, marks[LocalDate.of(2026, 11, 1)])
        assertEquals(null, marks[LocalDate.of(2026, 10, 20)])
        assertEquals(java.time.YearMonth.of(2026, 9)..java.time.YearMonth.of(2026, 11), CyclePlan.months(marks, today))
    }

    @Test fun `a month is whole weeks from the first day of the week`() {
        val october = CyclePlan.month(java.time.YearMonth.of(2026, 10), emptyMap(), today, java.time.DayOfWeek.MONDAY)
        // 1 October 2026 is a Thursday: three empty squares first.
        assertEquals(listOf(null, null, null), october.first().take(3))
        assertEquals(1, october.first()[3]?.date?.dayOfMonth)
        assertTrue(october.all { it.size == 7 })
        assertEquals(31, october.flatten().count { it != null })
        assertTrue(october.flatten().single { it?.today == true }!!.date == today)
        val sundayFirst = CyclePlan.month(java.time.YearMonth.of(2026, 10), emptyMap(), today, java.time.DayOfWeek.SUNDAY)
        assertEquals(4, sundayFirst.first().indexOfFirst { it != null })
    }
}
