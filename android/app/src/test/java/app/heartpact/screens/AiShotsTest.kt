package app.heartpact.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import app.heartpact.alarm.HeartComplete
import app.heartpact.data.DailyQuestion
import app.heartpact.data.DareIdea
import app.heartpact.data.DateIdea
import app.heartpact.data.DatePlan
import app.heartpact.data.ExerciseGoal
import app.heartpact.data.ExerciseOccurrence
import app.heartpact.data.ExerciseToday
import app.heartpact.data.GameIdea
import app.heartpact.data.GameType
import app.heartpact.data.NotificationPreferences
import app.heartpact.data.OccurrenceStatus
import app.heartpact.data.PlanBudget
import app.heartpact.data.PlanMood
import app.heartpact.data.PlanSetting
import app.heartpact.data.WeeklyRecap
import app.heartpact.data.WeeklyRecapStats
import app.heartpact.exercise.ExerciseType
import app.heartpact.i18n.Tr
import app.heartpact.ui.ai.CoachUi
import app.heartpact.ui.ai.DareIdeasUi
import app.heartpact.ui.ai.PlanScreen
import app.heartpact.ui.ai.PlanUi
import app.heartpact.ui.ai.WeeklyRecapCard
import app.heartpact.ui.ai.WeeklyRecapScreen
import app.heartpact.ui.components.Face
import app.heartpact.ui.graphics.StickTimeline
import app.heartpact.ui.home.HomeScreen
import app.heartpact.ui.home.Tab
import app.heartpact.ui.theme.Side
import app.heartpact.ui.today.NewDareScreen
import app.heartpact.ui.today.NewDareState
import app.heartpact.ui.today.TodayBuilder
import app.heartpact.ui.today.TodayTab
import app.heartpact.ui.today.TodayUi
import app.heartpact.ui.us.UsTab
import app.heartpact.ui.us.UsUi
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.Locale

