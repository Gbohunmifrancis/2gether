package app.heartpact.screens

import app.heartpact.data.GameSession
import app.heartpact.data.GameStatus
import app.heartpact.data.GameType
import app.heartpact.ui.games.GameActions
import app.heartpact.ui.games.GamesScreen
import app.heartpact.ui.games.GamesUi
import app.heartpact.ui.games.LiveSketch
import app.heartpact.ui.games.TicTacToeInk
import androidx.compose.ui.geometry.Offset
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class DrawAndGuessShotsTest {
    private val now = Instant.parse("2026-10-11T18:00:00Z")
    private fun session(state: String, turn: String?, status: GameStatus = GameStatus.InProgress) =
        GameSession("g", "c", GameType.Handwriting, status, currentTurnUserId = turn, stateVersion = 12, stateJson = state, invitedByUserId = "theo")

    private fun stroke(points: List<Pair<Double, Double>>, width: Double = 0.012) =
        """{"points":[${points.joinToString(",") { (x, y) -> String.format(Locale.ROOT, "[%.3f,%.3f]", x, y) }}],"width":$width}"""

    private fun arc(cx: Double, cy: Double, r: Double, from: Double, to: Double, steps: Int = 24) =
        (0..steps).map { i -> val a = from + (to - from) * i / steps; cx + r * cos(a) to cy + r * sin(a) }

    /** An umbrella: canopy, scallops, shaft and hook. */
    private val umbrella = listOf(
        stroke(arc(0.5, 0.5, 0.32, PI, 2 * PI)),
        stroke(arc(0.26, 0.5, 0.08, PI, 0.0, 10) + arc(0.42, 0.5, 0.08, PI, 0.0, 10) + arc(0.58, 0.5, 0.08, PI, 0.0, 10) + arc(0.74, 0.5, 0.08, PI, 0.0, 10)),
        stroke(listOf(0.5 to 0.18, 0.5 to 0.5, 0.5 to 0.8) + arc(0.44, 0.8, 0.06, 0.0, PI, 10)),
    ).joinToString(",")

    /** A cat's face: head, ears, eyes and whiskers. */
    private val cat = listOf(
        stroke(arc(0.5, 0.56, 0.26, 0.0, 2 * PI, 36)),
        stroke(listOf(0.31 to 0.4, 0.3 to 0.18, 0.45 to 0.31)),
        stroke(listOf(0.55 to 0.31, 0.7 to 0.18, 0.69 to 0.4)),
        stroke(listOf(0.41 to 0.5), 0.03), stroke(listOf(0.59 to 0.5), 0.03),
        stroke(listOf(0.2 to 0.6, 0.42 to 0.63)), stroke(listOf(0.58 to 0.63, 0.8 to 0.6)),
    ).joinToString(",")

    private val pizza = listOf(
        stroke(listOf(0.2 to 0.25, 0.8 to 0.25, 0.5 to 0.85, 0.2 to 0.25)),
        stroke(arc(0.4, 0.38, 0.05, 0.0, 2 * PI, 16)), stroke(arc(0.6, 0.4, 0.05, 0.0, 2 * PI, 16)), stroke(arc(0.5, 0.58, 0.05, 0.0, 2 * PI, 16)),
    ).joinToString(",")

    private val drawer = session("""{"players":["maya","theo"],"scores":{"maya":1,"theo":1},"round":2,"totalRounds":6,"phase":"drawing","drawer":"maya","guesser":"theo",
        "word":"umbrella","strokes":[$umbrella],"wrongGuesses":["kite"],"guessesLeft":2,"maxGuesses":3,
        "lastRound":{"round":1,"drawer":"theo","word":"guitar","outcome":"guessed","guess":"guitar","strokes":[],"wrongGuesses":[]}}""", "maya")

    private val guesser = session("""{"players":["maya","theo"],"scores":{"maya":1,"theo":1},"round":1,"totalRounds":6,"phase":"drawing","drawer":"theo","guesser":"maya",
        "word":null,"strokes":[$cat],"wrongGuesses":["fox"],"guessesLeft":2,"maxGuesses":3,
        "lastRound":{"round":0,"drawer":"maya","word":"rocket","outcome":"guessed","guess":"rocket","strokes":[],"wrongGuesses":[]}}""", "theo")

    private val reveal = session("""{"players":["maya","theo"],"scores":{"maya":2,"theo":2},"round":3,"totalRounds":6,"phase":"drawing","drawer":"theo","guesser":"maya",
        "word":null,"strokes":[],"wrongGuesses":[],"guessesLeft":3,"maxGuesses":3,
        "lastRound":{"round":2,"drawer":"maya","word":"pizza","outcome":"missed","guess":null,"strokes":[$pizza],"wrongGuesses":["cheese","triangle","sandwich"]}}""", "theo")

    @Test fun drawer() {
        shot("games-draw") { GamesScreen(GamesUi(listOf(drawer), loaded = true), "maya", "Theo", GameActions(), { now }) }
        shot("games-draw-night", night = true) { GamesScreen(GamesUi(listOf(drawer), loaded = true), "maya", "Theo", GameActions(), { now }) }
    }

    @Test fun guesser() {
        shot("games-draw-guess") { GamesScreen(GamesUi(listOf(guesser), loaded = true), "maya", "Theo", GameActions(), { now }) }
        shot("games-draw-guess-night", night = true) { GamesScreen(GamesUi(listOf(guesser), loaded = true), "maya", "Theo", GameActions(), { now }) }
    }

    /** Theo is drawing: the drawing so far, and the stroke under his finger as it reaches the guesser, message by message. */
    private fun watching(strokes: String, live: List<Pair<Double, Double>>, place: Int): Pair<GameSession, LiveSketch> {
        val s = session("""{"players":["maya","theo"],"scores":{"maya":1,"theo":1},"round":1,"totalRounds":6,"phase":"drawing","drawer":"theo","guesser":"maya",
            "word":null,"strokes":[$strokes],"wrongGuesses":[],"guessesLeft":3,"maxGuesses":3,
            "lastRound":{"round":0,"drawer":"maya","word":"rocket","outcome":"guessed","guess":"rocket","strokes":[],"wrongGuesses":[]}}""", "theo")
        val at = now.toEpochMilli()
        val sketch = LiveSketch(clock = { at })
        live.map { (x, y) -> Offset(x.toFloat(), y.toFloat()) }.chunked(6).forEachIndexed { i, chunk ->
            sketch.receive("g", "theo", place, 77, TicTacToeInk.inkNumbers(chunk).toList(), at - 50L * (live.size / 6 - i))
        }
        return s to sketch
    }

    @Test fun watchingLive() {
        // The cat so far, a whisker on its way.
        val catSoFar = cat.split("},").take(5).joinToString("},") + "}"
        val (catGame, catInk) = watching(catSoFar, listOf(0.2 to 0.6, 0.26 to 0.605, 0.32 to 0.612, 0.37 to 0.62), place = 5)
        shot("games-draw-live") { GamesScreen(GamesUi(listOf(catGame), loaded = true, sketch = catInk), "maya", "Theo", GameActions(), { now }) }
        // The first stroke, half drawn on an empty canvas: no "hasn't started yet" over it.
        val (first, firstInk) = watching("", arc(0.5, 0.52, 0.3, PI * 0.9, PI * 2.1, 30), place = 0)
        shot("games-draw-live-night", night = true) { GamesScreen(GamesUi(listOf(first), loaded = true, sketch = firstInk), "maya", "Theo", GameActions(), { now }) }
    }

    @Test fun revealed() {
        shot("games-draw-reveal") { GamesScreen(GamesUi(listOf(reveal), loaded = true), "maya", "Theo", GameActions(), { now }) }
        shot("games-draw-reveal-night", night = true) { GamesScreen(GamesUi(listOf(reveal), loaded = true), "maya", "Theo", GameActions(), { now }) }
    }
}
