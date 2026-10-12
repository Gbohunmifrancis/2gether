package app.heartpact.billing

import app.heartpact.data.PlanLimits
import kotlin.math.roundToInt

/** A plan as the Plus screen shows it: name, price line, and the offer that buying it uses. */
data class PlanOption(
    val productId: String,
    val name: String,
    val priceLine: String,
    val offerToken: String,
    /** The recurring price per month, in micros, for comparing plans; null when the period is odd. */
    val monthlyMicros: Long?,
    val currency: String,
    val savings: String? = null,
)

/** Pure rules for turning Play's products into what the Plus screen shows. */
object PlusOffers {
    /** Play's obfuscated account id: the Heartpact user id without dashes, as the server expects. */
    fun accountId(userId: String) = userId.replace("-", "")

    /**
     * Play only returns offers this person may take (a trial they haven't used, say). Take the one
     * that starts cheapest; on a tie, the plain base plan.
     */
    fun chooseOffer(product: StoreProduct): StoreOffer? =
        product.offers.filter { it.phases.isNotEmpty() }
            .minWithOrNull(compareBy<StoreOffer> { it.phases.first().priceMicros }.thenBy { if (it.offerId == null) 0 else 1 })

    fun planName(productId: String): String = when {
        "year" in productId.lowercase() || "annual" in productId.lowercase() -> "Yearly"
        "month" in productId.lowercase() -> "Monthly"
        else -> productId.substringAfterLast('_').replaceFirstChar { it.uppercase() }
    }

    /** "1 week", "3 months", "1 year" from P1W, P3M, P1Y (and P7D as "7 days"). */
    fun periodText(iso: String, cycles: Int = 1): String? {
        val match = Regex("^P(\\d+)([DWMY])$").find(iso) ?: return null
        val amount = match.groupValues[1].toInt() * cycles.coerceAtLeast(1)
        val unit = when (match.groupValues[2]) { "D" -> "day"; "W" -> "week"; "M" -> "month"; else -> "year" }
        return "$amount $unit${if (amount == 1) "" else "s"}"
    }

    /** "a month", "a year", "every 3 months". */
    private fun everyText(iso: String): String {
        val match = Regex("^P(\\d+)([DWMY])$").find(iso) ?: return "each period"
        val amount = match.groupValues[1].toInt()
        val unit = when (match.groupValues[2]) { "D" -> "day"; "W" -> "week"; "M" -> "month"; else -> "year" }
        return if (amount == 1) "a $unit" else "every $amount ${unit}s"
    }

    /** "£29.99 a year", "1 week free, then £29.99 a year", "£0.99 for 1 month, then £2.99 a month". */
    fun priceLine(offer: StoreOffer): String {
        val recurring = offer.phases.last()
        val base = "${recurring.formattedPrice} ${everyText(recurring.billingPeriod)}"
        val intro = offer.phases.dropLast(1).firstOrNull() ?: return base
        val length = periodText(intro.billingPeriod, intro.cycles) ?: return base
        return if (intro.priceMicros == 0L) "$length free, then $base" else "${intro.formattedPrice} for $length, then $base"
    }

    fun monthlyMicros(phase: StorePhase): Long? {
        val match = Regex("^P(\\d+)([WMY])$").find(phase.billingPeriod) ?: return null
        val amount = match.groupValues[1].toLong()
        return when (match.groupValues[2]) {
            "Y" -> phase.priceMicros / (12 * amount)
            "M" -> phase.priceMicros / amount
            else -> phase.priceMicros * 52 / (12 * amount)
        }
    }

    /** The plans on sale, yearly first as in the design, with the yearly saving over monthly. */
    fun options(products: List<StoreProduct>): List<PlanOption> {
        val plans = products.mapNotNull { product ->
            val offer = chooseOffer(product) ?: return@mapNotNull null
            val recurring = offer.phases.last()
            PlanOption(product.id, planName(product.id), priceLine(offer), offer.token, monthlyMicros(recurring), recurring.currency)
        }.sortedBy { if (it.name == "Yearly") 0 else if (it.name == "Monthly") 1 else 2 }
        val monthly = plans.firstOrNull { it.name == "Monthly" }
        return plans.map { plan ->
            val perMonth = plan.monthlyMicros
            val reference = monthly?.monthlyMicros
            if (plan === monthly || perMonth == null || reference == null || reference <= 0 || plan.currency != monthly.currency) plan
            else {
                val saving = ((1 - perMonth.toDouble() / reference) * 100).roundToInt()
                if (saving >= 5) plan.copy(savings = "Save $saving%") else plan
            }
        }
    }

    /** 500 MB, 2 GB. */
    fun storage(bytes: Long): String {
        val gb = bytes / (1024.0 * 1024 * 1024)
        if (gb >= 1) return if (gb % 1.0 == 0.0) "${gb.toInt()} GB" else "${"%.1f".format(gb)} GB"
        return "${(bytes / (1024.0 * 1024)).roundToInt()} MB"
    }

    /** The comparison table: label, free value, Plus value. */
    fun comparison(free: PlanLimits, plus: PlanLimits): List<Triple<String, String, String>> = listOf(
        Triple("Food photo scans a day", free.foodScansPerDay.toString(), plus.foodScansPerDay.toString()),
        Triple("Album space", storage(free.memoriesBytes), storage(plus.memoriesBytes)),
        Triple("Question history", "${free.questionHistoryDays} days", "${plus.questionHistoryDays} days"),
    )

    /** Google Play's page for this subscription, where it is cancelled or the payment fixed. */
    fun manageUrl(productId: String, packageName: String) =
        "https://play.google.com/store/account/subscriptions?sku=$productId&package=$packageName"
}
