package app.heartpact

import androidx.compose.ui.geometry.Offset
import app.heartpact.ui.play.skills.BeatPlan
import app.heartpact.ui.play.skills.Difficulty
import app.heartpact.ui.play.skills.DrawShape
import app.heartpact.ui.play.skills.FillPlay
import app.heartpact.ui.play.skills.HeartbeatPlay
import app.heartpact.ui.play.skills.Jar
import app.heartpact.ui.play.skills.JarKind
import app.heartpact.ui.play.skills.JarTable
import app.heartpact.ui.play.skills.RingPlay
import app.heartpact.ui.play.skills.ShapeTemplate
import app.heartpact.ui.play.skills.TAU
import app.heartpact.ui.play.skills.area
import app.heartpact.ui.play.skills.contains
import app.heartpact.ui.play.skills.fillScore
import app.heartpact.ui.play.skills.heartOutline
import app.heartpact.ui.play.skills.heartbeatScore
import app.heartpact.ui.play.skills.length
import app.heartpact.ui.play.skills.measureRing
import app.heartpact.ui.play.skills.measureShape
import app.heartpact.ui.play.skills.resample
import app.heartpact.ui.play.skills.ringScore
import app.heartpact.ui.play.skills.shapeScore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.random.Random

/**
 * The variety and the levels of Fill the heart, Heartbeat and Promise ring: every jar holds love sensibly, the beats
 * land where the plan says, every drawn shape is scored fairly, each level is stricter, and level 1 is the game as it
 * always was (Heart to heart duels play it).
 */
class SkillVarietyTest {
    private val top = Difficulty(Difficulty.RAMP)

    // Fill the heart

    @Test fun `every jar is a simple outline that fits under the tap`() {
        for (kind in JarKind.entries) {
            val jar = Jar.of(kind)
            val o = jar.outline
            assertTrue("$kind fits across", o.all { it.x in -2f..102f })
            assertTrue("$kind sits below the spout and on the stand", jar.top >= 4f && jar.bottom in 90f..100.01f)
            assertTrue("$kind holds a fair amount", area(o) > 1500f)
            // No crossings: the outline's own area matches a count of the points inside it.
            var inside = 0
            val n = 200
            for (i in 0 until n) for (j in 0 until n) if (contains(o, Offset((i + 0.5f) / n * 100f, (j + 0.5f) / n * 100f))) inside++
            assertEquals("$kind is a simple outline", area(o), inside * 0.25f, area(o) * 0.02f)
        }
    }

    @Test fun `every jar fills by volume, always upwards`() {
        for (kind in JarKind.entries) {
            val jar = Jar.of(kind)
            val table = JarTable(jar.outline)
            assertEquals("$kind holds its area", area(jar.outline), table.total, area(jar.outline) * 0.01f)
            var last = -1f
            for (i in 0..200) {
                val v = table.volumeBelow(i / 200f)
                assertTrue("$kind at ${i / 200f}", v > last || i == 0)
                last = v
            }
            var lastLevel = -1f
            for (i in 0..100) {
                val level = table.levelFor(table.total * i / 100f)
                assertTrue("$kind level rises", level >= lastLevel)
                lastLevel = level
            }
            for (f in listOf(0.1f, 0.33f, 0.5f, 0.72f, 0.9f)) assertEquals("$kind round trip", f, table.levelFor(table.volumeBelow(f)), 0.004f)
            // Somewhere to pour into at every height, so the love never jumps a gap.
            for (i in 1 until 100) assertTrue("$kind at $i", JarTable.widthAt(jar.outline, jar.top + (jar.bottom - jar.top) * i / 100f) > 0.5f)
        }
    }

    /** The jar's width from the bottom up, in [n] steps. */
    private fun widths(kind: JarKind, n: Int = 40): List<Float> {
        val jar = Jar.of(kind)
        return List(n) { i -> JarTable.widthAt(jar.outline, jar.bottom - (jar.bottom - jar.top) * (i + 0.5f) / n) }
    }

    /** Narrows and then widens again going up (a waist or a neck with a lip): more than [slack] units. */
    private fun hasWaist(w: List<Float>, slack: Float = 3f): Boolean {
        var lowest = Float.MAX_VALUE
        var widest = 0f
        for (x in w) {
            if (x > widest) widest = x
            if (widest - x > slack) lowest = minOf(lowest, x)
            if (lowest < Float.MAX_VALUE && x - lowest > slack) return true
        }
        return false
    }

