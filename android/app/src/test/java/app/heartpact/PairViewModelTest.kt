package app.heartpact

import app.heartpact.data.CoupleRepository
import app.heartpact.data.HeartpactApi
import app.heartpact.data.SessionStore
import app.heartpact.ui.pair.InviteInput
import app.heartpact.ui.pair.PairState
import app.heartpact.ui.pair.PairViewModel
import app.heartpact.ui.pair.PendingLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
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
import java.util.concurrent.ConcurrentLinkedQueue

/** The pair screen's moves against a scripted server: the sixth digit sends, links join, the partner's arrival celebrates. */
@OptIn(ExperimentalCoroutinesApi::class)
class PairViewModelTest {
    private val server = MockWebServer()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true; coerceInputValues = true }
    private lateinit var couple: CoupleRepository
    private val routes = mutableMapOf<String, MutableList<MockResponse>>()
    private val seen = ConcurrentLinkedQueue<String>()
    private val token = "ab".repeat(24)

    private val pending = """{"id":"c1","status":"pendingInvite","members":[{"userId":"u1","displayName":"Theo","side":"left"}]}"""
    private val active = """{"id":"c1","status":"active","members":[{"userId":"u2","displayName":"Maya Okafor","side":"left"},{"userId":"u1","displayName":"Theo","side":"right"}]}"""
    private val auth = """{"accessToken":"tok-2","user":{"id":"u1","email":"theo@example.com","displayName":"Theo"}}"""
    private val invite = """{"id":"i1","code":"482039","token":"${"cd".repeat(24)}","expiresAtUtc":"2099-01-01T00:00:00Z"}"""

    private fun route(key: String, vararg bodies: String) { routes[key] = bodies.map { MockResponse().setBody(it) }.toMutableList() }
    private fun route(key: String, response: MockResponse) { routes[key] = mutableListOf(response) }

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val key = "${request.method} ${request.requestUrl!!.encodedPath}"
                seen += key + " " + request.body.readUtf8()
                val list = routes[key] ?: return MockResponse().setResponseCode(404)
                return if (list.size > 1) list.removeAt(0) else list.first()
            }
        }
        server.start()
        val api = Retrofit.Builder().baseUrl(server.url("/")).client(OkHttpClient())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(HeartpactApi::class.java)
        couple = CoupleRepository(api, SessionStore(MemoryPreferences(), json), json)
    }

    @After fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun PairViewModel.await(condition: (PairState) -> Boolean): PairState = runBlocking {
        withContext(Dispatchers.IO) { withTimeout(5_000) { state.first(condition) } }
    }

    @Test fun `the sixth digit sends the code, and linking celebrates with the partner's first name`() {
        route("POST /api/couple/invites", invite)
        route("GET /api/couple", active)
        route("POST /api/couple/invites/accept", auth)
        val model = PairViewModel(couple, "u1", null, pollMs = 60_000)
        model.await { it.invite != null }
        model.startEntering()
        "48203".forEach { model.code(model.state.value.code + it) }
        assertTrue(seen.none { it.startsWith("POST /api/couple/invites/accept") })
        model.code("482031")
        val state = model.await { it.celebrating != null }
        assertEquals("Maya", state.celebrating)
        assertTrue(seen.any { it.startsWith("POST /api/couple/invites/accept") && it.contains("\"code\":\"482031\"") })
        model.celebrated()
        assertTrue(model.state.value.linked)
    }

    @Test fun `a wrong code says so by the boxes and waits for the next digit`() {
        route("POST /api/couple/invites", invite)
        route("POST /api/couple/invites/accept", MockResponse().setResponseCode(404).setBody("""{"code":"invite.invalid","message":"x"}"""))
        val model = PairViewModel(couple, "u1", null, pollMs = 60_000)
        model.code("111111")
        val state = model.await { it.codeError != null }
        assertEquals("That code didn't work. Check it with your partner, or ask for a new one.", state.codeError)
        assertNull(state.celebrating)
    }

    @Test fun `a link followed through sign-up joins at once, saying who it's joining`() {
        route("POST /api/couple/invites/preview", """{"inviterFirstName":"Maya","expiresAtUtc":"2099-01-01T00:00:00Z"}""")
        route("POST /api/couple/invites/accept", auth)
        route("GET /api/couple", active)
        val model = PairViewModel(couple, "u1", PendingLink(token, ask = false), pollMs = 60_000)
        assertEquals("Maya", model.await { it.celebrating != null }.celebrating)
        // No invite of its own was made on the way.
        assertTrue(seen.none { it.startsWith("POST /api/couple/invites ") })
        assertTrue(seen.any { it.startsWith("POST /api/couple/invites/accept") && it.contains(token) })
    }

    @Test fun `a link opened while signed in asks first, by name`() {
        route("POST /api/couple/invites", invite)
        route("POST /api/couple/invites/preview", """{"inviterFirstName":"Maya","expiresAtUtc":"2099-01-01T00:00:00Z"}""")
        route("POST /api/couple/invites/accept", auth)
        route("GET /api/couple", active)
        val model = PairViewModel(couple, "u1", PendingLink(token, ask = true), pollMs = 60_000)
        val asking = model.await { it.confirm != null }
        assertEquals("Maya", asking.confirm!!.name)
        assertTrue(seen.none { it.startsWith("POST /api/couple/invites/accept") })
        model.confirmLink()
        assertEquals("Maya", model.await { it.celebrating != null }.celebrating)
    }

    @Test fun `the partner joining shows at once from the hub, without waiting for the poll`() {
        route("POST /api/couple/invites", invite)
        route("GET /api/couple", active)
        val events = MutableSharedFlow<String>(extraBufferCapacity = 4)
        val model = PairViewModel(couple, "u1", null, events, pollMs = 600_000)
        model.await { it.invite != null }
        events.tryEmit("coupleConnected")
        assertEquals("Maya", model.await { it.celebrating != null }.celebrating)
    }

    @Test fun `a scanned code goes in like a typed one`() {
        route("POST /api/couple/invites", invite)
        route("POST /api/couple/invites/accept", auth)
        route("GET /api/couple", pending, active)
        val model = PairViewModel(couple, "u1", null, pollMs = 60_000)
        model.use(InviteInput.Code("482031"))
        model.await { it.celebrating != null }
        assertTrue(seen.any { it.contains("\"code\":\"482031\"") })
    }
}
