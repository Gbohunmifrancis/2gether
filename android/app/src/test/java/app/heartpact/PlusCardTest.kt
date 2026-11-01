package app.heartpact

import app.heartpact.billing.PlusAction
import app.heartpact.billing.PlusOffers
import app.heartpact.data.Entitlement
import app.heartpact.data.Plan
import app.heartpact.data.SubscriptionSummary
import app.heartpact.ui.plus.PlusText
import app.heartpact.ui.plus.PlusViewModel
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Card payment through Bachs: which button the Plus screen shows, and the plan a Play product maps to. */
class PlusCardTest {
    private val play = SubscriptionSummary("googlePlay", "heartpact_plus_yearly", "active", "2027-10-11T10:00:00Z", autoRenewing = true, purchasedByUserId = "me", purchasedByMe = true)
    private val card = play.copy(store = "bachs")
    private val free = Entitlement(Plan.Free, canPurchase = true, productIds = listOf("heartpact_plus_monthly", "heartpact_plus_yearly"))

    @Test fun `products chosen on Google's choice screen map to the card plans`() {
        assertEquals("yearly", PlusOffers.cardPlan("heartpact_plus_yearly"))
        assertEquals("monthly", PlusOffers.cardPlan("heartpact_plus_monthly"))
        assertEquals("yearly", PlusOffers.cardPlan("heartpact_plus_annual"))
        assertNull(PlusOffers.cardPlan("heartpact_test_product"))
    }

    @Test fun `my Google Play subscription is managed in Play, my card one in billing`() {
        assertEquals(PlusAction.ManageInPlay, PlusOffers.action(free.copy(plan = Plan.Plus, subscription = play)))
        assertEquals(PlusAction.ManageBilling, PlusOffers.action(free.copy(plan = Plan.Plus, subscription = card)))
        // A card subscription that lapsed (a failed payment) still opens billing, where it's fixed.
        assertEquals(PlusAction.ManageBilling, PlusOffers.action(free.copy(subscription = card.copy(status = "onHold"))))
        assertTrue(card.isCard)
        assertFalse(play.isCard)
    }

    @Test fun `my partner's subscription, by card or Play, offers nothing to manage`() {
        assertEquals(PlusAction.Done, PlusOffers.action(free.copy(plan = Plan.Plus, canPurchase = false, subscription = play.copy(purchasedByMe = false))))
        assertEquals(PlusAction.Done, PlusOffers.action(free.copy(plan = Plan.Plus, canPurchase = false, subscription = card.copy(purchasedByMe = false))))
        assertEquals("Theo pays by card and manages it", PlusText.managedBy(free.copy(subscription = card.copy(purchasedByMe = false)), "Theo"))
        assertEquals("Theo manages it in Google Play", PlusText.managedBy(free.copy(subscription = play.copy(purchasedByMe = false)), "Theo"))
        assertEquals("Paid by card. Change the card or cancel in Manage billing.", PlusText.managedBy(free.copy(subscription = card), "Theo"))
    }

    @Test fun `on Free the only way to buy is Google Play's sheet, with or without card in the app`() {
        // cardInApp changes what Play's sheet offers (Google's choice screen), never the app's own buttons.
        assertEquals(PlusAction.Buy, PlusOffers.action(free.copy(cardInApp = false)))
        assertEquals(PlusAction.Buy, PlusOffers.action(free.copy(cardInApp = true)))
        assertEquals(PlusAction.Done, PlusOffers.action(null))
        assertEquals(PlusAction.Done, PlusOffers.action(free.copy(plan = Plan.Plus, complimentary = true, canPurchase = false)))
    }

    @Test fun `a lapsed card payment points to billing, not Google Play`() {
        val grace = free.copy(plan = Plan.Plus, subscription = card.copy(status = "inGracePeriod"))
        assertTrue(PlusText.subscriptionStatus(grace, "Theo")!!.startsWith("Your last card payment didn't go through. Update your card in Manage billing"))
        val partners = free.copy(plan = Plan.Plus, subscription = card.copy(status = "inGracePeriod", purchasedByMe = false))
        assertTrue(PlusText.subscriptionStatus(partners, "Theo")!!.startsWith("Theo's last card payment didn't go through."))
    }

    @Test fun `each refusal from the server has its own sentence`() {
        assertEquals("Paying by card isn't available right now. Try again later, or pay with Google Play.", PlusViewModel.cardMessage("billing.bachs_unavailable", "x"))
        assertEquals("That plan can't be paid by card right now. Pay with Google Play instead.", PlusViewModel.cardMessage("billing.unknown_plan", "x"))
        assertEquals("You already have Plus, so there's nothing to pay.", PlusViewModel.cardMessage("billing.already_plus", "x"))
        assertTrue(PlusViewModel.cardMessage("billing.no_bachs_customer", "x").startsWith("We couldn't find card billing"))
        assertEquals("The server's own words.", PlusViewModel.cardMessage("billing.something_new", "The server's own words."))
    }

    @Test fun `the entitlement reads the card switches, and older servers leave them off`() {
        val json = Json { ignoreUnknownKeys = true }
        val older = json.decodeFromString<Entitlement>("""{"plan":"free","canPurchase":true}""")
        assertFalse(older.webCheckout)
        assertFalse(older.cardInApp)
        val newer = json.decodeFromString<Entitlement>("""{"plan":"plus","canPurchase":true,"webCheckout":true,"cardInApp":true,"subscription":{"store":"bachs","productId":"heartpact_plus_monthly","status":"active","expiresAtUtc":"2026-12-01T00:00:00Z","purchasedByMe":true}}""")
        assertTrue(newer.webCheckout && newer.cardInApp)
        assertTrue(newer.subscription!!.isCard)
    }
}
