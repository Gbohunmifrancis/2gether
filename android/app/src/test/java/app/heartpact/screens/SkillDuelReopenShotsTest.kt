package app.heartpact.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import app.heartpact.data.GameSession
import app.heartpact.data.GameStatus
import app.heartpact.data.GameType
import app.heartpact.ui.games.GameActions
import app.heartpact.ui.games.GamesScreen
import app.heartpact.ui.games.GamesUi
import app.heartpact.ui.graphics.LocalStillLogo
import app.heartpact.ui.play.skills.Difficulty
import app.heartpact.ui.play.skills.FairSharePlay
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.HeartpactTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/**
 * Heart to heart (docs/games.md): a round opens once the server has counted it (`open`), on the seed the server sends
 * for me; backing out and opening it again shows the round the server dealt anew, not the one seen before.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class SkillDuelReopenShotsTest {
    @get:Rule val rule = createComposeRule()

    private val now = Instant.parse("2026-10-26T18:00:00Z")
    private val dealt = 1000L
    /** The seed dealt on reopening: one whose treat is another kind, so the two rounds look different. */
    private val again = (dealt + 1..dealt + 400).first { FairSharePlay(it, Difficulty.Start).treat.kind != FairSharePlay(dealt, Difficulty.Start).treat.kind }

    private fun state(seed: Long, opens: Int) = """{"players":["maya","theo"],"phase":"playing","totalRounds":5,"rounds":[""" +
        """{"index":0,"kind":"FairShare","seed":$seed,"opens":$opens,"mine":null,"partner":null,"played":true,"winner":null,"tie":false},""" +
        listOf("Heartbeat", "PromiseRing", "OtherHalf", "KissTheCenter").mapIndexed { i, kind ->
            """{"index":${i + 1},"kind":"$kind","seed":${2000 + i},"opens":0,"mine":null,"partner":null,"played":false,"winner":null,"tie":false}"""
        }.joinToString(",") + """],"hearts":{"maya":0,"theo":0},"totals":null,"winner":null,"tie":false,"margin":null}"""

    @Test fun backingOutAndOpeningAgainShowsTheRoundDealtAnew() {
        var session by mutableStateOf(GameSession("r1", "c", GameType.SkillDuel, GameStatus.InProgress, currentTurnUserId = "maya", stateVersion = 6,
            stateJson = state(dealt, 0), invitedByUserId = "theo"))
        val sent = mutableListOf<String>()
        // The server, as far as this board needs it: an open counts, and a second open deals the round again.
        val actions = GameActions(onAct = { s, action, value, index ->
            sent += "$action:$index:${value.orEmpty()}"
            if (action == "open") {
                val opens = sent.count { it.startsWith("open:0") }
                session = s.copy(stateVersion = s.stateVersion + 1, stateJson = state(if (opens > 1) again else dealt, opens))
            }
        })
        rule.setContent {
            CompositionLocalProvider(LocalStillLogo provides true) {
                HeartpactTheme {
                    Box(Modifier.fillMaxSize().background(Heartpact.colors.page)) {
                        GamesScreen(GamesUi(listOf(session)), "maya", "Theo", actions, { now })
                    }
                }
            }
        }
        val card = hasContentDescription("Round 1: Fair share", substring = true)

        rule.onNode(card).performClick()
        rule.waitForIdle()
        assertEquals(listOf("open:0:"), sent)
        rule.onAllNodes(isRoot())[1].captureRoboImage("build/screens/games-duel-round-dealt.png")

        // Back out before scoring: nothing is sent, and the round is still there to play.
        rule.onAllNodesWithContentDescription("Back").onLast().performClick()
        rule.waitForIdle()
        assertEquals(1, sent.size)

        rule.onNode(card).performClick()
        rule.waitForIdle()
        assertEquals(listOf("open:0:", "open:0:"), sent)
        assertNotEquals(dealt, again)
        rule.onAllNodes(isRoot())[1].captureRoboImage("build/screens/games-duel-round-dealt-again.png")
    }
}
