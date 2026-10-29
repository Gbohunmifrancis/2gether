package app.heartpact.screens

import androidx.compose.runtime.Composable
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
class TicTacToeShotsTest {
    private val now = Instant.parse("2026-10-11T18:00:00Z")

    /** One letter per square, row by row: m for Maya, t for Theo, a dot for empty. */
    private fun cells(board: String) = board.map { if (it == 'm') "\"maya\"" else if (it == 't') "\"theo\"" else "null" }.joinToString(",", "[", "]")

    private fun state(board: String, game: Int, phase: String, results: String, maya: Int, theo: Int, line: String = "null") =
        """{"players":["maya","theo"],"columns":3,"rows":3,"cells":${cells(board)},"game":$game,"totalGames":5,"winsNeeded":3,"phase":"$phase","line":$line,"scores":{"maya":$maya,"theo":$theo},"results":$results}"""

    private fun session(state: String, turn: String?, status: GameStatus = GameStatus.InProgress) =
        GameSession("g", "c", GameType.TicTacToe, status, currentTurnUserId = turn, stateVersion = 9, stateJson = state, invitedByUserId = "theo")

    @Composable private fun screen(s: GameSession) = GamesScreen(GamesUi(listOf(s)), "maya", "Theo", GameActions(), { now })

    @Test fun midGame() {
        val s = session(state("m.t.m..t.", 1, "playing", """["maya"]""", 1, 0), "maya")
        shot("games-tictactoe") { screen(s) }
        shot("games-tictactoe-night", night = true) { screen(s) }
    }

    @Test fun win() = shot("games-tictactoe-win") {
        screen(session(state("mtt.m...m", 2, "gameOver", """["maya","theo","maya"]""", 2, 1, "[0,4,8]"), "theo"))
    }

    @Test fun draw() = shot("games-tictactoe-draw") {
        screen(session(state("tmttmmmtt", 1, "gameOver", """["maya",null]""", 1, 0), "maya"))
    }

    /** A finished match goes back to the picker, whose last-game card gives the score in games won. */
    @Test fun matchOver() = shot("games-tictactoe-match", night = true) {
        screen(session(state("ttt.mm.m.", 3, "matchOver", """["maya","theo","theo","theo"]""", 1, 3, "[0,1,2]"), null, GameStatus.Completed))
    }
}
