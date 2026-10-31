package app.heartpact.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import app.heartpact.ui.games.DieMath
import app.heartpact.ui.games.drawDie
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.click
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
 * Snakes and ladders: still shots of the board (start, mid-game, night, the end, the hub) and frames from the middle
 * of a move, with the clock held by hand: a ladder climb, a snake slide, a swap, and the die mid-tumble.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h1000dp-xxhdpi")
class SnakesAndLaddersShotsTest {
    @get:Rule val compose = createComposeRule()
    private val now = Instant.parse("2026-10-31T18:00:00Z")

    /** A board the server's Layout(2026) draws. */
    private val board = """{"ladders":[{"from":3,"to":17},{"from":19,"to":40},{"from":7,"to":48},{"from":51,"to":71},{"from":64,"to":84},{"from":25,"to":67},{"from":38,"to":79}],
        "snakes":[{"from":47,"to":34},{"from":39,"to":16},{"from":81,"to":37},{"from":53,"to":28},{"from":49,"to":10},{"from":93,"to":66},{"from":89,"to":70}],
        "hearts":[{"square":30,"kind":"swap"},{"square":52,"kind":"swap"},{"square":57,"kind":"boost"},{"square":8,"kind":"boost"},{"square":27,"kind":"shield"},{"square":76,"kind":"shield"},{"square":26,"kind":"heartbreak"}]}"""

    private fun state(maya: Int, theo: Int, turn: String?, last: String = "null", moves: Int = 0, mayaShield: Boolean = false, phase: String = "playing", winner: String? = null,
                      stats: String = """{"maya":{"rolls":14,"ladders":3,"snakes":2,"sixes":2,"swaps":1,"saves":1},"theo":{"rolls":13,"ladders":2,"snakes":3,"sixes":1,"swaps":0,"saves":0}}""") =
        """{"players":["maya","theo"],"phase":"$phase","goal":100,"first":"maya","turn":${turn?.let { "\"$it\"" } ?: "null"},"board":$board,
            "positions":{"maya":$maya,"theo":$theo},"shields":{"maya":$mayaShield,"theo":false},"stats":$stats,
            "sixes":0,"moves":$moves,"last":$last,"winner":${winner?.let { "\"$it\"" } ?: "null"}}"""

    private fun last(n: Int, player: String, roll: Int, from: Int, steps: List<Int>, events: String, to: Int, extra: Boolean = false) =
        """{"move":$n,"player":"$player","roll":$roll,"from":$from,"steps":${steps.joinToString(",", "[", "]")},"events":[$events],"to":$to,"extraTurn":$extra,"sixes":${if (roll == 6) 1 else 0}}"""

    private fun session(json: String, turn: String? = "maya", status: GameStatus = GameStatus.InProgress, version: Long = 5) =
        GameSession("g", "c", GameType.SnakesAndLadders, status, currentTurnUserId = turn, stateVersion = version, stateJson = json, invitedByUserId = "theo",
            endedAtUtc = if (status == GameStatus.Completed) "2026-10-31T17:59:00Z" else null)

