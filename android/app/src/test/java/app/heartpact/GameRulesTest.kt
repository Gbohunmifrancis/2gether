package app.heartpact

import androidx.test.core.app.ApplicationProvider
import app.heartpact.data.GameStatus
import app.heartpact.data.GameType
import app.heartpact.ui.games.GamePlay
import app.heartpact.ui.games.GameRules
import app.heartpact.ui.games.MemoryRulesSeen
import app.heartpact.ui.games.PrefsRulesSeen
import app.heartpact.ui.play.skills.SoundGate
import app.heartpact.ui.splash.splashVolume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GameRulesTest {
    @Test fun `every game on offer has short rules in four parts`() {
        GamePlay.offered.forEach { type ->
            val rules = GameRules.of(type)
            assertNotNull(type.name, rules)
            listOf(rules!!.goal, rules.turn, rules.win, rules.tip).forEach { part ->
                assertTrue("${type.name}: $part", part.isNotBlank() && part.length <= 200)
            }
        }
        assertNull(GameRules.of(GameType.Unknown))
    }

    @Test fun `the rules say what the server plays`() {
        assertTrue(GameRules.of(GameType.ConnectFour)!!.win.contains("two games"))
        assertTrue(GameRules.of(GameType.TicTacToe)!!.win.contains("three games"))
        assertTrue(GameRules.of(GameType.WordDash)!!.turn.contains("20 seconds"))
        assertTrue(GameRules.of(GameType.WordDash)!!.win.contains("five"))
        assertTrue(GameRules.of(GameType.SpotTheDifference)!!.turn.contains("90 seconds"))
        assertTrue(GameRules.of(GameType.ICallOn)!!.turn.contains("15 seconds"))
        assertTrue(GameRules.of(GameType.SnakesAndLadders)!!.win.contains("100 exactly"))
        assertTrue(GameRules.of(GameType.MemoryMatch)!!.turn.contains("six pairs"))
    }

    @Test fun `they open by themselves only the first time, and never mid-round in a timed game`() {
        val seen = MemoryRulesSeen()
        assertTrue(GameRules.autoShow(GameType.MemoryMatch, GameStatus.Waiting, seen.seen(GameType.MemoryMatch)))
        seen.markSeen(GameType.MemoryMatch)
        assertFalse(GameRules.autoShow(GameType.MemoryMatch, GameStatus.Waiting, seen.seen(GameType.MemoryMatch)))
        // Remembered per game.
        assertTrue(GameRules.autoShow(GameType.ConnectFour, GameStatus.InProgress, seen.seen(GameType.ConnectFour)))
        // A clock is running: the "?" is there, nothing pops up.
        assertFalse(GameRules.autoShow(GameType.SpotTheDifference, GameStatus.InProgress, false))
        assertTrue(GameRules.autoShow(GameType.SpotTheDifference, GameStatus.Waiting, false))
        // A game that's over, or one this app doesn't know.
        assertFalse(GameRules.autoShow(GameType.Trivia, GameStatus.Completed, false))
        assertFalse(GameRules.autoShow(GameType.Unknown, GameStatus.Waiting, false))
    }

    @Test fun `seen rules are kept on the phone per person and per game`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val maya = PrefsRulesSeen(context, "maya")
        assertFalse(maya.seen(GameType.Trivia))
        maya.markSeen(GameType.Trivia)
        assertTrue(PrefsRulesSeen(context, "maya").seen(GameType.Trivia))
        assertFalse(PrefsRulesSeen(context, "maya").seen(GameType.MemoryMatch))
        assertFalse(PrefsRulesSeen(context, "theo").seen(GameType.Trivia))
    }

    @Test fun `the splash plays in full the first time, then at a quarter`() {
        assertEquals(1f, splashVolume(heardBefore = false))
        assertEquals(0.25f, splashVolume(heardBefore = true))
    }

    @Test fun `the cleared sound never stacks`() {
        val gate = SoundGate(2_000)
        assertTrue(gate.allow(10_000))
        assertFalse(gate.allow(10_000))
        assertFalse(gate.allow(11_999))
        assertTrue(gate.allow(12_000))
        assertFalse(gate.allow(12_500))
        // A clock that went back (a fresh boot's elapsed time) never locks it out.
        assertTrue(gate.allow(500))
    }
}
