package app.heartpact

import app.heartpact.ui.graphics.StickTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StickTimelineTest {
    @Test fun `mockup 20's three frames`() {
        val start = StickTimeline.at(0f)
        assertEquals("nothing of your half yet, only the slot", 0f, start.alpha, 0.001f)
        assertFalse(start.whole)

        val held = StickTimeline.at(StickTimeline.DROP_MS)
        assertEquals(1.1f, held.scale, 0.001f)
        assertEquals(-6f, held.rotation, 0.001f)
        assertEquals(0.85f, held.alpha, 0.001f)
        assertTrue("lifted up and to the left", held.dx < 0 && held.dy < 0)

        val end = StickTimeline.at(StickTimeline.END_MS)
        assertTrue(end.whole)
        assertEquals(1f, end.scale, 0f)
    }

    @Test fun `it presses below its size before settling, and never runs past the end`() {
        val pressed = StickTimeline.at(StickTimeline.PRESS_MS)
        assertTrue(pressed.scale < 1f)
        assertEquals(0f, pressed.rotation, 0.001f)
        assertEquals(StickTimeline.at(StickTimeline.END_MS), StickTimeline.at(10_000f))
        assertEquals(StickTimeline.at(0f), StickTimeline.at(-50f))
        val scales = (0..240 step 10).map { StickTimeline.at(it.toFloat()).scale }
        assertTrue("no frame larger than the drop", scales.all { it <= 1.1f + 1e-4f })
    }
}
