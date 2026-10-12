package app.heartpact.ui.plus

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.heartpact.billing.PlanOption
import app.heartpact.billing.PlayStore
import app.heartpact.billing.PlusOffers
import app.heartpact.billing.PlusRestore
import app.heartpact.billing.ProductsResult
import app.heartpact.billing.PurchaseState
import app.heartpact.billing.PurchaseUpdate
import app.heartpact.billing.RestoreResult
import app.heartpact.data.ApiResult
import app.heartpact.data.Entitlement
import app.heartpact.data.OFFLINE_MESSAGE
import app.heartpact.data.Plan
import app.heartpact.data.PlusRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlusUi(
    val loading: Boolean = true,
    val entitlement: Entitlement? = null,
    val options: List<PlanOption> = emptyList(),
    val selected: String? = null,
    /** Why there are no plans to pick (Play missing, nothing on sale). */
    val storeMessage: String? = null,
    /** A one-off message: a refused purchase, offline. */
    val note: String? = null,
    val busy: Boolean = false,
    /** Bought, waiting for the payment (cash, bank transfer) to go through. */
    val pending: Boolean = false,
    /** Plus arrived while this screen was open: show the thank-you. */
    val welcomed: Boolean = false,
) {
    val selectedOption get() = options.firstOrNull { it.productId == selected }
    val isPlus get() = entitlement?.plan == Plan.Plus
    /** On Free and allowed to buy: limits elsewhere may point here. */
    val offersPlus get() = entitlement?.plan == Plan.Free && entitlement.canPurchase
}

/**
 * The Plus screen (mockup 18). The server says what the couple has and what is on sale; Play says the
 * prices and runs the purchase; the server checks every purchase with Google before Plus appears.
 */
class PlusViewModel(private val plus: PlusRepository, private val store: PlayStore, private val userId: String) : ViewModel() {
    private val _ui = MutableStateFlow(PlusUi())
    val ui: StateFlow<PlusUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch { store.updates.collect(::onUpdate) }
        load()
    }

    fun load() = viewModelScope.launch {
        _ui.update { it.copy(loading = it.entitlement == null, note = null) }
        when (val result = plus.entitlement()) {
            is ApiResult.Ok -> {
                _ui.update { it.copy(loading = false, entitlement = result.value) }
                if (result.value.canPurchase) loadPlans(result.value.productIds)
            }
            is ApiResult.Failure -> _ui.update { it.copy(loading = false, note = result.message) }
            ApiResult.Offline -> _ui.update { it.copy(loading = false, note = OFFLINE_MESSAGE) }
        }
    }

    private suspend fun loadPlans(productIds: List<String>) {
        when (val result = store.products(productIds)) {
            is ProductsResult.Ok -> {
                val options = PlusOffers.options(result.products)
                _ui.update { state ->
                    state.copy(
                        options = options,
                        selected = state.selected?.takeIf { id -> options.any { it.productId == id } } ?: options.firstOrNull()?.productId,
                        storeMessage = if (options.isEmpty()) "Plus isn't on sale in your country yet." else null,
                    )
                }
            }
            is ProductsResult.Unavailable -> _ui.update { it.copy(options = emptyList(), selected = null, storeMessage = result.message) }
        }
    }

    fun select(productId: String) = _ui.update { it.copy(selected = productId) }

    fun buy(activity: Activity) {
        val option = _ui.value.selectedOption ?: return
        _ui.update { it.copy(busy = true, note = null) }
        viewModelScope.launch {
            val error = store.buy(activity, option.productId, option.offerToken, PlusOffers.accountId(userId))
            // The sheet is open: its answer arrives through [onUpdate].
            if (error != null) _ui.update { it.copy(busy = false, note = error) }
        }
    }

    fun restore() {
        _ui.update { it.copy(busy = true, note = null) }
        viewModelScope.launch {
            val note = when (val result = PlusRestore.run(store, plus, userId, onlyUnacknowledged = false)) {
                is RestoreResult.Restored -> { accept(result.entitlement); null }
                is RestoreResult.NothingToRestore -> if (result.pending) PENDING else "This Google account has no Plus subscription for you."
                is RestoreResult.Refused -> result.message
                RestoreResult.StoreUnavailable -> "Can't reach Google Play. Check your connection and try again."
                RestoreResult.Offline -> OFFLINE_MESSAGE
            }
            _ui.update { it.copy(busy = false, note = note) }
        }
    }

    fun dismissNote() = _ui.update { it.copy(note = null) }

    private suspend fun onUpdate(update: PurchaseUpdate) {
        when (update) {
            is PurchaseUpdate.Bought -> {
                val mine = update.purchases
                if (mine.any { it.state == PurchaseState.Pending } && mine.none { it.state == PurchaseState.Purchased }) {
                    _ui.update { it.copy(busy = false, pending = true, note = null) }
                    return
                }
                for (purchase in mine.filter { it.state == PurchaseState.Purchased }) {
                    val productId = purchase.productIds.firstOrNull() ?: continue
                    when (val result = plus.verify(productId, purchase.token)) {
                        is ApiResult.Ok -> accept(result.value)
                        // Paid, but the server couldn't confirm yet: it is retried on the next start.
                        is ApiResult.Failure -> _ui.update { it.copy(note = "Your payment went through, but we couldn't switch Plus on yet. ${result.message}") }
                        ApiResult.Offline -> _ui.update { it.copy(note = "Your payment went through. Plus switches on as soon as you're back online.") }
                    }
                }
                _ui.update { it.copy(busy = false) }
            }
            PurchaseUpdate.Cancelled -> _ui.update { it.copy(busy = false) }
            PurchaseUpdate.AlreadyOwned -> restore()
            is PurchaseUpdate.Failed -> _ui.update { it.copy(busy = false, note = update.message) }
        }
    }

    private fun accept(entitlement: Entitlement) = _ui.update {
        it.copy(entitlement = entitlement, pending = false, welcomed = it.welcomed || (entitlement.plan == Plan.Plus && it.entitlement?.plan != Plan.Plus))
    }

    companion object {
        const val PENDING = "Your purchase is waiting for the payment to go through. Plus switches on as soon as Google confirms it."
    }
}

/** Just the entitlement, for the Us row and the "Get Plus" prompts elsewhere. */
class PlusSummaryViewModel(private val plus: PlusRepository) : ViewModel() {
    private val _ui = MutableStateFlow(PlusUi())
    val ui: StateFlow<PlusUi> = _ui.asStateFlow()

    fun load() = viewModelScope.launch {
        (plus.entitlement() as? ApiResult.Ok)?.let { result -> _ui.update { it.copy(loading = false, entitlement = result.value) } }
    }
}
