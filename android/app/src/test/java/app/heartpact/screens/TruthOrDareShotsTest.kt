package app.heartpact.screens

import app.heartpact.data.GameSession
import app.heartpact.data.GameStatus
import app.heartpact.data.GameType
import app.heartpact.ui.games.GameActions
import app.heartpact.ui.games.GamesScreen
import app.heartpact.ui.games.GamesUi
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class TruthOrDareShotsTest {
    private val now = Instant.parse("2026-10-11T18:00:00Z")
    private fun session(state: String, status: GameStatus = GameStatus.InProgress, turn: String? = "maya") =
        GameSession("g", "c", GameType.TruthOrDare, status, currentTurnUserId = turn, stateVersion = 4, stateJson = state, invitedByUserId = "theo")
    @androidx.compose.runtime.Composable private fun screen(s: GameSession) = GamesScreen(GamesUi(listOf(s), loaded = true), "maya", "Theo", GameActions(), { now })

    private val choosing = session("""{"players":["maya","theo"],"scores":{"maya":2,"theo":1},"turn":4,"totalTurns":10,"phase":"choosing","chooser":"maya","judge":"theo",
        "last":{"chooser":"theo","choice":"dare","prompt":"Send a voice note singing our song.","result":"done"}}""")
    private val judging = session("""{"players":["maya","theo"],"scores":{"maya":2,"theo":2},"turn":5,"totalTurns":10,"phase":"judging","chooser":"theo","judge":"maya","choice":"dare",
        "prompt":"Change your wallpaper to a photo of us for a day.","last":{"chooser":"maya","choice":"truth","prompt":"What was your very first impression of me?","result":"skipped"}}""")
    private val waiting = session("""{"players":["maya","theo"],"scores":{"maya":3,"theo":2},"turn":6,"totalTurns":10,"phase":"judging","chooser":"maya","judge":"theo","choice":"truth",
        "prompt":"Wetin be the first thing you notice about me?"}""", turn = "theo")
    private val done = session("""{"players":["maya","theo"],"scores":{"maya":4,"theo":3},"turn":10,"totalTurns":10,"phase":"completed",
        "last":{"chooser":"theo","choice":"truth","prompt":"When do you miss me the most?","result":"done"}}""", GameStatus.Completed, null)

    @Test fun choosing() = shot("games-truthordare-choose") { screen(choosing) }
    @Test fun choosingNight() = shot("games-truthordare-choose-night", night = true) { screen(choosing) }
    @Test fun judging() = shot("games-truthordare-judge") { screen(judging) }
    @Test fun judgingNight() = shot("games-truthordare-judge-night", night = true) { screen(judging) }
    @Test fun waitingForJudge() = shot("games-truthordare-wait") { screen(waiting) }
    @Test fun partnerChoosing() = shot("games-truthordare-partner") { screen(session(choosing.stateJson.replace("\"chooser\":\"maya\",\"judge\":\"theo\"", "\"chooser\":\"theo\",\"judge\":\"maya\""), turn = "theo")) }
    @Test fun completed() = shot("games-truthordare-done") { screen(done) }
}
