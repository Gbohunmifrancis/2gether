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

    @Test fun `flow after a dry stretch, well into the cycle, starts a new period`() {
        val start = LocalDate.of(2026, 9, 30)
        assertTrue(CyclePlan.startsNewPeriod(FlowLevel.Light, LocalDate.of(2026, 10, 27), start, 5, emptyList()))
        assertFalse("still the same period", CyclePlan.startsNewPeriod(FlowLevel.Heavy, LocalDate.of(2026, 10, 2), start, 5, emptyList()))
        assertFalse("spotting is not a period", CyclePlan.startsNewPeriod(FlowLevel.Spotting, LocalDate.of(2026, 10, 27), start, 5, emptyList()))
        assertFalse("continuing yesterday's flow", CyclePlan.startsNewPeriod(FlowLevel.Light, LocalDate.of(2026, 10, 28), start, 5, listOf(CycleDayLog("2026-10-27", FlowLevel.Medium))))
    }

    @Test fun `wording`() {
        assertEquals("Of about 28 days. Next period likely 27–29 October.", CyclePlan.summary(prediction, 28))
        assertEquals("31 October–2 November", CyclePlan.likely(LocalDate.of(2026, 11, 1)))
        assertEquals("Day 12 · Theo sees predictions", CyclePlan.usRow(true, 12, app.heartpact.data.CycleShare.PredictionsOnly, "Theo", null))
        assertEquals("Day 12 of Maya's cycle", CyclePlan.usRow(false, null, null, "Maya", 12))
    }
}
