package app.heartpact.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import app.heartpact.data.FoodDay
import app.heartpact.data.FoodEntry
import app.heartpact.data.FoodEntrySource
import app.heartpact.data.FoodPartnerDay
import app.heartpact.data.FoodScanResult
import app.heartpact.data.FoodShareLevel
import app.heartpact.data.FoodTotals
import app.heartpact.data.MealType
import app.heartpact.data.NutritionTarget
import app.heartpact.ui.album.PhotoSlot
import app.heartpact.ui.food.FoodEntryScreen
import app.heartpact.ui.food.FoodEntryUi
import app.heartpact.ui.food.FoodScanScreen
import app.heartpact.ui.food.FoodScanUi
import app.heartpact.ui.food.FoodTab
import app.heartpact.ui.food.FoodUi
import app.heartpact.ui.food.ScanPhase
import app.heartpact.ui.home.HomeScreen
import app.heartpact.ui.home.Tab
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class FoodShotsTest {
    private val plate: PhotoSlot = { _, _, modifier, _ -> Box(modifier.background(Brush.radialGradient(listOf(Color(0xFFF6D365), Color(0xFF7FB77E), Color(0xFF4A6B4F))))) }
    private val toast = FoodEntry("a", "maya", "2026-10-10", MealType.Breakfast, "French toast, berries", 640, 540, 760, source = FoodEntrySource.PhotoEstimate, loggedAtUtc = "2026-10-10T07:40:00Z")
    private val bowl = FoodEntry("b", "maya", "2026-10-10", MealType.Lunch, "Chickpea salad bowl", 600, 480, 700, source = FoodEntrySource.PhotoEstimate, loggedAtUtc = "2026-10-10T11:50:00Z")
    private val day = FoodDay(
        "2026-10-10", NutritionTarget(dailyCalories = 1900), FoodTotals(1240, entryCount = 2, caloriesLow = 1020, caloriesHigh = 1460), 660, listOf(toast, bowl),
        FoodPartnerDay("theo", "Theo", FoodShareLevel.TotalsOnly, 2400, FoodTotals(1610, entryCount = 3, caloriesLow = 1370, caloriesHigh = 1850)),
        scansUsedToday = 1, scanLimitPerDay = 3,
    )

    @Composable private fun Food(ui: FoodUi) = HomeScreen("Maya", "Theo", {}, initialTab = Tab.Food, food = {
        FoodTab(ui, "Theo", {}, {}, {}, {}, { _, _ -> }, today = LocalDate.of(2026, 10, 10))
    })

    @Test fun food() {
        shot("food") { Food(FoodUi(day)) }
        shot("food-empty") { Food(FoodUi(day.copy(entries = emptyList(), totals = FoodTotals(), partner = day.partner!!.copy(shareLevel = FoodShareLevel.None, totals = null)))) }
        shot("food-night", night = true) { Food(FoodUi(day)) }
    }

    @Test fun scan() {
        val scan = FoodScanResult("s", "Salmon poke bowl", 610, 520, 700, 34.0, 62.0, 22.0, 0.7, scansRemainingToday = 2)
        shot("food-scan") { FoodScanScreen(FoodScanUi(phase = ScanPhase.Ready(scan), meal = MealType.Lunch), null, {}, {}, {}, {}, {}, {}, image = plate) }
        shot("food-scan-reading") { FoodScanScreen(FoodScanUi(), "2 of 3 photo estimates left today", {}, {}, {}, {}, {}, {}, image = plate) }
        shot("food-scan-unavailable") { FoodScanScreen(FoodScanUi(phase = ScanPhase.Failed("food.estimation_unavailable", "Photo estimates are not set up on this server. Log the meal by hand.")), null, {}, {}, {}, {}, {}, {}, image = plate) }
    }

    @Test fun entry() {
        shot("food-entry") { FoodEntryScreen(FoodEntryUi(description = "Jollof rice and chicken", meal = MealType.Dinner, calories = "720"), {}, {}, {}, {}, {}, {}) }
        shot("food-entry-edit") { FoodEntryScreen(FoodEntryUi(existing = toast, description = toast.description, meal = toast.mealType, calories = "640"), {}, {}, {}, {}, {}, {}) }
    }
}
