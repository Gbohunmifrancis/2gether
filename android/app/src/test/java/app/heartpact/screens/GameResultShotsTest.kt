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

/** A game just finished: the result card (totals counted up, the winner circled, the stamp) over the final board. And the countdown. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class GameResultShotsTest {
    private val now = Instant.parse("2026-10-11T18:00:00Z")
    private fun done(type: GameType, state: String) =
        GameSession("g", "c", type, GameStatus.Completed, endedAtUtc = "2026-10-11T17:59:00Z", stateVersion = 30, stateJson = state, invitedByUserId = "theo")
    @Composable private fun screen(s: GameSession) = GamesScreen(GamesUi(listOf(s), loaded = true), "maya", "Theo", GameActions(), { now })

    private val memoryCards = listOf(1, 4, 2, 6, 3, 5, 5, 2, 6, 1, 4, 3).mapIndexed { i, v -> """{"index":$i,"value":$v,"matched":true}""" }.joinToString(",")
    private val memoryWon = done(GameType.MemoryMatch, """{"players":["maya","theo"],"scores":{"maya":4,"theo":2},"cards":[$memoryCards],"lastResult":"match"}""")

    @Test fun memoryWon() {
        shot("games-result-memory") { screen(memoryWon) }
        shot("games-result-memory-night", night = true) { screen(memoryWon) }
    }

    /** Theo took the match: he cheers, Maya waves, nothing unkind. */
    @Test fun ticTacToeLost() = shot("games-result-tictactoe") {
        val cells = "ttt.mm.m.".map { if (it == 'm') "\"maya\"" else if (it == 't') "\"theo\"" else "null" }.joinToString(",", "[", "]")
        screen(done(GameType.TicTacToe, """{"players":["maya","theo"],"columns":3,"rows":3,"cells":$cells,"game":3,"totalGames":5,"winsNeeded":3,"phase":"matchOver","line":[0,1,2],"scores":{"maya":1,"theo":3},"results":["maya","theo","theo","theo"]}"""))
    }

    @Test fun triviaDraw() = shot("games-result-draw") {
        screen(done(GameType.Trivia, """{"players":["maya","theo"],"scores":{"maya":6,"theo":6},"round":10,"totalRounds":10,"completed":true}"""))
    }

    /** Played as a team: one shared total. */
    @Test fun together() {
        val s = done(GameType.WouldYouRather, """{"players":["maya","theo"],"scores":{"maya":8,"theo":8},"round":10,"totalRounds":10,"phase":"completed",
            "last":{"round":9,"a":"Breakfast in bed","b":"Dinner under the stars","picks":{"maya":1,"theo":1},"match":true}}""")
        shot("games-result-together") { screen(s) }
        shot("games-result-together-night", night = true) { screen(s) }
    }

    @Test fun countdown() {
        val s = GameSession("g", "c", GameType.MemoryMatch, GameStatus.InProgress, currentTurnUserId = "maya", countdownEndsAtUtc = "2026-10-11T18:00:03Z",
            stateVersion = 1, stateJson = """{"players":["maya","theo"],"scores":{},"cards":[]}""", invitedByUserId = "theo")
        shot("games-countdown") { screen(s) }
        shot("games-countdown-night", night = true) { screen(s) }
    }
}