/** The AI features (docs/ai.md) as people see them: build/screens/ai-*.png, one at night and some in Arabic. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class AiShotsTest {
    private val english = Locale.getDefault()

    @After fun back() {
        Locale.setDefault(english)
        Tr.use("en", null)
    }

    private val ideas = listOf(
        DareIdea(ExerciseType.Squat, 20, scheduledTime = "18:30", reason = "Five more than last week. Theo's legs are ready."),
        DareIdea(ExerciseType.Plank, 45, scheduledTime = "07:15", reason = "A calm hold before breakfast, after three good days."),
        DareIdea(ExerciseType.PushUp, 12, scheduledTime = "19:00", reason = "Two up from Tuesday: small enough to say yes to."),
    )

    private val recap = WeeklyRecap(
        "r1", "2026-10-25", "2026-10-18T18:00:00Z", "2026-10-25T18:00:00Z",
        title = "A week of full hearts",
        lines = listOf("Five hearts between you two, and not one missed dare.", "Theo held a 45-second plank on Thursday.", "Three games, and you won two of them.",
            "Four new photos in the album."),
        suggestion = "Try a plank dare together on Wednesday evening.",
        stats = WeeklyRecapStats(heartsMade = 5, myDaresDone = 3, partnerDaresDone = 2, myStreakDays = 4, partnerStreakDays = 6, gamesPlayed = 3, myGamesWon = 2,
            partnerGamesWon = 1, photosAdded = 4, questionsAnswered = 9),
    )

    private val plan = DatePlan(
        dates = listOf(
            DateIdea("Blanket fort film night", "Cosy, free, and nobody has to get dressed."),
            DateIdea("Cook a new recipe together", "A small adventure in your own kitchen."),
            DateIdea("Candlelit board games", "Romantic, and it costs nothing."),
        ),
        games = listOf(GameIdea(GameType.SkillDuel, "You haven't duelled in two weeks."), GameIdea(GameType.WouldYouRather, "Easy laughs on the sofa.")),
        left = 3,
    )

    private val coach = CoachUi.Ready("Twenty sit-ups at a steady pace, and not one too fast. Day four of your streak! Next time, breathe out on the way up.")

    private val done = ExerciseOccurrence("o1", "g1", "maya", "Maya", ExerciseType.SitUp, "Sit-ups", 20, localDate = "2026-10-11",
        scheduledAtUtc = "2026-10-11T08:00:00Z", deadlineUtc = "2026-10-11T08:30:00Z", status = OccurrenceStatus.Completed, achievedCount = 20,
        completedAtUtc = "2026-10-11T07:56:00Z")

    @Test fun newDareIdeas() {
        val state = NewDareState(type = ExerciseType.Squat, target = 20, time = LocalTime.of(18, 30), ideas = DareIdeasUi(available = true, ideas = ideas, picked = 0, left = 7))
        shot("ai-new-dare-ideas") { NewDareScreen(state, "Theo", {}, {}, {}, {}, {}, {}) }
        shot("ai-new-dare-suggest") { NewDareScreen(NewDareState(ideas = DareIdeasUi(available = true, left = 10)), "Theo", {}, {}, {}, {}, {}, {}) }
        shot("ai-new-dare-thinking") { NewDareScreen(NewDareState(ideas = DareIdeasUi(available = true, busy = true, left = 10)), "Theo", {}, {}, {}, {}, {}, {}) }
    }

    @Test fun coachNote() {
        shot("ai-heart-complete-coach") { HeartComplete(done, "Theo", onDone = {}, frozenAtMs = StickTimeline.END_MS, coach = coach) }
        shot("ai-heart-complete-writing") { HeartComplete(done, "Theo", onDone = {}, frozenAtMs = StickTimeline.END_MS, coach = CoachUi.Writing) }
        val now = Instant.parse("2026-10-11T07:48:00Z")
        val goal = ExerciseGoal("g1", "maya", "Maya", "theo", ExerciseType.SitUp, "Sit-ups", 20, scheduledTime = "08:00", timeZoneId = "UTC", nextDueAtUtc = "2026-10-12T08:00:00Z")
        val his = done.copy(id = "o3", goalId = "g2", userId = "theo", exerciseType = ExerciseType.PushUp, title = "Push-ups", target = 30, achievedCount = 30,
            completedAtUtc = "2026-10-11T07:30:00Z")
        val view = TodayBuilder.build(ExerciseToday("x", listOf(goal), listOf(his)), null, "maya", now, ZoneOffset.UTC)
        val theoCoach = CoachUi.Ready("Thirty push-ups in under two minutes, clean all the way. Six days running! Try a slower last five tomorrow.")
        shot("ai-today-partner-coach") {
            HomeScreen("Maya", "Theo", {}, today = { TodayTab(TodayUi(view = view, loading = false, partnerCoach = "o3" to theoCoach), "Theo", {}, onCallOff = {}, onKeep = {}, onNewDare = {}, now = now) })
        }
    }

    @Test fun weeklyRecap() {
        shot("ai-us-recap") { Us() }
        shot("ai-recap-full") { WeeklyRecapScreen(recap, "Theo", onBack = {}, onPlan = {}) }
        shot("ai-recap-full-night", night = true) { WeeklyRecapScreen(recap, "Theo", onBack = {}, onPlan = {}) }
    }

    @Test fun planSomething() {
        val asked = PlanUi(mood = PlanMood.Cosy, budget = PlanBudget.Free, setting = PlanSetting.In, plan = plan, left = 3)
        shot("ai-plan") { Plan(PlanUi(left = 5)) }
        shot("ai-plan-out") { Plan(PlanUi(mood = PlanMood.Adventurous, budget = PlanBudget.Low, setting = PlanSetting.Out, city = "Lagos", left = 5)) }
        shot("ai-plan-ideas") { Plan(asked) }
        shot("ai-plan-ideas-night", night = true) { Plan(asked) }
    }

    /** Arabic, right to left: the dare ideas, the plan and the recap with their own words in Arabic too. */
    @Test fun arabic() {
        val file = File("src/main/assets/i18n/ar.json")
        Locale.setDefault(Locale.forLanguageTag("ar"))
        Tr.use("ar", file.readText())
        fun rtl(name: String, content: @Composable () -> Unit) = shot("ar/$name") {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { content() }
        }
        val arabicIdeas = listOf(
            DareIdea(ExerciseType.Squat, 20, scheduledTime = "18:30", reason = "خمسة أكثر من الأسبوع الماضي، ثيو جاهز."),
            DareIdea(ExerciseType.Plank, 45, scheduledTime = "07:15", reason = "ثبات هادئ قبل الفطور بعد ثلاثة أيام جيدة."),
            DareIdea(ExerciseType.PushUp, 12, scheduledTime = "19:00", reason = "اثنان أكثر من الثلاثاء: سهل أن يقول نعم."),
        )
        rtl("ai-new-dare-ideas") {
            NewDareScreen(NewDareState(type = ExerciseType.Plank, target = 45, time = LocalTime.of(7, 15), ideas = DareIdeasUi(available = true, ideas = arabicIdeas, picked = 1, left = 7)),
                "Theo", {}, {}, {}, {}, {}, {})
        }
        rtl("ai-plan-ideas") {
            Plan(PlanUi(mood = PlanMood.Romantic, budget = PlanBudget.Treat, setting = PlanSetting.Out, city = "القاهرة", left = 2, plan = DatePlan(
                dates = listOf(DateIdea("عشاء على ضوء الشموع", "دلال صغير لليلة خاصة."), DateIdea("نزهة على الكورنيش", "هواء النيل والحديث الطويل."),
                    DateIdea("درس رقص لشخصين", "ضحك كثير وقرب أكثر.")),
                games = listOf(GameIdea(GameType.CoupleQuiz, "لتعرفا كم تتشابهان."), GameIdea(GameType.Trivia, "منافسة لطيفة قبل النوم.")), left = 2)))
        }
        rtl("ai-recap-full") {
            WeeklyRecapScreen(recap.copy(title = "أسبوع مليء بالقلوب", lines = listOf("خمسة قلوب بينكما، ولا تحدٍّ فائت.", "ثلاث ألعاب، وفزت باثنتين.", "أربع صور جديدة في الألبوم."),
                suggestion = "جرّبا تحدي البلانك معًا يوم الأربعاء."), "Theo", onBack = {}, onPlan = {})
        }
        rtl("ai-heart-complete-coach") {
            HeartComplete(done, "Theo", onDone = {}, frozenAtMs = StickTimeline.END_MS, coach = CoachUi.Ready("عشرون تمرين بطن بإيقاع ثابت ولا تكرار سريع. اليوم الرابع في سلسلتك! في المرة القادمة ازفر وأنت تصعد."))
        }
    }

    @Composable private fun Plan(ui: PlanUi) = PlanScreen(ui, "Theo", {}, {}, {}, {}, {}, {}, {})

    @Composable private fun Us() {
        val question = DailyQuestion("q", "2026-10-11", "future-03", "future", "If we had one extra free weekend every month, what would you want us to do with it?",
            partnerAnswered = true, streakDays = 14)
        HomeScreen("Maya", "Theo", {}, initialTab = Tab.Us, us = {
            UsTab(UsUi(question = question, preferences = NotificationPreferences(quietHoursEnabled = true)), Face("maya", "Maya", 0, Side.Left), Face("theo", "Theo", 0, Side.Right),
                "together since 2 March", {}, {}, {}, recap = { WeeklyRecapCard(recap, onOpen = {}) }, onPlan = {})
        })
    }
}
