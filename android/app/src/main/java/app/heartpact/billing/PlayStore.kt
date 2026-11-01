package app.heartpact.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import app.heartpact.i18n.tr
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.OnPurchasesUpdatedSubResponseCode
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.UserChoiceDetails
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** One price step of an offer: a free week, then £2.99 a month, say. Mirrors Play's pricing phase. */
data class StorePhase(
    val priceMicros: Long,
    val formattedPrice: String,
    val currency: String,
    /** ISO 8601 period: P1W, P1M, P1Y. */
    val billingPeriod: String,
    /** How many periods this phase lasts; 0 when it repeats until cancelled. */
    val cycles: Int = 0,
)

/** A way to buy a plan: the base plan, or an offer on it (a trial) the person is eligible for. */
data class StoreOffer(val token: String, val basePlanId: String, val offerId: String?, val phases: List<StorePhase>)

data class StoreProduct(val id: String, val offers: List<StoreOffer>)

sealed interface ProductsResult {
    data class Ok(val products: List<StoreProduct>) : ProductsResult
    data class Unavailable(val message: String) : ProductsResult
}

enum class PurchaseState { Purchased, Pending, Unknown }

/** What Play says this Google account owns. [accountId] is the Heartpact user it was bought for. */
data class StorePurchase(val productIds: List<String>, val token: String, val state: PurchaseState, val acknowledged: Boolean, val accountId: String?)

/** What came back from the purchase sheet. */
sealed interface PurchaseUpdate {
    data class Bought(val purchases: List<StorePurchase>) : PurchaseUpdate
    data object Cancelled : PurchaseUpdate
    /** Play says this account already has it: restore instead of buying again. */
    data object AlreadyOwned : PurchaseUpdate
    data class Failed(val message: String) : PurchaseUpdate
    /**
     * On Google Play's billing choice screen the person chose to pay by card instead (user choice billing). Nothing
     * was bought from Google: the app starts a Bachs checkout for [productIds] with [externalTransactionToken].
     */
    data class ChoseCard(val productIds: List<String>, val externalTransactionToken: String) : PurchaseUpdate
}

/** Google Play Billing, narrowed to what Plus needs, so screens and tests don't depend on Play. */
interface PlayStore {
    val updates: SharedFlow<PurchaseUpdate>
    suspend fun products(ids: List<String>): ProductsResult
    /** Subscriptions this Google account holds; null when Play can't be reached. */
    suspend fun owned(): List<StorePurchase>?
    /** Opens Play's purchase sheet. Returns an error to show, or null when the sheet opened. */
    suspend fun buy(activity: Activity, productId: String, offerToken: String, accountId: String): String?
    /**
     * Whether purchases offer Google Play's billing choice screen (the entitlement's `cardInApp`). Takes effect on the
     * next purchase.
     */
    fun offerCardChoice(enabled: Boolean) {}
}

object PlayMessages {
    val UNAVAILABLE get() = tr("Google Play isn't available on this phone. Check you're signed in to the Play Store.")
    val UNREACHABLE get() = tr("Can't reach Google Play. Check your connection and try again.")
    val NOT_ON_SALE get() = tr("Plus isn't on sale in your country yet.")
    val FAILED get() = tr("Google Play couldn't finish that. Try again in a moment.")
    val DECLINED get() = tr("Your payment was declined: there wasn't enough money on that payment method. Try another one in Google Play.")
    val INELIGIBLE get() = tr("This offer isn't available on your Google account. Pick the plan without it.")

    /**
     * [subCode] is Play Billing 9's more specific reason for a failed purchase
     * ([BillingClient.OnPurchasesUpdatedSubResponseCode]); it wins over the general [code] when it says something.
     */
    fun forCode(code: Int, subCode: Int = OnPurchasesUpdatedSubResponseCode.NO_APPLICABLE_SUB_RESPONSE_CODE): String = when (subCode) {
        OnPurchasesUpdatedSubResponseCode.PAYMENT_DECLINED_DUE_TO_INSUFFICIENT_FUNDS -> DECLINED
        OnPurchasesUpdatedSubResponseCode.USER_INELIGIBLE -> INELIGIBLE
        else -> general(code)
    }

    // Since Play Billing 9, a Play Store the system blocks (an OEM kids mode) answers BILLING_UNAVAILABLE, not ERROR.
    private fun general(code: Int): String = when (code) {
        BillingResponseCode.BILLING_UNAVAILABLE, BillingResponseCode.FEATURE_NOT_SUPPORTED -> UNAVAILABLE
        BillingResponseCode.SERVICE_UNAVAILABLE, BillingResponseCode.SERVICE_DISCONNECTED,
        BillingResponseCode.SERVICE_TIMEOUT, BillingResponseCode.NETWORK_ERROR -> UNREACHABLE
        BillingResponseCode.ITEM_UNAVAILABLE -> NOT_ON_SALE
        else -> FAILED
    }
}

/**
 * The real store. Connects on first use; the client reconnects by itself after that.
 *
 * With [offerCardChoice] on, the client is built with user choice billing, so in the countries where Google allows it
 * Play's purchase sheet first asks whether to pay with Google Play or by card. Choosing Google Play carries on as
 * usual; choosing card calls [onCardChosen] instead of the purchase listener. Off, the client is built exactly as
 * without the programme. A change rebuilds the client before the next purchase.
 */
class GooglePlayStore(context: Context) : PlayStore {
    private val appContext = context.applicationContext
    private val _updates = MutableSharedFlow<PurchaseUpdate>(extraBufferCapacity = 8)
    override val updates: SharedFlow<PurchaseUpdate> = _updates.asSharedFlow()

