package app.heartpact

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.heartpact.data.ApiResult
import app.heartpact.data.AuthRepository
import app.heartpact.data.CoupleRepository
import app.heartpact.data.CoupleSide
import app.heartpact.data.CoupleStatus
import app.heartpact.data.HeartpactApi
import app.heartpact.data.SessionStore
import app.heartpact.data.bearer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** The real client stack (Retrofit, serialization, interceptor, DataStore) against a scripted server. */
class ApiTest {
    @get:Rule val folder = TemporaryFolder()
    private val server = MockWebServer()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true; coerceInputValues = true }
    private lateinit var session: SessionStore
    private lateinit var auth: AuthRepository
    private lateinit var couple: CoupleRepository

    @Before fun setUp() {
        server.start()
        session = SessionStore(PreferenceDataStoreFactory.create { folder.newFile("session.preferences_pb") }, json)
        val api = Retrofit.Builder().baseUrl(server.url("/"))
            .client(OkHttpClient.Builder().addInterceptor(bearer(session)).build())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build().create(HeartpactApi::class.java)
        auth = AuthRepository(api, session, json)
        couple = CoupleRepository(api, session, json)
    }

    @After fun tearDown() = server.shutdown()

    private val authBody = """{"accessToken":"tok-1","user":{"id":"u1","email":"maya@example.com","displayName":"Maya","coupleId":"c1","timeZoneId":"UTC","avatarUrl":null,"mapColor":"#1F2557","cycleOwner":true,"gender":"female","futureField":1}}"""

    @Test fun `signing in stores the session and later calls carry the token`() = runTest {
        server.enqueue(MockResponse().setBody(authBody))
        server.enqueue(MockResponse().setBody("""{"id":"c1","status":"active","linkedAtUtc":"2026-10-01T10:00:00Z","members":[{"userId":"u1","displayName":"Maya","side":"left"},{"userId":"u2","displayName":"Theo","side":"right"}]}"""))

        val result = auth.login(" maya@example.com ", "longenough")
        assertTrue(result is ApiResult.Ok)
        val login = server.takeRequest()
        assertEquals("/api/auth/login", login.path)
        assertTrue(login.body.readUtf8().contains("\"email\":\"maya@example.com\""))
        assertNull(login.getHeader("Authorization"))

        val current = couple.current("u1")
        assertEquals(CoupleStatus.Active, (current as ApiResult.Ok).value.status)
        assertEquals("Bearer tok-1", server.takeRequest().getHeader("Authorization"))

        val stored = session.load()!!
        assertEquals("Maya", stored.user.displayName)
        assertEquals(CoupleStatus.Active, stored.coupleStatus)
        assertEquals(CoupleSide.Left, stored.side)
    }

    @Test fun `api errors keep their code and message`() = runTest {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"code":"auth.email_exists","message":"An account with this email already exists."}"""))
        val result = auth.register(app.heartpact.data.RegisterRequest("maya@example.com", "longenough", "Maya", "1996-05-14", app.heartpact.data.Gender.Female))
        assertEquals(ApiResult.Failure("auth.email_exists", "An account with this email already exists.", 409), result)
        assertTrue(server.takeRequest().body.readUtf8().contains("\"gender\":\"female\""))
        assertNull(session.load())
    }

    @Test fun `an error without a body still gets a sentence`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        val result = couple.createInvite() as ApiResult.Failure
        assertEquals(503, result.status)
        assertTrue(result.message.isNotBlank())
    }

    @Test fun `no server means offline, not a crash`() = runTest {
        server.shutdown()
        assertEquals(ApiResult.Offline, auth.login("maya@example.com", "longenough"))
    }

    @Test fun `accepting an invite replaces the token, and signing out forgets it`() = runTest {
        server.enqueue(MockResponse().setBody(authBody.replace("tok-1", "tok-2")))
        couple.accept(code = "482039")
        assertTrue(server.takeRequest().body.readUtf8().contains("\"code\":\"482039\""))
        assertEquals("tok-2", session.token)
        auth.signOut()
        assertNull(session.token)
        assertNull(session.load())
    }
}
