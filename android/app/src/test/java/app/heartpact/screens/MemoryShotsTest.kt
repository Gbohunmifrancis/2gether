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

/** Memory match with the Naija cards (theme "naija"): jollof, suya, talking drum, danfo, palm tree, mortar and pestle. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class MemoryShotsTest {
    private val now = Instant.parse("2026-10-24T18:00:00Z")
    private val values = listOf(1, 4, 2, 6, 3, 5, 5, 2, 6, 1, 4, 3)

    private fun session(cards: String, status: GameStatus, extra: String = "") = GameSession(
        "g", "c", GameType.MemoryMatch, status, currentTurnUserId = "maya", stateVersion = 9, invitedByUserId = "theo", setupJson = """{"region":"ng"}""",
        stateJson = """{"players":["maya","theo"],"scores":{"maya":3,"theo":2},"cards":[$cards],"theme":"naija"$extra}""",
    )

    @Test fun naija() {
        // Pairs of 1, 2 and 6 matched; one 3 face up; the last miss (4 and 5) still showing.
        val cards = values.mapIndexed { i, v ->
            when {
                v == 1 || v == 2 || v == 6 -> """{"index":$i,"value":$v,"matched":true}"""
                i == 4 -> """{"index":$i,"value":3,"matched":false}"""
                else -> """{"index":$i,"value":null,"matched":false}"""
            }
        }.joinToString(",")
        shot("games-memory-naija") { GamesScreen(GamesUi(listOf(session(cards, GameStatus.InProgress, ""","revealed":[4]"""))), "maya", "Theo", GameActions(), { now }) }
    }

    @Test fun naijaMiss() {
        val cards = values.mapIndexed { i, v -> """{"index":$i,"value":${if (v <= 2) v else null},"matched":${v <= 2}}""" }.joinToString(",")
        shot("games-memory-naija-miss-night", night = true) {
            GamesScreen(GamesUi(listOf(session(cards, GameStatus.InProgress, ""","revealed":[],"lastResult":"different","lastPair":[{"index":1,"value":4},{"index":5,"value":5}]""").copy(currentTurnUserId = "theo"))), "maya", "Theo", GameActions(), { now })
        }
    }

    @Test fun naijaAllFaces() {
        // Everything face up but unmatched, to see all six stickers at full strength.
        val cards = values.mapIndexed { i, v -> """{"index":$i,"value":$v,"matched":false}""" }.joinToString(",")
        shot("games-memory-naija-faces") { GamesScreen(GamesUi(listOf(session(cards, GameStatus.InProgress))), "maya", "Theo", GameActions(), { now }) }
    }
}
