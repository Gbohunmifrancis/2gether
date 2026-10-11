package app.heartpact

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.heartpact.data.ApiResult
import app.heartpact.data.AuthRepository
import app.heartpact.data.CoupleRepository
import app.heartpact.data.CoupleSide
import app.heartpact.data.CoupleStatus
import app.heartpact.data.Gender
import app.heartpact.data.HeartpactApi
import app.heartpact.data.RegisterRequest
import app.heartpact.data.SessionStore
import app.heartpact.data.bearer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.UUID

/**
 * Runs the app's own client stack against a real Heartpact API. Skipped unless HEARTPACT_API_URL
 * is set, e.g. HEARTPACT_API_URL=http://localhost:5135/ ./gradlew testDebugUnitTest --tests '*LiveApiTest*'
 */
class LiveApiTest {
    @get:Rule val folder = TemporaryFolder()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true; coerceInputValues = true }

    private fun client(name: String): Triple<SessionStore, AuthRepository, CoupleRepository> {
        val session = SessionStore(PreferenceDataStoreFactory.create { folder.newFile("$name.preferences_pb") }, json)
        val api = Retrofit.Builder().baseUrl(System.getenv("HEARTPACT_API_URL")!!)
            .client(OkHttpClient.Builder().addInterceptor(bearer(session)).build())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build().create(HeartpactApi::class.java)
        return Triple(session, AuthRepository(api, session, json), CoupleRepository(api, session, json))
    }

    @Test fun `two people sign up and link by code`() = runTest {
        assumeTrue(System.getenv("HEARTPACT_API_URL") != null)
        val run = UUID.randomUUID().toString().take(8)
        val (mayaSession, mayaAuth, mayaCouple) = client("maya")
        val (theoSession, theoAuth, theoCouple) = client("theo")

        val maya = mayaAuth.register(RegisterRequest("maya.$run@example.com", "longenough", "Maya", "1996-05-14", Gender.Female)) as ApiResult.Ok
        assertEquals(true, maya.value.user.cycleOwner)
        val theo = theoAuth.register(RegisterRequest("theo.$run@example.com", "longenough", "Theo", "1995-02-03", Gender.Male)) as ApiResult.Ok

        val pending = mayaCouple.current(maya.value.user.id) as ApiResult.Ok
        assertEquals(CoupleStatus.PendingInvite, pending.value.status)

        val invite = mayaCouple.createInvite() as ApiResult.Ok
        assertEquals(6, invite.value.code.length)

        val wrong = theoCouple.accept(code = if (invite.value.code == "000000") "111111" else "000000")
        assertEquals("invite.invalid", (wrong as ApiResult.Failure).code)

        theoCouple.accept(code = invite.value.code) as ApiResult.Ok
        val linked = mayaCouple.current(maya.value.user.id) as ApiResult.Ok
        assertEquals(CoupleStatus.Active, linked.value.status)
        assertEquals(listOf("Maya" to CoupleSide.Left, "Theo" to CoupleSide.Right), linked.value.members.map { it.displayName to it.side })
        theoCouple.current(theo.value.user.id) as ApiResult.Ok
        assertEquals(CoupleSide.Left, mayaSession.load()!!.side)
        assertEquals(CoupleSide.Right, theoSession.load()!!.side)

        val again = mayaAuth.register(RegisterRequest("maya.$run@example.com", "longenough", "Maya", "1996-05-14", Gender.Female))
        assertEquals("auth.email_exists", (again as ApiResult.Failure).code)
        assertEquals(401, (theoAuth.login("theo.$run@example.com", "wrong-password") as ApiResult.Failure).status)
    }
}
