package app.heartpact.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.LayoutDirection
import app.heartpact.data.GameSession
import app.heartpact.data.GameStatus
import app.heartpact.data.GameType
import app.heartpact.ui.games.GameActions
import app.heartpact.ui.games.GamesScreen
import app.heartpact.ui.games.GamesUi
import app.heartpact.ui.graphics.LocalStillLogo
import app.heartpact.ui.theme.HeartpactTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * A board is a place, not a sentence: in Arabic (right to left) column 1 and square 1 stay on the left, where the
 * partner's phone, the server's numbering and the pen's win line put them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h1000dp-xxhdpi")
class BoardDirectionTest {
    @get:Rule val compose = createComposeRule()
    private val now = Instant.parse("2026-10-31T18:00:00Z")

    private fun showRightToLeft(s: GameSession) = compose.setContent {
        HeartpactTheme {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl, LocalStillLogo provides true) {
                GamesScreen(GamesUi(listOf(s), loaded = true), "maya", "Theo", GameActions(), { now })
            }
        }
    }

    private fun left(description: String) = compose.onNodeWithContentDescription(description, substring = true, useUnmergedTree = true).getUnclippedBoundsInRoot().left

    @Test fun connect_four_columns_run_left_to_right_in_arabic() {
        val empty = List(42) { "null" }.joinToString(",", "[", "]")
        showRightToLeft(GameSession("g", "c", GameType.ConnectFour, GameStatus.InProgress, currentTurnUserId = "maya", stateVersion = 3,
            stateJson = """{"players":["maya","theo"],"columns":7,"rows":6,"cells":$empty,"game":0,"totalGames":3,"winsNeeded":2,"phase":"playing","scores":{"maya":0,"theo":0},"results":[]}"""))
        assertTrue(left("Column 1") < left("Column 7"))
    }

    @Test fun tic_tac_toe_squares_run_left_to_right_in_arabic() {
        val empty = List(9) { "null" }.joinToString(",", "[", "]")
        showRightToLeft(GameSession("g", "c", GameType.TicTacToe, GameStatus.InProgress, currentTurnUserId = "maya", stateVersion = 3,
            stateJson = """{"players":["maya","theo"],"columns":3,"rows":3,"cells":$empty,"game":0,"totalGames":5,"winsNeeded":3,"phase":"playing","scores":{"maya":0,"theo":0},"results":[],"cooldownMs":650}"""))
        assertTrue(left("Square 1, empty") < left("Square 3, empty"))
    }
}
