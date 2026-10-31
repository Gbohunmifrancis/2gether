package app.heartpact

import app.heartpact.data.ServerClock
import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerClockTest {
    @After fun forget() = ServerClock.reset()

    @Test fun `a phone keeping good time counts by itself`() {
        ServerClock.observe("Mon, 26 Oct 2026 18:00:00 GMT", receivedAtMs = Instant.parse("2026-10-26T18:00:00.300Z").toEpochMilli())
        assertEquals(0L, ServerClock.offsetMs)
    }

    @Test fun `a phone set minutes wrong counts to the server's clock, and one odd reply doesn't move it`() {
        // The phone runs three minutes slow: the server's Date is three minutes ahead of when the reply arrives.
        val arrived = Instant.parse("2026-10-26T17:57:00Z").toEpochMilli()
        repeat(3) { ServerClock.observe("Mon, 26 Oct 2026 18:00:00 GMT", receivedAtMs = arrived + it * 100) }
        ServerClock.record(0)
        ServerClock.observe("not a date")
        assertTrue(ServerClock.offsetMs in 179_000L..181_000L)
        val shown = ServerClock.now().toEpochMilli() - System.currentTimeMillis()
        assertTrue(shown in 179_000L..181_500L)
    }
}
