package app.heartpact

import app.heartpact.data.AiRepository
import app.heartpact.data.ApiResult
import app.heartpact.data.CoachNote
import app.heartpact.data.DareIdea
import app.heartpact.data.GameType
import app.heartpact.data.HeartpactApi
import app.heartpact.data.PlanBudget
import app.heartpact.data.PlanMood
import app.heartpact.data.PlanSetting
import app.heartpact.data.WeeklyRecap
import app.heartpact.data.WeeklyRecapStats
import app.heartpact.exercise.ExerciseType
import app.heartpact.push.PushInbox
import app.heartpact.ui.ai.CoachLoader
import app.heartpact.ui.ai.CoachUi
import app.heartpact.ui.ai.DareIdeaPick
import app.heartpact.ui.ai.PlanForm
import app.heartpact.ui.ai.PlanUi
import app.heartpact.ui.ai.RecapText
import app.heartpact.ui.today.NewDareState
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
import java.time.LocalTime

/** The pure parts of the AI features (docs/ai.md), and the client against a scripted server. */
class AiFeaturesTest {
    private val server = MockWebServer()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true; coerceInputValues = true }
    private lateinit var ai: AiRepository

    @Before fun setUp() {
        server.start()
        val api = Retrofit.Builder().baseUrl(server.url("/")).client(OkHttpClient())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(HeartpactApi::class.java)
        ai = AiRepository(api, json)
    }

    @After fun tearDown() = server.shutdown()

    // ----- New dare -----

    @Test fun `tapping an idea fills the exercise, target and time and keeps the window`() {
        val form = NewDareState(type = ExerciseType.PushUp, target = 10, time = LocalTime.of(18, 30), window = 20)
        val filled = DareIdeaPick.apply(form, DareIdea(ExerciseType.Plank, 45, scheduledTime = "07:15", reason = "A calm hold."))
        assertEquals(ExerciseType.Plank, filled.type)
        assertEquals(45, filled.target)
        assertEquals(LocalTime.of(7, 15), filled.time)
        assertEquals(20, filled.window)
    }

    @Test fun `an idea outside the exercise's limits or with a bad time can't break the form`() {
        val form = NewDareState(time = LocalTime.of(18, 30))
        val clamped = DareIdeaPick.apply(form, DareIdea(ExerciseType.Squat, 900, scheduledTime = "25:99", reason = "x"))
        assertEquals(ExerciseType.Squat.maxTarget, clamped.target)
        assertEquals(LocalTime.of(18, 30), clamped.time)
        assertEquals(form, DareIdeaPick.apply(form, DareIdea(null, 10, scheduledTime = "08:00", reason = "unknown exercise")))
    }

    @Test fun `ideas for an exercise this app doesn't know are left out`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ideas":[{"exerciseType":"burpee","target":10,"measure":"reps","scheduledTime":"08:00","reason":"new"},
            {"exerciseType":"sitUp","target":12,"measure":"reps","scheduledTime":"07:05","reason":"Wake up those abs."}],"left":7}"""))
        val ideas = (ai.dareIdeas("theo") as ApiResult.Ok).value
        assertEquals(listOf(ExerciseType.SitUp), ideas.ideas.map { it.exerciseType })
        assertEquals(7, ideas.left)
        val sent = server.takeRequest()
        assertEquals("/api/ai/dare-ideas", sent.path)
        assertEquals("""{"assignedUserId":"theo"}""", sent.body.readUtf8())
    }

    // ----- The coach -----

    @Test fun `the coach's note shows once it is written, after a wait while it is being written`() = runTest {
        val answers = ArrayDeque(listOf<ApiResult<CoachNote>>(ApiResult.Ok(CoachNote(state = "pending")), ApiResult.Ok(CoachNote(state = "ready", text = " Nice pace! "))))
        val shown = mutableListOf<CoachUi>()
        val waits = mutableListOf<Long>()
        CoachLoader.load({ answers.removeFirst() }, { shown += it }, { waits += it })
        assertEquals(listOf(CoachUi.Writing, CoachUi.Ready("Nice pace!")), shown)
        assertEquals(listOf(CoachLoader.WAIT_MS), waits)
    }

    @Test fun `no note, an off switch, offline or a note that never comes all hide it`() = runTest {
        for (answer in listOf<ApiResult<CoachNote>>(ApiResult.Ok(CoachNote(state = "unavailable")), ApiResult.Failure("ai.off", "off", 404), ApiResult.Offline,
            ApiResult.Ok(CoachNote(state = "ready", text = "  ")))) {
            val shown = mutableListOf<CoachUi>()
            CoachLoader.load({ answer }, { shown += it }, {})
            assertEquals(CoachUi.Hidden, shown.last())
        }
        var asked = 0
        val shown = mutableListOf<CoachUi>()
        CoachLoader.load({ asked++; ApiResult.Ok(CoachNote(state = "pending")) }, { shown += it }, {})
        assertEquals(CoachLoader.TRIES, asked)
        assertEquals(CoachUi.Hidden, shown.last())
    }

    // ----- The weekly recap -----

    @Test fun `no recap this week is a 204, read as none`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        val none = ai.recap()
        assertTrue(none is ApiResult.Ok)
        assertNull((none as ApiResult.Ok).value)
        server.enqueue(MockResponse().setBody("""{"id":"r1","weekEnding":"2026-10-25","periodStartUtc":"2026-10-18T18:00:00Z","periodEndUtc":"2026-10-25T18:00:00Z",
            "title":"A week of full hearts","lines":["Five hearts."],"suggestion":"Plank on Wednesday.","stats":{"heartsMade":5}}"""))
        assertEquals("A week of full hearts", (ai.recap() as ApiResult.Ok).value?.title)
    }

    @Test fun `the recap's dates and numbers read naturally`() {
        val recap = WeeklyRecap("r1", "2026-10-25", "", "", "Title", stats = WeeklyRecapStats(heartsMade = 5, myDaresDone = 3, partnerDaresDone = 2, myStreakDays = 4, gamesPlayed = 1))
        assertEquals("19–25 Oct", RecapText.range(recap))
        assertEquals("27 Oct – 2 Nov", RecapText.range(recap.copy(weekEnding = "2026-11-02")))
        val tiles = RecapText.tiles(recap.stats, "Theo")
        assertEquals(listOf("5" to "hearts made", "3" to "dares you did", "2" to "dares Theo did", "4" to "days in your streak", "1" to "game played"), tiles)
        // A quiet corner of the week is left out rather than shown as a zero.
        assertEquals(listOf("0" to "hearts made"), RecapText.tiles(WeeklyRecapStats(), "Theo"))
    }

    @Test fun `the recap push opens Us and refreshes it`() {
        assertEquals("us", PushInbox.tabFor("weeklyRecap"))
        assertTrue(PushInbox.refreshesHome("weeklyRecap"))
    }

    // ----- Plan something -----

    @Test fun `a city goes only with going out, trimmed, and never empty`() {
        val out = PlanUi(mood = PlanMood.Romantic, budget = PlanBudget.Treat, setting = PlanSetting.Out, city = "  Lagos ")
        assertEquals("Lagos", PlanForm.request(out).city)
        assertNull(PlanForm.request(out.copy(setting = PlanSetting.In)).city)
        assertNull(PlanForm.request(out.copy(city = "   ")).city)
        assertEquals(PlanMood.Romantic, PlanForm.request(out).mood)
    }

    @Test fun `a plan's games this app doesn't know are left out and the request uses the wire names`() = runTest {
        server.enqueue(MockResponse().setBody("""{"dates":[{"title":"Picnic","why":"Sun's out."}],
            "games":[{"gameType":"chessBoxing","why":"new"},{"gameType":"skillDuel","why":"Duel!"}],"left":4}"""))
        val plan = (ai.plan(PlanForm.request(PlanUi(mood = PlanMood.Cosy, setting = PlanSetting.Out, city = "Lagos"))) as ApiResult.Ok).value
        assertEquals(listOf(GameType.SkillDuel), plan.games.map { it.gameType })
        assertEquals("""{"mood":"cosy","budget":"free","setting":"out","city":"Lagos"}""", server.takeRequest().body.readUtf8())
    }
}
