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
class GameShotsTest {
    private val now = Instant.parse("2026-10-11T18:00:00Z")
    private fun session(type: GameType, state: String, status: GameStatus = GameStatus.InProgress, turn: String? = "maya", deadline: String? = null) =
        GameSession("g", "c", type, status, currentTurnUserId = turn, deadlineUtc = deadline, stateVersion = 4, stateJson = state, invitedByUserId = "theo")
    private val done = session(GameType.MemoryMatch, """{"players":["maya","theo"],"scores":{"maya":4,"theo":2},"cards":[]}""", GameStatus.Completed, null)

    @Test fun picker() = shot("games") { GamesScreen(GamesUi(listOf(done), loaded = true), "maya", "Theo", GameActions(), { now }) }

    @Test fun invited() = shot("games-invited") {
        GamesScreen(GamesUi(listOf(session(GameType.ICallOn, "{}", GameStatus.Waiting, null))), "maya", "Theo", GameActions(), { now })
    }

    @Test fun memory() {
        val cards = (0 until 12).joinToString(",") { i ->
            when (i) {
                1, 7 -> """{"index":$i,"value":2,"matched":true}"""
                4 -> """{"index":$i,"value":5,"matched":false}"""
                else -> """{"index":$i,"value":null,"matched":false}"""
            }
        }
        shot("games-memory") {
            GamesScreen(GamesUi(listOf(session(GameType.MemoryMatch, """{"players":["maya","theo"],"scores":{"maya":1,"theo":0},"cards":[$cards],"revealed":[4],"lastPair":null}"""))), "maya", "Theo", GameActions(), { now })
        }
    }

    @Test fun quiz() = shot("games-quiz") {
        GamesScreen(GamesUi(listOf(session(GameType.CoupleQuiz, """{"players":["maya","theo"],"scores":{"maya":3,"theo":3},"round":5,"totalRounds":20,"turnIndex":0,"prompt":"Where would we go on a free Saturday?","answerCount":1,"lastResult":"match"}"""))), "maya", "Theo", GameActions(), { now })
    }

    @Test fun iCallOn() {
        shot("games-icallon") {
            GamesScreen(GamesUi(listOf(session(GameType.ICallOn, """{"players":["maya","theo"],"scores":{"maya":6,"theo":4},"round":1,"totalRounds":3,"letter":"B","categories":["Name","Place","Thing","Food"],"phase":"answering","submitted":["theo"],"pendingVetting":[]}""", deadline = "2026-10-11T18:00:12Z"))), "maya", "Theo", GameActions(), { now })
        }
        shot("games-icallon-vet") {
            GamesScreen(GamesUi(listOf(session(GameType.ICallOn, """{"players":["maya","theo"],"scores":{"maya":0,"theo":0},"round":0,"totalRounds":3,"letter":"B","categories":["Name","Place","Thing","Food"],"phase":"vetting","submitted":["maya","theo"],"pendingVetting":[{"player":"theo","judge":"maya","category":"Thing","answer":"Bumbershoot","allowed":null}]}""", deadline = "2026-10-11T18:00:15Z"))), "maya", "Theo", GameActions(), { now })
        }
    }
}
