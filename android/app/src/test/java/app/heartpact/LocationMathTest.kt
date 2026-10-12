package app.heartpact

import app.heartpact.ui.map.LocationMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class LocationMathTest {
    @Test fun `distance reads like a person would say it`() {
        // Two points in Lagos about 1.8 km apart.
        val m = LocationMath.meters(6.4300, 3.4200, 6.4400, 3.4350)
        assertEquals(1990.0, m, 60.0)
        assertEquals("2.0 km away", LocationMath.distance(m))
        assertEquals("350 m away", LocationMath.distance(347.0))
        assertEquals("right next to you", LocationMath.distance(40.0))
        assertEquals("12 km away", LocationMath.distance(12_400.0))
    }

    @Test fun `ages and pauses`() {
        val now = Instant.parse("2026-10-11T18:04:00Z")
        assertEquals("updated 2 min ago", LocationMath.age("2026-10-11T18:02:00Z", now))
        assertEquals("updated just now", LocationMath.age("2026-10-11T18:03:40Z", now))
        val evening = ZonedDateTime.of(2026, 10, 11, 22, 30, 0, 0, ZoneId.of("Africa/Lagos"))
        assertEquals(570, LocationMath.minutesUntilTomorrow(evening))
        val early = ZonedDateTime.of(2026, 10, 11, 7, 0, 0, 0, ZoneId.of("Africa/Lagos"))
        assertEquals("capped at the server's 24 hours", 1440, LocationMath.minutesUntilTomorrow(early))
    }

    @Test fun `two points land inside the frame, the right way round`() {
        val (me, them) = LocationMath.place(6.4300, 3.4200, 6.4400, 3.4350, aspect = 390f / 520f)
        listOf(me, them).forEach { (x, y) -> assertTrue(x in 0.2f..0.8f && y in 0.2f..0.8f) }
        assertTrue("partner is north-east: right and up", them.first > me.first && them.second < me.second)
    }
}
