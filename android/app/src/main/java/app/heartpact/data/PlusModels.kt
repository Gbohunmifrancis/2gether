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
)

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
)

@Serializable
data class VerifyPurchaseRequest(val productId: String, val purchaseToken: String)
