package app.heartpact.screens

import app.heartpact.billing.PlanOption
import app.heartpact.data.Entitlement
import app.heartpact.data.Plan
import app.heartpact.data.PlanLimits
import app.heartpact.data.SubscriptionSummary
import app.heartpact.ui.plus.PlusScreen
import app.heartpact.ui.plus.PlusUi
import app.heartpact.ui.plus.PlusViewModel
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class PlusShotsTest {
    private val freeLimits = PlanLimits(3, 500L * 1024 * 1024, 7)
    private val plusLimits = PlanLimits(20, 2L * 1024 * 1024 * 1024, 90)
    private val free = Entitlement(Plan.Free, freeLimits, null, canPurchase = true, freeLimits = freeLimits, plusLimits = plusLimits, productIds = listOf("heartpact_plus_monthly", "heartpact_plus_yearly"))
    private val options = listOf(
        PlanOption("heartpact_plus_yearly", "Yearly", "1 week free, then £23.99 a year", "y", 1_999_166, "GBP", savings = "Save 33%"),
        PlanOption("heartpact_plus_monthly", "Monthly", "£2.99 a month", "m", 2_990_000, "GBP"),
    )
    private val mine = SubscriptionSummary("googlePlay", "heartpact_plus_yearly", "active", "2027-10-11T10:00:00Z", autoRenewing = true, purchasedByUserId = "u1", purchasedByMe = true)

    private fun plus(ui: PlusUi, name: String, night: Boolean = false) = shot(name, night) { PlusScreen(ui, "Theo", {}, {}, {}, {}, {}) }

    @Test fun free() = plus(PlusUi(loading = false, entitlement = free, options = options, selected = "heartpact_plus_yearly"), "plus")

    @Test fun freeNight() = plus(PlusUi(loading = false, entitlement = free, options = options, selected = "heartpact_plus_monthly"), "plus-night", night = true)

    @Test fun noPlay() = plus(PlusUi(loading = false, entitlement = free, storeMessage = "Google Play isn't available on this phone. Check you're signed in to the Play Store."), "plus-no-play")

    @Test fun pending() = plus(PlusUi(loading = false, entitlement = free, options = options, selected = "heartpact_plus_yearly", pending = true), "plus-pending")

    @Test fun welcomed() = plus(PlusUi(loading = false, welcomed = true, entitlement = free.copy(plan = Plan.Plus, limits = plusLimits, subscription = mine)), "plus-mine")

    @Test fun partnerPays() = plus(PlusUi(loading = false, entitlement = free.copy(plan = Plan.Plus, canPurchase = false,
        subscription = mine.copy(purchasedByMe = false, autoRenewing = false, status = "canceled"))), "plus-partner")

    private val card = mine.copy(store = "bachs")

    /** Card in the app: the same Google Play plans (Google's own choice screen comes after), nothing extra. */
    @Test fun cardInApp() = plus(PlusUi(loading = false, entitlement = free.copy(cardInApp = true, webCheckout = true), options = options, selected = "heartpact_plus_yearly"), "plus-card-in-app")

    @Test fun awaitingCard() = plus(PlusUi(loading = false, entitlement = free.copy(cardInApp = true, webCheckout = true), options = options, selected = "heartpact_plus_yearly", awaitingCard = true), "plus-card-waiting")

    @Test fun cardRefused() = plus(PlusUi(loading = false, entitlement = free.copy(cardInApp = true), options = options, selected = "heartpact_plus_yearly",
        note = PlusViewModel.cardMessage("billing.bachs_unavailable", "")), "plus-card-unavailable")

    @Test fun mineByCard() = plus(PlusUi(loading = false, entitlement = free.copy(plan = Plan.Plus, limits = plusLimits, subscription = card)), "plus-mine-card")

    @Test fun mineByCardNight() = plus(PlusUi(loading = false, entitlement = free.copy(plan = Plan.Plus, limits = plusLimits, subscription = card.copy(status = "inGracePeriod"))), "plus-mine-card-grace-night", night = true)

    @Test fun partnerPaysByCard() = plus(PlusUi(loading = false, entitlement = free.copy(plan = Plan.Plus, canPurchase = false, subscription = card.copy(purchasedByMe = false))), "plus-partner-card")

    @Test fun complimentary() = plus(PlusUi(loading = false, options = options, selected = "heartpact_plus_yearly",
        entitlement = free.copy(plan = Plan.Plus, complimentary = true, complimentaryUntilUtc = "2026-11-10T16:00:00Z")), "plus-complimentary")
}
