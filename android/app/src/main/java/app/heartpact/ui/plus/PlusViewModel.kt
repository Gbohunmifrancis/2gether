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
import app.heartpact.i18n.tr
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
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
    /** Sent to Bachs' checkout (card, from Google Play's choice screen): waiting for the server to hear it was paid. */
    val awaitingCard: Boolean = false,
    /** The card plan picked under "Pay by card": "monthly" or "yearly". */
    val cardSelected: String = "monthly",
    /** Plus just started for this person: the full-screen celebration to show once ([PlusCelebrations]). */
    val celebrate: Celebration? = null,
) {
    val selectedOption get() = options.firstOrNull { it.productId == selected }
    val isPlus get() = entitlement?.plan == Plan.Plus
    /** On Free and allowed to buy: limits elsewhere may point here. */
    val offersPlus get() = entitlement?.plan == Plan.Free && entitlement.canPurchase
}

/**
 * The Plus screen (mockup 18). The server says what the couple has and what is on sale; Play says the
 * prices and runs the purchase; the server checks every purchase with Google before Plus appears.
 *
 * Where the server allows card payment in the app (`cardInApp`), Play's sheet may offer Google's billing choice
 * screen. Choosing card there starts a Bachs checkout in the browser; back in the app, the entitlement is asked
 * for every [pollEveryMs] for up to [pollForMs] until Bachs has told the server, which is what switches Plus on.
 */
