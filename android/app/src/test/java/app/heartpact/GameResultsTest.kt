package app.heartpact

import app.heartpact.data.GameSession
import app.heartpact.data.GameStatus
import app.heartpact.data.GameType
import app.heartpact.ui.games.GamePlay
import app.heartpact.ui.games.GameScore
import app.heartpact.ui.games.Turn
import app.heartpact.ui.games.coupleGames
import app.heartpact.ui.games.lastLine
import app.heartpact.ui.games.score
import app.heartpact.ui.games.stamp
import app.heartpact.ui.games.waitingOn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameResultsTest {
    private fun done(type: GameType, state: String, status: GameStatus = GameStatus.Completed) = GameSession("g", "c", type, status, stateJson = state)

    @Test fun `the last game reads from the phone holder's side`() {
        val memory = done(GameType.MemoryMatch, """{"scores":{"maya":4,"theo":2}}""")
        assertEquals("You won Memory match, 4 to 2", GamePlay.lastLine(memory, "maya", "Theo"))
        assertEquals("Maya won Memory match, 4 to 2", GamePlay.lastLine(memory, "theo", "Maya"))
        val draw = done(GameType.TicTacToe, """{"scores":{"maya":2,"theo":2}}""")
        assertEquals("Tic-tac-toe was a draw, 2 each", GamePlay.lastLine(draw, "maya", "Theo"))
        assertEquals("Memory match ended early", GamePlay.lastLine(memory.copy(status = GameStatus.Abandoned), "maya", "Theo"))
    }

    @Test fun `team games give one shared total out of their rounds`() {
        val rather = done(GameType.WouldYouRather, """{"scores":{"maya":7,"theo":7},"totalRounds":10}""")
        assertEquals(GameScore(7, 7, together = true, outOf = 10), GamePlay.score(rather, "maya"))
        assertEquals("Last time, Would you rather: 7 matching picks", GamePlay.lastLine(rather, "maya", "Theo"))
        assertNull(GamePlay.score(done(GameType.SkillDuel, "{}"), "maya"))
    }

    @Test fun `stamps are kind whoever wins`() {
        assertEquals("You won!", GamePlay.stamp(GameScore(4, 2), "Theo"))
        assertEquals("Theo won", GamePlay.stamp(GameScore(2, 4), "Theo"))
        assertEquals("A draw", GamePlay.stamp(GameScore(3, 3), "Theo"))
        assertEquals("In sync!", GamePlay.stamp(GameScore(8, 8, together = true, outOf = 10), "Theo"))
        assertEquals("Well matched!", GamePlay.stamp(GameScore(5, 5, together = true, outOf = 10), "Theo"))
        assertEquals("Good game!", GamePlay.stamp(GameScore(1, 1, together = true, outOf = 10), "Theo"))
    }

    @Test fun `in games played at once, whoever hasn't moved is up`() {
        val players = listOf("maya", "theo")
        assertEquals(Turn.Both, waitingOn(players, "maya", emptyList()))
        assertEquals(Turn.Theirs, waitingOn(players, "maya", listOf("maya")))
        assertEquals(Turn.Mine, waitingOn(players, "maya", listOf("theo")))
        assertEquals(Turn.None, waitingOn(players, "maya", players))
    }

    @Test fun `the hub offers Heart to heart first, and every game once`() {
        assertEquals(GameType.SkillDuel, coupleGames.first())
        assertEquals(coupleGames.size, coupleGames.toSet().size)
        assertTrue(coupleGames.containsAll(GamePlay.offered))
    }
}