    @Test fun `level 1 pours into the simple jars, the odd ones join as it rises`() {
        val first = Jar.pool(Difficulty.Start)
        assertEquals(listOf(JarKind.Heart, JarKind.Mug, JarKind.Tumbler), first)
        for (kind in first) assertFalse("$kind has no waist", hasWaist(widths(kind)))
        var last = first.size
        for (level in 2..Difficulty.RAMP) {
            val pool = Jar.pool(Difficulty(level))
            assertTrue(pool.size >= last)
            last = pool.size
        }
        assertEquals(JarKind.entries.size, Jar.pool(top).size)
        assertTrue(JarKind.entries.size >= 13)
        for (kind in listOf(JarKind.Hourglass, JarKind.WaistCup, JarKind.Vase)) assertTrue("$kind has a waist", hasWaist(widths(kind)))
        // Level 1 rounds only ever use level-1 jars, and a jar new at its level turns up often.
        for (seed in 0L until 60L) assertTrue(FillPlay(seed).jarKind in first)
        val fresh = (0L until 100L).count { FillPlay(it, Difficulty(JarKind.Hourglass.unlock)).jarKind == JarKind.Hourglass }
        assertTrue("fresh: $fresh", fresh > 35)
    }

    @Test fun `an hourglass races through its waist`() {
        val jar = Jar.of(JarKind.Hourglass)
        val table = JarTable(jar.outline)
        val waist = (jar.bottom - jar.top) * 0.45f
        val waistLevel = 1f - (55f - jar.top) / (jar.bottom - jar.top)
        val slice = table.volumeBelow(waistLevel + 0.05f) - table.volumeBelow(waistLevel - 0.05f)
        val bottom = table.volumeBelow(0.1f)
        assertTrue("waist $slice vs bottom $bottom ($waist)", slice * 3 < bottom)
    }

    @Test fun `fill gets faster, hides the line sooner and scores stricter up the levels`() {
        val first = FillPlay(3)
        assertEquals(3.3f, first.fillSeconds, 1e-4f)
        assertEquals(0L, first.peekMs)
        assertEquals(14f, first.k, 1e-4f)
        assertTrue(first.canPour)
        for (seed in 0L until 40L) assertTrue(FillPlay(seed).target in 0.34f..0.78f)
        val hard = FillPlay(3, top)
        assertEquals(2.0f, hard.fillSeconds, 1e-4f)
        assertEquals(1000L, hard.peekMs)
        assertEquals(9.8f, hard.k, 1e-3f)
        // From the peek level, the pour waits for the line's moment.
        assertFalse(hard.canPour)
        hard.press()
        assertEquals(FillPlay.Phase.Ready, hard.phase)
        hard.showLine()
        assertTrue(hard.lineShows)
        hard.hideLine()
        assertFalse(hard.lineShows)
        assertTrue(hard.canPour)
        assertTrue(hard.hint.isNotEmpty())
        for (seed in 0L until 40L) {
            val t = FillPlay(seed, top).target
            assertTrue("$t", t in 0.2f..0.86f && abs(t - 0.5f) >= 0.05f)
        }
        assertTrue(FillPlay(1, Difficulty(FillPlay.PEEK_LEVEL - 1)).peekMs == 0L)
        // The same gap scores less as the level rises, and level 1 scores as it always did.
        assertEquals(70, fillScore(0.55f, 0.5f, 0.2f).score)
        assertEquals(70, fillScore(0.55f, 0.5f, 0.2f, FillPlay(1).k).score)
        assertTrue(fillScore(0.55f, 0.5f, 0.2f, hard.k).score < 62)
    }

    @Test fun `the heart jar at level 1 is the original heart`() {
        assertEquals(heartOutline(240), Jar.of(JarKind.Heart).outline)
    }

    // Heartbeat

    @Test fun `level 1 beats exactly as before`() {
        for (seed in 0L until 200L) {
            val plan = BeatPlan.of(seed, Difficulty.Start)
            val rnd = Random(seed)
            assertEquals(64 + rnd.nextInt(9) * 5, plan.bpm)
            assertEquals(4 + rnd.nextInt(2), plan.shown)
            assertEquals(8, plan.target)
            assertEquals(0f, plan.drift)
            for (i in 1..12) assertEquals((i - 1) * plan.period, plan.offset(i))
        }
        assertTrue(HeartbeatPlay(5).countShows)
        assertEquals(256f, HeartbeatPlay(5).k, 1e-4f)
        assertTrue(HeartbeatPlay(5).hint.startsWith("Tap on beat 8."))
    }

