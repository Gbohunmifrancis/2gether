package app.heartpact

import android.app.Activity
import app.heartpact.billing.PurchaseState
import app.heartpact.billing.PurchaseUpdate
import app.heartpact.billing.ProductsResult
import app.heartpact.billing.StoreOffer
import app.heartpact.billing.StorePhase
import app.heartpact.billing.StoreProduct
import app.heartpact.billing.StorePurchase
import app.heartpact.data.Entitlement
import app.heartpact.data.HeartpactApi
import app.heartpact.data.Plan
import app.heartpact.data.PlanLimits
import app.heartpact.data.PlusRepository
import app.heartpact.data.SubscriptionSummary
import app.heartpact.ui.plus.Celebration
import app.heartpact.ui.plus.CelebrationDecision
import app.heartpact.ui.plus.CelebrationKind
import app.heartpact.ui.plus.CelebrationMemory
import app.heartpact.ui.plus.CelebrationText
import app.heartpact.ui.plus.PlusCelebrations
import app.heartpact.ui.plus.PlusSummaryViewModel
import app.heartpact.ui.plus.PlusUi
import app.heartpact.ui.plus.PlusViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** The Plus celebration: when it shows, which words, and that it shows once per Plus. */
class PlusCelebrationTest {
    private val plusLimits = PlanLimits(20, 2L * 1024 * 1024 * 1024, 90)
    private val free = Entitlement(Plan.Free, PlanLimits(3, 500L * 1024 * 1024, 7), plusLimits = plusLimits)
    private val mine = SubscriptionSummary("googlePlay", "heartpact_plus_yearly", "active", "2027-10-11T10:00:00Z", autoRenewing = true, purchasedByUserId = "maya", purchasedByMe = true)
    private val boughtByMe = free.copy(plan = Plan.Plus, limits = plusLimits, subscription = mine)
    private val boughtByPartner = boughtByMe.copy(subscription = mine.copy(purchasedByUserId = "theo", purchasedByMe = false))
    private val gift = free.copy(plan = Plan.Plus, limits = plusLimits, complimentary = true, complimentaryUntilUtc = "2026-12-01T00:00:00Z")

    @Test fun `the key says who bought which plan in which store, or the gift and its end, and Free has none`() {
        assertNull(PlusCelebrations.key(free))
        assertEquals("sub:googlePlay:heartpact_plus_yearly:me", PlusCelebrations.key(boughtByMe))
        assertEquals("sub:googlePlay:heartpact_plus_yearly:partner", PlusCelebrations.key(boughtByPartner))
        assertEquals("sub:bachs:heartpact_plus_yearly:me", PlusCelebrations.key(boughtByMe.copy(subscription = mine.copy(store = "bachs"))))
        assertEquals("gift:2026-12-01T00:00:00Z", PlusCelebrations.key(gift))
        // A paid plan over a gift is the paid plan.
        assertEquals(PlusCelebrations.key(boughtByMe), PlusCelebrations.key(boughtByMe.copy(complimentary = true, complimentaryUntilUtc = "2026-12-01T00:00:00Z")))
    }

    @Test fun `three variants, from this person's side`() {
        assertEquals(Celebration(CelebrationKind.BoughtByMe, null, plusLimits), PlusCelebrations.celebration(boughtByMe))
        assertEquals(Celebration(CelebrationKind.BoughtByPartner, null, plusLimits), PlusCelebrations.celebration(boughtByPartner))
        assertEquals(Celebration(CelebrationKind.Gift, "2026-12-01T00:00:00Z", plusLimits), PlusCelebrations.celebration(gift))
    }

    @Test fun `never on Free, which is remembered once so the next Plus is new`() {
        val first = PlusCelebrations.decide(free, null)
        assertNull(first.show)
        assertEquals(PlusCelebrations.FREE, first.remember)
        // Seen as Free already: nothing to do.
        assertEquals(CelebrationDecision(), PlusCelebrations.decide(free, PlusCelebrations.FREE))
        // Plus ended: Free again, nothing shown, and the old Plus forgotten.
        val ended = PlusCelebrations.decide(free, "sub:googlePlay:heartpact_plus_yearly:me")
        assertNull(ended.show)
        assertEquals(PlusCelebrations.FREE, ended.remember)
    }

