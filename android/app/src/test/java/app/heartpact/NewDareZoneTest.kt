package app.heartpact

import app.heartpact.ui.today.otherZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** New dare says whose clock the time is on only when the partner's keeps a different one. */
class NewDareZoneTest {
    private val london = ZoneId.of("Europe/London")
    private val now = Instant.parse("2026-10-11T07:48:00Z")

    @Test fun `a partner in another zone is named`() =
        assertEquals(ZoneId.of("America/New_York"), otherZone("America/New_York", london, now))

    @Test fun `the same clock, an unknown zone or none says nothing`() {
        assertNull(otherZone("Europe/London", london, now))
        // Lisbon keeps London's clock.
        assertNull(otherZone("Europe/Lisbon", london, now))
        assertNull(otherZone("Mars/Olympus_Mons", london, now))
        assertNull(otherZone(null, london, now))
    }
}
