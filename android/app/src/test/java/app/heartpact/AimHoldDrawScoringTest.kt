package app.heartpact

import androidx.compose.ui.geometry.Offset
import app.heartpact.ui.play.skills.CupidFlight
import app.heartpact.ui.play.skills.Difficulty
import app.heartpact.ui.play.skills.arrowTip
import app.heartpact.ui.play.skills.centroid
import app.heartpact.ui.play.skills.cupidScore
import app.heartpact.ui.play.skills.cupidShot
import app.heartpact.ui.play.skills.heartOutline
import app.heartpact.ui.play.skills.heartScore
import app.heartpact.ui.play.skills.measureHeart
import app.heartpact.ui.play.skills.rotateAround
import app.heartpact.ui.play.skills.slowKissScore
import app.heartpact.ui.play.skills.slowKissTarget
import app.heartpact.ui.play.skills.slowKissTimerSeconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Cupid's aim, Slow kiss and Draw a heart: their flights, holds and drawings, and where each score falls off. */
class AimHoldDrawScoringTest {
    private val level1 = Difficulty.Start
    private val top = Difficulty(Difficulty.RAMP)

    // Cupid's aim.

    /** A loose time whose arrow passes the heart's centre within a hair, found by trying the times along a second. */
    private fun bestLoose(f: CupidFlight, from: Float = 0f): Float =
        (0 until 8000).map { from + it * 0.0005f }.minBy { abs(cupidShot(f, it).dx) }

    @Test fun `an arrow loosed at the right moment goes through the centre and scores 100`() {
        for (seed in 0L until 8L) {
            val f = CupidFlight(seed)
            val t = bestLoose(f)
            val shot = cupidShot(f, t)
            assertTrue("seed $seed: ${shot.dx}", abs(shot.dx) < 0.004f)
            assertTrue(shot.hit)
            // The tip really is at the heart's height when it's measured.
            assertEquals(f.at(shot.crossed).y, arrowTip(t, shot.crossed).y, 1e-4f)
            assertEquals(100, cupidScore(shot, f.size, level1.k(0.3f)).score)
            assertEquals("Right through the heart.", cupidScore(shot, f.size, level1.k(0.3f)).note)
        }
    }

    @Test fun `cupid's aim falls off with the miss and says early or late`() {
        assertEquals(100, cupidScore(0.01f, true, 0.01f, true).score)
        val near = cupidScore(0.1f, true, 0.05f, true)
        assertTrue(near.score in 72..82)
        assertEquals("Through the heart, 0.05 s early.", near.note)
        assertEquals("Through the heart, 0.08 s late.", cupidScore(0.2f, true, -0.08f, false).note)
        val far = cupidScore(0.9f, false, -0.4f, false)
        assertTrue(far.score < 10)
        assertEquals("Missed it. Loosed 0.40 s too late.", far.note)
        // A heart turning on the spot gives no timing: the note says which side.
        assertEquals("Missed it, to the left.", cupidScore(0.7f, false, null, false).note)
        assertEquals("Through the heart, right of center.", cupidScore(0.2f, true, null, true).note)
        // Stricter up the levels: the same miss scores lower.
        assertTrue(cupidScore(0.1f, true, 0.05f, true, top.k(0.3f)).score < near.score)
    }

    @Test fun `loosing late or early is read from the heart's way of travel`() {
        val f = CupidFlight(3)
        val t = bestLoose(f)
        val v = cupidShot(f, t).velocityX
        // Loose a little early: the heart hasn't got there yet, so it passes behind the heart's direction of travel.
        val early = cupidShot(f, t - 0.08f)
        val scored = cupidScore(early, f.size, level1.k(0.3f))
        assertTrue("$v ${early.dx} ${scored.note}", scored.note.contains("early"))
        assertTrue(scored.score in 30..95)
        assertTrue(cupidScore(cupidShot(f, t + 0.08f), f.size, level1.k(0.3f)).note.contains("late"))
    }

    @Test fun `the heart flies faster, smaller and wilder up the levels`() {
        val easy = CupidFlight(1, level1)
        val hard = CupidFlight(1, top)
        assertTrue(hard.size < easy.size && hard.speed > easy.speed && hard.bob > easy.bob)
        assertFalse(easy.decoy)
        assertTrue(hard.decoy)
        assertTrue(easy.aimLine && !hard.aimLine)
        // It stays on the board, its whole width inside.
        for (f in listOf(easy, hard, CupidFlight(5, Difficulty(9)))) {
            for (i in 0 until 3000) {
                val p = f.at(i * 0.02f)
                assertTrue(p.x - f.size / 2 > 0f && p.x + f.size / 2 < 1f)
            }
        }
        // Level 1 never turns back mid-board; level 9 does.
        fun turnsMidBoard(f: CupidFlight) = (1 until 1500).count { i ->
            val a = f.velocityX(i * 0.02f); val b = f.velocityX((i + 1) * 0.02f)
            val x = f.at(i * 0.02f).x
            a * b < 0f && x in 0.3f..0.7f
        }
        assertEquals(0, turnsMidBoard(easy))
        assertTrue(turnsMidBoard(CupidFlight(5, Difficulty(9))) > 0)
    }