    @Volatile private var cardChoice = false
    private var builtWithCardChoice = false
    private var client: BillingClient = build(cardChoice = false)

    private fun build(cardChoice: Boolean): BillingClient {
        val builder = BillingClient.newBuilder(appContext)
            .setListener { result, purchases -> _updates.tryEmit(update(result, purchases)) }
            // Cash and bank-transfer purchases complete later: Play reports them as pending first.
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().enablePrepaidPlans().build())
            .enableAutoServiceReconnection()
        if (cardChoice) builder.enableUserChoiceBilling(::onCardChosen)
        builtWithCardChoice = cardChoice
        return builder.build()
    }

    /** Kept thin: Google's token and the products go straight to the Plus screen, which asks the server for a checkout. */
    private fun onCardChosen(details: UserChoiceDetails) {
        _updates.tryEmit(PurchaseUpdate.ChoseCard(details.products.map { it.id }, details.externalTransactionToken))
    }

    override fun offerCardChoice(enabled: Boolean) { cardChoice = enabled }

    private val connecting = Mutex()
    /** Play needs the exact ProductDetails object to open the sheet, so keep what [products] returned. */
    private val details = mutableMapOf<String, ProductDetails>()

    private suspend fun ready(): BillingResult? = connecting.withLock {
        if (builtWithCardChoice != cardChoice) {
            client.endConnection()
            client = build(cardChoice)
        }
        if (client.isReady) return@withLock null
        val done = CompletableDeferred<BillingResult>()
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) { done.complete(result) }
            override fun onBillingServiceDisconnected() { done.complete(BillingResult.newBuilder().setResponseCode(BillingResponseCode.SERVICE_DISCONNECTED).build()) }
        })
        val result = withTimeoutOrNull(10_000) { done.await() }
            ?: BillingResult.newBuilder().setResponseCode(BillingResponseCode.SERVICE_TIMEOUT).build()
        result.takeIf { it.responseCode != BillingResponseCode.OK }
    }

    override suspend fun products(ids: List<String>): ProductsResult {
        if (ids.isEmpty()) return ProductsResult.Unavailable(PlayMessages.NOT_ON_SALE)
        ready()?.let { return ProductsResult.Unavailable(PlayMessages.forCode(it.responseCode)) }
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(ids.map { QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(BillingClient.ProductType.SUBS).build() })
            .build()
        val result = client.queryProductDetails(params)
        if (result.billingResult.responseCode != BillingResponseCode.OK) {
            Log.w(TAG, "Product query failed: ${result.billingResult.responseCode} ${result.billingResult.debugMessage}")
            return ProductsResult.Unavailable(PlayMessages.forCode(result.billingResult.responseCode))
        }
        val found = result.productDetailsList.orEmpty()
        found.forEach { details[it.productId] = it }
        if (found.isEmpty()) return ProductsResult.Unavailable(PlayMessages.NOT_ON_SALE)
        return ProductsResult.Ok(found.map { product ->
            StoreProduct(product.productId, product.subscriptionOfferDetails.orEmpty().map { offer ->
                StoreOffer(offer.offerToken, offer.basePlanId, offer.offerId, offer.pricingPhases.pricingPhaseList.map { phase ->
                    StorePhase(phase.priceAmountMicros, phase.formattedPrice, phase.priceCurrencyCode, phase.billingPeriod, phase.billingCycleCount)
                })
            })
        })
    }

    override suspend fun owned(): List<StorePurchase>? {
        if (ready() != null) return null
        val result = client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build())
        if (result.billingResult.responseCode != BillingResponseCode.OK) return null
        return result.purchasesList.map(::toStore)
    }

    override suspend fun buy(activity: Activity, productId: String, offerToken: String, accountId: String): String? {
        ready()?.let { return PlayMessages.forCode(it.responseCode) }
        val product = details[productId] ?: return PlayMessages.FAILED
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(product).setOfferToken(offerToken).build()))
            // The server only accepts a purchase made for the account that reports it.
            .setObfuscatedAccountId(accountId)
            .build()
        val result = client.launchBillingFlow(activity, params)
        return when (result.responseCode) {
            BillingResponseCode.OK -> null
            BillingResponseCode.ITEM_ALREADY_OWNED -> { _updates.tryEmit(PurchaseUpdate.AlreadyOwned); null }
            BillingResponseCode.USER_CANCELED -> null
            else -> PlayMessages.forCode(result.responseCode, result.onPurchasesUpdatedSubResponseCode)
        }
    }

    private fun update(result: BillingResult, purchases: List<Purchase>?): PurchaseUpdate = when (result.responseCode) {
        BillingResponseCode.OK -> PurchaseUpdate.Bought(purchases.orEmpty().map(::toStore))
        BillingResponseCode.USER_CANCELED -> PurchaseUpdate.Cancelled
        BillingResponseCode.ITEM_ALREADY_OWNED -> PurchaseUpdate.AlreadyOwned
        else -> PurchaseUpdate.Failed(PlayMessages.forCode(result.responseCode, result.onPurchasesUpdatedSubResponseCode))
    }

    private fun toStore(purchase: Purchase) = StorePurchase(
        productIds = purchase.products,
        token = purchase.purchaseToken,
        state = when (purchase.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> PurchaseState.Purchased
            Purchase.PurchaseState.PENDING -> PurchaseState.Pending
            else -> PurchaseState.Unknown
        },
        acknowledged = purchase.isAcknowledged,
        accountId = purchase.accountIdentifiers?.obfuscatedAccountId,
    )

    private companion object {
        const val TAG = "PlayStore"
    }
}