    @Test fun `already Plus on this version's first look is remembered silently`() {
        listOf(boughtByMe, boughtByPartner, gift).forEach {
            val seeded = PlusCelebrations.decide(it, null)
            assertNull(seeded.show)
            assertEquals(PlusCelebrations.key(it), seeded.remember)
        }
        // Unless the caller watched it go from Free to Plus itself (bought on the Plus screen).
        assertEquals(CelebrationKind.BoughtByMe, PlusCelebrations.decide(boughtByMe, null, sawFree = true).show?.kind)
    }

    @Test fun `free to Plus shows once, and a new Plus later shows again`() {
        val shown = PlusCelebrations.decide(boughtByPartner, PlusCelebrations.FREE)
        assertEquals(CelebrationKind.BoughtByPartner, shown.show?.kind)
        assertEquals(PlusCelebrations.key(boughtByPartner), shown.remember)
        // The next look: the same Plus, nothing again.
        assertEquals(CelebrationDecision(), PlusCelebrations.decide(boughtByPartner, shown.remember))
        // A gift after the paid plan, or a longer gift, is a new Plus.
        assertEquals(CelebrationKind.Gift, PlusCelebrations.decide(gift, shown.remember).show?.kind)
        val longer = gift.copy(complimentaryUntilUtc = "2027-01-01T00:00:00Z")
        assertEquals("2027-01-01T00:00:00Z", PlusCelebrations.decide(longer, PlusCelebrations.key(gift)).show?.untilUtc)
    }

    @Test fun `memory per person on the same phone`() {
        val memory = MapMemory()
        assertNull(memory.see("maya", free))
        assertNull(memory.see("theo", boughtByPartner)) // Theo's first look on this version: already Plus, seeded.
        assertEquals(CelebrationKind.BoughtByMe, memory.see("maya", boughtByMe)?.kind)
        assertNull(memory.see("maya", boughtByMe))
        assertNull(memory.see("theo", boughtByPartner))
        assertEquals("sub:googlePlay:heartpact_plus_yearly:me", memory.map["maya"])
    }

    @Test fun `the words for each variant and the benefits from the Plus limits`() {
        assertEquals("Welcome to Heartpact Plus", CelebrationText.title(Celebration(CelebrationKind.BoughtByMe), "Theo"))
        assertEquals("Theo got you Heartpact Plus", CelebrationText.title(Celebration(CelebrationKind.BoughtByPartner), "Theo"))
        assertEquals("A gift from Heartpact", CelebrationText.title(Celebration(CelebrationKind.Gift, "2026-12-01T12:00:00Z"), "Theo"))
        assertEquals("Plus is on us. Here's what you both get.", CelebrationText.subtitle(Celebration(CelebrationKind.Gift), "Theo"))
        assertEquals(
            listOf("Unlimited daily dares", "20 food photo estimates a day", "2 GB of shared album space", "90 days of question history"),
            CelebrationText.benefits(plusLimits),
        )
        assertEquals("Up to 3 daily dares each", CelebrationText.benefits(plusLimits.copy(activeDaresPerPerson = 3)).first())
        assertEquals(emptyList<String>(), CelebrationText.benefits(null))
    }

    private class MapMemory : CelebrationMemory {
        val map = mutableMapOf<String, String>()
        override fun get(userId: String) = map[userId]
        override fun set(userId: String, key: String) { map[userId] = key }
        fun see(userId: String, entitlement: Entitlement) = with(app.heartpact.ui.plus.PlusCelebrations.decide(entitlement, get(userId))) {
            remember?.let { set(userId, it) }
            show
        }
    }
}

