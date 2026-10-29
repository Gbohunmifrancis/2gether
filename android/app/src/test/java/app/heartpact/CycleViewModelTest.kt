package app.heartpact

import app.heartpact.data.CycleRepository
import app.heartpact.data.FlowLevel
import app.heartpact.data.HeartpactApi
import app.heartpact.data.PregnancyRepository
import app.heartpact.ui.cycle.CycleMode
import app.heartpact.ui.cycle.CycleUi
import app.heartpact.ui.cycle.CycleViewModel
import app.heartpact.ui.pregnancy.PregnancyUi
import app.heartpact.ui.pregnancy.PregnancyViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.LocalDate
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class CycleViewModelTest {
    private val server = MockWebServer()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true; coerceInputValues = true }
    private lateinit var api: HeartpactApi
    private val today = LocalDate.of(2026, 10, 11)
    /** Path (without query) to body; a list gives one body per call, the last one repeating. */
    private val routes = mutableMapOf<String, MutableList<String>>()

    private fun me(owner: Boolean) = """{"id":"u1","email":"maya@example.com","displayName":"Maya","cycleOwner":$owner,"gender":"female"}"""

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                val bodies = routes["${request.method} $path"] ?: return MockResponse().setResponseCode(404)
                return MockResponse().setBody(if (bodies.size > 1) bodies.removeAt(0) else bodies.first())
            }
        }
        server.start()
        api = Retrofit.Builder().baseUrl(server.url("/")).client(OkHttpClient())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(HeartpactApi::class.java)
    }

    @After fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
    }

    private suspend fun CycleViewModel.awaitUi(condition: (CycleUi) -> Boolean): CycleUi =
        withContext(Dispatchers.IO) { withTimeout(5_000) { ui.first(condition) } }

    private suspend fun PregnancyViewModel.awaitUi(condition: (PregnancyUi) -> Boolean): PregnancyUi =
        withContext(Dispatchers.IO) { withTimeout(5_000) { ui.first(condition) } }

    private fun requests(): List<RecordedRequest> = generateSequence { server.takeRequest(1, TimeUnit.SECONDS) }.toList()

    @Test fun `a woman whose partner tracks a cycle and shares nothing sees what anyone shown nothing sees`() = runTest {
        routes["GET /api/auth/me"] = mutableListOf(me(owner = false))
        routes["GET /api/cycle/shared"] = mutableListOf("""{"profile":null,"prediction":null,"logs":[]}""")
        val model = CycleViewModel(CycleRepository(api, json)) { today }
        model.load()
        assertEquals(CycleMode.Unavailable, model.awaitUi { it.mode != CycleMode.Loading }.mode)
        assertTrue(requests().none { it.path!!.contains("claim") })
    }

    @Test fun `a period logged on a past day saves on that day and the forecast follows the server`() = runTest {
        routes["GET /api/auth/me"] = mutableListOf(me(owner = true))
        routes["GET /api/cycle/profile"] = mutableListOf("""{"userId":"u1","averageCycleLengthDays":28,"averagePeriodLengthDays":5,"shareLevel":"none","lastPeriodStartDate":"2026-09-12"}""")
        routes["GET /api/cycle/prediction"] = mutableListOf(
            """{"nextPeriodStart":"2026-10-10","cycleStart":"2026-09-12","cycleDay":30,"lastPeriodStart":"2026-09-12","daysLate":1}""",
            """{"nextPeriodStart":"2026-11-06","cycleStart":"2026-10-09","cycleDay":3,"lastPeriodStart":"2026-10-09"}""",
        )
        routes["GET /api/cycle/logs"] = mutableListOf("[]")
        routes["PUT /api/cycle/logs/2026-10-09"] = mutableListOf("""{"logDate":"2026-10-09","flowLevel":"light"}""")
        val model = CycleViewModel(CycleRepository(api, json)) { today }
        model.load()
        model.awaitUi { it.mode == CycleMode.Owner }
        assertEquals(1, model.ui.value.prediction.daysLate)

        model.pickDay(today.minusDays(61))
        assertNull("only the last 60 days", model.ui.value.day)
        model.pickDay(LocalDate.of(2026, 10, 9))
        model.flow(FlowLevel.Light)
        assertEquals(FlowLevel.Light, model.ui.value.logFor(LocalDate.of(2026, 10, 9)).flowLevel)
        advanceTimeBy(600)
        val reloaded = model.awaitUi { it.prediction.lastPeriodStart == "2026-10-09" }
        assertNull(reloaded.prediction.daysLate)

        val sent = requests()
        assertTrue(sent.single { it.method == "GET" && it.path!!.startsWith("/api/cycle/logs") }.path!!.contains("from=2026-08-12"))
        assertTrue(sent.single { it.method == "PUT" }.body.readUtf8().contains("\"light\""))
        assertTrue("the profile is not rewritten: the server reads the start from the logs", sent.none { it.method == "PUT" && it.path == "/api/cycle/profile" })
    }

    @Test fun `ended pregnancies are listed so they can be deleted`() = runTest {
        routes["GET /api/pregnancy"] = mutableListOf(
            """[{"id":"p1","isMine":true,"dueDate":"2027-03-14","status":"ended","shareWithPartner":false,"endedAtUtc":"2026-10-01T10:00:00Z"}]""",
            "[]",
        )
        routes["DELETE /api/pregnancy/p1"] = mutableListOf("")
        val model = PregnancyViewModel(PregnancyRepository(api, json), today = { today })
        model.load()
        val ui = model.awaitUi { it.loaded }
        assertNull(ui.pregnancy)
        assertEquals(listOf("p1"), ui.ended.map { it.id })

        model.delete("p1")
        model.awaitUi { it.ended.isEmpty() && !it.busy }
        val sent = requests()
        assertTrue(sent.first().path!!.contains("includeEnded=true"))
        assertTrue(sent.any { it.method == "DELETE" && it.path == "/api/pregnancy/p1" })
    }
}
