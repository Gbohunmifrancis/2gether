package app.heartpact.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import app.heartpact.alarm.AlarmRinging
import app.heartpact.alarm.HeartComplete
import app.heartpact.album.LocalPhoto
import app.heartpact.data.DailyAnswer
import app.heartpact.data.DailyQuestion
import app.heartpact.data.ExerciseGoal
import app.heartpact.data.ExerciseHistory
import app.heartpact.data.ExerciseOccurrence
import app.heartpact.data.ExerciseToday
import app.heartpact.data.FoodDay
import app.heartpact.data.FoodEntry
import app.heartpact.data.FoodEntrySource
import app.heartpact.data.FoodPartnerDay
import app.heartpact.data.FoodShareLevel
import app.heartpact.data.FoodTotals
import app.heartpact.data.GameSession
import app.heartpact.data.GameStatus
import app.heartpact.data.GameType
import app.heartpact.data.MealType
import app.heartpact.data.Memory
import app.heartpact.data.MemoryUsage
import app.heartpact.data.NutritionTarget
import app.heartpact.data.OccurrenceStatus
import app.heartpact.exercise.CounterState
import app.heartpact.exercise.ExerciseType
import app.heartpact.exercise.Landmark
import app.heartpact.exercise.Pose
import app.heartpact.i18n.Tr
import app.heartpact.ui.album.AlbumTab
import app.heartpact.ui.album.AlbumUi
import app.heartpact.ui.album.PhotoSlot
import app.heartpact.ui.camera.CameraRepContent
import app.heartpact.ui.camera.CameraUi
import app.heartpact.ui.food.FoodTab
import app.heartpact.ui.food.FoodUi
import app.heartpact.ui.games.GameActions
import app.heartpact.ui.games.GamesScreen
import app.heartpact.ui.games.GamesUi
import app.heartpact.ui.home.HomeScreen
import app.heartpact.ui.home.Tab
import app.heartpact.ui.pacts.PactsBuilder
import app.heartpact.ui.pacts.PactsTab
import app.heartpact.ui.pacts.PactsUi
import app.heartpact.ui.theme.Side
import app.heartpact.ui.today.NewDareScreen
import app.heartpact.ui.today.NewDareState
import app.heartpact.ui.today.TodayBuilder
import app.heartpact.ui.today.TodayTab
import app.heartpact.ui.today.TodayUi
import app.heartpact.ui.us.QuestionScreen
import app.heartpact.ui.us.QuestionUi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The real screens behind the Play Store listing (marketing/play-store), in every language the app speaks, into
 * build/screens/store/<language>/. Photos are the licensed stand-ins in test resources (design/heartpact-v3/assets);
 * the camera's pose points are what the app's own model (pose_landmarker_lite.task) found on that photo. Text the
 * server would send (the daily question, the word to draw) comes from the server's catalogs.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class StoreShotsTest {
    private val english = Locale.getDefault()
    private val LANGUAGES = listOf("en", "fr", "pt", "es", "ar", "hi")

    @After fun back() {
        Locale.setDefault(english)
        Tr.use("en", null)
    }

    @Test fun english() = screens("en")
    @Test fun french() = screens("fr")
    @Test fun portuguese() = screens("pt")
    @Test fun spanish() = screens("es")
    @Test fun arabic() = screens("ar")
    @Test fun hindi() = screens("hi")

    /** The same screens on a 7-inch (600 × 960 dp) and a 10-inch (800 × 1280 dp) tablet. */
    @Test @Config(qualifiers = "w600dp-h960dp-xhdpi") fun tablet7() = LANGUAGES.forEach { screens(it, "store-tablet7") }
    @Test @Config(qualifiers = "w800dp-h1280dp-xhdpi") fun tablet10() = LANGUAGES.forEach { screens(it, "store-tablet10") }

    /** What the two of them typed: people write in their own language. */
    private val typed = mapOf(
        "en" to listOf("Somewhere with no signal. A cabin, a lake, and no alarms at all, not even yours.", "Learn to sail, badly, and laugh about it.", "French toast, berries", "Chickpea salad bowl", "kite"),
        "fr" to listOf("Un endroit sans réseau. Une cabane, un lac, et aucun réveil, même pas le tien.", "Apprendre la voile, mal, et en rire.", "Pain perdu, fruits rouges", "Bol de salade de pois chiches", "cerf-volant"),
        "pt" to listOf("Um lugar sem sinal. Uma cabana, um lago e nenhum alarme, nem o seu.", "Aprender a velejar, mal, e rir disso.", "Rabanada com frutas vermelhas", "Bowl de salada de grão-de-bico", "pipa"),
        "es" to listOf("Un lugar sin señal. Una cabaña, un lago y ninguna alarma, ni siquiera la tuya.", "Aprender a navegar, mal, y reírnos de eso.", "Pan francés con frutos rojos", "Bowl de ensalada de garbanzos", "cometa"),
        "ar" to listOf("مكان بلا إشارة. كوخ وبحيرة، ولا منبّهات أبدًا، ولا حتى منبّهك.", "أن نتعلّم الإبحار، بشكل سيّئ، ونضحك على ذلك.", "توست فرنسي مع توت", "سلطة الحمص", "طائرة ورقية"),
        "hi" to listOf("कहीं ऐसी जगह जहाँ नेटवर्क न हो। एक केबिन, एक झील, और कोई अलार्म नहीं, तुम्हारा भी नहीं।", "नाव चलाना सीखना, बुरी तरह, और उस पर हँसना।", "फ्रेंच टोस्ट, बेरी", "छोले का सलाद", "पतंग"),
    )

    private fun screens(language: String, folder: String = "store") {
        Locale.setDefault(english)
        Tr.use("en", null)
        if (language != "en") {
            val file = File("src/main/assets/i18n/$language.json")
            Locale.setDefault(Locale.forLanguageTag(language))
            Tr.use(language, file.readText())
        }
        val server = File("../../src/Twogether.Shared/Localization/i18n/$language.json").takeIf { it.exists() }
            ?.let { Json.parseToJsonElement(it.readText()).jsonObject }
        val srv = { english: String -> server?.get(english)?.jsonPrimitive?.content ?: english }
        val (mineText, theirsText, toastName, bowlName, kite) = typed.getValue(language)
        val rtl = language == "ar"
        fun at(name: String, night: Boolean = false, content: @Composable () -> Unit) = shot("$folder/$language/$name", night) {
            CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) { content() }
        }

        // The morning dare: Theo set Maya 20 sit-ups at 7:40; it's ringing.
        val now = Instant.parse("2026-10-11T07:48:00Z")
        val goal = ExerciseGoal("g1", "maya", "Maya", "theo", ExerciseType.SitUp, "Sit-ups", 20, scheduledTime = "07:40", timeZoneId = "UTC", nextDueAtUtc = "2026-10-12T07:40:00Z")
        val ringing = ExerciseOccurrence("o1", "g1", "maya", "Maya", ExerciseType.SitUp, "Sit-ups", 20, localDate = "2026-10-11",
            scheduledAtUtc = "2026-10-11T07:40:00Z", deadlineUtc = "2026-10-11T08:10:00Z", status = OccurrenceStatus.Alarming)
        val hisGoal = ExerciseGoal("g2", "theo", "Theo", "maya", ExerciseType.PushUp, "Push-ups", 30, scheduledTime = "18:30", timeZoneId = "UTC", nextDueAtUtc = "2026-10-11T18:30:00Z")
        val history = ExerciseHistory(7, listOf("05" to OccurrenceStatus.Completed, "06" to OccurrenceStatus.Completed, "07" to OccurrenceStatus.Completed,
            "08" to OccurrenceStatus.Completed, "09" to OccurrenceStatus.Missed, "10" to OccurrenceStatus.Completed).mapIndexed { i, (day, status) ->
            ringing.copy(id = "h$i", localDate = "2026-10-$day", status = status)
        })
        val view = TodayBuilder.build(ExerciseToday("x", listOf(goal, hisGoal), listOf(ringing)), history, "maya", now, ZoneOffset.UTC)
        at("today") { HomeScreen("Maya", "Theo", {}, today = { TodayTab(TodayUi(view = view, loading = false), "Theo", {}, onCallOff = {}, onKeep = {}, onNewDare = {}, now = now) }) }
        at("new-dare") { NewDareScreen(NewDareState(type = ExerciseType.Squat, target = 20), "Theo", {}, {}, {}, {}, {}, {}) }
        at("alarm") { AlarmRinging(ringing, "Theo", setByPartner = true, mySide = Side.Left, busy = false, note = null, {}, {}, {}, now = { now }) }

        // Squats in front of the camera, Theo watching. The photo is cropped to the camera area (1170 × 1868 px).
        val squat = MutableList(Pose.COUNT) { Landmark(0.5f, 0.5f, 0.1f) }.apply {
            this[Pose.L_SHOULDER] = Landmark(0.512f, 0.375f, 1f); this[Pose.R_SHOULDER] = Landmark(0.356f, 0.365f, 1f)
            this[Pose.L_ELBOW] = Landmark(0.686f, 0.421f, 0.9f); this[Pose.R_ELBOW] = Landmark(0.548f, 0.475f, 1f)
            this[Pose.L_WRIST] = Landmark(0.858f, 0.471f, 0.92f); this[Pose.R_WRIST] = Landmark(0.840f, 0.471f, 0.99f)
            this[Pose.L_HIP] = Landmark(0.274f, 0.562f, 1f); this[Pose.R_HIP] = Landmark(0.138f, 0.554f, 1f)
            this[Pose.L_KNEE] = Landmark(0.546f, 0.643f, 0.83f); this[Pose.R_KNEE] = Landmark(0.243f, 0.676f, 0.99f)
            this[Pose.L_ANKLE] = Landmark(0.408f, 0.811f, 0.84f); this[Pose.R_ANKLE] = Landmark(0.143f, 0.871f, 0.99f)
        }
        val workout = CameraUi(ExerciseType.Squat, 20, CounterState(count = 14, inFrame = true, confidence = 0.9), Pose(squat, 0, 1170f / 1868f),
            secondsLeft = 24 * 60 + 10, elapsedSeconds = 96, setBy = "Theo", silenced = false)
        val squatPhoto = photo("camera-squat.jpg")
        at("camera") { CameraRepContent(workout, {}, {}) { Image(squatPhoto, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) } }

        val done = ringing.copy(status = OccurrenceStatus.Completed, achievedCount = 20, completedAtUtc = "2026-10-11T07:52:00Z")
        at("heart-complete") { HeartComplete(done, "Theo", onDone = {}, frozenAtMs = app.heartpact.ui.graphics.StickTimeline.END_MS) }

        val pactsView = PactsBuilder.build(listOf(goal, hisGoal), history.copy(occurrences = history.occurrences + done.copy(id = "t")), "maya", now, ZoneOffset.UTC)
        at("pacts") { HomeScreen("Maya", "Theo", {}, initialTab = Tab.Pacts, pacts = { PactsTab(PactsUi(pactsView), "Theo", {}, {}) }) }

        // Today's question, both answered.
        val theirs = DailyAnswer("theo", "Theo", theirsText, "2026-10-11T06:12:00Z")
        val mine = DailyAnswer("maya", "Maya", mineText, "2026-10-11T07:30:00Z")
        val question = DailyQuestion("q", "2026-10-11", "future-01", "future", srv("If we had one extra free weekend a month, what would you want us to do with it?"),
            partnerAnswered = true, streakDays = 14, myAnswer = mine, partnerAnswer = theirs)
        val yesterday = DailyQuestion("y", "2026-10-10", "everyday-10", "everyday", srv("What is one small habit of mine that you secretly like?"), myAnswer = mine, partnerAnswered = true, partnerAnswer = theirs)
        at("question") { QuestionScreen(QuestionUi(today = question, earlier = listOf(yesterday)), "Theo", {}, {}, {}, {}, today = LocalDate.of(2026, 10, 11)) }

        // Draw and guess: Maya draws an umbrella; Theo has tried one word.
        val umbrella = listOf(
            stroke(arc(0.5, 0.5, 0.32, PI, 2 * PI)),
            stroke(arc(0.26, 0.5, 0.08, PI, 0.0, 10) + arc(0.42, 0.5, 0.08, PI, 0.0, 10) + arc(0.58, 0.5, 0.08, PI, 0.0, 10) + arc(0.74, 0.5, 0.08, PI, 0.0, 10)),
            stroke(listOf(0.5 to 0.18, 0.5 to 0.5, 0.5 to 0.8) + arc(0.44, 0.8, 0.06, 0.0, PI, 10)),
        ).joinToString(",")
        val draw = GameSession("g", "c", GameType.Handwriting, GameStatus.InProgress, currentTurnUserId = "maya", stateVersion = 12, invitedByUserId = "theo",
            stateJson = """{"players":["maya","theo"],"scores":{"maya":2,"theo":2},"round":2,"totalRounds":6,"phase":"drawing","drawer":"maya","guesser":"theo",
                "word":${quote(srv("umbrella"))},"strokes":[$umbrella],"wrongGuesses":[${quote(kite)}],"guessesLeft":2,"maxGuesses":3,
                "lastRound":{"round":1,"drawer":"theo","word":${quote(srv("guitar"))},"outcome":"guessed","guess":${quote(srv("guitar"))},"strokes":[],"wrongGuesses":[]}}""")
        at("games-draw") { GamesScreen(GamesUi(listOf(draw), loaded = true), "maya", "Theo", GameActions(), { Instant.parse("2026-10-11T18:00:00Z") }) }
        at("games") { GamesScreen(GamesUi(emptyList(), loaded = true), "maya", "Theo", GameActions(), { now }) }

        // The album: a few photos still only on this phone, the shared ones, and on this day.
        val photos = listOf("beach", "boat", "chairs", "coffee", "dinner", "hike", "lake").associateWith { photo("album-$it.jpg") }
        val names = photos.keys.toList()
        val real: PhotoSlot = { model, _, modifier, crop ->
            val key = model.toString().filter { it.isDigit() }.toIntOrNull() ?: 0
            Image(photos.getValue(names[key % names.size]), null, modifier, contentScale = if (crop) ContentScale.Crop else ContentScale.Fit)
        }
        val mb = 1024L * 1024
        val local = (1..4).map { LocalPhoto("c$it", "c$it.jpg", "c$it.jpg", "image/jpeg", 3 * mb + it * 100_000L, null, 0) }
        val shared = (1..11).map { Memory("m${it + 4}", if (it % 3 == 0) "theo" else "maya", if (it % 3 == 0) "Theo" else "Maya", "image/jpeg", sortAtUtc = "2026-10-0${it % 9 + 1}T10:00:00Z", url = "m${it + 4}") }
        val onThisDay = listOf(Memory("m7", "theo", "Theo", "image/jpeg", sortAtUtc = "2025-10-11T09:00:00Z", url = "m7"), Memory("m6", "maya", "Maya", "image/jpeg", sortAtUtc = "2024-10-11T09:00:00Z", url = "m6"))
        at("album") {
            HomeScreen("Maya", "Theo", {}, initialTab = Tab.Album, album = {
                AlbumTab(AlbumUi(local = local, shared = shared, usage = MemoryUsage(count = 214, usedBytes = 812 * mb, limitBytes = 5L * 1024 * mb), loaded = true, onThisDay = onThisDay),
                    mapOf("maya" to Side.Left, "theo" to Side.Right), localModel = { it.clientId }, onAdd = {}, onSync = {}, onRemove = {}, onRetry = {}, onLoadMore = {},
                    onDismissResult = {}, unmetered = { true }, image = real)
            })
        }

        // Food: two meals from photos, each an estimate shown as a range.
        val toast = FoodEntry("a", "maya", "2026-10-10", MealType.Breakfast, toastName, 640, 540, 760, source = FoodEntrySource.PhotoEstimate, loggedAtUtc = "2026-10-10T07:40:00Z")
        val bowl = FoodEntry("b", "maya", "2026-10-10", MealType.Lunch, bowlName, 600, 480, 700, source = FoodEntrySource.PhotoEstimate, loggedAtUtc = "2026-10-10T11:50:00Z")
        val day = FoodDay("2026-10-10", NutritionTarget(dailyCalories = 1900), FoodTotals(1240, entryCount = 2, caloriesLow = 1020, caloriesHigh = 1460), 660, listOf(toast, bowl),
            FoodPartnerDay("theo", "Theo", FoodShareLevel.TotalsOnly, 2400, FoodTotals(1610, entryCount = 3, caloriesLow = 1370, caloriesHigh = 1850)), scansUsedToday = 1, scanLimitPerDay = 3)
        at("food") { HomeScreen("Maya", "Theo", {}, initialTab = Tab.Food, food = { FoodTab(FoodUi(day), "Theo", {}, {}, {}, {}, { _, _ -> }, today = LocalDate.of(2026, 10, 10)) }) }
    }

    private fun photo(name: String): ImageBitmap =
        javaClass.getResourceAsStream("/store/$name")!!.use { BitmapFactory.decodeStream(it) }.asImageBitmap()

    private fun quote(text: String) = Json.encodeToString(kotlinx.serialization.json.JsonPrimitive(text))

    private fun stroke(points: List<Pair<Double, Double>>, width: Double = 0.012) =
        """{"points":[${points.joinToString(",") { (x, y) -> String.format(Locale.ROOT, "[%.3f,%.3f]", x, y) }}],"width":$width}"""

    private fun arc(cx: Double, cy: Double, r: Double, from: Double, to: Double, steps: Int = 24) =
        (0..steps).map { i -> val a = from + (to - from) * i / steps; cx + r * cos(a) to cy + r * sin(a) }
}