class PlusViewModel(
    private val plus: PlusRepository,
    private val store: PlayStore,
    private val userId: String,
    private val pollEveryMs: Long = 3_000,
    private val pollForMs: Long = 90_000,
    /** Remembers the Plus celebrated, so Home doesn't celebrate it again ([PlusCelebrations]). */
    private val celebrations: CelebrationMemory? = null,
) : ViewModel() {
    private val _ui = MutableStateFlow(PlusUi())
    val ui: StateFlow<PlusUi> = _ui.asStateFlow()

    /** Pages to open in the browser, each once: Bachs' checkout and billing portal. */
    private val _links = Channel<String>(Channel.BUFFERED)
    val links: Flow<String> = _links.receiveAsFlow()
    private var waiting: Job? = null

    init {
        viewModelScope.launch { store.updates.collect(::onUpdate) }
        load()
    }

    fun load() = viewModelScope.launch {
        _ui.update { it.copy(loading = it.entitlement == null, note = null) }
        when (val result = plus.entitlement()) {
            is ApiResult.Ok -> {
                _ui.update { it.copy(loading = false, entitlement = result.value) }
                store.offerCardChoice(result.value.cardInApp)
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
                        storeMessage = if (options.isEmpty()) tr("Plus isn't on sale in your country yet.") else null,
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
                is RestoreResult.NothingToRestore -> if (result.pending) PENDING else tr("This Google account has no Plus subscription for you.")
                is RestoreResult.Refused -> result.message
                RestoreResult.StoreUnavailable -> tr("Can't reach Google Play. Check your connection and try again.")
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
                        is ApiResult.Failure -> _ui.update { it.copy(note = tr("Your payment went through, but we couldn't switch Plus on yet. {serverMessage}", "serverMessage" to result.message)) }
                        ApiResult.Offline -> _ui.update { it.copy(note = tr("Your payment went through. Plus switches on as soon as you're back online.")) }
                    }
                }
                _ui.update { it.copy(busy = false) }
            }
            PurchaseUpdate.Cancelled -> _ui.update { it.copy(busy = false) }
            PurchaseUpdate.AlreadyOwned -> restore()
            is PurchaseUpdate.Failed -> _ui.update { it.copy(busy = false, note = update.message) }
            is PurchaseUpdate.ChoseCard -> payByCard(update)
        }
    }

    fun selectCard(plan: String) = _ui.update { it.copy(cardSelected = plan) }

    /** The app's own "Pay by card" (the admin's card switch is on): a Bachs checkout for the picked plan, in the browser. */
    fun payByCard() {
        if (_ui.value.busy) return
        viewModelScope.launch { cardCheckout(_ui.value.cardSelected, null) }
    }

    /** Google's choice screen said card: ask the server for a Bachs checkout for that plan and open it. */
    private suspend fun payByCard(choice: PurchaseUpdate.ChoseCard) {
        val plan = choice.productIds.firstNotNullOfOrNull(PlusOffers::cardPlan)
        if (plan == null) {
            _ui.update { it.copy(busy = false, note = cardMessage("billing.unknown_plan", "")) }
            return
        }
        cardCheckout(plan, choice.externalTransactionToken)
    }

    private suspend fun cardCheckout(plan: String, externalTransactionToken: String?) {
        _ui.update { it.copy(busy = true, note = null) }
        when (val result = plus.cardCheckout(plan, externalTransactionToken)) {
            is ApiResult.Ok -> open(result.value.checkoutUrl, awaitingCard = true)
            is ApiResult.Failure -> {
                _ui.update { it.copy(busy = false, note = cardMessage(result.code, result.message)) }
                // Bought meanwhile (on the website, say): show the plan as it is now, keeping the note.
                if (result.code == "billing.already_plus") (plus.entitlement() as? ApiResult.Ok)?.let { fresh -> _ui.update { it.copy(entitlement = fresh.value) } }
            }
            ApiResult.Offline -> _ui.update { it.copy(busy = false, note = OFFLINE_MESSAGE) }
        }
    }

    /** "Manage billing": Bachs' portal for my card subscription, made on tap (its link expires). */
    fun manageBilling() {
        _ui.update { it.copy(busy = true, note = null) }
        viewModelScope.launch {
            when (val result = plus.cardPortal()) {
                is ApiResult.Ok -> open(result.value.url, awaitingCard = false)
                is ApiResult.Failure -> _ui.update { it.copy(busy = false, note = cardMessage(result.code, result.message)) }
                ApiResult.Offline -> _ui.update { it.copy(busy = false, note = OFFLINE_MESSAGE) }
            }
        }
    }

    private suspend fun open(url: String, awaitingCard: Boolean) {
        if (!url.startsWith("https://")) {
            _ui.update { it.copy(busy = false, note = tr("That didn't work. Try again.")) }
            return
        }
        _ui.update { it.copy(busy = false, awaitingCard = it.awaitingCard || awaitingCard) }
        _links.send(url)
    }

    /**
     * The screen is back in front (after the browser). While a card payment is awaited, ask the server until Plus
     * arrives or [pollForMs] pass; coming back from the checkout proves nothing by itself.
     */
    fun resumed() {
        if (!_ui.value.awaitingCard || waiting?.isActive == true) return
        waiting = viewModelScope.launch {
            val tries = (pollForMs / pollEveryMs).toInt().coerceAtLeast(1)
            repeat(tries) { attempt ->
                if (attempt > 0) delay(pollEveryMs)
                val entitlement = (plus.entitlement() as? ApiResult.Ok)?.value
                if (entitlement?.plan == Plan.Plus) {
                    accept(entitlement)
                    _ui.update { it.copy(awaitingCard = false) }
                    return@launch
                }
            }
            _ui.update { it.copy(awaitingCard = false, note = tr("We haven't heard about your card payment yet. If you paid, Plus switches on by itself in a few minutes.")) }
        }
    }

    private fun accept(entitlement: Entitlement) {
        val arrived = entitlement.plan == Plan.Plus && _ui.value.entitlement?.plan != Plan.Plus
        // Watched it go from Free to Plus here: celebrate now (and Home, remembering it, won't again).
        val celebrate = if (arrived) celebrations?.observe(userId, entitlement, sawFree = true) else null
        _ui.update { it.copy(entitlement = entitlement, pending = false, welcomed = it.welcomed || arrived, celebrate = celebrate ?: it.celebrate) }
    }

    /** "Let's go": the celebration is done. */
    fun celebrated() = _ui.update { it.copy(celebrate = null) }

    companion object {
        val PENDING get() = tr("Your purchase is waiting for the payment to go through. Plus switches on as soon as Google confirms it.")
        val AWAITING_CARD get() = tr("Waiting for your payment to be confirmed…")

        /** What to say when a card checkout or the billing portal is refused, by the server's code. */
        fun cardMessage(code: String, serverMessage: String): String = when (code) {
            "billing.bachs_unavailable", "billing.card_in_app_unavailable" -> tr("Paying by card isn't available right now. Try again later, or pay with Google Play.")
            "billing.unknown_plan" -> tr("That plan can't be paid by card right now. Pay with Google Play instead.")
            "billing.already_plus" -> tr("You already have Plus, so there's nothing to pay.")
            "billing.no_bachs_customer" -> tr("We couldn't find card billing for your account. If that's wrong, write to support@heartpact.me.")
            else -> serverMessage.ifBlank { tr("That didn't work. Try again.") }
        }
    }
}

/**
 * Just the entitlement, for the Us row, the Plus look and the "Get Plus" prompts elsewhere. On Home ([celebrations] and
 * [userId] set) it also decides the Plus celebration ([PlusCelebrations]): each load, and when a `plusStarted` push
 * arrives while the app is open.
 */
class PlusSummaryViewModel(
    private val plus: PlusRepository,
    private val celebrations: CelebrationMemory? = null,
    private val userId: String? = null,
    events: Flow<String>? = null,
) : ViewModel() {
    private val _ui = MutableStateFlow(PlusUi())
    val ui: StateFlow<PlusUi> = _ui.asStateFlow()

    init {
        events?.let { flow -> viewModelScope.launch { flow.collect { if (it == "plusStarted") load() } } }
    }

    fun load() = viewModelScope.launch {
        (plus.entitlement() as? ApiResult.Ok)?.let { result ->
            val show = if (celebrations != null && userId != null) celebrations.observe(userId, result.value) else null
            _ui.update { it.copy(loading = false, entitlement = result.value, celebrate = show ?: it.celebrate) }
        }
    }

    /** "Let's go": the celebration is done. */
    fun celebrated() = _ui.update { it.copy(celebrate = null) }
}
