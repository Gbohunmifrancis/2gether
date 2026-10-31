package app.heartpact.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import app.heartpact.data.ExerciseGoal
import app.heartpact.data.ExerciseHistory
import app.heartpact.data.ExerciseToday
import app.heartpact.data.GameSession
import app.heartpact.data.GameStatus
import app.heartpact.data.GameType
import app.heartpact.exercise.ExerciseType
import app.heartpact.i18n.Tr
import app.heartpact.ui.home.HomeScreen
import app.heartpact.ui.today.TodayBuilder
import app.heartpact.ui.today.TodayGames
import app.heartpact.ui.today.TodayTab
import app.heartpact.ui.today.TodayUi
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

/** Today with a game waiting on me: Theo's invite, my turn, my move; by day, by night, right to left, on a tablet. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class TodayGamesShotsTest {
    private val english = Locale.getDefault()
    private val now = Instant.parse("2026-10-11T07:48:00Z")
    private val goal = ExerciseGoal("g1", "maya", "Maya", "theo", ExerciseType.SitUp, "Sit-ups", 20, scheduledTime = "18:00", timeZoneId = "UTC", nextDueAtUtc = "2026-10-11T18:00:00Z")
    private val hisGoal = ExerciseGoal("g2", "theo", "Theo", "maya", ExerciseType.PushUp, "Push-ups", 30, scheduledTime = "18:30", timeZoneId = "UTC", nextDueAtUtc = "2026-10-11T18:30:00Z")

    @After fun back() {
        Locale.setDefault(english)
        Tr.use("en", null)
    }

    private val invite = GameSession("g", "c", GameType.SnakesAndLadders, GameStatus.Waiting, invitedByUserId = "theo", setupJson = """{"region":"global"}""")
    private val turn = GameSession("g", "c", GameType.ConnectFour, GameStatus.InProgress, currentTurnUserId = "maya", stateVersion = 7, invitedByUserId = "theo", stateJson = "{}")
    private val move = GameSession("g", "c", GameType.CoupleQuiz, GameStatus.InProgress, currentTurnUserId = "maya", stateVersion = 7, invitedByUserId = "maya",
        stateJson = """{"players":["maya","theo"],"scores":{"maya":2,"theo":2},"round":3,"totalRounds":10,"answered":["theo"],"mine":null}""")

    @Composable private fun Today(game: GameSession, rtl: Boolean = false) {
        val view = TodayBuilder.build(ExerciseToday("x", listOf(goal, hisGoal), emptyList()), ExerciseHistory(7, emptyList()), "maya", now, ZoneOffset.UTC)
        CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            HomeScreen("Maya", "Theo", {}, today = {
                TodayTab(TodayUi(view = view, loading = false), "Theo", {}, onCallOff = {}, onKeep = {}, onNewDare = {}, now = now,
                    game = TodayGames.waiting(listOf(game), "maya"))
            })
        }
    }

    @Test fun invite() = shot("today-game-invite") { Today(invite) }
    @Test fun inviteNight() = shot("today-game-invite-night", night = true) { Today(invite) }
    @Test fun turn() = shot("today-game-turn") { Today(turn) }
    @Test fun turnNight() = shot("today-game-turn-night", night = true) { Today(turn) }
    @Test fun move() = shot("today-game-move") { Today(move) }

    @Test fun arabic() {
        Locale.setDefault(Locale.forLanguageTag("ar"))
        Tr.use("ar", File("src/main/assets/i18n/ar.json").readText())
        shot("ar/today-game-invite") { Today(invite, rtl = true) }
        shot("ar/today-game-turn-night", night = true) { Today(turn, rtl = true) }
    }

    @Test fun hindi() {
        Locale.setDefault(Locale.forLanguageTag("hi"))
        Tr.use("hi", File("src/main/assets/i18n/hi.json").readText())
        shot("hi/today-game-move") { Today(move) }
    }

    @Config(sdk = [35], qualifiers = "w320dp-h640dp-mdpi")
    @Test fun small() = shot("today-game-invite-small") { Today(invite) }

    @Config(sdk = [35], qualifiers = "w1280dp-h800dp-land-xhdpi")
    @Test fun tablet() = shot("today-game-invite-tablet") { Today(invite) }
}
