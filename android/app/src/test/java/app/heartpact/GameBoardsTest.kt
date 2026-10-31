package app.heartpact

import app.heartpact.data.GameSession
import app.heartpact.data.GameStatus
import app.heartpact.data.GameType
import app.heartpact.ui.games.GamesUi
import app.heartpact.ui.games.reconnections
import app.heartpact.ui.games.spotValue
import app.heartpact.ui.games.winRuns
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the boards make of the server's state: winning lines, refreshes, and what a spot says on the wire. */
class GameBoardsTest {
    @Test fun one_winning_line_is_struck_end_to_end() {
        // Tic-tac-toe's diagonal, and a connect four row of five (the middle disc made it).
        assertEquals(listOf(0 to 8), winRuns(setOf(0, 4, 8), 3, 3))
        assertEquals(listOf(36 to 40), winRuns(setOf(36, 37, 38, 39, 40), 7, 6))
        assertEquals(listOf(12 to 30), winRuns(setOf(30, 24, 18, 12), 7, 6))
    }

    @Test fun a_mark_that_makes_two_lines_strikes_both_not_one_from_the_smallest_to_the_largest() {
        // The top row and the right column through square 2: the old pen ran from 0 to 8, through a square nobody won.
        assertEquals(setOf(0 to 2, 2 to 8), winRuns(setOf(0, 1, 2, 5, 8), 3, 3).toSet())
        // The middle row and the middle column through the centre.
        assertEquals(setOf(3 to 5, 1 to 7), winRuns(setOf(1, 3, 4, 5, 7), 3, 3).toSet())
        // Connect four: a row and a diagonal through the same disc (35..38 and 38, 30, 22, 14).
        assertEquals(setOf(35 to 38, 14 to 38), winRuns(setOf(35, 36, 37, 38, 30, 22, 14), 7, 6).toSet())
        // A line never wraps round the edge of the board.
        assertEquals(emptyList<Pair<Int, Int>>(), winRuns(setOf(5, 6, 7), 7, 6))
    }

    @Test fun the_games_reload_each_time_the_hub_comes_back_but_not_when_it_was_up_already() = runTest {
        assertEquals(2, reconnections(flowOf(true, false, true, true, false, true)).toList().size)
        assertEquals(1, reconnections(flowOf(false, true)).toList().size)
        assertEquals(0, reconnections(flowOf(true, false)).toList().size)
    }

    @Test fun a_refresh_never_rolls_a_board_back() {
        fun session(id: String, version: Long, status: GameStatus = GameStatus.InProgress) =
            GameSession(id, "c", GameType.ConnectFour, status, stateVersion = version, stateJson = "{}")
        val ui = GamesUi(listOf(session("a", 7), session("b", 2, GameStatus.Completed)))
        // The partner's move arrived (version 7) while the list was being fetched at version 6.
        val after = ui.withSessions(listOf(session("a", 6), session("b", 2, GameStatus.Completed), session("c", 1)))
        assertEquals(listOf(7L, 2L, 1L), after.sessions.map { it.stateVersion })
        // A newer one from the server replaces what's held.
        assertEquals(9L, ui.withSessions(listOf(session("a", 9))).sessions.single().stateVersion)
    }

    @Test fun a_spot_names_its_picture() {
        assertEquals("""{"scene":2}""", spotValue(2))
    }
}
