package app.heartpact.map

import app.heartpact.ui.map.LocationMath
import app.heartpact.ui.map.MapMotion
import app.heartpact.ui.map.MarkerTween
import app.heartpact.ui.map.Pos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class MapMotionTest {
    private val a = Pos(6.4300, 3.4200)
    private val b = Pos(6.4400, 3.4350)

    @Test fun `a marker glides from one fix to the next over 800 ms and then rests`() {
        val tween = MarkerTween(a)
        tween.moveTo(b, nowMs = 10_000)
        assertEquals(a, tween.at(10_000))
        val mid = tween.at(10_400)
        assertEquals("halfway in time is halfway in place", (a.lat + b.lat) / 2, mid.lat, 1e-9)
        assertEquals((a.lng + b.lng) / 2, mid.lng, 1e-9)
        assertTrue(tween.isMoving(10_799))
        assertEquals(b, tween.at(10_800))
        assertFalse(tween.isMoving(10_800))
        assertEquals(b, tween.at(60_000))
    }

    @Test fun `the glide eases in and out`() {
        val tween = MarkerTween(a)
        tween.moveTo(b, 1_000)
        val early = tween.at(1_100).lat - a.lat
        val linearEarly = (b.lat - a.lat) * 100 / 800
        assertTrue("slower than linear at the start", early < linearEarly)
    }

    @Test fun `a fix that arrives mid-glide continues from where the marker is, not from the old fix`() {
        val tween = MarkerTween(a)
        tween.moveTo(b, 1_000)
        val here = tween.at(1_400)
        val c = Pos(6.4500, 3.4500)
        tween.moveTo(c, 1_400)
        assertEquals(here, tween.at(1_400))
        assertEquals(c, tween.at(2_200))
    }

    @Test fun `the same fix again does not restart the glide`() {
        val tween = MarkerTween(a)
        tween.moveTo(b, 1_000)
        tween.moveTo(b, 1_500)
        assertEquals(b, tween.at(1_800))
    }

    @Test fun `snapping jumps without a glide`() {
        val tween = MarkerTween(a)
        tween.snapTo(b)
        assertEquals(b, tween.at(0))
        assertFalse(tween.isMoving(0))
    }

    @Test fun `positions interpolate the short way over the date line`() {
        val mid = MapMotion.lerp(Pos(0.0, 179.0), Pos(0.0, -179.0), 0.5f)
        assertEquals(180.0, kotlin.math.abs(mid.lng), 1e-9)
    }

    @Test fun `a fix is live for five minutes`() {
        assertTrue(MapMotion.isLive(0))
        assertTrue(MapMotion.isLive(4))
        assertFalse(MapMotion.isLive(5))
    }

    @Test fun `the halo grows and fades through each pulse`() {
        val start = MapMotion.halo(0)
        val late = MapMotion.halo(MapMotion.PULSE_MS - 50)
        assertTrue(late.radius > start.radius)
        assertTrue(late.opacity < start.opacity)
        assertEquals("it repeats", MapMotion.halo(300).radius, MapMotion.halo(300 + MapMotion.PULSE_MS).radius, 1e-4f)
        for (t in 0L..MapMotion.PULSE_MS step 100) {
            val h = MapMotion.halo(t)
            assertTrue(h.radius in 0f..1f)
            assertTrue(h.opacity in 0f..1f)
        }
    }

    @Test fun `the sway starts exactly where the camera was left and stays within a few degrees`() {
        assertEquals(40.0, MapMotion.sway(40.0, 0), 1e-9)
        for (t in 0L..MapMotion.SWAY_PERIOD_MS step 1_000) {
            assertEquals(40.0, MapMotion.sway(40.0, t), MapMotion.SWAY_DEGREES + 1e-9)
        }
        // Left pointing nearly north, it sways across north and stays a valid bearing.
        for (t in 0L..MapMotion.SWAY_PERIOD_MS step 1_000) assertTrue(MapMotion.sway(358.0, t) in 0.0..360.0)
        assertEquals(350.0, MapMotion.normalizeBearing(-10.0), 1e-9)
    }

    @Test fun `framing never zooms closer than street level`() {
        assertEquals(17.0, MapMotion.frameZoom(21.4), 0.0)
        assertEquals(14.2, MapMotion.frameZoom(14.2), 0.0)
    }

    @Test fun `the marker says how old the fix is`() {
        val now = Instant.parse("2026-10-11T18:04:00Z")
        assertEquals("now", LocationMath.markerAge("2026-10-11T18:03:40Z", now))
        assertEquals("now", LocationMath.markerAge("2026-10-11T18:05:00Z", now))
        assertEquals("2 min ago", LocationMath.markerAge("2026-10-11T18:02:00Z", now))
        assertEquals("3 h ago", LocationMath.markerAge("2026-10-11T15:00:00Z", now))
        assertEquals("over a day ago", LocationMath.markerAge("2026-10-09T15:00:00Z", now))
    }
}
