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

    @Test fun `a month is whole weeks from the first day of the week, edged with the months either side`() {
        val october = CyclePlan.month(java.time.YearMonth.of(2026, 10), emptyMap(), today, java.time.DayOfWeek.MONDAY)
        // 1 October 2026 is a Thursday: the week starts with 28-30 September, faint.
        assertEquals(listOf(28, 29, 30), october.first().take(3).map { it.date.dayOfMonth })
        assertTrue(october.first().take(3).none { it.inMonth })
        assertEquals(1, october.first()[3].date.dayOfMonth)
        assertTrue(october.all { it.size == 7 })
        assertEquals(31, october.flatten().count { it.inMonth })
        assertTrue(october.flatten().single { it.today }.date == today)
        val sundayFirst = CyclePlan.month(java.time.YearMonth.of(2026, 10), emptyMap(), today, java.time.DayOfWeek.SUNDAY)
        assertEquals(4, sundayFirst.first().indexOfFirst { it.inMonth })
        // A period that began on 30 September shows its first day in October's first week.
        val marked = CyclePlan.month(java.time.YearMonth.of(2026, 10), CyclePlan.marks(prediction, emptyList(), 5, today), today, java.time.DayOfWeek.MONDAY)
        assertEquals(CycleMark.Period, marked.first().first { it.date == LocalDate.of(2026, 9, 30) }.mark)
    }

    /** What a server forecast says for [date], counted from a cycle that started on [start]. */
    private fun forecastFor(start: LocalDate, date: LocalDate) = CyclePrediction(
        nextPeriodStart = start.plusDays(28).toString(), fertileWindowStart = start.plusDays(9).toString(), periodEnd = start.plusDays(32).toString(),
        fertileWindowEnd = start.plusDays(14).toString(), cycleStart = start.toString(),
        cycleDay = java.time.temporal.ChronoUnit.DAYS.between(start, date).toInt() + 1, lastPeriodStart = start.toString())

    /** The dial's number, the knob and the calendar's ringed today are always one day. */
    private fun assertAgree(prediction: CyclePrediction, today: LocalDate, clockwise: Boolean = true) {
        val ring = CyclePlan.ring(prediction, emptyList(), 5, today, clockwise)
        val knob = ring.single { it.today }
        val start = LocalDate.parse(prediction.cycleStart)
        // The knob is on today, today's day of the cycle (day 1 is the start), and sits at that day's angle.
        assertEquals(today, knob.date)
        assertEquals(java.time.temporal.ChronoUnit.DAYS.between(start, today).toInt() + 1, knob.day)
        val step = 360f / ring.size
        assertEquals(if (clockwise) -90f + (knob.day - 0.5f) * step else -90f - (knob.day - 0.5f) * step, knob.midDegrees, 0.01f)
        // The middle names that same day.
        val big = CyclePlan.dial(prediction, ring, 5, today)!!.big
        assertTrue("$big for day ${knob.day}", big == "Day ${knob.day}" || big == "Period day ${knob.day}")
        // The calendar rings that same date, and only it.
        val month = CyclePlan.month(java.time.YearMonth.from(today), CyclePlan.marks(prediction, emptyList(), 5, today), today)
        assertEquals(listOf(today), month.flatten().filter { it.today }.map { it.date })
    }

    @Test fun `the dial's day, the knob and the calendar's today always agree`() {
        val start = LocalDate.of(2026, 9, 30)
        (0L until 28L).forEach { assertAgree(forecastFor(start, start.plusDays(it)), start.plusDays(it)) }
        (0L until 28L).forEach { assertAgree(forecastFor(start, start.plusDays(it)), start.plusDays(it), clockwise = false) }
        // The fixture the screenshots use: 30 September is day 1, so 11 October is day 12, on the dial and the calendar.
        assertAgree(prediction, today)
        assertEquals("Day 12", CyclePlan.dial(prediction, CyclePlan.ring(prediction, emptyList(), 5, today), 5, today)!!.big)
    }

    @Test fun `a forecast asked for the day before or after still agrees with the phone's today`() {
        val start = LocalDate.of(2026, 9, 30)
        val day = LocalDate.of(2026, 10, 11)
        // The screen left open past midnight (the forecast is yesterday's), or a server that read the date a day on.
        assertAgree(forecastFor(start, day.minusDays(1)), day)
        assertAgree(forecastFor(start, day.plusDays(1)), day)
    }

    @Test fun `near midnight in zones far from UTC, the phone's own date is the day everywhere on the screen`() {
        val start = LocalDate.of(2026, 9, 30)
        listOf("Pacific/Kiritimati", "America/Los_Angeles", "Asia/Kolkata", "Pacific/Pago_Pago", "Africa/Lagos").forEach { zone ->
            listOf("2026-10-11T09:59:30Z", "2026-10-11T10:00:30Z", "2026-10-11T23:59:30Z", "2026-10-12T00:00:30Z", "2026-10-11T18:29:59Z", "2026-10-11T18:30:01Z").forEach { at ->
                val clock = java.time.Clock.fixed(java.time.Instant.parse(at), java.time.ZoneId.of(zone))
                val local = LocalDate.now(clock)
                // The server answers for the caller's own date (docs/cycle.md); the UTC date may be a day off.
                assertAgree(forecastFor(start, local), local)
                assertAgree(forecastFor(start, LocalDate.now(java.time.Clock.fixed(clock.instant(), java.time.ZoneOffset.UTC))), local)
            }
        }
    }
}
