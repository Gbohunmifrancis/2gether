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
        assertEquals("us", PushInbox.tabFor("dailyQuestionAnswered"))
        assertTrue(PushInbox.refreshesToday("exerciseCompleted"))
        assertFalse(PushInbox.refreshesToday("memoriesSynced"))
    }
}
