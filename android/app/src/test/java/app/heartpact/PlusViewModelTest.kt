package app.heartpact

import android.app.Activity
import app.heartpact.billing.PlayStore
import app.heartpact.billing.PlusAction
import app.heartpact.billing.PlusOffers
import app.heartpact.billing.PlusRestore
import app.heartpact.billing.ProductsResult
import app.heartpact.billing.PurchaseState
import app.heartpact.billing.PurchaseUpdate
import app.heartpact.billing.RestoreResult
import app.heartpact.billing.StoreOffer
import app.heartpact.billing.StorePhase
import app.heartpact.billing.StoreProduct
import app.heartpact.billing.StorePurchase
import app.heartpact.data.HeartpactApi
import app.heartpact.data.Plan
import app.heartpact.data.PlusRepository
import app.heartpact.ui.plus.PlusUi
import app.heartpact.ui.plus.PlusViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** A Play that sells what it's told to and reports what the test says happened. */
class FakePlayStore(var products: ProductsResult = ProductsResult.Ok(emptyList()), var ownedList: List<StorePurchase>? = emptyList()) : PlayStore {
    override val updates = MutableSharedFlow<PurchaseUpdate>(extraBufferCapacity = 8)
    val launched = mutableListOf<Triple<String, String, String>>()
    var buyError: String? = null
    /** What the Plus screen last asked for: Google's billing choice screen on purchases, or not. */
    var cardChoice: Boolean? = null
    override fun offerCardChoice(enabled: Boolean) { cardChoice = enabled }
    override suspend fun products(ids: List<String>) = products
    override suspend fun owned() = ownedList
    override suspend fun buy(activity: Activity, productId: String, offerToken: String, accountId: String): String? {
        launched += Triple(productId, offerToken, accountId)
        return buyError
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PlusViewModelTest {
    private val server = MockWebServer()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true; coerceInputValues = true }
    private lateinit var plus: PlusRepository
    private val userId = "0a1b2c3d-4e5f-6071-8293-a4b5c6d7e8f9"
    private val account = "0a1b2c3d4e5f60718293a4b5c6d7e8f9"
    private val limits = """"freeLimits":{"foodScansPerDay":3,"memoriesBytes":524288000,"questionHistoryDays":7},"plusLimits":{"foodScansPerDay":20,"memoriesBytes":2147483648,"questionHistoryDays":90},"productIds":["heartpact_plus_monthly","heartpact_plus_yearly"]"""
    private val free = """{"plan":"free","limits":{"foodScansPerDay":3,"memoriesBytes":524288000,"questionHistoryDays":7},"subscription":null,"canPurchase":true,"complimentary":false,$limits}"""
    private val plusMine = """{"plan":"plus","limits":{"foodScansPerDay":20,"memoriesBytes":2147483648,"questionHistoryDays":90},"subscription":{"store":"googlePlay","productId":"heartpact_plus_yearly","status":"active","expiresAtUtc":"2027-10-11T10:00:00Z","autoRenewing":true,"purchasedByUserId":"$userId","purchasedByMe":true},"canPurchase":true,$limits}"""
    private val products = ProductsResult.Ok(listOf(
        StoreProduct("heartpact_plus_monthly", listOf(StoreOffer("m", "monthly", null, listOf(StorePhase(2_990_000, "£2.99", "GBP", "P1M"))))),
        StoreProduct("heartpact_plus_yearly", listOf(StoreOffer("y", "yearly", null, listOf(StorePhase(23_990_000, "£23.99", "GBP", "P1Y"))))),
    ))
    private fun bought(state: PurchaseState = PurchaseState.Purchased, accountId: String? = account, acknowledged: Boolean = false, token: String = "tok") =
        StorePurchase(listOf("heartpact_plus_yearly"), token, state, acknowledged, accountId)

    /** The HTTP calls finish on OkHttp's threads, outside the test scheduler: wait for the state itself. */
    private suspend fun PlusViewModel.awaitUi(condition: (PlusUi) -> Boolean): PlusUi =
        withContext(Dispatchers.IO) { withTimeout(5_000) { ui.first(condition) } }

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

    @Test fun `free couple sees yearly first and buys it for their own account, then Plus is verified with the server`() = runTest {
        server.enqueue(MockResponse().setBody(free))
        val store = FakePlayStore(products)
        val model = PlusViewModel(plus, store, userId)
        model.awaitUi { it.options.isNotEmpty() }
        assertEquals(listOf("heartpact_plus_yearly", "heartpact_plus_monthly"), model.ui.value.options.map { it.productId })
        assertEquals("heartpact_plus_yearly", model.ui.value.selected)

        model.buy(Activity())
        assertEquals(Triple("heartpact_plus_yearly", "y", account), store.launched.single())
        assertTrue(model.ui.value.busy)

        server.enqueue(MockResponse().setBody(plusMine))
        store.updates.emit(PurchaseUpdate.Bought(listOf(bought())))
        model.awaitUi { it.entitlement?.plan == Plan.Plus && !it.busy }
        server.takeRequest()
        val verify = server.takeRequest()
        assertEquals("/api/billing/google/verify", verify.path)
        assertEquals("""{"productId":"heartpact_plus_yearly","purchaseToken":"tok"}""", verify.body.readUtf8())
        assertEquals(Plan.Plus, model.ui.value.entitlement?.plan)
        assertTrue(model.ui.value.welcomed)
        assertFalse(model.ui.value.busy)
    }

    @Test fun `a pending payment waits, a cancelled sheet just closes, a failed one says why`() = runTest {
        server.enqueue(MockResponse().setBody(free))
        val store = FakePlayStore(products)
        val model = PlusViewModel(plus, store, userId)
        model.awaitUi { it.options.isNotEmpty() }
        store.updates.emit(PurchaseUpdate.Bought(listOf(bought(PurchaseState.Pending))))
        assertTrue(model.ui.value.pending)
        assertEquals(1, server.requestCount)

        store.updates.emit(PurchaseUpdate.Cancelled)
        assertFalse(model.ui.value.busy)
        assertNull(model.ui.value.note)

        store.updates.emit(PurchaseUpdate.Failed("Can't reach Google Play."))
        assertEquals("Can't reach Google Play.", model.ui.value.note)
    }

    @Test fun `without Play the screen explains instead of offering plans`() = runTest {
        server.enqueue(MockResponse().setBody(free))
        val model = PlusViewModel(plus, FakePlayStore(ProductsResult.Unavailable("Google Play isn't available on this phone.")), userId)
        model.awaitUi { it.storeMessage != null }
        assertTrue(model.ui.value.options.isEmpty())
        assertEquals("Google Play isn't available on this phone.", model.ui.value.storeMessage)
    }

    @Test fun `when the partner pays, nothing is offered and Play is never asked`() = runTest {
        server.enqueue(MockResponse().setBody(plusMine.replace("\"purchasedByMe\":true", "\"purchasedByMe\":false").replace("\"canPurchase\":true", "\"canPurchase\":false")))
        val store = object : PlayStore by FakePlayStore() {
            override suspend fun products(ids: List<String>): ProductsResult = error("Play must not be asked")
        }
        val model = PlusViewModel(plus, store, userId)
        model.awaitUi { !it.loading }
        assertTrue(model.ui.value.isPlus)
        assertTrue(model.ui.value.options.isEmpty())
    }

    @Test fun `restore verifies only this person's finished purchases`() = runTest {
        val store = FakePlayStore(ownedList = listOf(
            bought(token = "mine", acknowledged = true),
            bought(token = "someone-else", accountId = "ffffffffffffffffffffffffffffffff"),
            bought(PurchaseState.Pending, token = "pending"),
        ))
        server.enqueue(MockResponse().setBody(plusMine))
        val result = PlusRestore.run(store, plus, userId, onlyUnacknowledged = false)
        assertTrue(result is RestoreResult.Restored)
        assertEquals(1, server.requestCount)
        assertTrue(server.takeRequest().body.readUtf8().contains("\"mine\""))

        // At start-up only what the server hasn't acknowledged is sent.
        assertEquals(RestoreResult.NothingToRestore(pending = true), PlusRestore.run(store, plus, userId, onlyUnacknowledged = true))
        assertEquals(1, server.requestCount)
        assertEquals(RestoreResult.StoreUnavailable, PlusRestore.run(FakePlayStore(ownedList = null), plus, userId, onlyUnacknowledged = true))
    }

    private val freeCard = free.replace("\"canPurchase\":true", "\"canPurchase\":true,\"webCheckout\":true,\"cardInApp\":true")
    private val plusCard = plusMine.replace("\"store\":\"googlePlay\"", "\"store\":\"bachs\"")

    @Test fun `Google's choice screen is asked for only when the server allows card in the app`() = runTest {
        server.enqueue(MockResponse().setBody(free))
        val off = FakePlayStore(products)
        PlusViewModel(plus, off, userId).awaitUi { it.options.isNotEmpty() }
        assertEquals(false, off.cardChoice)

        server.enqueue(MockResponse().setBody(freeCard))
        val on = FakePlayStore(products)
        PlusViewModel(plus, on, userId).awaitUi { it.options.isNotEmpty() }
        assertEquals(true, on.cardChoice)
    }

    @Test fun `choosing card on Google's screen opens a Bachs checkout, then waits for the server to say Plus`() = runTest {
        server.enqueue(MockResponse().setBody(freeCard))
        val store = FakePlayStore(products)
        val model = PlusViewModel(plus, store, userId, pollEveryMs = 50, pollForMs = 2_000)
        model.awaitUi { it.options.isNotEmpty() }
        server.takeRequest()
        model.buy(Activity())

        server.enqueue(MockResponse().setBody("""{"checkoutUrl":"https://checkout.bachs.io/c/abc"}"""))
        val link = async(Dispatchers.IO) { withTimeout(5_000) { model.links.first() } }
        store.updates.emit(PurchaseUpdate.ChoseCard(listOf("heartpact_plus_yearly"), "ext-token"))
        assertEquals("https://checkout.bachs.io/c/abc", link.await())
        val checkout = server.takeRequest()
        assertEquals("/api/billing/bachs/checkout", checkout.path)
        assertEquals("""{"plan":"yearly","from":"app","externalTransactionToken":"ext-token"}""", checkout.body.readUtf8())
        model.awaitUi { it.awaitingCard && !it.busy }

        // Back from the browser: still Free once, then Bachs' webhook has reached the server.
        server.enqueue(MockResponse().setBody(freeCard))
        server.enqueue(MockResponse().setBody(plusCard))
        model.resumed()
        model.awaitUi { it.isPlus && !it.awaitingCard }
        assertTrue(model.ui.value.welcomed)
        assertTrue(model.ui.value.entitlement!!.subscription!!.isCard)
    }

    @Test fun `with the card switch on and Play not selling, Pay by card opens a Bachs checkout for the chosen plan`() = runTest {
        val direct = free.dropLast(1) + ""","webCheckout":true,"cardDirect":true,"cardPrices":{"monthly":"$7.99","yearly":"$39.99"}}"""
        server.enqueue(MockResponse().setBody(direct))
        val store = FakePlayStore(ProductsResult.Unavailable("Plus isn't on sale in your country yet."))
        val model = PlusViewModel(plus, store, userId, pollEveryMs = 50, pollForMs = 2_000)
        model.awaitUi { it.entitlement?.cardDirect == true && it.storeMessage != null }
        server.takeRequest()
        assertEquals("monthly", model.ui.value.cardSelected)
        model.selectCard("yearly")

        server.enqueue(MockResponse().setBody("""{"checkoutUrl":"https://checkout.bachs.io/c/direct"}"""))
        val link = async(Dispatchers.IO) { withTimeout(5_000) { model.links.first() } }
        model.payByCard()
        assertEquals("https://checkout.bachs.io/c/direct", link.await())
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("\"plan\":\"yearly\"") && body.contains("\"from\":\"app\""))
        assertFalse(body, body.contains("ext"))
        model.awaitUi { it.awaitingCard && !it.busy }
        assertTrue(store.launched.isEmpty())
    }

