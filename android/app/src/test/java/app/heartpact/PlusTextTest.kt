package app.heartpact

import app.heartpact.data.Entitlement
import app.heartpact.data.Plan
import app.heartpact.data.SubscriptionSummary
import app.heartpact.ui.plus.PlusText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneOffset

class PlusTextTest {
    private val mine = SubscriptionSummary("googlePlay", "heartpact_plus_monthly", "active", "2026-11-10T16:00:00Z", autoRenewing = true, purchasedByMe = true)

    @Test fun `the status line says whose plan it is and what happens next`() {
        val plus = Entitlement(Plan.Plus, subscription = mine)
        assertEquals("Your monthly plan renews on 10 Nov 2026.", PlusText.subscriptionStatus(plus, "Theo"))
        assertEquals("Theo's monthly plan is set not to renew. Plus ends on 10 Nov 2026.",
            PlusText.subscriptionStatus(plus.copy(subscription = mine.copy(purchasedByMe = false, autoRenewing = false, status = "canceled")), "Theo"))
        assertEquals("Google couldn't take the last payment. Fix it in Google Play by 10 Nov 2026 to keep Plus.",
            PlusText.subscriptionStatus(plus.copy(subscription = mine.copy(status = "inGracePeriod")), "Theo"))
        assertNull(PlusText.subscriptionStatus(Entitlement(), "Theo"))
    }

    @Test fun `subtitles and the Us row follow where Plus comes from`() {
        assertEquals("One plan covers both of you. Either of you can buy it.", PlusText.subtitle(Entitlement(), "Theo"))
        assertEquals("Theo pays for Plus. It covers you both.", PlusText.subtitle(Entitlement(Plan.Plus, subscription = mine.copy(purchasedByMe = false)), "Theo"))
        assertEquals("Plus · Theo pays, it covers you both", PlusText.usRow(Entitlement(Plan.Plus, subscription = mine.copy(purchasedByMe = false)), "Theo"))
        assertEquals("Plus · on us until 10 Nov 2026", PlusText.usRow(Entitlement(Plan.Plus, complimentary = true, complimentaryUntilUtc = "2026-11-10T12:00:00Z"), "Theo"))
        assertEquals("10 Nov 2026", PlusText.date("2026-11-10T23:30:00Z", ZoneOffset.UTC))
    }
}
