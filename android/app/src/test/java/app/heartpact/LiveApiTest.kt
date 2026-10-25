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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

    private val sessions = mutableMapOf<String, SessionStore>()

    private fun client(name: String): Triple<SessionStore, AuthRepository, CoupleRepository> {
        val session = SessionStore(PreferenceDataStoreFactory.create { folder.newFile("$name.preferences_pb") }, json)
        val api = Retrofit.Builder().baseUrl(System.getenv("HEARTPACT_API_URL")!!)
            .client(OkHttpClient.Builder().addInterceptor(bearer(session)).build())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build().create(HeartpactApi::class.java)
        exercises[name] = ExerciseRepository(api, json)
        apis[name] = api
        sessions[name] = session
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
        // An app that sends no checks or token: nothing noticed, and (with Integrity on) the app is unverified.
        assertEquals(0, done.checks?.formFaults)
        assertEquals("missing", done.checks?.integrity)
        assertEquals(listOf("unverifiedApp"), done.checks?.flags)

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
        // What the camera noticed travels with the finish; Integrity's fake provider passes "fake.pass" (docs/exercise-checks.md).
        val checks = app.heartpact.exercise.ExerciseChecks(formFaults = 2, fastReps = 1, challengesPassed = 0, challengesMissed = 1, phoneMovedReps = 0, outOfFrameSeconds = 4)
        val finished = (mayaEx.complete(started.id, 1, 0.9, checks, "fake.pass") as ApiResult.Ok).value
        assertEquals(OccurrenceStatus.Completed, finished.status)
        assertEquals("passed", finished.checks?.integrity)
        assertEquals(listOf("missedCheckIn"), finished.checks?.flags)
        // Theo sees it on Today, flags first.
        val theirs = (theoEx.today() as ApiResult.Ok).value.occurrences.single { it.id == started.id }
        assertEquals("Worth a look: missed a camera check-in.", theirs.checks?.partnerLine())

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
        // And a video, with its poster frame as the thumbnail.
        val clip = UUID.randomUUID().toString()
        queue.photosDir.resolve("$clip.mp4").writeBytes(ByteArray(4096) { (it % 241).toByte() })
        queue.thumbsDir.resolve("$clip.jpg").writeBytes(ByteArray(300) { 2 })
        queue.add(app.heartpact.album.LocalPhoto(clip, "$clip.mp4", "$clip.jpg", "video/mp4", 4096L, null, 0))
        engine.reportPending()
        assertEquals(4, (app.heartpact.data.apiCall(json) { mayaApi.albumSync() } as ApiResult.Ok).value.pendingCount)
        // Nothing is shared until Maya syncs.
        assertEquals(0, (app.heartpact.data.apiCall(json) { theoApi.memories(null) } as ApiResult.Ok).value.items.size)

        val outcome = withContext(Dispatchers.IO) { engine.run() }
        assertEquals(4, outcome.synced)
        assertTrue(queue.photos.value.isEmpty())

        val seen = (app.heartpact.data.apiCall(json) { theoApi.memories(null) } as ApiResult.Ok).value.items
        assertEquals(4, seen.size)
        assertTrue(seen.all { it.uploadedByUserId == maya.id && it.thumbnailUrl != null })
        assertEquals(1, seen.count { it.isVideo && it.contentType == "video/mp4" })
        val status = (app.heartpact.data.apiCall(json) { mayaApi.albumSync() } as ApiResult.Ok).value
        assertEquals(0, status.pendingCount)
        assertEquals(4, status.usage.count)
        assertEquals("taken a year ago today", 3, (app.heartpact.data.apiCall(json) { theoApi.onThisDay("2026-10-11") } as ApiResult.Ok).value.size)

        // Only the uploader captions or deletes; the partner sees the caption and loses the photo too.
        val photo = seen.first { !it.isVideo }
        val captioned = (app.heartpact.data.apiCall(json) { mayaApi.updateMemory(photo.id, app.heartpact.data.UpdateMemoryRequest("The lake, before the rain", photo.takenAtUtc)) } as ApiResult.Ok).value
        assertEquals("The lake, before the rain", captioned.caption)
        assertEquals("the date is kept", photo.takenAtUtc?.take(19), captioned.takenAtUtc?.take(19))
        assertEquals("The lake, before the rain", (app.heartpact.data.apiCall(json) { theoApi.memories(null) } as ApiResult.Ok).value.items.first { it.id == photo.id }.caption)
        assertEquals(403, (app.heartpact.data.apiCall(json) { theoApi.deleteMemory(photo.id) } as ApiResult.Failure).status)
        assertTrue(app.heartpact.data.apiCall(json) { mayaApi.deleteMemory(photo.id) } is ApiResult.Ok)
        assertTrue((app.heartpact.data.apiCall(json) { theoApi.memories(null) } as ApiResult.Ok).value.items.none { it.id == photo.id })
        assertEquals(3, (app.heartpact.data.apiCall(json) { mayaApi.albumSync() } as ApiResult.Ok).value.usage.count)
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

    @Test fun `a cycle predicts from the last period, the partner sees what is shared, and pregnancy pauses it`() = runTest(timeout = kotlin.time.Duration.parse("1m")) {
        assumeTrue(System.getenv("HEARTPACT_API_URL") != null)
        val run = UUID.randomUUID().toString().take(8)
        val (_, mayaAuth, mayaCouple) = client("maya")
        val (_, theoAuth, theoCouple) = client("theo")
        val maya = (mayaAuth.register(RegisterRequest("maya.$run@example.com", "longenough", "Maya", "1996-05-14", Gender.Female)) as ApiResult.Ok).value.user
        theoAuth.register(RegisterRequest("theo.$run@example.com", "longenough", "Theo", "1995-02-03", Gender.Male)) as ApiResult.Ok
        theoCouple.accept(code = (mayaCouple.createInvite() as ApiResult.Ok).value.code) as ApiResult.Ok
        val mayaCycle = app.heartpact.data.CycleRepository(apis.getValue("maya"), json)
        val theoCycle = app.heartpact.data.CycleRepository(apis.getValue("theo"), json)
        val today = java.time.LocalDate.now(ZoneOffset.UTC)

        assertTrue((mayaCycle.me() as ApiResult.Ok).value.cycleOwner)
        // Last period 40 days ago, 28-day cycles: the forecast rolls forward to the current cycle.
        mayaCycle.saveProfile(app.heartpact.data.UpdateCycleProfile(28, 5, today.minusDays(40).toString(), app.heartpact.data.CycleShare.PredictionsOnly)) as ApiResult.Ok
        val prediction = (mayaCycle.prediction(today.toString()) as ApiResult.Ok).value
        assertEquals(13, prediction.cycleDay)
        assertEquals(today.plusDays(16).toString(), prediction.nextPeriodStart)
        assertEquals(today.plusDays(16 - 14).toString(), prediction.fertileWindowEnd)
        mayaCycle.log(today.toString(), app.heartpact.data.CycleLogRequest(app.heartpact.data.FlowLevel.None, listOf("Tired"), notes = "private")) as ApiResult.Ok

        val shared = (theoCycle.shared(today.toString()) as ApiResult.Ok).value
        assertEquals(13, shared.prediction?.cycleDay)
        assertTrue("predictions only: no logs", shared.logs.isEmpty())

        val mayaP = app.heartpact.data.PregnancyRepository(apis.getValue("maya"), json)
        val theoP = app.heartpact.data.PregnancyRepository(apis.getValue("theo"), json)
        val p = (mayaP.start(app.heartpact.data.StartPregnancyRequest(dueDate = today.plusDays(150).toString())) as ApiResult.Ok).value
        assertTrue(p.isMine)
        assertEquals("cycle predictions pause", null, (mayaCycle.prediction(today.toString()) as ApiResult.Ok).value.nextPeriodStart)
        val seen = (theoP.list(today.toString()) as ApiResult.Ok).value.single()
        theoP.addAppointment(seen.id, app.heartpact.data.AppointmentRequest("Anatomy scan", java.time.Instant.now().plusSeconds(86_400).toString(), "City Hospital")) as ApiResult.Ok
        assertEquals(1, (mayaP.appointments(p.id) as ApiResult.Ok).value.size)
        val session = (mayaP.startKicks(p.id) as ApiResult.Ok).value
        assertEquals(7, (mayaP.recordKicks(session.id, 7, end = true) as ApiResult.Ok).value.count)
        assertTrue("only she counts kicks", theoP.startKicks(p.id) is ApiResult.Failure)
    }

    @Test fun `location goes through the live hub only while both share, and pausing hides it`() = runTest(timeout = kotlin.time.Duration.parse("1m")) {
        assumeTrue(System.getenv("HEARTPACT_API_URL") != null)
        val url = System.getenv("HEARTPACT_API_URL")!!
        val run = UUID.randomUUID().toString().take(8)
        val (_, mayaAuth, mayaCouple) = client("maya")
        val (_, theoAuth, theoCouple) = client("theo")
        mayaAuth.register(RegisterRequest("maya.$run@example.com", "longenough", "Maya", "1996-05-14", Gender.Female)) as ApiResult.Ok
        theoAuth.register(RegisterRequest("theo.$run@example.com", "longenough", "Theo", "1995-02-03", Gender.Male)) as ApiResult.Ok
        theoCouple.accept(code = (mayaCouple.createInvite() as ApiResult.Ok).value.code) as ApiResult.Ok
        // Linking issues new tokens with the couple in them: the hub reads the stored one.
        val mayaHub = app.heartpact.live.LiveHub(url, sessions.getValue("maya"))
        val theoHub = app.heartpact.live.LiveHub(url, sessions.getValue("theo"))
        val theoEvents = java.util.concurrent.CopyOnWriteArrayList<app.heartpact.live.LiveEvent>()
        val collector = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { theoHub.events.collect { theoEvents += it } }
        try {
            mayaHub.hold("test"); theoHub.hold("test")
            withContext(Dispatchers.IO) {
                kotlinx.coroutines.withTimeout(20_000) { mayaHub.connected.first { it }; theoHub.connected.first { it } }
            }
            val mayaLoc = app.heartpact.data.LocationRepository(apis.getValue("maya"), json)
            val theoLoc = app.heartpact.data.LocationRepository(apis.getValue("theo"), json)

            mayaHub.updateLocation(6.4300, 3.4200, 12.0)
            withContext(Dispatchers.IO) { Thread.sleep(800) }
            assertEquals("a fix never starts sharing", app.heartpact.data.SharingStatus.Off, (mayaLoc.status() as ApiResult.Ok).value.me.status)

            mayaLoc.start() as ApiResult.Ok
            withContext(Dispatchers.IO) { Thread.sleep(5_200) } // fixes closer than 5 s apart are dropped
            mayaHub.updateLocation(6.4300, 3.4200, 12.0)
            withContext(Dispatchers.IO) { Thread.sleep(800) }
            assertEquals("not mutual yet: Theo sees only himself", emptyList<String>(), (theoLoc.points() as ApiResult.Ok).value.map { it.displayName })
            assertEquals("and not when Maya last moved", null, (theoLoc.status() as ApiResult.Ok).value.partner?.lastUpdatedAtUtc)

            theoLoc.start() as ApiResult.Ok
            theoHub.updateLocation(6.4400, 3.4350, 15.0)
            withContext(Dispatchers.IO) { kotlinx.coroutines.withTimeout(10_000) { while (theoEvents.none { it.name == "LocationUpdated" && "Maya" in it.json }) delay(100) } }
            assertTrue((mayaLoc.status() as ApiResult.Ok).value.mutual)
            assertEquals(2, (theoLoc.points() as ApiResult.Ok).value.size)

            assertEquals(app.heartpact.data.SharingStatus.Paused, (mayaLoc.pause(60) as ApiResult.Ok).value.me.status)
            assertEquals("paused: nothing of Maya", 1, (theoLoc.points() as ApiResult.Ok).value.size)
            assertTrue(theoEvents.any { it.name == "LocationSharingChanged" })

            assertTrue(mayaLoc.stop() is ApiResult.Ok)
            assertTrue("stop is idempotent", mayaLoc.stop() is ApiResult.Ok)
            assertEquals(app.heartpact.data.SharingStatus.Off, (theoLoc.status() as ApiResult.Ok).value.partner?.status)
        } finally {
            collector.cancel()
            mayaHub.stopAll(); theoHub.stopAll()
        }
    }

    /** Needs the API started with Email__Provider=log, and HEARTPACT_API_LOG pointing at its log, to read the code. */
    @Test fun `a forgotten password resets with an emailed code and ends the old session`() = runTest(timeout = kotlin.time.Duration.parse("1m")) {
        assumeTrue(System.getenv("HEARTPACT_API_URL") != null && System.getenv("HEARTPACT_API_LOG") != null)
        val run = UUID.randomUUID().toString().take(8)
        val email = "maya.$run@example.com"
        val (oldSession, mayaAuth, mayaCouple) = client("maya")
        val maya = (mayaAuth.register(RegisterRequest(email, "longenough", "Maya", "1996-05-14", Gender.Female)) as ApiResult.Ok).value.user
        assertTrue((mayaCouple.current(maya.id) is ApiResult.Ok))
        val (_, phoneAuth, _) = client("phone")
        // A live connection from the old session, only listening: the reset must close it too.
        val oldHub = app.heartpact.live.LiveHub(System.getenv("HEARTPACT_API_URL")!!, oldSession)
        oldHub.hold("test")
        withContext(Dispatchers.IO) { kotlinx.coroutines.withTimeout(20_000) { oldHub.connected.first { it } } }

        assertTrue("no account, same answer", phoneAuth.forgot("nobody.$run@example.com") is ApiResult.Ok)
        assertTrue(phoneAuth.forgot(email) is ApiResult.Ok)
        val code = withContext(Dispatchers.IO) {
            var found: String? = null
            repeat(50) {
                if (found == null) {
                    found = Regex("""reset your Heartpact password is (\d{6})""").findAll(java.io.File(System.getenv("HEARTPACT_API_LOG")!!).readText())
                        .lastOrNull { true }?.groupValues?.get(1)
                    if (found == null) Thread.sleep(100)
                }
            }
            found!!
        }
        // Token times have whole seconds: make sure the old session is from an earlier second than the reset.
        withContext(Dispatchers.IO) { Thread.sleep(1100) }
        val wrong = if (code == "000000") "111111" else "000000"
        assertEquals("auth.invalid_reset_code", (phoneAuth.reset(email, wrong, "brandnewpass") as ApiResult.Failure).code)
        val reset = (phoneAuth.reset(email, code, "brandnewpass") as ApiResult.Ok).value
        assertEquals(maya.id, reset.user.id)
        withContext(Dispatchers.IO) { kotlinx.coroutines.withTimeout(5_000) { oldHub.connected.first { !it } } }
        oldHub.stopAll()
        assertEquals("a code works once", "auth.invalid_reset_code", (phoneAuth.reset(email, code, "another1pass") as ApiResult.Failure).code)

        // The session from before the reset no longer works; the new password does.
        assertTrue(oldSession.token != reset.accessToken)
        assertEquals(401, (mayaCouple.current(maya.id) as ApiResult.Failure).status)
        assertTrue(mayaAuth.login(email, "brandnewpass") is ApiResult.Ok)
        assertTrue(mayaAuth.login(email, "longenough") is ApiResult.Failure)
    }

    @Test fun `an invite becomes a game both phones play, turn by turn, with no clock on memory match`() = runTest(timeout = kotlin.time.Duration.parse("1m")) {
        assumeTrue(System.getenv("HEARTPACT_API_URL") != null)
        val run = UUID.randomUUID().toString().take(8)
        val (_, mayaAuth, mayaCouple) = client("maya")
        val (_, theoAuth, theoCouple) = client("theo")
        val maya = (mayaAuth.register(RegisterRequest("maya.$run@example.com", "longenough", "Maya", "1996-05-14", Gender.Female)) as ApiResult.Ok).value.user
        val theo = (theoAuth.register(RegisterRequest("theo.$run@example.com", "longenough", "Theo", "1995-02-03", Gender.Male)) as ApiResult.Ok).value.user
        theoCouple.accept(code = (mayaCouple.createInvite() as ApiResult.Ok).value.code) as ApiResult.Ok
        val mayaGames = app.heartpact.data.GameRepository(apis.getValue("maya"), json)
        val theoGames = app.heartpact.data.GameRepository(apis.getValue("theo"), json)

        // I call on keeps the chosen letter for the first round.
        val call = (mayaGames.create(app.heartpact.data.GameType.ICallOn, "Q") as ApiResult.Ok).value
        val callStarted = (theoGames.accept(call.id) as ApiResult.Ok).value
        assertEquals("Q", app.heartpact.ui.games.GamePlay.iCallOn(callStarted).letter)
        mayaGames.abandon(call.id) as ApiResult.Ok

        val invite = (mayaGames.create(app.heartpact.data.GameType.MemoryMatch) as ApiResult.Ok).value
        assertEquals(app.heartpact.data.GameStatus.Waiting, invite.status)
        assertEquals("one open game at a time", "game.active_session", (theoGames.create(app.heartpact.data.GameType.CoupleQuiz) as ApiResult.Failure).code)
        var game = (theoGames.accept(invite.id) as ApiResult.Ok).value
        assertEquals(null, game.deadlineUtc)
        withContext(Dispatchers.IO) { Thread.sleep(5_300) } // the shared countdown

        // Whoever moves first flips two cards; on a miss both cards stay visible and the turn passes.
        val first = game.currentTurnUserId!!
        val mover = if (first == maya.id) mayaGames else theoGames
        game = (mover.act(game.id, game.stateVersion, "flip", index = 0) as ApiResult.Ok).value
        var firstIndex = 0
        var firstValue = app.heartpact.ui.games.GamePlay.memory(game).cards[0].value!!
        var index = 1
        while (true) {
            val next = (mover.act(game.id, game.stateVersion, "flip", index = index) as ApiResult.Ok).value
            val state = app.heartpact.ui.games.GamePlay.memory(next)
            game = next
            if (state.lastResult == "different") {
                assertEquals(listOf(firstIndex, index), state.lastPair!!.map { it.index })
                assertEquals(firstValue, state.lastPair.first().value)
                assertTrue(next.currentTurnUserId != first)
                break
            }
            // A lucky match: the same player goes again with a fresh first card.
            firstIndex = index + 1
            game = (mover.act(game.id, game.stateVersion, "flip", index = firstIndex) as ApiResult.Ok).value
            firstValue = app.heartpact.ui.games.GamePlay.memory(game).cards[firstIndex].value!!
            index = firstIndex + 1
        }
        val stale = mover.act(game.id, game.stateVersion - 1, "flip", index = 11)
        assertEquals("game.version_conflict", (stale as ApiResult.Failure).code)
        assertTrue((if (first == maya.id) theoGames else mayaGames).abandon(game.id) is ApiResult.Ok)
        assertTrue(mayaGames.sessions().let { it is ApiResult.Ok && it.value.none { s -> s.open } })
        assertTrue(theo.id.isNotEmpty())
    }

    @Test fun `one partner buys Plus and both have it, and nobody else can claim the purchase`() = runTest(timeout = kotlin.time.Duration.parse("1m")) {
        assumeTrue(System.getenv("HEARTPACT_API_URL") != null)
        val run = UUID.randomUUID().toString().take(8)
        val (_, mayaAuth, mayaCouple) = client("maya")
        val (_, theoAuth, theoCouple) = client("theo")
        val mayaUser = (mayaAuth.register(RegisterRequest("maya.$run@example.com", "longenough", "Maya", "1996-05-14", Gender.Female)) as ApiResult.Ok).value.user
        theoAuth.register(RegisterRequest("theo.$run@example.com", "longenough", "Theo", "1995-02-03", Gender.Male)) as ApiResult.Ok
        theoCouple.accept(code = (mayaCouple.createInvite() as ApiResult.Ok).value.code) as ApiResult.Ok
        val maya = app.heartpact.data.PlusRepository(apis.getValue("maya"), json)
        val theo = app.heartpact.data.PlusRepository(apis.getValue("theo"), json)

        val before = (maya.entitlement() as ApiResult.Ok).value
        assertEquals(app.heartpact.data.Plan.Free, before.plan)
        assertTrue(before.canPurchase)
        assertTrue("the plans on sale come from the server", before.productIds.containsAll(listOf("heartpact_plus_monthly", "heartpact_plus_yearly")))
        assertTrue(before.plusLimits!!.foodScansPerDay > before.freeLimits!!.foodScansPerDay)

        // Billing__Provider=fake understands tokens shaped fake.{state}.{days}.{accountId}.{productId}.
        val token = "fake.active.30.${app.heartpact.billing.PlusOffers.accountId(mayaUser.id)}.heartpact_plus_monthly"
        val bought = maya.verify("heartpact_plus_monthly", token)
        assumeTrue("fake billing is not on for this API", !(bought is ApiResult.Failure && bought.code == "billing.unavailable"))
        val mine = (bought as ApiResult.Ok).value
        assertEquals(app.heartpact.data.Plan.Plus, mine.plan)
        assertTrue(mine.subscription!!.purchasedByMe)

        val partner = (theo.entitlement() as ApiResult.Ok).value
        assertEquals(app.heartpact.data.Plan.Plus, partner.plan)
        assertEquals("one plan covers both: the partner isn't offered another", false, partner.canPurchase)
        assertEquals(false, partner.subscription!!.purchasedByMe)

        assertEquals("billing.not_your_purchase", (theo.verify("heartpact_plus_monthly", token) as ApiResult.Failure).code)
        assertEquals("billing.unknown_product", (maya.verify("cheap_test_item", token) as ApiResult.Failure).code)
    }
}
