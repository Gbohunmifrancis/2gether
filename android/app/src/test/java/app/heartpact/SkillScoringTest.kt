package app.heartpact

import androidx.compose.ui.geometry.Offset
import app.heartpact.ui.play.skills.HalfShape
import app.heartpact.ui.play.skills.JarTable
import app.heartpact.ui.play.skills.TAU
import app.heartpact.ui.play.skills.Treat
import app.heartpact.ui.play.skills.area
import app.heartpact.ui.play.skills.centroid
import app.heartpact.ui.play.skills.contains
import app.heartpact.ui.play.skills.cut
import app.heartpact.ui.play.skills.fairShareScore
import app.heartpact.ui.play.skills.fillScore
import app.heartpact.ui.play.skills.fitCircle
import app.heartpact.ui.play.skills.heartOutline
import app.heartpact.ui.play.skills.heartbeatMissed
import app.heartpact.ui.play.skills.heartbeatScore
import app.heartpact.ui.play.skills.kissScore
import app.heartpact.ui.play.skills.measureRing
import app.heartpact.ui.play.skills.otherHalfScore
import app.heartpact.ui.play.skills.ringScore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** The skill games' geometry and scores: areas and cuts are exact, and each score falls off where we said it would. */
class SkillScoringTest {
    private val square = listOf(Offset(0f, 0f), Offset(1f, 0f), Offset(1f, 1f), Offset(0f, 1f))

    @Test fun `area and centre of area`() {
        assertEquals(1f, area(square), 1e-6f)
        assertEquals(Offset(0.5f, 0.5f), centroid(square))
        // An L: its centre of area is not the middle of its box.
        val l = listOf(Offset(0f, 0f), Offset(2f, 0f), Offset(2f, 1f), Offset(1f, 1f), Offset(1f, 2f), Offset(0f, 2f))
        assertEquals(3f, area(l), 1e-6f)
        val c = centroid(l)
        assertEquals(5f / 6f, c.x, 1e-5f)
        assertEquals(5f / 6f, c.y, 1e-5f)
    }

    @Test fun `a diagonal halves a square and the fold halves the heart`() {
        val (a, b) = cut(square, Offset(0f, 0f), Offset(1f, 1f))
        assertEquals(0.5f, area(a), 1e-6f)
        assertEquals(0.5f, area(b), 1e-6f)
        val heart = heartOutline()
        val (l, r) = cut(heart, Offset(50f, -10f), Offset(50f, 120f))
        assertEquals(area(l), area(r), area(heart) * 1e-4f)
    }

    @Test fun `cuts match fine sampling, even through concave shapes`() {
        val rnd = Random(4)
        for (seed in 0L until 24L) {
            val t = Treat.of(seed)
            val a = Offset(rnd.nextFloat(), 0f)
            val b = Offset(rnd.nextFloat(), 1f)
            val (p, q) = cut(t.outline, a, b)
            assertEquals("pieces add up ($seed)", area(t.outline), area(p) + area(q), area(t.outline) * 1e-4f)
            // Count a 400 by 400 grid of points on the positive side of the line inside the shape.
            var inside = 0
            var positive = 0
            val n = 400
            for (i in 0 until n) for (j in 0 until n) {
                val pt = Offset((i + 0.5f) / n, (j + 0.5f) / n)
                if (!contains(t.outline, pt)) continue
                inside++
                if ((b.x - a.x) * (pt.y - a.y) - (b.y - a.y) * (pt.x - a.x) >= 0) positive++
            }
            val share = area(p) / area(t.outline)
            assertEquals("share of ${t.kind} ($seed)", positive / inside.toFloat(), share, 0.004f)
        }
    }

    @Test fun `fair share is steep around half`() {
        assertEquals(100, fairShareScore(50f, false).score)
        assertEquals("Dead even. Perfectly fair.", fairShareScore(50f, false).note)
        assertEquals(70, fairShareScore(45f, false).score)
        assertTrue(fairShareScore(49.5f, true).score >= 96)
        assertTrue(fairShareScore(35f, true).score < 40)
        assertEquals("Left side got 2.4% more.", fairShareScore(51.2f, false).note)
        assertEquals("Right side got 2.4% more.", fairShareScore(48.8f, false).note)
        // The note matches the shares the pieces show, each to a tenth.
        assertEquals("Right side got 6.4% more.", fairShareScore(46.84f, false).note)
        assertEquals("The bottom got 10.0% more.", fairShareScore(45f, true).note)
    }

    @Test fun `fill scores the gap to the line and says how long to hold`() {
        val on = fillScore(0.5f, 0.5f, 0f)
        assertEquals(100, on.score)
        assertEquals("Right on the line.", on.note)
        assertEquals(70, fillScore(0.55f, 0.5f, 0.2f).score)
        assertEquals("Held on 0.20 s too long.", fillScore(0.55f, 0.5f, 0.2f).note)
        assertEquals("Let go 0.12 s too early.", fillScore(0.47f, 0.5f, 0.12f).note)
    }

    @Test fun `the jar fills fast at the tip and slow at the lobes`() {
        val heart = heartOutline(240)
        val jar = JarTable(heart)
        assertEquals(area(heart), jar.total, area(heart) * 0.01f)
        for (f in listOf(0.05f, 0.3f, 0.5f, 0.77f, 0.95f)) assertEquals(f, jar.levelFor(jar.volumeBelow(f)), 0.003f)
        // The bottom tenth holds far less than the tenth across the lobes.
        assertTrue(jar.volumeBelow(0.1f) * 5 < jar.volumeBelow(0.7f) - jar.volumeBelow(0.6f))
    }

