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

    @Test fun `the partner starting their dare opens the live view of it`() {
        val id = "6f1c2a52-6a3c-4c76-9b1e-2f0a0d5b9c11"
        val target = PushInbox.openFor("exerciseStarted", "{\"occurrenceId\":\"$id\",\"exercise\":\"squat\"}")
        assertEquals("watch/$id", target)
        assertEquals(id, PushInbox.watchedOccurrence(target))
        // Without the dare in its data it falls back to Today; other kinds go where they always did.
        assertEquals("today", PushInbox.openFor("exerciseStarted", null))
        assertEquals("today", PushInbox.openFor("exerciseCompleted", "{\"occurrenceId\":\"$id\"}"))
        assertNull(PushInbox.watchedOccurrence("today"))
        assertNull(PushInbox.watchedOccurrence("watch/../us"))
        // It changes what Today shows; the live stream itself never makes anything refetch.
        assertTrue(PushInbox.refreshesToday("exerciseStarted"))
        listOf("exerciseLive", "exerciseWatching", "exerciseCheer").forEach { assertFalse(it, PushInbox.refreshesToday(it)) }
    }
}