/** The view models: Home decides from each load and a plusStarted push; the Plus screen celebrates a purchase at once. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlusCelebrationFlowTest {
    private val server = MockWebServer()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true; coerceInputValues = true }
    private lateinit var plus: PlusRepository
    private val limits = """"freeLimits":{"foodScansPerDay":3,"memoriesBytes":524288000,"questionHistoryDays":7},"plusLimits":{"foodScansPerDay":20,"memoriesBytes":2147483648,"questionHistoryDays":90},"productIds":["heartpact_plus_monthly","heartpact_plus_yearly"]"""
    private val free = """{"plan":"free","limits":{"foodScansPerDay":3,"memoriesBytes":524288000,"questionHistoryDays":7},"canPurchase":true,$limits}"""
    private fun plusBy(me: Boolean) = """{"plan":"plus","limits":{"foodScansPerDay":20,"memoriesBytes":2147483648,"questionHistoryDays":90},"subscription":{"store":"googlePlay","productId":"heartpact_plus_yearly","status":"active","expiresAtUtc":"2027-10-11T10:00:00Z","autoRenewing":true,"purchasedByUserId":"x","purchasedByMe":$me},"canPurchase":true,$limits}"""
    private val memory = object : CelebrationMemory {
        val map = mutableMapOf<String, String>()
        override fun get(userId: String) = map[userId]
        override fun set(userId: String, key: String) { map[userId] = key }
    }

    private suspend fun StateFlow<PlusUi>.await(condition: (PlusUi) -> Boolean): PlusUi =
        withContext(Dispatchers.IO) { withTimeout(5_000) { first(condition) } }

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server.start()
        val api = Retrofit.Builder().baseUrl(server.url("/")).client(OkHttpClient())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(HeartpactApi::class.java)
        plus = PlusRepository(api, json)
    }

    @After fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
    }

    @Test fun `home celebrates the partner's purchase once, after a plusStarted push, and never on Free`() = runTest {
        val events = MutableSharedFlow<String>(extraBufferCapacity = 4)
        val model = PlusSummaryViewModel(plus, memory, "maya", events)
        server.enqueue(MockResponse().setBody(free))
        withContext(Dispatchers.IO) { withTimeout(5_000) { model.load().join() } }
        assertEquals(Plan.Free, model.ui.value.entitlement?.plan)
        assertNull(model.ui.value.celebrate)
        assertEquals(PlusCelebrations.FREE, memory.map["maya"])

        server.enqueue(MockResponse().setBody(plusBy(me = false)))
        events.emit("plusStarted")
        val shown = model.ui.await { it.celebrate != null }
        assertEquals(CelebrationKind.BoughtByPartner, shown.celebrate?.kind)
        model.celebrated()
        assertNull(model.ui.value.celebrate)

        // Home shows again: the same Plus, no second celebration.
        server.enqueue(MockResponse().setBody(plusBy(me = false)))
        withContext(Dispatchers.IO) { withTimeout(5_000) { model.load().join() } }
        assertEquals(3, server.requestCount)
        assertNull(model.ui.value.celebrate)
    }

    @Test fun `already Plus when this version first looks, nothing shown, only remembered`() = runTest {
        val model = PlusSummaryViewModel(plus, memory, "maya")
        server.enqueue(MockResponse().setBody(plusBy(me = true)))
        withContext(Dispatchers.IO) { withTimeout(5_000) { model.load().join() } }
        assertEquals(Plan.Plus, model.ui.value.entitlement?.plan)
        assertNull(model.ui.value.celebrate)
        assertEquals("sub:googlePlay:heartpact_plus_yearly:me", memory.map["maya"])
    }

    @Test fun `bought on the Plus screen celebrates there at once, and Home then doesn't`() = runTest {
        val products = ProductsResult.Ok(listOf(StoreProduct("heartpact_plus_yearly", listOf(StoreOffer("y", "yearly", null, listOf(StorePhase(23_990_000, "£23.99", "GBP", "P1Y")))))))
        val store = FakePlayStore(products)
        server.enqueue(MockResponse().setBody(free))
        val screen = PlusViewModel(plus, store, "maya", celebrations = memory)
        screen.ui.await { it.options.isNotEmpty() }
        screen.buy(Activity())
        server.enqueue(MockResponse().setBody(plusBy(me = true)))
        store.updates.emit(PurchaseUpdate.Bought(listOf(StorePurchase(listOf("heartpact_plus_yearly"), "tok", PurchaseState.Purchased, false, "maya"))))
        val shown = screen.ui.await { it.celebrate != null }
        assertEquals(CelebrationKind.BoughtByMe, shown.celebrate?.kind)
        screen.celebrated()
        assertNull(screen.ui.value.celebrate)

        val home = PlusSummaryViewModel(plus, memory, "maya")
        server.enqueue(MockResponse().setBody(plusBy(me = true)))
        withContext(Dispatchers.IO) { withTimeout(5_000) { home.load().join() } }
        assertEquals(Plan.Plus, home.ui.value.entitlement?.plan)
        assertNull(home.ui.value.celebrate)
    }
}
