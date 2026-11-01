package app.heartpact

import app.heartpact.push.BannerArt
import app.heartpact.push.BannerQueue
import app.heartpact.push.InAppNotice
import app.heartpact.push.PushData
import app.heartpact.push.bannerArt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BannerQueueTest {
    private fun notice(id: String, type: String = "message") = InAppNotice(id, type, "Title $id", "Body $id")

    @Test fun `banners play one at a time, in the order they came`() {
        var queue = BannerQueue().offer(notice("a")).offer(notice("b")).offer(notice("c"))
        assertEquals("a", queue.showing?.id)
        assertEquals(listOf("b", "c"), queue.waiting.map { it.id })
        queue = queue.next()
        assertEquals("b", queue.showing?.id)
        queue = queue.next().next()
        assertNull(queue.showing)
        assertEquals(0, queue.size)
    }

    @Test fun `the same notice from the push and the hub shows once, even after it went`() {
        var queue = BannerQueue().offer(notice("a")).offer(notice("a"))
        assertEquals(1, queue.size)
        queue = queue.next().offer(notice("a"))
        assertNull(queue.showing)
    }

    @Test fun `a long burst keeps the newest few waiting`() {
        var queue = BannerQueue()
        (1..10).forEach { queue = queue.offer(notice("n$it")) }
        assertEquals("n1", queue.showing?.id)
        assertEquals(BannerQueue.MAX_WAITING, queue.waiting.size)
        assertEquals("n10", queue.waiting.last().id)
    }

    @Test fun `the alarm and the album nudge are never banners`() {
        val queue = BannerQueue().offer(notice("x", "exerciseDue")).offer(notice("y", "albumSyncReminder"))
        assertNull(queue.showing)
    }

    @Test fun `the hub's NotificationCreated reads as a notice`() {
        val json = """{"id":"9b1","userId":"u","type":"exerciseCallOffAsked","title":"Maya asks to call off today's squats","body":"Knee is sore",
            "dataJson":"{\"occurrenceId\":\"occ\",\"goalId\":\"g\"}","createdAtUtc":"2026-10-11T08:00:00Z","readAtUtc":null}"""
        val read = InAppNotice.fromHub(json)
        assertEquals(InAppNotice("9b1", "exerciseCallOffAsked", "Maya asks to call off today's squats", "Knee is sore", PushData(occurrenceId = "occ", goalId = "g")), read)
        assertNull(InAppNotice.fromHub("""{"type":"message"}"""))
        assertNull(InAppNotice.fromHub("nonsense"))
    }

    @Test fun `each kind wears its own art`() {
        assertEquals(BannerArt.Message, bannerArt("message"))
        assertEquals(BannerArt.DareDone, bannerArt("exerciseCompleted"))
        assertEquals(BannerArt.CallOff, bannerArt("exerciseCallOffAsked"))
        assertEquals(BannerArt.Live, bannerArt("exerciseStarted"))
        assertEquals(BannerArt.Game, bannerArt("gameTurn"))
        assertEquals(BannerArt.Album, bannerArt("memoriesSynced"))
        assertEquals(BannerArt.Heart, bannerArt("somethingNewer"))
    }
}
