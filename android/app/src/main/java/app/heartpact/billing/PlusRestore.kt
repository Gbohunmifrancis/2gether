package app.heartpact.billing

import app.heartpact.data.ApiResult
import app.heartpact.data.Entitlement
import app.heartpact.data.PlusRepository

/**
 * Tells the server about purchases it may not have seen: the app was closed mid-purchase, or a
 * pending payment went through later. The server checks each with Google and acknowledges it, which
 * Play requires within three days or it refunds the purchase.
 */
object PlusRestore {
    /**
     * Verifies this Google account's purchases made for [userId] (or with no account recorded).
     * [onlyUnacknowledged] keeps the start-up check cheap; the Restore button checks everything.
     * Returns the newest entitlement the server gave back, or null when nothing was sent.
     */
    suspend fun run(store: PlayStore, plus: PlusRepository, userId: String, onlyUnacknowledged: Boolean): RestoreResult {
        val owned = store.owned() ?: return RestoreResult.StoreUnavailable
        val mine = PlusOffers.accountId(userId)
        val candidates = owned.filter { purchase ->
            purchase.state == PurchaseState.Purchased && (purchase.accountId == null || purchase.accountId == mine) && (!onlyUnacknowledged || !purchase.acknowledged)
        }
        var latest: Entitlement? = null
        var failure: ApiResult.Failure? = null
        for (purchase in candidates) {
            val productId = purchase.productIds.firstOrNull() ?: continue
            when (val result = plus.verify(productId, purchase.token)) {
                is ApiResult.Ok -> latest = result.value
                is ApiResult.Failure -> failure = result
                ApiResult.Offline -> return RestoreResult.Offline
            }
        }
        return when {
            latest != null -> RestoreResult.Restored(latest)
            failure != null -> RestoreResult.Refused(failure.message)
            else -> RestoreResult.NothingToRestore(owned.any { it.state == PurchaseState.Pending })
        }
    }
}

sealed interface RestoreResult {
    data class Restored(val entitlement: Entitlement) : RestoreResult
    data class NothingToRestore(val pending: Boolean) : RestoreResult
    data class Refused(val message: String) : RestoreResult
    data object StoreUnavailable : RestoreResult
    data object Offline : RestoreResult
}
