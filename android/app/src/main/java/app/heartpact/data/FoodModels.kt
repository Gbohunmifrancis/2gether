package app.heartpact.data

import app.heartpact.i18n.tr
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire contracts of api/food (docs/food-log.md). */

@Serializable
enum class MealType {
    @SerialName("breakfast") Breakfast,
    @SerialName("lunch") Lunch,
    @SerialName("dinner") Dinner,
    @SerialName("snack") Snack;

    val label: String get() = when (this) {
        Breakfast -> tr("Breakfast")
        Lunch -> tr("Lunch")
        Dinner -> tr("Dinner")
        Snack -> tr("Snack")
    }
}

/** What my partner sees of my food. */
@Serializable
enum class FoodShareLevel { @SerialName("none") None, @SerialName("totalsOnly") TotalsOnly, @SerialName("fullDetail") FullDetail }

@Serializable
enum class FoodEntrySource { @SerialName("manual") Manual, @SerialName("photoEstimate") PhotoEstimate }

@Serializable
data class NutritionTarget(
    val userId: String = "",
    val dailyCalories: Int = 2000,
    val proteinGrams: Double? = null,
    val carbsGrams: Double? = null,
    val fatGrams: Double? = null,
    val shareLevel: FoodShareLevel = FoodShareLevel.TotalsOnly,
)

@Serializable
data class FoodEntry(
    val id: String,
    val userId: String = "",
    val localDate: String,
    val mealType: MealType,
    val description: String,
    val calories: Int,
    val caloriesLow: Int? = null,
    val caloriesHigh: Int? = null,
    val proteinGrams: Double? = null,
    val carbsGrams: Double? = null,
    val fatGrams: Double? = null,
    val source: FoodEntrySource = FoodEntrySource.Manual,
    val confidence: Double? = null,
    val scanId: String? = null,
    val loggedAtUtc: String,
) {
    val low: Int get() = caloriesLow ?: calories
    val high: Int get() = caloriesHigh ?: calories
}

@Serializable
data class FoodTotals(
    val calories: Int = 0,
    val proteinGrams: Double = 0.0,
    val carbsGrams: Double = 0.0,
    val fatGrams: Double = 0.0,
    val entryCount: Int = 0,
    val caloriesLow: Int? = null,
    val caloriesHigh: Int? = null,
) {
    /** Servers before the range existed send none: the total is then exact. */
    val low: Int get() = caloriesLow?.takeIf { it > 0 || calories == 0 } ?: calories
    val high: Int get() = caloriesHigh?.takeIf { it > 0 || calories == 0 } ?: calories
}

@Serializable
data class FoodPartnerDay(
    val userId: String,
    val userName: String,
    val shareLevel: FoodShareLevel,
    val dailyCalories: Int? = null,
    val totals: FoodTotals? = null,
    val entries: List<FoodEntry> = emptyList(),
)

@Serializable
data class FoodDay(
    val localDate: String,
    val targets: NutritionTarget = NutritionTarget(),
    val totals: FoodTotals = FoodTotals(),
    val remainingCalories: Int = 0,
    val entries: List<FoodEntry> = emptyList(),
    val partner: FoodPartnerDay? = null,
    val scansUsedToday: Int = 0,
    val scanLimitPerDay: Int = 0,
)

@Serializable
data class FoodScanItem(val name: String, val portion: String = "", val calories: Int = 0)

@Serializable
data class FoodScanResult(
    val scanId: String,
    val description: String,
    val calories: Int,
    val caloriesLow: Int,
    val caloriesHigh: Int,
    val proteinGrams: Double = 0.0,
    val carbsGrams: Double = 0.0,
    val fatGrams: Double = 0.0,
    val confidence: Double = 0.0,
    val items: List<FoodScanItem> = emptyList(),
    val model: String = "",
    val scansRemainingToday: Int = 0,
)

@Serializable
data class FoodScanRequest(val imageBase64: String, val mediaType: String = "image/jpeg", val portion: String? = null, val hint: String? = null)

@Serializable
data class CreateFoodEntryRequest(
    val localDate: String,
    val mealType: MealType,
    val description: String,
    val calories: Int,
    val proteinGrams: Double? = null,
    val carbsGrams: Double? = null,
    val fatGrams: Double? = null,
    val scanId: String? = null,
    val portionFactor: Double? = null,
)

@Serializable
data class UpdateFoodEntryRequest(
    val localDate: String,
    val mealType: MealType,
    val description: String,
    val calories: Int,
    val proteinGrams: Double? = null,
    val carbsGrams: Double? = null,
    val fatGrams: Double? = null,
)

@Serializable
data class UpsertTargetRequest(val dailyCalories: Int, val proteinGrams: Double? = null, val carbsGrams: Double? = null, val fatGrams: Double? = null, val shareLevel: FoodShareLevel)
