package app.heartpact

import app.heartpact.push.PushInbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushInboxTest {
    @Test fun `a push seen before is dropped and the memory stays bounded`() {
        var seen = emptyList<String>()
        seen = PushInbox.accept(seen, "a")!!
        assertNull(PushInbox.accept(seen, "a"))
        repeat(PushInbox.KEEP + 5) { seen = PushInbox.accept(seen, "n$it")!! }
        assertEquals(PushInbox.KEEP, seen.size)
        assertEquals("n${PushInbox.KEEP + 4}", seen.last())
    }

    @Test fun `taps open the tab the news is about`() {
        assertEquals("today", PushInbox.tabFor("exerciseCallOffAsked"))
        assertEquals("album", PushInbox.tabFor("memoriesSynced"))
        assertEquals("album", PushInbox.tabFor("albumAlmostFull"))
        assertEquals("today", PushInbox.tabFor("exercisePausedByPlan"))
        assertEquals("us", PushInbox.tabFor("plusEnding"))
        assertEquals("us", PushInbox.tabFor("dailyQuestionAnswered"))
        // Plus starting, and the partner sharing their location (the map card) or cycle (the cycle row): all on Us.
        listOf("plusStarted", "locationShared", "cycleShared").forEach { assertEquals(it, "us", PushInbox.tabFor(it)) }
        // A game invite, a turn waiting, a game ended or declined: straight to the games, not the Us tab.
        listOf("gameInvite", "gameTurn", "gameEnded", "gameStarted").forEach { assertEquals(PushInbox.GAMES, PushInbox.tabFor(it)) }
        assertTrue(PushInbox.refreshesToday("exerciseCompleted"))
        assertFalse(PushInbox.refreshesToday("memoriesSynced"))
        // These refresh Home at once (the Plus celebration, the map card, the cycle row), not Today.
        listOf("plusStarted", "locationShared", "cycleShared").forEach { assertTrue(PushInbox.refreshesHome(it)); assertFalse(PushInbox.refreshesToday(it)) }
        assertFalse(PushInbox.refreshesHome("plusEnding"))
        assertFalse(PushInbox.refreshesHome(null))
    }
}