    @Test fun `the same seed flies the same way on both phones`() {
        val a = CupidFlight(77)
        val b = CupidFlight(77)
        for (i in 0 until 50) assertEquals(a.at(i * 0.3f), b.at(i * 0.3f))
        assertTrue((0 until 50).any { CupidFlight(78).at(it * 0.3f) != a.at(it * 0.3f) })
    }

    // Slow kiss.

    @Test fun `slow kiss is perfect within 20 ms and says which way it missed`() {
        assertEquals(100, slowKissScore(3.01f, 3f).score)
        assertEquals("Exactly 3.00 s. A perfect kiss.", slowKissScore(2.99f, 3f).note)
        val near = slowKissScore(3.15f, 3f)
        assertTrue(near.score in 70..80)
        assertEquals("Held on 0.15 s too long.", near.note)
        assertEquals("Let go 0.40 s too early.", slowKissScore(2.6f, 3f).note)
        assertTrue(slowKissScore(1f, 3f).score < 2)
        assertTrue(slowKissScore(9f, 3f).score == 0)
        // The miss is a share of the kiss: 0.3 s off a 6 s kiss is as good as 0.15 s off a 3 s one.
        assertTrue(abs(slowKissScore(6.3f, 6f).score - slowKissScore(3.15f, 3f).score) <= 3)
        assertTrue(slowKissScore(3.15f, 3f, top.k(0.15f)).score < near.score)
    }

    @Test fun `the kiss gets longer and the timer hides sooner up the levels`() {
        val easy = (0L until 40L).map { slowKissTarget(it, level1) }.toSet()
        assertEquals(setOf(2.5f, 3f, 3.5f), easy)
        val hard = (0L until 200L).map { slowKissTarget(it, top) }
        assertTrue(hard.all { it in 6f..8f })
        assertTrue(hard.any { it % 0.5f != 0f })
        assertEquals(slowKissTarget(9, Difficulty(7)), slowKissTarget(9, Difficulty(7)))
        assertEquals(1.6f, slowKissTimerSeconds(level1), 1e-6f)
        assertTrue(slowKissTimerSeconds(Difficulty(8)) in 0.4f..1.5f)
        assertEquals(0f, slowKissTimerSeconds(Difficulty(12)))
    }

    // Draw a heart.

    /** The app's heart as one stroke from the dip, in px, [size] tall at [at], with a change [bend] to each point. */
    private fun heartStroke(size: Float = 300f, at: Offset = Offset(200f, 300f), turnDeg: Float = 0f, cut: Int = 0, bend: (Offset, Int) -> Offset = { p, _ -> p }): List<Offset> {
        val h = heartOutline(240)
        val c = centroid(h)
        val tall = h.maxOf { it.y } - h.minOf { it.y }
        // Start at the dip (the top middle, where the lobes meet) and go round.
        val startAt = h.indices.filter { abs(h[it].x - 50f) < 6f && h[it].y < 50f }.minBy { abs(h[it].x - 50f) }
        val loop = (h.drop(startAt) + h.take(startAt) + h[startAt]).dropLast(cut)
        return loop.mapIndexed { i, p ->
            val q = bend(p, i)
            (at + (q - c) * (size / tall)).rotateAround(at, Math.toRadians(turnDeg.toDouble()).toFloat())
        }
    }

    @Test fun `a true heart scores high wherever it is, however big, and leaning a little`() {
        val m = measureHeart(heartStroke(), 40f)
        assertNotNull(m)
        val s = heartScore(m!!)
        assertTrue("${s.score} ${m.apart}", s.score >= 95)
        assertEquals("A true heart.", s.note)
        assertEquals(90f, m.tipAngle, 2f)
        val small = heartScore(measureHeart(heartStroke(120f, Offset(90f, 500f), turnDeg = 20f), 40f)!!)
        assertTrue("${small.score}", small.score >= 95)
    }

