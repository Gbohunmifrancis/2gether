package app.heartpact

import app.heartpact.data.GameSession
import app.heartpact.data.GameStatus
import app.heartpact.data.GameType
import app.heartpact.ui.games.GamePlay
import app.heartpact.ui.games.GamesUi
import app.heartpact.ui.games.resendable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Same answer, Would you rather and Trivia: picks that cross the partner's, and a refresh that never rolls a board back. */
class CoupleGamesTest {
    private fun session(type: GameType, state: String, version: Long = 4, status: GameStatus = GameStatus.InProgress) =
        GameSession("g", "c", type, status, stateVersion = version, stateJson = state)

    @Test fun `a pick that crossed the partner's goes again while the round is still open`() {
        val before = session(GameType.WouldYouRather, """{"round":3,"picked":[],"myPick":null}""")
        // Theo's pick landed first: same round, mine isn't in.
        assertTrue(GamePlay.resendable(before, session(GameType.WouldYouRather, """{"round":3,"picked":["theo"],"myPick":null}""", 5)))
        // A double tap: my first pick already landed.
        assertFalse(GamePlay.resendable(before, session(GameType.WouldYouRather, """{"round":3,"picked":["maya"],"myPick":1}""", 5)))
        // The round moved on: the pick was for a question that's gone.
        assertFalse(GamePlay.resendable(before, session(GameType.WouldYouRather, """{"round":4,"picked":[],"myPick":null}""", 6)))
        // The game ended meanwhile.
        assertFalse(GamePlay.resendable(before, session(GameType.WouldYouRather, """{"round":3,"picked":["theo"],"myPick":null}""", 5, GameStatus.Abandoned)))
    }

    @Test fun `trivia and same answer resend on the same terms`() {
        val trivia = session(GameType.Trivia, """{"round":2,"answered":[],"myAnswer":null}""")
        assertTrue(GamePlay.resendable(trivia, session(GameType.Trivia, """{"round":2,"answered":["theo"],"myAnswer":null}""", 5)))
        assertFalse(GamePlay.resendable(trivia, session(GameType.Trivia, """{"round":2,"answered":["maya"],"myAnswer":0}""", 5)))
        assertFalse(GamePlay.resendable(trivia, session(GameType.Trivia, """{"round":3,"answered":[],"myAnswer":null}""", 6)))

        val quiz = session(GameType.CoupleQuiz, """{"round":0,"answered":[],"mine":null}""")
        assertTrue(GamePlay.resendable(quiz, session(GameType.CoupleQuiz, """{"round":0,"answered":["theo"],"mine":null}""", 5)))
        assertFalse(GamePlay.resendable(quiz, session(GameType.CoupleQuiz, """{"round":0,"answered":["maya"],"mine":"Paris"}""", 5)))
        assertFalse(GamePlay.resendable(quiz, session(GameType.CoupleQuiz, """{"round":1,"answered":[],"mine":null}""", 6)))
    }

    @Test fun `turn by turn games never resend on their own`() {
        val guess = session(GameType.GuessMyAnswer, """{"round":1}""")
        assertFalse(GamePlay.resendable(guess, session(GameType.GuessMyAnswer, """{"round":1}""", 5)))
    }

    @Test fun `a refresh keeps a board the hub already moved further along`() {
        val ahead = session(GameType.WouldYouRather, """{"round":4}""", 9)
        val ui = GamesUi(listOf(ahead))
        val fetched = listOf(session(GameType.WouldYouRather, """{"round":3}""", 7), GameSession("old", "c", GameType.Trivia, GameStatus.Completed, stateVersion = 3))
        val merged = ui.newest(fetched)
        assertEquals(listOf(9L, 3L), merged.map { it.stateVersion })
        assertEquals(listOf("g", "old"), merged.map { it.id })
        // A newer one from the server replaces the board on screen.
        assertEquals(10L, ui.newest(listOf(session(GameType.WouldYouRather, "{}", 10))).single().stateVersion)
    }
}
