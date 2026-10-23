package app.heartpact.ui.food

import app.heartpact.data.FoodEntry
import app.heartpact.data.FoodEntrySource
import app.heartpact.data.FoodScanResult
import app.heartpact.data.FoodShareLevel
import app.heartpact.data.FoodTotals
import app.heartpact.data.MealType
import app.heartpact.i18n.tr
import app.heartpact.i18n.trn
import java.text.NumberFormat
import java.time.LocalTime
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** How much of the photo the person actually ate. Mirrors FoodRules.ScaleRange on the server. */
enum class Portion(val factor: Double) {
    Smaller(0.75),
    AsShown(1.0),
    Larger(1.3);

    val label: String get() = when (this) {
        Smaller -> tr("Smaller")
        AsShown -> tr("As shown")
        Larger -> tr("Larger")
    }
}

/** Pure wording and numbers for the Food tab: estimates are ranges, never one made-up number. */
object FoodText {
    private val numbers get() = NumberFormat.getIntegerInstance(Locale.getDefault())
    fun n(value: Int): String = numbers.format(value)
    fun range(low: Int, high: Int): String = if (low == high) n(low) else "${n(low)}–${n(high)}"

    private val words get() = listOf(tr("no"), tr("one"), tr("two"), tr("three"), tr("four"), tr("five"), tr("six"), tr("seven"), tr("eight"), tr("nine"), tr("ten"))
    private fun count(n: Int) = words.getOrElse(n) { n.toString() }

    fun headline(entries: List<FoodEntry>, totals: FoodTotals): String {
        if (entries.isEmpty()) return tr("Nothing logged yet today")
        val photos = entries.count { it.source == FoodEntrySource.PhotoEstimate }
        val what = if (photos == entries.size) trn(photos, "one meal photo", "{number} meal photos", "number" to count(photos))
        else trn(entries.size, "one meal", "{number} meals", "number" to count(entries.size))
        return if (totals.low == totals.high) tr("{calories} kcal so far, from {meals}", "calories" to n(totals.calories), "meals" to what)
        else tr("About {range} kcal so far, from {meals}", "range" to range(totals.low, totals.high), "meals" to what)
    }

    fun caption(totals: FoodTotals, aim: Int): String = when {
        totals.entryCount == 0 -> tr("Snap a meal and you'll get a range, never a single made-up number. Your aim is about {calories} kcal.", "calories" to n(aim))
        totals.low != totals.high -> tr("Each photo is a range, so the dashed end shows where today really lands.")
        else -> tr("Your aim is about {calories} kcal.", "calories" to n(aim))
    }

    fun entryLine(entry: FoodEntry, time: String): String =
        if (entry.source == FoodEntrySource.PhotoEstimate) tr("{meal} · {time} · from a photo", "meal" to entry.mealType.label, "time" to time)
        else "${entry.mealType.label} · $time"

    fun partnerLine(level: FoodShareLevel): String = when (level) {
        FoodShareLevel.None -> tr("keeps food private")
        FoodShareLevel.TotalsOnly -> tr("shares daily totals only")
        FoodShareLevel.FullDetail -> tr("shares every meal")
    }

    fun shareChoice(level: FoodShareLevel, partner: String): String = when (level) {
        FoodShareLevel.None -> tr("Nothing: {partner} sees no food at all", "partner" to partner)
        FoodShareLevel.TotalsOnly -> tr("Daily totals: {partner} sees your day's range, not the meals", "partner" to partner)
        FoodShareLevel.FullDetail -> tr("Every meal: {partner} sees what you logged", "partner" to partner)
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

    fun grams(range: Pair<Int, Int>) =
        tr("{grams} g", "grams" to if (range.first == range.second) "${range.first}" else "${range.first}–${range.second}")

    fun scansLeft(remaining: Int, limit: Int) = when {
        limit <= 0 -> null
        remaining <= 0 -> tr("No photo estimates left today")
        else -> trn(remaining, "{count} of {limit} photo estimates left today", "{count} of {limit} photo estimates left today", "limit" to limit)
    }
}
