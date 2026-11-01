package app.heartpact

import app.heartpact.data.ApiResult
import app.heartpact.data.CoupleRepository
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
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.Instant

/** The invite the pair screen shows is kept, so opening it again never revokes a code already sent. */
class InviteApiTest {
    private val server = MockWebServer()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true; coerceInputValues = true }
    private lateinit var session: SessionStore
    private lateinit var couple: CoupleRepository
    private val now = Instant.parse("2026-11-01T10:00:00Z").toEpochMilli()

    @Before fun setUp() {
        server.start()
        session = SessionStore(MemoryPreferences(), json)
        val api = Retrofit.Builder().baseUrl(server.url("/"))
            .client(OkHttpClient.Builder().addInterceptor(bearer(session)).build())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build().create(HeartpactApi::class.java)
        couple = CoupleRepository(api, session, json)
    }

    @After fun tearDown() = server.shutdown()

    private fun invite(code: String, expires: String) = MockResponse().setBody("""{"id":"i-$code","code":"$code","token":"${"ab".repeat(24)}","expiresAtUtc":"$expires","accessToken":"tok-$code"}""")

    @Test fun `the same invite comes back until it is nearly over, then a new one is made`() = runTest {
        server.enqueue(invite("482039", "2026-11-03T10:00:00"))
        assertEquals("482039", (couple.invite("u1", now) as ApiResult.Ok).value.code)
        assertEquals("/api/couple/invites", server.takeRequest().path)
        assertEquals("tok-482039", session.token)

        // Opening the screen again: no request, same code.
        assertEquals("482039", (couple.invite("u1", now + 3_600_000) as ApiResult.Ok).value.code)
        assertEquals(1, server.requestCount)

        // Another account on this phone never sees it.
        server.enqueue(invite("111222", "2026-11-03T10:00:00Z"))
        assertEquals("111222", (couple.invite("u2", now) as ApiResult.Ok).value.code)

        // Within a minute of the end, a fresh one.
        server.enqueue(invite("555666", "2026-11-05T10:00:00Z"))
        val nearEnd = Instant.parse("2026-11-03T09:59:30Z").toEpochMilli()
        assertEquals("555666", (couple.invite("u2", nearEnd) as ApiResult.Ok).value.code)
    }

    @Test fun `linking forgets the kept invite`() = runTest {
        server.enqueue(invite("482039", "2026-11-03T10:00:00Z"))
        couple.invite("u1", now)
        server.enqueue(MockResponse().setBody("""{"accessToken":"tok-2","user":{"id":"u1","email":"maya@example.com","displayName":"Maya"}}"""))
        couple.accept(token = "ab".repeat(24))
        assertNull(session.invite("u1"))
    }

    @Test fun `a preview sends only the link's token`() = runTest {
        server.enqueue(MockResponse().setBody("""{"inviterFirstName":"Maya","expiresAtUtc":"2026-11-03T10:00:00Z"}"""))
        val preview = couple.preview("ab".repeat(24)) as ApiResult.Ok
        assertEquals("Maya", preview.value.inviterFirstName)
        val request = server.takeRequest()
        assertEquals("/api/couple/invites/preview", request.path)
        assertTrue(request.body.readUtf8() == """{"token":"${"ab".repeat(24)}"}""")
    }

    @Test fun `a link opened before signing up waits until it is used`() = runTest {
        assertNull(session.pendingInvite())
        session.savePendingInvite("ab".repeat(24), signedOut = true)
        assertEquals("ab".repeat(24) to true, session.pendingInvite())
        session.clearPendingInvite()
        assertNull(session.pendingInvite())
    }
}
