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
    private val apis = mutableMapOf<String, HeartpactApi>()

    private fun client(name: String): Triple<SessionStore, AuthRepository, CoupleRepository> {
        val session = SessionStore(PreferenceDataStoreFactory.create { folder.newFile("$name.preferences_pb") }, json)
        val api = Retrofit.Builder().baseUrl(System.getenv("HEARTPACT_API_URL")!!)
            .client(OkHttpClient.Builder().addInterceptor(bearer(session)).build())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build().create(HeartpactApi::class.java)
        exercises[name] = ExerciseRepository(api, json)
        apis[name] = api
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

    /** Done before it rings: it counts, and that day's alarm is gone from the server and the phone. */
    @Test fun `a dare done early cancels that day's alarm`() = runTest(timeout = kotlin.time.Duration.parse("2m")) {
        assumeTrue(System.getenv("HEARTPACT_API_URL") != null)
        val run = UUID.randomUUID().toString().take(8)
        val (_, mayaAuth, mayaCouple) = client("maya")
        val (_, theoAuth, theoCouple) = client("theo")
        val maya = (mayaAuth.register(RegisterRequest("maya.$run@example.com", "longenough", "Maya", "1996-05-14", Gender.Female)) as ApiResult.Ok).value.user
        theoAuth.register(RegisterRequest("theo.$run@example.com", "longenough", "Theo", "1995-02-03", Gender.Male)) as ApiResult.Ok
        theoCouple.accept(code = (mayaCouple.createInvite() as ApiResult.Ok).value.code) as ApiResult.Ok
        val mayaEx = exercises.getValue("maya"); val theoEx = exercises.getValue("theo")

        // Due in 10 minutes (UTC), unless that crosses midnight, where "early today" cannot apply.
        val due = Instant.now().plusSeconds(600).atOffset(ZoneOffset.UTC)
        assumeTrue(due.toLocalDate() == Instant.now().atOffset(ZoneOffset.UTC).toLocalDate())
        val goal = (theoEx.createGoal(CreateGoalRequest(maya.id, ExerciseType.PushUp, null, 1, due.format(DateTimeFormatter.ofPattern("HH:mm")), "UTC", 10)) as ApiResult.Ok).value

        assertEquals("exercise.not_assignee", (theoEx.startEarly(goal.id) as ApiResult.Failure).code)
        val started = (mayaEx.startEarly(goal.id) as ApiResult.Ok).value
        assertEquals(OccurrenceStatus.InProgress, started.status)
        assertEquals(app.heartpact.data.utc(goal.nextDueAtUtc), app.heartpact.data.utc(started.scheduledAtUtc))

        withContext(Dispatchers.IO) { Thread.sleep(ExerciseType.PushUp.minimumMillis(1) + 300) }
        assertEquals(OccurrenceStatus.Completed, (mayaEx.complete(started.id, 1, 0.9) as ApiResult.Ok).value.status)

        val today = (mayaEx.today() as ApiResult.Ok).value
        val moved = app.heartpact.data.utc(today.goals.single { it.id == goal.id }.nextDueAtUtc)
        assertEquals(app.heartpact.data.utc(goal.nextDueAtUtc).plusSeconds(86_400), moved)
        val plan = AlarmPlan.plan(today, maya.id, "Theo", Instant.now())
        assertEquals(listOf(moved.toEpochMilli()), plan.filter { it.goalId == goal.id }.map { it.dueAtEpochMs })
        assertEquals("exercise.not_due_today", (mayaEx.startEarly(goal.id) as ApiResult.Failure).code)
    }

    /** Needs the API started with S3-compatible storage (Media__ServiceUrl etc.), e.g. MinIO or moto. */
    @Test fun `photos wait on the phone, then one sync puts them in the shared album`() = runTest(timeout = kotlin.time.Duration.parse("2m")) {
        assumeTrue(System.getenv("HEARTPACT_API_URL") != null)
        val run = UUID.randomUUID().toString().take(8)
        val (_, mayaAuth, mayaCouple) = client("maya")
        val (_, theoAuth, theoCouple) = client("theo")
        val maya = (mayaAuth.register(RegisterRequest("maya.$run@example.com", "longenough", "Maya", "1996-05-14", Gender.Female)) as ApiResult.Ok).value.user
        theoAuth.register(RegisterRequest("theo.$run@example.com", "longenough", "Theo", "1995-02-03", Gender.Male)) as ApiResult.Ok
        theoCouple.accept(code = (mayaCouple.createInvite() as ApiResult.Ok).value.code) as ApiResult.Ok
        val mayaApi = apis.getValue("maya"); val theoApi = apis.getValue("theo")
        val storage = app.heartpact.data.apiCall(json) { mayaApi.memories(null) }
        assumeTrue("storage is not configured on this API", storage is ApiResult.Ok)

        val queue = app.heartpact.album.AlbumQueue(folder.newFolder("album"))
        val engine = app.heartpact.album.SyncEngine(mayaApi, OkHttpClient(), queue, json)
        repeat(3) { n ->
            val id = UUID.randomUUID().toString()
            queue.photosDir.resolve("$id.jpg").writeBytes(ByteArray(2048 + n) { (it % 251).toByte() })
            queue.thumbsDir.resolve("$id.jpg").writeBytes(ByteArray(300) { 1 })
            queue.add(app.heartpact.album.LocalPhoto(id, "$id.jpg", "$id.jpg", "image/jpeg", 2048L + n, "2025-10-11T09:00:00Z", 0))
        }
        engine.reportPending()
        assertEquals(3, (app.heartpact.data.apiCall(json) { mayaApi.albumSync() } as ApiResult.Ok).value.pendingCount)
        // Nothing is shared until Maya syncs.
        assertEquals(0, (app.heartpact.data.apiCall(json) { theoApi.memories(null) } as ApiResult.Ok).value.items.size)

        val outcome = withContext(Dispatchers.IO) { engine.run() }
        assertEquals(3, outcome.synced)
        assertTrue(queue.photos.value.isEmpty())

        val seen = (app.heartpact.data.apiCall(json) { theoApi.memories(null) } as ApiResult.Ok).value.items
        assertEquals(3, seen.size)
        assertTrue(seen.all { it.uploadedByUserId == maya.id && it.thumbnailUrl != null })
        val status = (app.heartpact.data.apiCall(json) { mayaApi.albumSync() } as ApiResult.Ok).value
        assertEquals(0, status.pendingCount)
        assertEquals(3, status.usage.count)
        assertEquals("taken a year ago today", 3, (app.heartpact.data.apiCall(json) { theoApi.onThisDay("2026-10-11") } as ApiResult.Ok).value.size)
    }

    @Test fun `meals logged by hand add up, the partner sees totals only, and estimates say when they're not set up`() = runTest(timeout = kotlin.time.Duration.parse("1m")) {
        assumeTrue(System.getenv("HEARTPACT_API_URL") != null)
        val run = UUID.randomUUID().toString().take(8)
        val (_, mayaAuth, mayaCouple) = client("maya")
        val (_, theoAuth, theoCouple) = client("theo")
        mayaAuth.register(RegisterRequest("maya.$run@example.com", "longenough", "Maya", "1996-05-14", Gender.Female)) as ApiResult.Ok
        theoAuth.register(RegisterRequest("theo.$run@example.com", "longenough", "Theo", "1995-02-03", Gender.Male)) as ApiResult.Ok
        theoCouple.accept(code = (mayaCouple.createInvite() as ApiResult.Ok).value.code) as ApiResult.Ok
        val maya = app.heartpact.data.FoodRepository(apis.getValue("maya"), json)
        val theo = app.heartpact.data.FoodRepository(apis.getValue("theo"), json)
        val today = java.time.LocalDate.now(ZoneOffset.UTC).toString()

        maya.create(app.heartpact.data.CreateFoodEntryRequest(today, app.heartpact.data.MealType.Breakfast, "Oats", 450)) as ApiResult.Ok
        val toast = (maya.create(app.heartpact.data.CreateFoodEntryRequest(today, app.heartpact.data.MealType.Lunch, "Toast", 300)) as ApiResult.Ok).value
        assertEquals("food.invalid_portion", (maya.create(app.heartpact.data.CreateFoodEntryRequest(today, app.heartpact.data.MealType.Snack, "Tea", 40, portionFactor = 0.75)) as ApiResult.Failure).code)

        val mine = (maya.day(today) as ApiResult.Ok).value
        assertEquals(750, mine.totals.calories)
        assertEquals(750 to 750, mine.totals.low to mine.totals.high)
        maya.update(toast.id, app.heartpact.data.UpdateFoodEntryRequest(today, app.heartpact.data.MealType.Lunch, "Toast and egg", 380)) as ApiResult.Ok

        val seen = (theo.day(today) as ApiResult.Ok).value.partner!!
        assertEquals(app.heartpact.data.FoodShareLevel.TotalsOnly, seen.shareLevel)
        assertEquals(830, seen.totals!!.calories)
        assertTrue("totals only: no meals", seen.entries.isEmpty())

        maya.saveTargets(app.heartpact.data.UpsertTargetRequest(1900, shareLevel = app.heartpact.data.FoodShareLevel.None)) as ApiResult.Ok
        assertEquals(null, (theo.day(today) as ApiResult.Ok).value.partner!!.totals)

        // A tiny JPEG: the server checks the type before asking the estimator.
        val scan = maya.scan(app.heartpact.data.FoodScanRequest("/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQH/wAALCAABAAEBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AKp//2Q=="))
        if (scan is ApiResult.Failure) assertTrue(scan.code, scan.code in setOf("food.estimation_unavailable", "food.not_food", "food.image_invalid"))
    }

    @Test fun `the daily question opens only after answering, settings save, and ending the pact unlinks both`() = runTest(timeout = kotlin.time.Duration.parse("1m")) {
        assumeTrue(System.getenv("HEARTPACT_API_URL") != null)
        val run = UUID.randomUUID().toString().take(8)
        val (_, mayaAuth, mayaCouple) = client("maya")
        val (_, theoAuth, theoCouple) = client("theo")
        val maya = (mayaAuth.register(RegisterRequest("maya.$run@example.com", "longenough", "Maya", "1996-05-14", Gender.Female)) as ApiResult.Ok).value.user
        theoAuth.register(RegisterRequest("theo.$run@example.com", "longenough", "Theo", "1995-02-03", Gender.Male)) as ApiResult.Ok
        theoCouple.accept(code = (mayaCouple.createInvite() as ApiResult.Ok).value.code) as ApiResult.Ok
        val mayaUs = app.heartpact.data.UsRepository(apis.getValue("maya"), json)
        val theoUs = app.heartpact.data.UsRepository(apis.getValue("theo"), json)
        val today = java.time.LocalDate.now(ZoneOffset.UTC).toString()

        val q = (theoUs.question(today) as ApiResult.Ok).value
        theoUs.answer(q.id, "Learn to sail, badly.") as ApiResult.Ok
        val before = (mayaUs.question(today) as ApiResult.Ok).value
        assertTrue(before.partnerAnswered)
        assertEquals("hidden until Maya answers", null, before.partnerAnswer)
        val after = (mayaUs.answer(q.id, "A cabin with no signal.") as ApiResult.Ok).value
        assertEquals("Learn to sail, badly.", after.partnerAnswer?.body)

        val prefs = (mayaUs.preferences() as ApiResult.Ok).value
        val saved = (mayaUs.savePreferences(prefs.copy(quietHoursEnabled = true, quietStart = "23:00", quietEnd = "06:30", album = false, timeZoneId = "Africa/Lagos")) as ApiResult.Ok).value
        assertEquals(Triple(true, "23:00", "06:30"), Triple(saved.quietHoursEnabled, saved.quietStart, saved.quietEnd))
        assertEquals(false, saved.album)

        mayaUs.endPact() as ApiResult.Ok
        assertTrue((mayaCouple.current(maya.id) as? ApiResult.Ok)?.value?.status != CoupleStatus.Active)
    }
}
