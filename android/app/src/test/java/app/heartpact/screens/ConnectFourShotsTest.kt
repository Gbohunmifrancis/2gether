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
class ConnectFourShotsTest {
    private val now = Instant.parse("2026-10-11T18:00:00Z")

    /** Six rows of seven from the top: m for Maya, t for Theo, a dot for empty. */
    private fun cells(vararg rows: String) = rows.joinToString("").map { if (it == 'm') "\"maya\"" else if (it == 't') "\"theo\"" else "null" }.joinToString(",", "[", "]")

    private fun state(cells: String, game: Int, phase: String, results: String, maya: Int, theo: Int, line: String = "null") =
        """{"players":["maya","theo"],"columns":7,"rows":6,"cells":$cells,"game":$game,"totalGames":3,"winsNeeded":2,"phase":"$phase","line":$line,"scores":{"maya":$maya,"theo":$theo},"results":$results}"""

    private fun session(state: String, turn: String?, status: GameStatus = GameStatus.InProgress) =
        GameSession("g", "c", GameType.ConnectFour, status, currentTurnUserId = turn, stateVersion = 12, stateJson = state, invitedByUserId = "theo")

    @Composable private fun screen(s: GameSession) = GamesScreen(GamesUi(listOf(s)), "maya", "Theo", GameActions(), { now })

    @Test fun midGame() {
        val s = session(state(cells(
            ".......",
            ".......",
            ".......",
            "...t...",
            "..mm.tt",
            ".tmtmmt",
        ), 1, "playing", """["theo"]""", 0, 1), "maya")
        shot("games-connectfour") { screen(s) }
        shot("games-connectfour-night", night = true) { screen(s) }
    }

    @Test fun win() = shot("games-connectfour-win") {
        screen(session(state(cells(
            ".......",
            ".......",
            "....m..",
            "...mt..",
            "..mtt..",
            ".mttm.m",
        ), 0, "gameOver", """["maya"]""", 1, 0, "[18,24,30,36]"), "theo"))
    }

    /** Columns stack Theo first or Maya first in the pattern AABBAAB, so no four ever line up. */
    @Test fun draw() {
        val pattern = intArrayOf(0, 0, 1, 1, 0, 0, 1)
        val rows = (0 until 6).map { row -> (0 until 7).map { column -> if ((5 - row + pattern[column]) % 2 == 0) 't' else 'm' }.joinToString("") }
        shot("games-connectfour-draw") {
            screen(session(state(cells(*rows.toTypedArray()), 1, "gameOver", """["maya",null]""", 1, 0), "maya"))
        }
    }
}