    @Test fun `the beat moves later, the tempos spread and fewer beats are heard up the levels`() {
        val plans = (0L until 300L).map { BeatPlan.of(it, top) }
        assertTrue(plans.all { it.target in 10..12 })
        assertEquals(setOf(10, 11, 12), plans.map { it.target }.toSet())
        assertTrue(plans.all { it.bpm in 50..140 })
        assertTrue(plans.minOf { it.bpm } < 60 && plans.maxOf { it.bpm } > 125)
        assertTrue(plans.all { it.shown in 3..4 })
        // The average beat to tap on never falls as the levels rise.
        var last = 0.0
        for (level in 1..Difficulty.RAMP) {
            val mean = (0L until 100L).map { BeatPlan.of(it, Difficulty(level)).target }.average()
            assertTrue("level $level: $mean", mean >= last - 0.15)
            last = mean
        }
        assertTrue(last >= 10.5)
    }

    @Test fun `from level 12 the tempo drifts, and the schedule follows it`() {
        assertTrue((0L until 200L).none { BeatPlan.of(it, Difficulty(BeatPlan.DRIFT_LEVEL - 1)).drift != 0f })
        val drifting = (0L until 200L).map { BeatPlan.of(it, top) }.filter { it.drift != 0f }
        assertTrue(drifting.size > 150)
        assertTrue(drifting.any { it.drift > 0 } && drifting.any { it.drift < 0 })
        for (p in drifting) {
            assertTrue("gentle: ${p.drift}", abs(p.drift) in 0.014f..0.031f)
            assertTrue("heard enough to feel it", p.shown >= 4)
            for (i in 2..p.target) {
                val gap = p.offset(i + 1) - p.offset(i)
                val before = p.offset(i) - p.offset(i - 1)
                // Slowing: each gap a little longer than the one before; speeding up: a little shorter.
                assertEquals(p.drift.sign, (gap - before).toFloat().sign)
            }
        }
        val slow = BeatPlan(60, 4, 10, 0.03f)
        assertEquals(1000.0 * (1 - 1.03.pow(9)) / (1 - 1.03), slow.offset(10).toDouble(), 2.0)
        // The play reads the plan: its hint names the drift and the beat.
        val seed = (0L until 200L).first { BeatPlan.of(it, top).drift != 0f }
        val play = HeartbeatPlay(seed, top)
        assertTrue(play.hint, play.hint.contains("drifts") && play.hint.contains("beat ${play.target}"))
        assertFalse(play.countShows)
    }

    @Test fun `heartbeat scores stricter up the levels`() {
        assertEquals(heartbeatScore(100f).score, heartbeatScore(100f, HeartbeatPlay(1).k).score)
        assertTrue(heartbeatScore(100f, HeartbeatPlay(1, top).k).score < heartbeatScore(100f).score - 8)
        assertEquals(100, heartbeatScore(10f, HeartbeatPlay(1, top).k).score)
    }

    // Promise ring

    /** [shape]'s outline traced by a steady hand: [scale] dp across its spread, centred on [at], running a little past its start. */
    private fun trace(shape: DrawShape, scale: Float = 90f, at: Offset = Offset(200f, 300f), turned: Boolean = false, over: Float = 0.05f,
                      stretch: Offset = Offset(1f, 1f), cut: Float = 0f): List<Offset> {
        val t = ShapeTemplate.of(shape, turned)
        var pts = resample(t.line, length(t.line) / 400f)
        if (shape.closed && over > 0f) pts = pts + pts.subList(1, (pts.size * over).toInt())
        if (cut > 0f) pts = pts.subList(0, (pts.size * (1 - cut)).toInt())
        return pts.map { at + Offset(it.x * stretch.x, it.y * stretch.y) * scale }
    }

    @Test fun `a steady trace of every shape scores high`() {
        for (shape in DrawShape.entries) {
            val fit = measureShape(trace(shape), shape)
            assertNotNull("$shape", fit)
            val s = shapeScore(fit!!)
            assertTrue("$shape: $s", s.score >= 95)
            assertEquals("Spot on. Just like the guide.", s.note)
        }
        // Wherever it's drawn, at any size, and a little turned.
        val star = trace(DrawShape.Star, 60f, Offset(90f, 500f)).map { (it - Offset(90f, 500f)).let { d -> Offset(90f, 500f) + Offset(d.x * cos(0.15f) - d.y * sin(0.15f), d.x * sin(0.15f) + d.y * cos(0.15f)) } }
        assertTrue(shapeScore(measureShape(star, DrawShape.Star)!!).score >= 92)
        val upright = measureShape(trace(DrawShape.Oval, turned = true), DrawShape.Oval, turned = true)!!
        assertTrue(shapeScore(upright).score >= 95)
    }