    /** Opens on [start], settles, moves to [then], and takes a frame at each of [at] ms after. */
    private fun frames(name: String, start: GameSession, then: GameSession?, at: List<Long>, night: Boolean = false, before: () -> Unit = {}) {
        var session by mutableStateOf(start)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            HeartpactTheme(night = night) {
                Box(Modifier.fillMaxSize().background(Heartpact.colors.page)) {
                    GamesScreen(GamesUi(listOf(session), loaded = true), "maya", "Theo", GameActions(), { now })
                }
            }
        }
        compose.mainClock.advanceTimeBy(1500)
        before()
        if (then != null) session = then
        var t = 0L
        at.forEach { ms ->
            compose.mainClock.advanceTimeBy(ms - t)
            t = ms
            compose.onRoot().captureRoboImage("build/screens/motion/$name-${ms}ms.png")
        }
    }

    /** Theo rolls a 3 from 16: 17, 18, 19, up the ladder to 40. Watched live on Maya's phone. */
    @Test fun ladderClimb() {
        val before = session(state(12, 16, "theo", last(6, "maya", 2, 10, listOf(11, 12), "", 12), moves = 6), turn = "theo")
        val after = session(state(12, 40, "maya", last(7, "theo", 3, 16, listOf(17, 18, 19), """{"kind":"ladder","from":19,"to":40}""", 40), moves = 7), turn = "maya", version = 6)
        frames("snakes-ladder", before, after, listOf(400, 1250, 1900, 2200, 2500))
    }

    /** Maya rolls a 3 from 46: 47, 48, 49, and the snake gulps her down to 10. */
    @Test fun snakeSlide() {
        val before = session(state(46, 70, "maya", last(10, "theo", 1, 69, listOf(70), "", 70), moves = 10))
        val after = session(state(10, 70, "theo", last(11, "maya", 3, 46, listOf(47, 48, 49), """{"kind":"snake","from":49,"to":10}""", 10), moves = 11), turn = "theo", version = 6)
        frames("snakes-slide", before, after, listOf(1900, 2300, 2700, 3100))
    }

    /** Theo lands on a kiss & swap on 30 while Maya is ahead on 77: they fly past each other. */
    @Test fun swap() {
        val before = session(state(77, 28, "theo", last(14, "maya", 4, 73, listOf(74, 75, 76, 77), "", 77), moves = 14), turn = "theo")
        val after = session(state(30, 77, "maya", last(15, "theo", 2, 28, listOf(29, 30), """{"kind":"swap","from":30,"to":77}""", 77), moves = 15), turn = "maya", version = 6)
        frames("snakes-swap", before, after, listOf(2350, 2650, 2900))
    }

    /** The die mid-tumble, the moment after Maya taps it (the server hasn't answered yet). */
    @Test fun dieTumble() {
        val s = session(state(31, 42, "maya", last(8, "theo", 2, 40, listOf(41, 42), "", 42), moves = 8))
        frames("snakes-die", s, null, listOf(90, 240)) {
            compose.onNodeWithContentDescription("Roll the die").performTouchInput { click() }
        }
    }

    /** The same climb at night. */
    @Test fun ladderClimbNight() {
        val before = session(state(12, 16, "theo", last(6, "maya", 2, 10, listOf(11, 12), "", 12), moves = 6), turn = "theo")
        val after = session(state(12, 40, "maya", last(7, "theo", 3, 16, listOf(17, 18, 19), """{"kind":"ladder","from":19,"to":40}""", 40), moves = 7), turn = "maya", version = 6)
        frames("snakes-ladder-night", before, after, listOf(1900), night = true)
    }
}

