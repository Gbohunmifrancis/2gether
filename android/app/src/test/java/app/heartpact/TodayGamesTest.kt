package app.heartpact

import app.heartpact.data.GameSession
import app.heartpact.data.GameStatus
import app.heartpact.data.GameType
import app.heartpact.data.QuizReveal
import app.heartpact.ui.games.GamePlay
import app.heartpact.ui.games.countStillWanted
import app.heartpact.ui.today.GameWait
import app.heartpact.ui.today.TodayGames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Today's game card (what waits on me) and Same answer's "That counts!" (what can still be counted). */
class TodayGamesTest {
    private fun session(type: GameType, status: GameStatus = GameStatus.InProgress, state: String = "{}", turn: String? = null, invitedBy: String? = "theo", id: String = "g") =
        GameSession(id, "c", type, status, currentTurnUserId = turn, stateJson = state, invitedByUserId = invitedBy)

    private fun waits(vararg sessions: GameSession) = TodayGames.waiting(sessions.toList(), "maya")

    @Test fun `an invite from my partner waits on me, mine waits on them`() {
        assertEquals(GameWait.Invite, waits(session(GameType.ConnectFour, GameStatus.Waiting))?.wait)
        assertNull(waits(session(GameType.ConnectFour, GameStatus.Waiting, invitedBy = "maya")))
        // A game this app can't play isn't offered from Today.
        assertNull(waits(session(GameType.Unknown, GameStatus.Waiting)))
    }

    @Test fun `nothing waits when no game is open or it's paused`() {
        assertNull(waits())
        assertNull(waits(session(GameType.ConnectFour, GameStatus.Completed, turn = "maya")))
        assertNull(waits(session(GameType.ConnectFour, GameStatus.Abandoned)))
        assertNull(waits(session(GameType.ConnectFour, GameStatus.Paused, turn = "maya")))
        // The open game is the one that counts, wherever it sits in the list.
        assertEquals(GameWait.Turn, waits(session(GameType.Trivia, GameStatus.Completed, id = "old"), session(GameType.ConnectFour, turn = "maya"))?.wait)
    }

    @Test fun `games played in turn wait on whoever's turn it is`() {
        for (type in listOf(GameType.GuessMyAnswer, GameType.MemoryMatch, GameType.ConnectFour, GameType.SnakesAndLadders, GameType.TruthOrDare)) {
            val mine = waits(session(type, turn = "maya"))
            assertEquals(type.name, GameWait.Turn, mine?.wait)
            assertTrue(mine!!.partnerWaiting)
            assertNull(type.name, waits(session(type, turn = "theo")))
        }
    }

    @Test fun `games on a clock or played live never show`() {
        for (type in listOf(GameType.ICallOn, GameType.WordDash, GameType.SpotTheDifference, GameType.TicTacToe, GameType.Handwriting))
            assertNull(type.name, waits(session(type, turn = "maya")))
    }

    @Test fun `games played at once wait on me until I've moved this round`() {
        val quiz = { s: String -> waits(session(GameType.CoupleQuiz, state = s)) }
        assertEquals(GameWait.Move, quiz("""{"players":["maya","theo"],"round":2,"totalRounds":10,"answered":[],"mine":null}""")?.wait)
        assertFalse(quiz("""{"players":["maya","theo"],"round":2,"totalRounds":10,"answered":[],"mine":null}""")!!.partnerWaiting)
        assertTrue(quiz("""{"players":["maya","theo"],"round":2,"totalRounds":10,"answered":["theo"],"mine":null}""")!!.partnerWaiting)
        assertNull(quiz("""{"players":["maya","theo"],"round":2,"totalRounds":10,"answered":["maya"],"mine":"Paris"}"""))
        assertNull(quiz("""{"players":["maya","theo"],"round":10,"totalRounds":10,"answered":[]}"""))

        assertEquals(GameWait.Move, waits(session(GameType.WouldYouRather, state = """{"players":["maya","theo"],"round":1,"phase":"picking","picked":["theo"],"myPick":null}"""))?.wait)
        assertNull(waits(session(GameType.WouldYouRather, state = """{"players":["maya","theo"],"round":1,"phase":"picking","picked":["maya"],"myPick":0}""")))

        assertEquals(GameWait.Move, waits(session(GameType.Trivia, state = """{"players":["maya","theo"],"round":1,"question":{"text":"Q","options":["a","b"]},"answered":[],"myAnswer":null}"""))?.wait)
        assertNull(waits(session(GameType.Trivia, state = """{"players":["maya","theo"],"round":1,"question":{"text":"Q","options":["a","b"]},"answered":["maya"],"myAnswer":1}""")))

        val duel = { rounds: String -> waits(session(GameType.SkillDuel, state = """{"players":["maya","theo"],"rounds":[$rounds]}""")) }
        assertTrue(duel("""{"index":0,"mine":80,"played":true},{"index":1,"mine":null,"played":true}""")!!.partnerWaiting)
        assertFalse(duel("""{"index":0,"mine":80,"played":false},{"index":1,"mine":null,"played":false}""")!!.partnerWaiting)
        assertNull(duel("""{"index":0,"mine":80,"played":false},{"index":1,"mine":70,"played":false}"""))
    }

    @Test fun `a revealed round that didn't match can be counted, once`() {
        assertTrue(QuizReveal(answers = mapOf("maya" to "pizza", "theo" to "pepperoni pizza")).countable)
        assertFalse(QuizReveal(answers = mapOf("maya" to "pizza", "theo" to "pizza"), match = true).countable)
        val counted = QuizReveal(answers = mapOf("maya" to "pizza", "theo" to "pepperoni pizza"), match = true, accepted = true, acceptedBy = "theo")
        assertFalse(counted.countable)
        assertFalse(counted.matched)
        // An older server's state has no accepted field: a plain miss.
        val old = GamePlay.quiz(session(GameType.CoupleQuiz, state = """{"last":{"round":0,"answers":{"maya":"a","theo":"b"},"match":false}}""")).last!!
        assertTrue(old.countable)
    }

    @Test fun `a count that crossed a move goes again while the round is still there to count`() {
        val history = """[{"round":0,"answers":{"maya":"a","theo":"a"},"match":true},{"round":1,"answers":{"maya":"pizza","theo":"pepperoni pizza"},"match":false}]"""
        val playing = session(GameType.CoupleQuiz, state = """{"round":2,"history":$history}""")
        assertTrue(GamePlay.countStillWanted(playing, 1))
        assertFalse(GamePlay.countStillWanted(playing, 0))
        assertFalse(GamePlay.countStillWanted(playing, null))
        // The finished game, still on screen, can be counted too; an ended one can't.
        assertTrue(GamePlay.countStillWanted(playing.copy(status = GameStatus.Completed), 1))
        assertFalse(GamePlay.countStillWanted(playing.copy(status = GameStatus.Abandoned), 1))
        // The partner counted it at the same moment: nothing left to do.
        val counted = """[{"round":1,"answers":{"maya":"pizza","theo":"pepperoni pizza"},"match":true,"accepted":true,"acceptedBy":"theo"}]"""
        assertFalse(GamePlay.countStillWanted(session(GameType.CoupleQuiz, state = """{"round":2,"history":$counted}"""), 1))
    }
}
