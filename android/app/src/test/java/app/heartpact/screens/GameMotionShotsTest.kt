package app.heartpact.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.heartpact.data.GameSession
import app.heartpact.data.GameStatus
import app.heartpact.data.GameType
import app.heartpact.ui.games.GameActions
import app.heartpact.ui.games.GamesScreen
import app.heartpact.ui.games.GamesUi
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.HeartpactTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/**
 * Frames from the middle of the games' motion, with the clock held by hand: a screen settles, the game moves on
 * (a disc dropped, a card turned, a point scored), and frames are taken a few milliseconds in.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class GameMotionShotsTest {
    @get:Rule val compose = createComposeRule()
    private val now = Instant.parse("2026-10-11T18:00:00Z")

    /** Opens on [start], lets it settle for [settle] ms, moves to [then], and takes a frame at each of [at] ms after. */
    private fun frames(name: String, start: GameSession?, then: GameSession?, at: List<Long>, settle: Long = 4000, night: Boolean = false, history: List<GameSession> = emptyList()) {
        var session by mutableStateOf(start)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            HeartpactTheme(night = night) {
                Box(Modifier.fillMaxSize().background(Heartpact.colors.page)) {
                    GamesScreen(GamesUi(listOfNotNull(session) + history, loaded = true), "maya", "Theo", GameActions(), { now })
                }
            }
        }
        compose.mainClock.advanceTimeBy(settle)
        if (then != null) session = then
        var t = 0L
        at.forEach { ms ->
            compose.mainClock.advanceTimeBy(ms - t)
            t = ms
            compose.onRoot().captureRoboImage("build/screens/motion/$name-${ms}ms.png")
        }
    }

    private fun session(type: GameType, state: String, turn: String? = "maya", version: Long = 5, status: GameStatus = GameStatus.InProgress, ended: String? = null, countdown: String? = null) =
        GameSession("g", "c", type, status, currentTurnUserId = turn, endedAtUtc = ended, countdownEndsAtUtc = countdown, stateVersion = version, stateJson = state, invitedByUserId = "theo")

    private val doneMemory = session(GameType.MemoryMatch, """{"players":["maya","theo"],"scores":{"maya":4,"theo":2},"cards":[]}""", null, status = GameStatus.Completed, ended = "2026-10-10T18:00:00Z")

    /** The hub opening: blobs popping in one after another. */
    @Test fun hubOpening() = frames("hub-open", null, null, listOf(120, 260, 420), settle = 0, history = listOf(doneMemory))

    private fun connectFour(cells: String, turn: String, version: Long) =
        session(GameType.ConnectFour, """{"players":["maya","theo"],"columns":7,"rows":6,"cells":$cells,"game":0,"totalGames":3,"winsNeeded":2,"phase":"playing","scores":{"maya":0,"theo":0},"results":[]}""", turn, version)

    private fun c4(board: List<String>) = board.joinToString("").map { if (it == 'm') "\"maya\"" else if (it == 't') "\"theo\"" else "null" }.joinToString(",", "[", "]")

    /** A disc falling down its column and squashing as it lands. */
    @Test fun discDrop() {
        val before = listOf(".......", ".......", ".......", ".......", "...t...", "..mm.t.")
        val after = listOf(".......", ".......", ".......", ".......", "..mt...", "..mm.t.")
        frames("connectfour-drop", connectFour(c4(before), "maya", 5), connectFour(c4(after), "theo", 6), listOf(150, 260, 330, 420))
    }

    /** Tic-tac-toe: the third piece lands and the pen strikes through the line. */
    @Test fun winLine() {
        fun ttt(board: String, phase: String, line: String, maya: Int, version: Long) = session(GameType.TicTacToe,
            """{"players":["maya","theo"],"columns":3,"rows":3,"cells":${board.map { if (it == 'm') "\"maya\"" else if (it == 't') "\"theo\"" else "null" }.joinToString(",", "[", "]")},"game":0,"totalGames":5,"winsNeeded":3,"phase":"$phase","line":$line,"scores":{"maya":$maya,"theo":0},"results":${if (maya > 0) "[\"maya\"]" else "[]"}${if (phase == "gameOver") ",\"nextGameAtUtc\":\"2026-10-11T18:00:05Z\"" else ""}}""",
            if (maya > 0) "theo" else "maya", version)
        frames("tictactoe-win", ttt("mt.tm....", "playing", "null", 0, 5), ttt("mt.tm...m", "gameOver", "[0,4,8]", 1, 6), listOf(120, 600, 900, 1600))
    }

    /** Memory: a card turning over, and a pair found popping with hearts. */
    @Test fun memoryFlip() {
        fun cards(vararg up: Pair<Int, Int>, matched: Set<Int> = emptySet()) = (0 until 12).joinToString(",") { i ->
            val v = up.firstOrNull { it.first == i }?.second
            """{"index":$i,"value":${v ?: "null"},"matched":${i in matched}}"""
        }
        val one = session(GameType.MemoryMatch, """{"players":["maya","theo"],"scores":{"maya":1,"theo":1},"cards":[${cards(4 to 2)}],"revealed":[4]}""", version = 5)
        val pair = session(GameType.MemoryMatch, """{"players":["maya","theo"],"scores":{"maya":2,"theo":1},"cards":[${cards(4 to 2, 9 to 2, matched = setOf(4, 9))}],"revealed":[],"lastResult":"match","lastPair":[{"index":4,"value":2},{"index":9,"value":2}]}""", version = 6)
        frames("memory-pair", one, pair, listOf(90, 160, 400, 650, 1000))
    }

    /** The countdown's number dropping in. */
    @Test fun countdown() = frames("countdown", null,
        session(GameType.TicTacToe, """{"players":["maya","theo"],"cells":[],"game":0,"totalGames":5}""", countdown = "2026-10-11T18:00:02Z"), listOf(60, 140, 260, 900), settle = 0)

    /** A finished game's card: counting up, the pen going round, the stamp coming down. */
    @Test fun result() = frames("result", null,
        session(GameType.MemoryMatch, """{"players":["maya","theo"],"scores":{"maya":4,"theo":2},"cards":[]}""", null, status = GameStatus.Completed, ended = "2026-10-11T17:59:30Z"),
        listOf(500, 1150, 1500, 1800, 2400), settle = 0)
}
