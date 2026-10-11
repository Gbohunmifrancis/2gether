package app.heartpact

import app.heartpact.billing.PlusOffers
import app.heartpact.billing.StoreOffer
import app.heartpact.billing.StorePhase
import app.heartpact.billing.StoreProduct
import app.heartpact.data.PlanLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlusOffersTest {
    private fun phase(micros: Long, text: String, period: String, cycles: Int = 0) = StorePhase(micros, text, "GBP", period, cycles)
    private val monthlyBase = StoreOffer("m-base", "monthly", null, listOf(phase(2_990_000, "£2.99", "P1M")))
    private val yearlyBase = StoreOffer("y-base", "yearly", null, listOf(phase(23_990_000, "£23.99", "P1Y")))
    private val yearlyTrial = StoreOffer("y-trial", "yearly", "trial", listOf(phase(0, "Free", "P1W", 1), phase(23_990_000, "£23.99", "P1Y")))

    @Test fun `the account id is the user id without dashes`() =
        assertEquals("0a1b2c3d4e5f60718293a4b5c6d7e8f9", PlusOffers.accountId("0a1b2c3d-4e5f-6071-8293-a4b5c6d7e8f9"))

    @Test fun `a trial the person may take beats the base plan, and ties go to the base plan`() {
        assertEquals("y-trial", PlusOffers.chooseOffer(StoreProduct("heartpact_plus_yearly", listOf(yearlyBase, yearlyTrial)))?.token)
        val sameOffer = yearlyBase.copy(token = "y-offer", offerId = "loyal")
        assertEquals("y-base", PlusOffers.chooseOffer(StoreProduct("p", listOf(sameOffer, yearlyBase)))?.token)
        assertNull(PlusOffers.chooseOffer(StoreProduct("p", emptyList())))
    }

    @Test fun `price lines read as sentences`() {
        assertEquals("£2.99 a month", PlusOffers.priceLine(monthlyBase))
        assertEquals("1 week free, then £23.99 a year", PlusOffers.priceLine(yearlyTrial))
        val intro = StoreOffer("i", "monthly", "intro", listOf(phase(990_000, "£0.99", "P1M", 3), phase(2_990_000, "£2.99", "P1M")))
        assertEquals("£0.99 for 3 months, then £2.99 a month", PlusOffers.priceLine(intro))
        assertEquals("£8.99 every 3 months", PlusOffers.priceLine(StoreOffer("q", "q", null, listOf(phase(8_990_000, "£8.99", "P3M")))))
    }

    @Test fun `periods and monthly prices`() {
        assertEquals("7 days", PlusOffers.periodText("P7D"))
        assertEquals("2 weeks", PlusOffers.periodText("P1W", 2))
        assertNull(PlusOffers.periodText("nonsense"))
        assertEquals(1_999_166L, PlusOffers.monthlyMicros(phase(23_990_000, "£23.99", "P1Y")))
        assertEquals(2_990_000L, PlusOffers.monthlyMicros(phase(2_990_000, "£2.99", "P1M")))
    }

    @Test fun `plans come yearly first with the saving over monthly`() {
        val options = PlusOffers.options(listOf(StoreProduct("heartpact_plus_monthly", listOf(monthlyBase)), StoreProduct("heartpact_plus_yearly", listOf(yearlyBase))))
        assertEquals(listOf("Yearly", "Monthly"), options.map { it.name })
        assertEquals("Save 33%", options[0].savings)
        assertNull(options[1].savings)
    }

    @Test fun `no saving across currencies or when it is tiny`() {
        val euroYear = StoreOffer("y", "yearly", null, listOf(StorePhase(23_990_000, "€23.99", "EUR", "P1Y")))
        assertNull(PlusOffers.options(listOf(StoreProduct("heartpact_plus_monthly", listOf(monthlyBase)), StoreProduct("heartpact_plus_yearly", listOf(euroYear))))[0].savings)
        val dearYear = StoreOffer("y", "yearly", null, listOf(phase(35_000_000, "£35.00", "P1Y")))
        assertNull(PlusOffers.options(listOf(StoreProduct("heartpact_plus_monthly", listOf(monthlyBase)), StoreProduct("heartpact_plus_yearly", listOf(dearYear))))[0].savings)
    }

    @Test fun `the comparison reads the server's limits`() {
        val rows = PlusOffers.comparison(PlanLimits(3, 500L * 1024 * 1024, 7), PlanLimits(20, 2L * 1024 * 1024 * 1024, 90))
        assertEquals(listOf("3", "500 MB", "7 days"), rows.map { it.second })
        assertEquals(listOf("20", "2 GB", "90 days"), rows.map { it.third })
        assertEquals("1.5 GB", PlusOffers.storage(1536L * 1024 * 1024))
    }
}
