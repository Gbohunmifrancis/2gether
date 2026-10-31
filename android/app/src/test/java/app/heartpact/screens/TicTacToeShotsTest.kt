package app.heartpact.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import app.heartpact.data.GameSession
import app.heartpact.data.GameStatus
import app.heartpact.data.GameType
import app.heartpact.data.TicTacToeStroke
import app.heartpact.ui.games.GameActions
import app.heartpact.ui.games.GamesScreen
import app.heartpact.ui.games.GamesUi
import app.heartpact.ui.games.LiveInk
import app.heartpact.ui.games.PendingMark
import app.heartpact.ui.games.TicTacToeInk
import app.heartpact.ui.games.TicTacToeUi
import app.heartpact.ui.games.TooSlow
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Tic-tac-toe at once: Maya (X, holding the phone) against Theo (O). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class TicTacToeShotsTest {
    private val now = Instant.parse("2026-10-11T18:00:00Z")
    private val nowMs = now.toEpochMilli()

    /** One letter per square, row by row: m for Maya, t for Theo, a dot for empty. */
    private fun cells(board: String) = board.map { if (it == 'm') "\"maya\"" else if (it == 't') "\"theo\"" else "null" }.joinToString(",", "[", "]")

    /** Hand-drawn marks for the squares: Maya's a scrawled X, Theo's a loop, each a little different. */
    private fun marks(board: String) = board.mapIndexed { i, c ->
        when (c) {
            'm' -> markJson(listOf(scrawl(0.2f + i * 0.01f, 0.22f, 0.8f, 0.78f - i * 0.01f), scrawl(0.78f, 0.2f, 0.24f + i * 0.01f, 0.82f)))
            't' -> markJson(listOf(loop(i)))
            else -> "null"
        }
    }.joinToString(",", "[", "]")

    private fun scrawl(x0: Float, y0: Float, x1: Float, y1: Float) = (0..8).map { k ->
        val t = k / 8f
        Offset(x0 + (x1 - x0) * t + sin(t * 3f) * 0.02f, y0 + (y1 - y0) * t)
    }

    private fun loop(seed: Int) = (0..22).map { k ->
        val a = -PI / 2 - k / 22.0 * 2.1 * PI
        val r = 0.3f + 0.02f * sin(k * 0.8f + seed)
        Offset((0.5 + r * cos(a) * 1.08).toFloat().coerceIn(0f, 1f), (0.5 + r * sin(a)).toFloat().coerceIn(0f, 1f))
    }

    /** A mark as the wire has it: `{"strokes":[{"points":[[x,y],...]},...]}`. */
    private fun markJson(strokes: List<List<Offset>>) =
        strokes.joinToString(",", """{"strokes":[""", "]}") { """{"points":${Json.encodeToString(TicTacToeInk.stroke(it).points)}}""" }

    private fun state(board: String, game: Int, phase: String, results: String, maya: Int, theo: Int, line: String = "null", next: String? = null) =
        """{"players":["maya","theo"],"columns":3,"rows":3,"cells":${cells(board)},"marks":${marks(board)},"game":$game,"totalGames":5,"winsNeeded":3,"phase":"$phase","line":$line,"scores":{"maya":$maya,"theo":$theo},"results":$results,"cooldownMs":650${next?.let { ",\"nextGameAtUtc\":\"$it\"" } ?: ""}}"""

    private fun session(state: String, status: GameStatus = GameStatus.InProgress) =
        GameSession("g", "c", GameType.TicTacToe, status, currentTurnUserId = "maya".takeIf { status == GameStatus.InProgress }, stateVersion = 9, stateJson = state, invitedByUserId = "theo")

    @Composable private fun screen(s: GameSession, ttt: TicTacToeUi = TicTacToeUi(), ink: LiveInk = LiveInk()) =
        GamesScreen(GamesUi(listOf(s), ttt = ttt, ink = ink), "maya", "Theo", GameActions(), { now })

    /** Mid-game: Maya's newest X on its way and her pen half refilled, Theo drawing his O live in the corner. */
    @Test fun midGame() {
        val s = session(state("m.t.m..t.", 1, "playing", """["maya"]""", 1, 0))
        val ttt = TicTacToeUi(
            pending = listOf(PendingMark("g", 1, 6, listOf(TicTacToeStroke(listOf(listOf(0.2, 0.2), listOf(0.5, 0.52), listOf(0.8, 0.8))), TicTacToeStroke(listOf(listOf(0.8, 0.22), listOf(0.2, 0.78)))), 1)),
            penFromMs = nowMs - 330, penReadyAtMs = nowMs + 320,
        )
        val ink = LiveInk().apply {
            receive("g", 8, 1, TicTacToeInk.inkNumbers(loop(8).take(13)).toList(), nowMs)
        }
        shot("games-tictactoe") { screen(s, ttt, ink) }
        shot("games-tictactoe-night", night = true) { screen(s, ttt, ink) }
    }

    /** Theo got to the middle square first: Maya's mark has bounced off it. */
    @Test fun tooSlow() = shot("games-tictactoe-tooslow") {
        screen(session(state("m...t....", 0, "playing", "[]", 0, 0)), TicTacToeUi(tooSlow = TooSlow("g", 0, 4, 7, taken = true), penReadyAtMs = nowMs))
    }

    /** The third in a row lands and the pen strikes through it; the next game is a beat away. */
    @Test fun win() = shot("games-tictactoe-win") {
        screen(session(state("mtt.m...m", 2, "gameOver", """["maya","theo","maya"]""", 2, 1, "[0,4,8]", next = "2026-10-11T18:00:01Z")))
    }

    @Test fun draw() = shot("games-tictactoe-draw") {
        screen(session(state("tmttmmmtt", 1, "gameOver", """["maya",null]""", 1, 0, next = "2026-10-11T18:00:01Z")))
    }

    /** Once the beat is over the fresh board shows, even before the server's update arrives. */
    @Test fun nextGame() = shot("games-tictactoe-next") {
        screen(session(state("mtt.m...m", 2, "gameOver", """["maya","theo","maya"]""", 2, 1, "[0,4,8]", next = "2026-10-11T17:59:59Z")))
    }

    /**
     * A match finished before the game went live (turn by turn, no marks): its final board still reads, each square
     * with a pen mark.
     */
    @Test fun oldMatchOver() = shot("games-tictactoe-match", night = true) {
        screen(session("""{"players":["maya","theo"],"columns":3,"rows":3,"cells":${cells("ttt.mm.m.")},"game":3,"totalGames":5,"winsNeeded":3,"phase":"matchOver","turnIndex":0,"line":[0,1,2],"scores":{"maya":1,"theo":3},"results":["maya","theo","theo","theo"],"winnerUserId":"theo"}""",
            GameStatus.Completed).copy(endedAtUtc = "2026-10-11T17:58:00Z"))
    }
}
