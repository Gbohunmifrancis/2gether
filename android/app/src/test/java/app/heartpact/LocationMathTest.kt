package app.heartpact

import app.heartpact.data.SharingState
import app.heartpact.data.SharingStatus
import app.heartpact.data.SharingStatusView
import app.heartpact.ui.map.LocationMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        assertEquals("before 8:00, this morning's 8:00", 60, LocationMath.minutesUntilTomorrow(early))
        val night = ZonedDateTime.of(2026, 10, 11, 1, 0, 0, 0, ZoneId.of("Africa/Lagos"))
        assertEquals(420, LocationMath.minutesUntilTomorrow(night))
        val justBefore = ZonedDateTime.of(2026, 10, 11, 7, 55, 0, 0, ZoneId.of("Africa/Lagos"))
        assertEquals("the server's shortest pause", 15, LocationMath.minutesUntilTomorrow(justBefore))
        val atEight = ZonedDateTime.of(2026, 10, 11, 8, 0, 0, 0, ZoneId.of("Africa/Lagos"))
        assertEquals("capped at the server's 24 hours", 1440, LocationMath.minutesUntilTomorrow(atEight))
    }

    @Test fun `a phone clock behind the server never shows a future age`() {
        val now = Instant.parse("2026-10-11T18:04:00Z")
        assertEquals(0L, LocationMath.minutesSince("2026-10-11T18:10:00Z", now))
        assertEquals("updated just now", LocationMath.age("2026-10-11T18:10:00Z", now))
        assertEquals(2L, LocationMath.minutesSince("2026-10-11T18:02:00Z", now))
    }

    @Test fun `old last known fixes are not sent`() {
        val second = 1_000_000_000L
        val now = 10_000 * second
        assertTrue(LocationMath.isRecentFix(now - 30 * second, now))
        assertFalse(LocationMath.isRecentFix(now - 3 * 60 * second, now))
        assertFalse("from before a reboot", LocationMath.isRecentFix(now + second, now))
    }

    @Test fun `the sharing phone checks in at its beat, or just after its pause ends`() {
        val now = Instant.parse("2026-10-11T18:00:00Z")
        val beat = 4 * 60_000L
        assertEquals(beat, LocationMath.nextBeatMs(null, now, beat))
        assertEquals(63_000L, LocationMath.nextBeatMs("2026-10-11T18:01:00Z", now, beat))
        assertEquals(beat, LocationMath.nextBeatMs("2026-10-11T19:00:00Z", now, beat))
        assertEquals("already over", 3_000L, LocationMath.nextBeatMs("2026-10-11T17:00:00Z", now, beat))
    }

    @Test fun `screens refresh when the earliest pause ends`() {
        val now = Instant.parse("2026-10-11T18:00:00Z")
        val paused = SharingStatusView(
            SharingState("maya", status = SharingStatus.Paused, pausedUntilUtc = "2026-10-11T19:00:00Z"),
            SharingState("theo", status = SharingStatus.Paused, pausedUntilUtc = "2026-10-11T18:10:00Z"),
        )
        assertEquals(603_000L, LocationMath.refreshAfterPauseMs(paused, now))
        assertNull(LocationMath.refreshAfterPauseMs(SharingStatusView(SharingState("maya", status = SharingStatus.Sharing)), now))
        assertNull(LocationMath.refreshAfterPauseMs(null, now))
    }

    @Test fun `points from the hub show only while your own share is on`() {
        assertTrue(LocationMath.acceptsPoints(SharingStatus.Sharing))
        assertFalse(LocationMath.acceptsPoints(SharingStatus.Paused))
        assertFalse(LocationMath.acceptsPoints(SharingStatus.Off))
    }

    @Test fun `a sharing phone stops when the hub says its own person's share is off`() {
        // Signed out (or stopped) on another phone: the same person's share reads off.
        assertTrue(LocationMath.saysMyShareIsOff(SharingState("maya", "Maya", SharingStatus.Off), "maya"))
        // The partner stopping, or my own pause, is not a reason to stop here.
        assertFalse(LocationMath.saysMyShareIsOff(SharingState("theo", "Theo", SharingStatus.Off), "maya"))
        assertFalse(LocationMath.saysMyShareIsOff(SharingState("maya", "Maya", SharingStatus.Paused, pausedUntilUtc = "2026-10-11T19:00:00Z"), "maya"))
        assertFalse(LocationMath.saysMyShareIsOff(SharingState("maya", "Maya", SharingStatus.Sharing), "maya"))
        assertFalse(LocationMath.saysMyShareIsOff(null, "maya"))
        assertFalse(LocationMath.saysMyShareIsOff(SharingState("maya", "Maya", SharingStatus.Off), null))
    }

    @Test fun `two points land inside the frame, the right way round`() {
        val (me, them) = LocationMath.place(6.4300, 3.4200, 6.4400, 3.4350, aspect = 390f / 520f)
        listOf(me, them).forEach { (x, y) -> assertTrue(x in 0.2f..0.8f && y in 0.2f..0.8f) }
        assertTrue("partner is north-east: right and up", them.first > me.first && them.second < me.second)
    }
}