    @Test fun `when the payment isn't confirmed in time the screen stops waiting and says so`() = runTest {
        server.enqueue(MockResponse().setBody(freeCard))
        val store = FakePlayStore(products)
        val model = PlusViewModel(plus, store, userId, pollEveryMs = 20, pollForMs = 100)
        model.awaitUi { it.options.isNotEmpty() }
        server.enqueue(MockResponse().setBody("""{"checkoutUrl":"https://checkout.bachs.io/c/abc"}"""))
        store.updates.emit(PurchaseUpdate.ChoseCard(listOf("heartpact_plus_monthly"), "t"))
        model.awaitUi { it.awaitingCard }
        repeat(5) { server.enqueue(MockResponse().setBody(freeCard)) }
        model.resumed()
        val ui = model.awaitUi { !it.awaitingCard }
        assertFalse(ui.isPlus)
        assertTrue(ui.note!!.startsWith("We haven't heard about your card payment yet."))
    }

    @Test fun `a refused card checkout says why, and an unknown product never reaches the server`() = runTest {
        server.enqueue(MockResponse().setBody(freeCard))
        val store = FakePlayStore(products)
        val model = PlusViewModel(plus, store, userId)
        model.awaitUi { it.options.isNotEmpty() }
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"code":"billing.bachs_unavailable","message":"Card payments are not set up."}"""))
        store.updates.emit(PurchaseUpdate.ChoseCard(listOf("heartpact_plus_monthly"), "t"))
        assertEquals("Paying by card isn't available right now. Try again later, or pay with Google Play.", model.awaitUi { it.note != null && !it.busy }.note)
        assertFalse(model.ui.value.awaitingCard)

        val before = server.requestCount
        store.updates.emit(PurchaseUpdate.ChoseCard(listOf("heartpact_test_product"), "t"))
        // The choice is handled on the collector's thread: wait for its note rather than read it the moment it is sent.
        val refused = "That plan can't be paid by card right now. Pay with Google Play instead."
        assertEquals(refused, model.awaitUi { it.note == refused }.note)
        assertEquals(before, server.requestCount)

        // Already Plus (bought on the website meanwhile): say so and show the plan.
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"code":"billing.already_plus","message":"x"}"""))
        server.enqueue(MockResponse().setBody(plusCard))
        store.updates.emit(PurchaseUpdate.ChoseCard(listOf("heartpact_plus_yearly"), "t"))
        model.awaitUi { it.isPlus }
        assertEquals(PlusAction.ManageBilling, PlusOffers.action(model.ui.value.entitlement))
    }

    @Test fun `manage billing opens Bachs' portal, made on tap`() = runTest {
        server.enqueue(MockResponse().setBody(plusCard))
        val model = PlusViewModel(plus, FakePlayStore(products), userId)
        model.awaitUi { it.isPlus }
        server.takeRequest()
        server.enqueue(MockResponse().setBody("""{"url":"https://billing.bachs.io/p/xyz"}"""))
        val link = async(Dispatchers.IO) { withTimeout(5_000) { model.links.first() } }
        model.manageBilling()
        assertEquals("https://billing.bachs.io/p/xyz", link.await())
        assertEquals("/api/billing/bachs/portal", server.takeRequest().path)
        assertFalse(model.awaitUi { !it.busy }.awaitingCard)

        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"code":"billing.no_bachs_customer","message":"x"}"""))
        model.manageBilling()
        assertTrue(model.awaitUi { it.note != null }.note!!.startsWith("We couldn't find card billing"))
    }

    @Test fun `a refused purchase keeps the server's words`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"code":"billing.not_your_purchase","message":"This purchase belongs to another account."}"""))
        val result = PlusRestore.run(FakePlayStore(ownedList = listOf(bought())), plus, userId, onlyUnacknowledged = true)
        assertEquals(RestoreResult.Refused("This purchase belongs to another account."), result)
    }
}