    @Test fun `a circle is no star`() {
        val circle = trace(DrawShape.Circle)
        assertTrue(shapeScore(measureShape(circle, DrawShape.Circle)!!).score >= 95)
        for (shape in listOf(DrawShape.Star, DrawShape.Triangle, DrawShape.Infinity, DrawShape.Spiral)) {
            val s = shapeScore(measureShape(circle, shape)!!)
            assertTrue("circle as $shape: $s", s.score < 35)
        }
        val square = shapeScore(measureShape(circle, DrawShape.Square)!!)
        assertTrue("circle as square: $square", square.score < 50)
        assertEquals("Sharpen the corners.", square.note)
        val star = shapeScore(measureShape(circle, DrawShape.Star)!!)
        assertEquals("Make the points sharper.", star.note)
        // And a sideways oval is no upright one.
        assertTrue(shapeScore(measureShape(trace(DrawShape.Oval), DrawShape.Oval, turned = true)!!).score < 50)
    }

    @Test fun `each shape's note names its flaw`() {
        val wide = shapeScore(measureShape(trace(DrawShape.Square, stretch = Offset(1.25f, 0.85f)), DrawShape.Square)!!)
        assertEquals("Too wide for its height.", wide.note)
        val tall = shapeScore(measureShape(trace(DrawShape.Heart, stretch = Offset(0.8f, 1.2f)), DrawShape.Heart)!!)
        assertEquals("Too tall for its width.", tall.note)
        val open = shapeScore(measureShape(trace(DrawShape.Triangle, over = 0f, cut = 0.12f), DrawShape.Triangle)!!)
        assertEquals("Left a gap. Join the ends.", open.note)
        assertTrue(open.score < 70)
        // A spiral is open: stopping at its end is no gap.
        val spiral = measureShape(trace(DrawShape.Spiral), DrawShape.Spiral)!!
        assertEquals(0f, spiral.gap)
        // Half a heart can't score like a whole one.
        assertTrue(shapeScore(measureShape(trace(DrawShape.Heart, over = 0f, cut = 0.5f), DrawShape.Heart)!!).score < 50)
        // Slips are not attempts.
        assertNull(measureShape(listOf(Offset(0f, 0f), Offset(10f, 10f), Offset(20f, 20f)), DrawShape.Star))
        assertNull(measureShape(trace(DrawShape.Star, scale = 15f), DrawShape.Star))
    }

    @Test fun `from the size level, the size counts too`() {
        val fit = measureShape(trace(DrawShape.Square, scale = 90f), DrawShape.Square)!!
        assertTrue(shapeScore(fit, guideScale = 90f).score >= 95)
        val big = shapeScore(fit, guideScale = 75f)
        assertEquals("20% too big for the guide.", big.note)
        assertTrue(big.score < 75)
        assertEquals("25% too small for the guide.", shapeScore(fit, guideScale = 120f).note)
        val ring = measureRing(List(400) { i -> val t = TAU * i / 399; Offset(200f + 90f * cos(t), 200f + 90f * sin(t)) })!!
        assertEquals(ringScore(ring).score, ringScore(ring, guideRadius = 90f).score)
        assertEquals("20% too small for the guide.", ringScore(ring, guideRadius = 112.5f).note)
    }

    @Test fun `promise ring is a ring at level 1, and gets stricter and more varied up the levels`() {
        for (seed in 0L until 40L) {
            val play = RingPlay(seed)
            assertEquals(DrawShape.Circle, play.shape)
            assertFalse(play.hasGuide)
            assertEquals(1f, play.k)
        }
        assertEquals("Draw a circle. Anywhere on the page. One go.", RingPlay(1).hint)
        assertEquals(listOf(DrawShape.Circle), DrawShape.pool(Difficulty.Start))
        assertEquals(DrawShape.entries, DrawShape.pool(top))
        val shapes = (0L until 200L).map { RingPlay(it, top).shape }.toSet()
        assertEquals(DrawShape.entries.toSet(), shapes)
        val hard = RingPlay(3, top)
        assertTrue(hard.guideHides && hard.sizeCounts && hard.hasGuide)
        assertTrue(hard.hint, hard.hint.contains("hides") && hard.hint.contains("size"))
        // The same drawing scores lower at the top of the ramp.
        val fit = measureShape(trace(DrawShape.Star, stretch = Offset(1.1f, 0.95f)), DrawShape.Star)!!
        assertTrue(shapeScore(fit, hard.k).score < shapeScore(fit).score - 5)
        val oval = measureRing(List(400) { i -> val t = TAU * i / 399; Offset(200f + 100f * cos(t), 200f + 80f * sin(t)) })!!
        assertTrue(ringScore(oval, hard.k).score < ringScore(oval).score - 5)
    }
}
