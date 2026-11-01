package app.heartpact.billing

import app.heartpact.data.Entitlement
import app.heartpact.data.PlanLimits
import app.heartpact.i18n.tr
import app.heartpact.i18n.trn
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

/**
 * What the Plus screen's main button does. [Buy] opens Google Play's purchase sheet, which (with `cardInApp`) may
 * first offer Google's billing choice screen; there is no separate card button.
 */
enum class PlusAction { Buy, ManageInPlay, ManageBilling, Done }

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

    /** 0 yearly, 1 monthly, 2 anything else: the order plans are shown in. */
    private fun rank(productId: String): Int = when {
        "year" in productId.lowercase() || "annual" in productId.lowercase() -> 0
        "month" in productId.lowercase() -> 1
        else -> 2
    }

    fun planName(productId: String): String = when (rank(productId)) {
        0 -> tr("Yearly")
        1 -> tr("Monthly")
        else -> productId.substringAfterLast('_').replaceFirstChar { it.uppercase() }
    }

    /** "1 week", "3 months", "1 year" from P1W, P3M, P1Y (and P7D as "7 days"). */
    fun periodText(iso: String, cycles: Int = 1): String? {
        val match = Regex("^P(\\d+)([DWMY])$").find(iso) ?: return null
        val amount = match.groupValues[1].toInt() * cycles.coerceAtLeast(1)
        return when (match.groupValues[2]) {
            "D" -> trn(amount, "{count} day", "{count} days")
            "W" -> trn(amount, "{count} week", "{count} weeks")
            "M" -> trn(amount, "{count} month", "{count} months")
            else -> trn(amount, "{count} year", "{count} years")
        }
    }

    /** "£2.99 a month", "£29.99 a year", "£7.99 every 3 months". */
    private fun recurringText(price: String, iso: String): String {
        val match = Regex("^P(\\d+)([DWMY])$").find(iso) ?: return tr("{price} each period", "price" to price)
        val amount = match.groupValues[1].toInt()
        return when (match.groupValues[2]) {
            "D" -> trn(amount, "{price} a day", "{price} every {count} days", "price" to price)
            "W" -> trn(amount, "{price} a week", "{price} every {count} weeks", "price" to price)
            "M" -> trn(amount, "{price} a month", "{price} every {count} months", "price" to price)
            else -> trn(amount, "{price} a year", "{price} every {count} years", "price" to price)
        }
    }

    /** "£29.99 a year", "1 week free, then £29.99 a year", "£0.99 for 1 month, then £2.99 a month". */
    fun priceLine(offer: StoreOffer): String {
        val recurring = offer.phases.last()
        val base = recurringText(recurring.formattedPrice, recurring.billingPeriod)
        val intro = offer.phases.dropLast(1).firstOrNull() ?: return base
        val length = periodText(intro.billingPeriod, intro.cycles) ?: return base
        return if (intro.priceMicros == 0L) tr("{length} free, then {recurring}", "length" to length, "recurring" to base)
        else tr("{introPrice} for {length}, then {recurring}", "introPrice" to intro.formattedPrice, "length" to length, "recurring" to base)
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
        }.sortedBy { rank(it.productId) }
        val monthly = plans.firstOrNull { rank(it.productId) == 1 }
        return plans.map { plan ->
            val perMonth = plan.monthlyMicros
            val reference = monthly?.monthlyMicros
            if (plan === monthly || perMonth == null || reference == null || reference <= 0 || plan.currency != monthly.currency) plan
            else {
                val saving = ((1 - perMonth.toDouble() / reference) * 100).roundToInt()
                if (saving >= 5) plan.copy(savings = tr("Save {percent}%", "percent" to saving)) else plan
            }
        }
    }

    /** 500 MB, 2 GB. */
    fun storage(bytes: Long): String {
        val gb = bytes / (1024.0 * 1024 * 1024)
        if (gb >= 1) return tr("{size} GB", "size" to if (gb % 1.0 == 0.0) "${gb.toInt()}" else "%.1f".format(gb))
        return tr("{size} MB", "size" to (bytes / (1024.0 * 1024)).roundToInt())
    }

    /** The comparison table: label, free value, Plus value. */
    fun comparison(free: PlanLimits, plus: PlanLimits): List<Triple<String, String, String>> = listOf(
        Triple(tr("Daily dares"), dares(free.activeDaresPerPerson), dares(plus.activeDaresPerPerson)),
        Triple(tr("Food photo scans a day"), free.foodScansPerDay.toString(), plus.foodScansPerDay.toString()),
        Triple(tr("Album space"), storage(free.memoriesBytes), storage(plus.memoriesBytes)),
        Triple(tr("Question history"), trn(free.questionHistoryDays, "{count} day", "{count} days"), trn(plus.questionHistoryDays, "{count} day", "{count} days")),
    )

    /** Active dares each: "1 each", or "Unlimited". */
    fun dares(limit: Int?): String = limit?.let { trn(it, "{count} each", "{count} each") } ?: tr("Unlimited")

    /**
     * The card plan (Bachs checkout's `plan`) for a Play product chosen on Google's billing choice screen: "yearly" or
     * "monthly", by the same reading of the product id as [planName]; null for anything else.
     */
    /** The card plans, as cards like Play's: "card:monthly" and "card:yearly". */
    fun cardOptions(prices: app.heartpact.data.CardPrices): List<PlanOption> = listOf(
        PlanOption("card:yearly", tr("Yearly"), tr("{price} a year", "price" to prices.yearly), "", null, "", savings = tr("Best value")),
        PlanOption("card:monthly", tr("Monthly"), tr("{price} a month", "price" to prices.monthly), "", null, ""),
    )

    fun cardPlan(productId: String): String? = when (rank(productId)) {
        0 -> "yearly"
        1 -> "monthly"
        else -> null
    }

    /** The Plus screen's main button. */
    fun action(entitlement: Entitlement?): PlusAction {
        val subscription = entitlement?.subscription
        return when {
            entitlement == null -> PlusAction.Done
            subscription != null && subscription.purchasedByMe -> if (subscription.isCard) PlusAction.ManageBilling else PlusAction.ManageInPlay
            entitlement.canPurchase && subscription == null -> PlusAction.Buy
            else -> PlusAction.Done
        }
    }

    /** Google Play's page for this subscription, where it is cancelled or the payment fixed. */
    fun manageUrl(productId: String, packageName: String) =
        "https://play.google.com/store/account/subscriptions?sku=$productId&package=$packageName"
}
