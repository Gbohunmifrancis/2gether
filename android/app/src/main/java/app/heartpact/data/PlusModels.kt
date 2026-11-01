package app.heartpact.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** api/billing (docs/billing.md): one Plus plan covers the couple; either partner can buy it. */
@Serializable
enum class Plan { @SerialName("free") Free, @SerialName("plus") Plus }

@Serializable
data class PlanLimits(
    val foodScansPerDay: Int = 0,
    val memoriesBytes: Long = 0,
    val questionHistoryDays: Int = 0,
    /** Active daily dares each partner can have at once; null is no limit (Plus, or an older server). */
    val activeDaresPerPerson: Int? = null,
)

/** The subscription that gives the couple Plus. [status] stays a string: Google adds states. */
@Serializable
data class SubscriptionSummary(
    val store: String = "googlePlay",
    val productId: String,
    val status: String,
    val expiresAtUtc: String,
    val autoRenewing: Boolean = false,
    val purchasedByUserId: String = "",
    val purchasedByMe: Boolean = false,
) {
    /** Paid by card through Bachs (on the website, or after Google Play's billing choice screen), not Google Play. */
    val isCard get() = store == STORE_BACHS

    companion object {
        const val STORE_BACHS = "bachs"
    }
}

/**
 * [canPurchase] is false when the partner already pays. [complimentary] is Plus given by Heartpact
 * until [complimentaryUntilUtc]. The limits and product ids come from the server, so the comparison
 * and the plans on sale are never hard-coded in the app.
 */
@Serializable
data class Entitlement(
    val plan: Plan = Plan.Free,
    val limits: PlanLimits = PlanLimits(),
    val subscription: SubscriptionSummary? = null,
    val canPurchase: Boolean = true,
    val complimentary: Boolean = false,
    val complimentaryUntilUtc: String? = null,
    val freeLimits: PlanLimits? = null,
    val plusLimits: PlanLimits? = null,
    val productIds: List<String> = emptyList(),
    /** The server sells Plus by card through Bachs (on the website). */
    val webCheckout: Boolean = false,
    /**
     * Card payment may be offered in the app: Bachs is set up, the admin flag is on and this person is in a country
     * where it's allowed. Google Play's purchase sheet then shows Google's billing choice screen (PlayStore).
     */
    val cardInApp: Boolean = false,
    /** The admin's "Card payments in the app" is on: the Plus screen offers its own "Pay by card", in any country. */
    val cardDirect: Boolean = false,
    /** The card prices to show next to "Pay by card"; Bachs' checkout shows the exact amount. */
    val cardPrices: CardPrices? = null,
)

@Serializable
data class CardPrices(val monthly: String, val yearly: String)

@Serializable
data class VerifyPurchaseRequest(val productId: String, val purchaseToken: String)

/**
 * `POST api/billing/bachs/checkout`. [externalTransactionToken] is Google Play's token from its billing choice screen
 * when the person chose to pay by card there; the server reports the sale to Google with it.
 */
@Serializable
data class BachsCheckoutRequest(val plan: String, val from: String = "app", val externalTransactionToken: String? = null)

@Serializable
data class BachsCheckout(val checkoutUrl: String)

/** Bachs' customer portal for my own card subscription: change the card, see receipts, cancel. */
@Serializable
data class BachsPortal(val url: String)
