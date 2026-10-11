package app.heartpact

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.heartpact.data.ApiResult
import app.heartpact.data.AuthRepository
import app.heartpact.data.CoupleRepository
import app.heartpact.data.CreateGoalRequest
import app.heartpact.data.ExerciseRepository
import app.heartpact.data.OccurrenceStatus
import app.heartpact.alarm.AlarmPlan
import app.heartpact.exercise.ExerciseType
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
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
import org.junit.Assert.assertTrue
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

    private val exercises = mutableMapOf<String, ExerciseRepository>()

    private fun client(name: String): Triple<SessionStore, AuthRepository, CoupleRepository> {
        val session = SessionStore(PreferenceDataStoreFactory.create { folder.newFile("$name.preferences_pb") }, json)
        val api = Retrofit.Builder().baseUrl(System.getenv("HEARTPACT_API_URL")!!)
            .client(OkHttpClient.Builder().addInterceptor(bearer(session)).build())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build().create(HeartpactApi::class.java)
        exercises[name] = ExerciseRepository(api, json)
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

    /**
     * A dare end to end on the real server: set it a minute ahead, wait for the alarm worker to open
     * it, ask to call it off, grant it; then a second dare done on camera (start and complete).
     */
    @Test fun `a dare rings, can be called off together, and can be completed`() = runTest(timeout = kotlin.time.Duration.parse("5m")) {
        assumeTrue(System.getenv("HEARTPACT_API_URL") != null)
        val run = UUID.randomUUID().toString().take(8)
        val (_, mayaAuth, mayaCouple) = client("maya")
        val (_, theoAuth, theoCouple) = client("theo")
        val maya = (mayaAuth.register(RegisterRequest("maya.$run@example.com", "longenough", "Maya", "1996-05-14", Gender.Female)) as ApiResult.Ok).value.user
        (theoAuth.register(RegisterRequest("theo.$run@example.com", "longenough", "Theo", "1995-02-03", Gender.Male)) as ApiResult.Ok)
        val code = (mayaCouple.createInvite() as ApiResult.Ok).value.code
        theoCouple.accept(code = code) as ApiResult.Ok
        val mayaEx = exercises.getValue("maya"); val theoEx = exercises.getValue("theo")

        // Theo dares Maya: two dares at the next minute, in UTC.
        val at = Instant.now().plusSeconds(60).atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("HH:mm"))
        val situps = (theoEx.createGoal(CreateGoalRequest(maya.id, ExerciseType.SitUp, null, 20, at, "UTC", 10)) as ApiResult.Ok).value
        val pushups = (theoEx.createGoal(CreateGoalRequest(maya.id, ExerciseType.PushUp, null, 1, at, "UTC", 10)) as ApiResult.Ok).value

        val planned = AlarmPlan.plan((mayaEx.today() as ApiResult.Ok).value, maya.id, "Theo", Instant.now())
        assertEquals(setOf(situps.id, pushups.id), planned.map { it.goalId }.toSet())
        assertEquals("Theo", planned.first().setByName)

        // Wait (real time) for the worker to open both occurrences.
        val open = withContext(Dispatchers.IO) {
            var found = emptyList<app.heartpact.data.ExerciseOccurrence>()
            repeat(50) {
                found = (mayaEx.today() as ApiResult.Ok).value.occurrences.filter { it.status == OccurrenceStatus.Alarming }
                if (found.size == 2) return@withContext found
                Thread.sleep(3_000)
            }
            found
        }
        assertEquals(2, open.size)
        assertTrue(AlarmPlan.ringingNow(AlarmPlan.plan((mayaEx.today() as ApiResult.Ok).value, maya.id, "Theo", Instant.now()), emptySet(), Instant.now()).size == 2)

        // Sit-ups: Maya asks, Theo agrees.
        val sit = open.first { it.goalId == situps.id }
        val asked = (mayaEx.askOff(sit.id, "  Knee is sore today  ") as ApiResult.Ok).value
        assertEquals("Knee is sore today", asked.callOffNote)
        assertEquals("exercise.already_asked", (mayaEx.askOff(sit.id, null) as ApiResult.Failure).code)
        assertEquals(OccurrenceStatus.Cancelled, (theoEx.cancel(sit.id) as ApiResult.Ok).value.status)

        // Push-ups: done on camera. The server checks the count, confidence and time taken.
        val push = open.first { it.goalId == pushups.id }
        assertEquals(OccurrenceStatus.InProgress, (mayaEx.start(push.id) as ApiResult.Ok).value.status)
        withContext(Dispatchers.IO) { Thread.sleep(ExerciseType.PushUp.minimumMillis(1) + 300) }
        val done = (mayaEx.complete(push.id, 1, 0.9) as ApiResult.Ok).value
        assertEquals(OccurrenceStatus.Completed, done.status)

        val history = (mayaEx.history(7) as ApiResult.Ok).value
        assertEquals(setOf(OccurrenceStatus.Cancelled, OccurrenceStatus.Completed), history.occurrences.map { it.status }.toSet())
    }
}
