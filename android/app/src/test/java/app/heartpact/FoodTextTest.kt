package app.heartpact

import app.heartpact.data.FoodEntry
import app.heartpact.data.FoodEntrySource
import app.heartpact.data.FoodScanResult
import app.heartpact.data.FoodTotals
import app.heartpact.data.MealType
import app.heartpact.ui.food.FoodText
import app.heartpact.ui.food.Portion
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime

class FoodTextTest {
    private fun entry(cal: Int, low: Int? = null, high: Int? = null, photo: Boolean = low != null) =
        FoodEntry("e$cal", "", "2026-10-11", MealType.Lunch, "x", cal, low, high, source = if (photo) FoodEntrySource.PhotoEstimate else FoodEntrySource.Manual, loggedAtUtc = "2026-10-11T12:00:00Z")

    @Test fun `the headline is a range when any meal is an estimate`() {
        val photos = listOf(entry(640, 540, 760), entry(600, 480, 700))
        assertEquals("About 1,020–1,460 kcal so far, from two meal photos", FoodText.headline(photos, FoodTotals(1240, entryCount = 2, caloriesLow = 1020, caloriesHigh = 1460)))
        assertEquals("450 kcal so far, from one meal", FoodText.headline(listOf(entry(450)), FoodTotals(450, entryCount = 1, caloriesLow = 450, caloriesHigh = 450)))
        assertEquals("Nothing logged yet today", FoodText.headline(emptyList(), FoodTotals()))
    }

    @Test fun `an older server without totals ranges reads as exact`() {
        val totals = FoodTotals(calories = 900, entryCount = 2)
        assertEquals(900 to 900, totals.low to totals.high)
    }

    @Test fun `portions scale the range outward like the server and macros carry the same spread`() {
        val scan = FoodScanResult("s", "Salmon poke bowl", 610, 520, 700, proteinGrams = 34.0, carbsGrams = 62.0, fatGrams = 22.0)
        val shown = FoodText.adjust(scan, Portion.AsShown)
        assertEquals(Triple(520, 700, 610), Triple(shown.low, shown.high, shown.calories))
        assertEquals(28 to 40, shown.protein)
        val smaller = FoodText.adjust(scan, Portion.Smaller)
        assertEquals(Triple(390, 525, 458), Triple(smaller.low, smaller.high, smaller.calories))
        assertEquals(25.5, smaller.proteinGrams, 0.001)
    }

    @Test fun `the meal is guessed from the clock`() {
        assertEquals(MealType.Breakfast, FoodText.mealFor(LocalTime.of(8, 40)))
        assertEquals(MealType.Lunch, FoodText.mealFor(LocalTime.of(12, 50)))
        assertEquals(MealType.Snack, FoodText.mealFor(LocalTime.of(16, 0)))
        assertEquals(MealType.Dinner, FoodText.mealFor(LocalTime.of(19, 30)))
    }
}

class UsTextTest {
    private val q = app.heartpact.data.DailyQuestion("q", "2026-10-11", category = "future", text = "?", streakDays = 14)

    @org.junit.Test fun `question status never reveals before answering`() {
        org.junit.Assert.assertEquals("Theo answered · answer to see theirs", app.heartpact.ui.us.UsText.questionStatus(q.copy(partnerAnswered = true), "Theo"))
        org.junit.Assert.assertEquals("Not answered yet", app.heartpact.ui.us.UsText.questionStatus(q, "Theo"))
        org.junit.Assert.assertEquals("Day 14 of asking each other · plans and dreams", app.heartpact.ui.us.UsText.dayLine(q))
    }

    @org.junit.Test fun `together since reads like a date`() {
        org.junit.Assert.assertEquals("together since 2 March", app.heartpact.ui.us.UsText.togetherSince("2026-03-02T10:00:00Z", java.time.LocalDate.of(2026, 10, 11), java.time.ZoneOffset.UTC))
        org.junit.Assert.assertEquals("together since 2 March 2025", app.heartpact.ui.us.UsText.togetherSince("2025-03-02T10:00:00Z", java.time.LocalDate.of(2026, 10, 11), java.time.ZoneOffset.UTC))
    }
}