    @Test fun `heartbeat is perfect within 15 ms and poor at a quarter second`() {
        assertEquals(100, heartbeatScore(-12f).score)
        assertEquals("Right on the beat.", heartbeatScore(12f).note)
        val poor = heartbeatScore(250f).score
        assertTrue("250 ms: $poor", poor in 35..45)
        assertEquals("Tapped 0.12 s early.", heartbeatScore(-120f).note)
        assertEquals("Tapped 0.08 s late.", heartbeatScore(80f).note)
        assertEquals(0, heartbeatMissed().score)
    }

    @Test fun `kiss scores the distance from the centre and says which way`() {
        val c = Offset(100f, 100f)
        assertEquals(100, kissScore(c, c, 200f).score)
        assertEquals("Right on the center.", kissScore(c + Offset(0.5f, 0.5f), c, 200f).note)
        val five = kissScore(c + Offset(-10f, 0f), c, 200f)
        assertTrue("5%: ${five.score}", five.score in 68..76)
        assertEquals("Off center by 5.0%, to the left.", five.note)
        assertEquals("Off center by 7.1%, up and to the right.", kissScore(c + Offset(10f, -10f), c, 200f).note)
        assertEquals("Off center by 5.0%, too low.", kissScore(c + Offset(0f, 10f), c, 200f).note)
    }

    @Test fun `other half rewards a mirror and punishes half a drawing`() {
        val truth = HalfShape.of(3).half
        val perfect = otherHalfScore(listOf(truth), truth)
        assertTrue("perfect: $perfect", perfect.score >= 98)
        val half = otherHalfScore(listOf(truth.subList(0, truth.size / 2)), truth)
        assertTrue("half: $half", half.score < perfect.score - 20)
        assertTrue(half.note.startsWith("Part of the bottom half"))
        val wide = otherHalfScore(listOf(truth.map { Offset(it.x * 1.1f + 0.015f, it.y) }), truth)
        assertEquals("A bit too wide. Hug the fold closer.", wide.note)
        assertEquals(0, otherHalfScore(emptyList(), truth).score)
    }

    @Test fun `every half shape runs from the fold to the fold`() {
        for (seed in 0L until 30L) {
            val h = HalfShape.of(seed).half
            assertEquals(0f, h.first().x, 0.002f)
            assertEquals(0f, h.last().x, 0.002f)
            assertTrue(h.all { it.x >= -0.001f && it.y in -0.001f..1.001f })
        }
    }

    @Test fun `treats are the same for the same seed`() {
        for (seed in listOf(1L, 99L, -7L)) {
            assertEquals(Treat.of(seed).outline, Treat.of(seed).outline)
            assertTrue(area(Treat.of(seed).outline) > 0.15f)
        }
        // Lopsided shapes still hold their own centre, so there is somewhere to aim.
        for (seed in 0L until 40L) {
            val t = Treat.of(seed * 31 + 7, odd = true)
            assertTrue("odd $seed ${t.kind}", contains(t.outline, centroid(t.outline)))
        }
    }

    private fun circle(r: Float, from: Float, turn: Float, c: Offset = Offset(200f, 200f), wobble: (Float) -> Float = { 0f }) =
        List(400) { i ->
            val t = from + turn * i / 399
            c + Offset(cos(t), sin(t)) * (r + wobble(t))
        }

    @Test fun `a fitted circle finds the centre`() {
        val (c, r) = fitCircle(circle(80f, 0f, 2f))
        assertEquals(200f, c.x, 0.01f)
        assertEquals(200f, c.y, 0.01f)
        assertEquals(80f, r, 0.01f)
    }

    @Test fun `a round ring scores high, an oval says so, an open one says how open`() {
        val round = measureRing(circle(90f, 0f, TAU))!!
        val perfect = ringScore(round)
        assertTrue("round: $perfect", perfect.score >= 97)
        assertEquals("Round as a promise.", perfect.note)
        val ovalRing = measureRing(List(400) { i -> val t = TAU * i / 399; Offset(200f + 100f * cos(t), 200f + 80f * sin(t)) })!!
        val oval = ringScore(ovalRing)
        assertTrue(oval.note.contains("oval"))
        assertTrue("oval: $oval", oval.score in 50..85)
        val open = measureRing(circle(90f, 1f, TAU * 340f / 360f))!!
        assertEquals(20f, open.gapDegrees, 1.5f)
        assertEquals("Left 20° open.", ringScore(open).note)
    }

    @Test fun `the overlap past a full turn is trimmed, and slips are not rings`() {
        val over = measureRing(circle(90f, 0f, TAU * 1.2f))!!
        assertTrue(over.overlap.isNotEmpty())
        assertEquals(0f, over.gapDegrees, 0.01f)
        assertTrue(ringScore(over).score >= 97)
        assertNull(measureRing(listOf(Offset(0f, 0f), Offset(10f, 10f), Offset(20f, 20f))))
        assertNull(measureRing(circle(90f, 0f, TAU * 0.3f)))
        assertNull(measureRing(circle(12f, 0f, TAU)))
        val shaky = measureRing(circle(90f, 0f, TAU) { t -> 2.2f * sin(t * 47f) })
        assertNotNull(shaky)
        assertTrue(ringScore(shaky!!).score < ringScore(over).score)
        assertTrue(abs(shaky.radius - 90f) < 2f)
    }
}
