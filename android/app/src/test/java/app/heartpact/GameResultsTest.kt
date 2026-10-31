package app.heartpact

import app.heartpact.data.GameSession
import app.heartpact.data.GameStatus
import app.heartpact.data.GameType
import app.heartpact.ui.games.GameEndedEvent
import app.heartpact.ui.games.GamePlay
import app.heartpact.ui.games.GamesUi
import app.heartpact.ui.games.endedNote
import app.heartpact.ui.games.GameScore
import app.heartpact.ui.games.Turn
import app.heartpact.ui.games.coupleGames
import app.heartpact.ui.games.lastLine
import app.heartpact.ui.games.score
import app.heartpact.ui.games.stamp
import app.heartpact.ui.games.waitingOn
import app.heartpact.ui.games.won
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private val duelDone = """{"players":["maya","theo"],"phase":"completed","rounds":[],"hearts":{"maya":3,"theo":2},"totals":{"maya":300,"theo":280},"winner":"maya","decidedBy":"hearts","margin":1,"pointMargin":20}"""

    @Test fun `the hub says how a duel went, not just its name`() {
        val duel = done(GameType.SkillDuel, duelDone)
        assertEquals("You won Heart to heart by 1 heart", GamePlay.lastLine(duel, "maya", "Theo"))
        assertEquals("Maya won Heart to heart by 1 heart", GamePlay.lastLine(duel, "theo", "Maya"))
        val onPoints = done(GameType.SkillDuel, duelDone.replace("\"decidedBy\":\"hearts\"", "\"decidedBy\":\"points\""))
        assertEquals("You won Heart to heart on points", GamePlay.lastLine(onPoints, "maya", "Theo"))
        assertEquals(true, GamePlay.won(duel, "maya"))
        assertEquals(false, GamePlay.won(duel, "theo"))
        // A game the app doesn't know never claims a score it can't read.
        assertEquals("Played", GamePlay.outcome(done(GameType.Unknown, "{}"), "maya", "Theo"))
        assertNull(GamePlay.won(done(GameType.MemoryMatch, """{"scores":{"maya":3,"theo":3}}"""), "maya"))
    }

    @Test fun `a game ended without you says who ended it and how`() {
        val invite = GameSession("g", "c", GameType.MemoryMatch, GameStatus.Abandoned, invitedByUserId = "maya")
        val playing = invite.copy(inviteAcceptedAtUtc = "2026-10-10T20:00:00Z", startedAtUtc = "2026-10-10T20:00:05Z")
        assertEquals("Maya took back the invite.", endedNote(GameEndedEvent("g", "maya", "Maya"), invite, "theo"))
        assertEquals("Theo can't play right now.", endedNote(GameEndedEvent("g", "theo", "Theo"), invite, "maya"))
        assertEquals("Theo ended the game.", endedNote(GameEndedEvent("g", "theo", "Theo"), playing, "maya"))
        assertEquals("That game sat untouched for too long, so it ended.", endedNote(GameEndedEvent("g", "00000000-0000-0000-0000-000000000000", "Heartpact"), playing, "maya"))
        // Whoever ended it already knows.
        assertNull(endedNote(GameEndedEvent("g", "maya", "Maya"), playing, "maya"))
    }

    @Test fun `a refresh never rolls a board back to an older version`() {
        val newer = GameSession("g", "c", GameType.MemoryMatch, GameStatus.InProgress, stateVersion = 7)
        val ui = GamesUi(listOf(newer))
        val fetched = ui.withList(listOf(newer.copy(stateVersion = 6), GameSession("h", "c", GameType.Trivia, GameStatus.Completed, stateVersion = 3)))
        assertEquals(listOf(7L, 3L), fetched.sessions.map { it.stateVersion })
        assertEquals(8L, ui.withList(listOf(newer.copy(stateVersion = 8))).sessions.single().stateVersion)
        assertFalse(fetched.sessions.isEmpty())
    }
}