    @Test fun `a hand drawn heart scores well, a circle and a half do not`() {
        val hand = heartScore(measureHeart(heartStroke { p, i -> p + Offset(1.6f * sin(i * 0.11f), 1.4f * cos(i * 0.07f)) }, 40f)!!)
        val rough = heartScore(measureHeart(heartStroke { p, i -> p + Offset(3.5f * sin(i * 0.09f), 3f * cos(i * 0.05f + 1f)) }, 40f)!!)
        val circle = heartScore(measureHeart(List(120) { i -> Offset(200f, 300f) + Offset(cos(i / 119f * 6.3f), sin(i / 119f * 6.3f)) * 140f }, 40f)!!)
        val half = heartScore(measureHeart(heartStroke(cut = 120), 40f)!!)
        assertTrue("$hand", hand.score in 80..97)
        assertTrue("$rough", rough.score in 55..85)
        assertTrue("$circle", circle.score < 40)
        assertTrue("$half", half.score < 30)
        // Stricter up the levels.
        val m = measureHeart(heartStroke { p, i -> p + Offset(3.5f * sin(i * 0.09f), 3f * cos(i * 0.05f + 1f)) }, 40f)!!
        assertTrue(heartScore(m, top.k(0.075f)).score < rough.score)
    }

    @Test fun `the note names the biggest flaw`() {
        // Left open near the top, and at the point.
        val open = heartScore(measureHeart(heartStroke(cut = 30), 40f)!!)
        assertEquals("Close the top. The lobes don't meet.", open.note)
        assertTrue("$open", open.score < 50)
        val tipOpen = heartScore(measureHeart(heartStroke().let { s -> val i = s.indices.maxBy { s[it].y }; s.drop(i + 12) + s.drop(1).take(i - 12) }, 40f)!!)
        assertEquals("Close the bottom point.", tipOpen.note)
        // A shrunken lobe, either side.
        val shrink = { p: Offset, left: Boolean -> if ((p.x < 50f) == left && p.y < 60f) Offset(50f + (p.x - 50f) * 0.78f, 60f + (p.y - 60f) * 0.8f) else p }
        assertEquals("The left lobe is smaller.", heartScore(measureHeart(heartStroke { p, _ -> shrink(p, true) }, 40f)!!).note)
        assertEquals("The right lobe is smaller.", heartScore(measureHeart(heartStroke { p, _ -> shrink(p, false) }, 40f)!!).note)
        // A round bottom, no point.
        val round = heartScore(measureHeart(heartStroke { p, _ -> if (p.y > 70f) Offset(50f + (p.x - 50f) * 1.4f.coerceAtMost(1f + (100f - p.y) / 30f), 70f + (p.y - 70f) * 0.55f) else p }, 40f)!!)
        assertEquals("Sharpen the point at the bottom.", round.note)
        // A flat top, no dip between the lobes.
        val flat = heartScore(measureHeart(heartStroke { p, _ -> if (p.y < 20f) Offset(p.x, 7f + (p.y - 7f) * 0.1f) else p }, 40f)!!)
        assertEquals("Give it a dip between the lobes.", flat.note)
        // A shaky hand.
        val shaky = heartScore(measureHeart(heartStroke { p, i -> p + Offset(if (i % 2 == 0) 1.6f else -1.6f, 0f) }, 40f)!!)
        assertEquals("A shaky line. Slow and steady.", shaky.note)
    }

    @Test fun `slips and scribbles are not hearts yet`() {
        assertNull(measureHeart(listOf(Offset(0f, 0f), Offset(1f, 1f)), 40f))
        assertNull(measureHeart(List(30) { Offset(100f + it * 8f, 200f) }, 40f))
        assertNull(measureHeart(heartStroke(20f), 40f))
    }

    @Test fun `with a guide, size and place count`() {
        val guide = Offset(200f, 300f) to 300f
        val on = measureHeart(heartStroke(), 40f, guide)!!
        assertTrue(heartScore(on, guided = true).score >= 95)
        val big = measureHeart(heartStroke(380f), 40f, guide)!!
        val bigScore = heartScore(big, guided = true)
        assertEquals("Too big. Keep inside the dashed heart.", bigScore.note)
        assertTrue(bigScore.score < heartScore(measureHeart(heartStroke(380f), 40f)!!).score)
        assertEquals("Too small. Fill the dashed heart.", heartScore(measureHeart(heartStroke(220f), 40f, guide)!!, guided = true).note)
        val off = heartScore(measureHeart(heartStroke(at = Offset(250f, 330f)), 40f, guide)!!, guided = true)
        assertEquals("Off the guide. Trace the dashed heart.", off.note)
        assertTrue("$off", off.score < 50)
        assertEquals("It leans. Keep the point straight down.", heartScore(measureHeart(heartStroke(turnDeg = 25f), 40f, guide)!!, guided = true).note)
    }
}
