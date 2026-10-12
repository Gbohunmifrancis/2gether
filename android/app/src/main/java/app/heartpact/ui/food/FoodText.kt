package app.heartpact.ui.food

import app.heartpact.data.FoodEntry
import app.heartpact.data.FoodEntrySource
import app.heartpact.data.FoodScanResult
import app.heartpact.data.FoodShareLevel
import app.heartpact.data.FoodTotals
import app.heartpact.data.MealType
import java.text.NumberFormat
import java.time.LocalTime
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** How much of the photo the person actually ate. Mirrors FoodRules.ScaleRange on the server. */
enum class Portion(val label: String, val factor: Double) {
    Smaller("Smaller", 0.75),
    AsShown("As shown", 1.0),
    Larger("Larger", 1.3),
}

/** Pure wording and numbers for the Food tab: estimates are ranges, never one made-up number. */
object FoodText {
    private val numbers get() = NumberFormat.getIntegerInstance(Locale.getDefault())
    fun n(value: Int): String = numbers.format(value)
    fun range(low: Int, high: Int): String = if (low == high) n(low) else "${n(low)}–${n(high)}"

    private val words = listOf("no", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten")
    private fun count(n: Int) = words.getOrElse(n) { n.toString() }

    fun headline(entries: List<FoodEntry>, totals: FoodTotals): String {
        if (entries.isEmpty()) return "Nothing logged yet today"
        val photos = entries.count { it.source == FoodEntrySource.PhotoEstimate }
        val what = when {
            photos == entries.size -> if (photos == 1) "one meal photo" else "${count(photos)} meal photos"
            entries.size == 1 -> "one meal"
            else -> "${count(entries.size)} meals"
        }
        return if (totals.low == totals.high) "${n(totals.calories)} kcal so far, from $what"
        else "About ${range(totals.low, totals.high)} kcal so far, from $what"
    }

    fun caption(totals: FoodTotals, aim: Int): String = when {
        totals.entryCount == 0 -> "Snap a meal and you'll get a range, never a single made-up number. Your aim is about ${n(aim)} kcal."
        totals.low != totals.high -> "Each photo is a range, so the dashed end shows where today really lands."
        else -> "Your aim is about ${n(aim)} kcal."
    }

    fun entryLine(entry: FoodEntry, time: String): String =
        "${entry.mealType.label} · $time" + if (entry.source == FoodEntrySource.PhotoEstimate) " · from a photo" else ""

    fun partnerLine(level: FoodShareLevel): String = when (level) {
        FoodShareLevel.None -> "keeps food private"
        FoodShareLevel.TotalsOnly -> "shares daily totals only"
        FoodShareLevel.FullDetail -> "shares every meal"
    }

    fun shareChoice(level: FoodShareLevel, partner: String): String = when (level) {
        FoodShareLevel.None -> "Nothing: $partner sees no food at all"
        FoodShareLevel.TotalsOnly -> "Daily totals: $partner sees your day's range, not the meals"
        FoodShareLevel.FullDetail -> "Every meal: $partner sees what you logged"
    }

    /** A guess at the meal from the clock; the person can change it. */
    fun mealFor(time: LocalTime): MealType = when {
        time < LocalTime.of(10, 30) -> MealType.Breakfast
        time < LocalTime.of(15, 0) -> MealType.Lunch
        time < LocalTime.of(17, 0) -> MealType.Snack
        time < LocalTime.of(21, 30) -> MealType.Dinner
        else -> MealType.Snack
    }

    /** The scan, adjusted for the portion: the same outward rounding as the server. */
    data class Adjusted(val low: Int, val high: Int, val calories: Int, val protein: Pair<Int, Int>, val carbs: Pair<Int, Int>, val fat: Pair<Int, Int>, val proteinGrams: Double, val carbsGrams: Double, val fatGrams: Double)

    fun adjust(scan: FoodScanResult, portion: Portion): Adjusted {
        val f = portion.factor
        val low = floor(scan.caloriesLow * f).toInt()
        val high = ceil(scan.caloriesHigh * f).toInt()
        val calories = (scan.calories * f).roundToInt().coerceIn(low, high)
        // Macros come as single values; they carry the same uncertainty as the calories.
        fun spread(grams: Double): Pair<Int, Int> {
            if (scan.calories <= 0) return grams.roundToInt() to grams.roundToInt()
            return floor(grams * f * scan.caloriesLow / scan.calories).toInt() to ceil(grams * f * scan.caloriesHigh / scan.calories).toInt()
        }
        fun round1(v: Double) = (v * 10).roundToInt() / 10.0
        return Adjusted(low, high, calories, spread(scan.proteinGrams), spread(scan.carbsGrams), spread(scan.fatGrams),
            round1(scan.proteinGrams * f), round1(scan.carbsGrams * f), round1(scan.fatGrams * f))
    }

    fun grams(range: Pair<Int, Int>) = if (range.first == range.second) "${range.first} g" else "${range.first}–${range.second} g"

    fun scansLeft(remaining: Int, limit: Int) = when {
        limit <= 0 -> null
        remaining <= 0 -> "No photo estimates left today"
        else -> "$remaining of $limit photo estimates left today"
    }
}