/** Still shots of the board, held still as the screenshot helper does. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h1000dp-xxhdpi")
class SnakesAndLaddersStillShotsTest {
    private val now = Instant.parse("2026-10-31T18:00:00Z")

    /** A board the server's Layout(2026) draws. */
    private val board = """{"ladders":[{"from":3,"to":17},{"from":19,"to":40},{"from":7,"to":48},{"from":51,"to":71},{"from":64,"to":84},{"from":25,"to":67},{"from":38,"to":79}],
        "snakes":[{"from":47,"to":34},{"from":39,"to":16},{"from":81,"to":37},{"from":53,"to":28},{"from":49,"to":10},{"from":93,"to":66},{"from":89,"to":70}],
        "hearts":[{"square":30,"kind":"swap"},{"square":52,"kind":"swap"},{"square":57,"kind":"boost"},{"square":8,"kind":"boost"},{"square":27,"kind":"shield"},{"square":76,"kind":"shield"},{"square":26,"kind":"heartbreak"}]}"""

    private fun state(maya: Int, theo: Int, turn: String?, last: String = "null", moves: Int = 0, mayaShield: Boolean = false, phase: String = "playing", winner: String? = null,
                      stats: String = """{"maya":{"rolls":14,"ladders":3,"snakes":2,"sixes":2,"swaps":1,"saves":1},"theo":{"rolls":13,"ladders":2,"snakes":3,"sixes":1,"swaps":0,"saves":0}}""") =
        """{"players":["maya","theo"],"phase":"$phase","goal":100,"first":"maya","turn":${turn?.let { "\"$it\"" } ?: "null"},"board":$board,
            "positions":{"maya":$maya,"theo":$theo},"shields":{"maya":$mayaShield,"theo":false},"stats":$stats,
            "sixes":0,"moves":$moves,"last":$last,"winner":${winner?.let { "\"$it\"" } ?: "null"}}"""

    private fun last(n: Int, player: String, roll: Int, from: Int, steps: List<Int>, events: String, to: Int, extra: Boolean = false) =
        """{"move":$n,"player":"$player","roll":$roll,"from":$from,"steps":${steps.joinToString(",", "[", "]")},"events":[$events],"to":$to,"extraTurn":$extra,"sixes":${if (roll == 6) 1 else 0}}"""

    private fun session(json: String, turn: String? = "maya", status: GameStatus = GameStatus.InProgress, version: Long = 5) =
        GameSession("g", "c", GameType.SnakesAndLadders, status, currentTurnUserId = turn, stateVersion = version, stateJson = json, invitedByUserId = "theo",
            endedAtUtc = if (status == GameStatus.Completed) "2026-10-31T17:59:00Z" else null)

    private fun still(name: String, s: GameSession, night: Boolean = false) = shot(name, night) { GamesScreen(GamesUi(listOf(s), loaded = true), "maya", "Theo", GameActions(), { now }) }

    /** The first roll: both waiting on the start strip, the die glowing. */
    @Test fun start() = still("games-snakes-start", session(state(0, 0, "maya")))

    /** Mid-game: Maya on 34 with a hug shield, Theo on 58, Theo's roll. */
    @Test fun midGame() {
        val s = session(state(34, 58, "theo", last(20, "maya", 3, 31, listOf(32, 33, 34), "", 34), moves = 20, mayaShield = true), turn = "theo")
        still("games-snakes-mid", s)
        still("games-snakes-mid-night", s, night = true)
    }

    /** Sharing a square, the two halves make one heart. */
    @Test fun together() = still("games-snakes-together", session(state(45, 45, "maya", last(9, "theo", 2, 43, listOf(44, 45), "", 45), moves = 9)))

    /** The end: Maya made it to 100, Theo on 87; stamp, both stats. */
    @Test @Config(qualifiers = "w390dp-h1500dp-xxhdpi") fun end() = still("games-snakes-end",
        session(state(100, 87, null, last(31, "maya", 4, 96, listOf(97, 98, 99, 100), """{"kind":"win","from":100,"to":100}""", 100), moves = 31, phase = "completed", winner = "maya"),
            turn = null, status = GameStatus.Completed))

    /** The hub with Snakes and ladders in the grid. */
    @Test @Config(qualifiers = "w390dp-h1300dp-xxhdpi") fun hub() = shot("games-snakes-hub") {
        GamesScreen(GamesUi(loaded = true, region = "global"), "maya", "Theo", GameActions(), { now })
    }


    /** The die at rest on each face, and part way through a tumble: for checking the faces and the 3D. */
    @Test @Config(qualifiers = "w600dp-h300dp-xxhdpi") fun dieFaces() = shot("games-snakes-die-faces") {
        Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            (1..6).forEach { face ->
                val (x, y, z) = DieMath.rest(face)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$face")
                    Canvas(Modifier.size(90.dp)) { drawDie(x, y, z) }
                    Canvas(Modifier.size(90.dp)) { drawDie(x + 0.7f, y + 0.5f, z + 0.3f) }
                }
            }
        }
    }
}
