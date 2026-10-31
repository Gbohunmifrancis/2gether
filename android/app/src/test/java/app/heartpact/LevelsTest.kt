package app.heartpact

import app.heartpact.ui.play.skills.Difficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LevelsTest {
    @Test fun `level 1 is the game as it was, the ramp tops out at 20`() {
        assertEquals(0f, Difficulty(1).d)
        assertEquals(1f, Difficulty(Difficulty.RAMP).d)
        assertEquals(1f, Difficulty(57).d)
        assertEquals(14f, Difficulty(1).k(14f))
        assertEquals(14f * 0.7f, Difficulty(20).k(14f), 0.001f)
    }

    @Test fun `the pass mark rises from 50 to 80 and the stars come 10 and 20 over it`() {
        assertEquals(50, Difficulty(1).passMark)
        assertEquals(80, Difficulty(20).passMark)
        assertTrue((1..20).map { Difficulty(it).passMark }.zipWithNext().all { (a, b) -> b >= a })
        val one = Difficulty(1)
        assertEquals(0, one.stars(49))
        assertEquals(1, one.stars(50))
        assertEquals(2, one.stars(60))
        assertEquals(3, one.stars(70))
        // At the top, three stars still need 95, never more than 100.
        assertEquals(2, Difficulty(20).stars(94))
        assertEquals(3, Difficulty(20).stars(95))
    }

    @Test fun `lerp, step and from`() {
        val mid = Difficulty(1 + (Difficulty.RAMP - 1) / 2)
        assertTrue(mid.lerp(0f, 10f) in 4f..6f)
        assertEquals(8, Difficulty(1).step(8, 12))
        assertEquals(12, Difficulty(20).step(8, 12))
        assertTrue(Difficulty(10).from(10))
        assertTrue(!Difficulty(9).from(10))
    }
}
