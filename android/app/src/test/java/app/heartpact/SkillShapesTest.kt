package app.heartpact

import androidx.compose.ui.geometry.Offset
import app.heartpact.ui.play.skills.Difficulty
import app.heartpact.ui.play.skills.HalfShape
import app.heartpact.ui.play.skills.KissMotion
import app.heartpact.ui.play.skills.Lop
import app.heartpact.ui.play.skills.Treat
import app.heartpact.ui.play.skills.TreatKind
import app.heartpact.ui.play.skills.area
import app.heartpact.ui.play.skills.centroid
import app.heartpact.ui.play.skills.contains
import app.heartpact.ui.play.skills.cut
import app.heartpact.ui.play.skills.distanceToPolyline
import app.heartpact.ui.play.skills.fairShareScore
import app.heartpact.ui.play.skills.isSimple
import app.heartpact.ui.play.skills.kissScore
import app.heartpact.ui.play.skills.otherHalfScore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shapes the skill games play on, and how the levels change them: every treat and half is sound to play, new
 * ones come in level by level, the scoring tightens, and level 1 (what duels play) is exactly as it was.
 */
class SkillShapesTest {
    private val top = Difficulty(Difficulty.RAMP)

    /** Treats at [level] for many seeds, until every kind in play has turned up a few times. */
    private fun treats(level: Int, odd: Boolean, seeds: LongRange = 0L until 600L) = seeds.map { Treat.of(it, odd, Difficulty(level)) }

    @Test fun `level 1 has the four treats and five halves there always were`() {
        assertEquals(listOf(TreatKind.Cookie, TreatKind.Chocolate, TreatKind.Pizza, TreatKind.Jelly), Treat.pool(1))
        assertEquals(listOf("heart", "vase", "wave", "drop", "bell"), HalfShape.pool(1))
        assertEquals(setOf(TreatKind.Cookie, TreatKind.Chocolate, TreatKind.Pizza, TreatKind.Jelly), treats(1, false).map { it.kind }.toSet())
    }

    /**
     * Fingerprints of the level 1 shapes, taken from the games before levels existed: duels play level 1, so a
     * partner on an older version of the app still gets the very same shape from the same seed.
     */
    @Test fun `level 1 shapes are exactly the shapes from before levels`() {
        assertEquals(LEVEL_ONE_TREATS, fingerprint((0L until 200L).map { Treat.of(it).outline }), 1e-3)
        assertEquals(LEVEL_ONE_KISSES, fingerprint((0L until 200L).map { Treat.of(it * 31 + 7, odd = true).outline }), 1e-3)
        assertEquals(LEVEL_ONE_HALVES, fingerprint((0L until 200L).map { HalfShape.of(it).half }), 1e-3)
        // The default is level 1.
        for (seed in 0L until 20L) {
            assertEquals(Treat.of(seed).outline, Treat.of(seed, difficulty = Difficulty.Start).outline)
            assertEquals(HalfShape.of(seed).half, HalfShape.of(seed, Difficulty.Start).half)
        }
    }

    @Test fun `the treats come in level by level, and from level 12 every one is in play`() {
        var last = Treat.pool(1)
        for (level in 2..Difficulty.RAMP) {
            val now = Treat.pool(level)
            assertTrue("level $level keeps what level ${level - 1} had", now.containsAll(last))
            last = now
        }
        assertTrue(Treat.pool(5).size in 8..10)
        assertEquals(TreatKind.entries, Treat.pool(12))
        assertEquals(TreatKind.entries.toSet(), treats(12, false).map { it.kind }.toSet())
        // Kiss the center never gets the donut: its middle is in the hole.
        assertFalse(TreatKind.Donut in Treat.pool(20, odd = true))
        assertEquals(TreatKind.entries.toSet() - TreatKind.Donut, treats(12, true).map { it.kind }.toSet())
        assertTrue("at least 8 new treats", TreatKind.entries.size >= 12)
    }

    @Test fun `the halves come in level by level, and from level 12 every one is in play`() {
        var last = HalfShape.pool(1)
        for (level in 2..Difficulty.RAMP) {
            val now = HalfShape.pool(level)
            assertTrue(now.containsAll(last))
            last = now
        }
        assertEquals(HalfShape.all.map { it.first }, HalfShape.pool(12))
        assertTrue("at least 10 new halves", HalfShape.all.size >= 15)
        val seen = (0L until 600L).map { HalfShape.of(it, Difficulty(12)).name }.toSet()
        assertEquals(HalfShape.all.map { it.first }.toSet(), seen)
    }

    @Test fun `every treat is sound to share at every level`() {
        for (level in listOf(1, 2, 5, 8, 12, 16, 20)) for (t in treats(level, false, 0L until 300L)) {
            val name = "${t.kind} at level $level"
            val a = area(t.outline)
            assertTrue("$name area $a", a in 0.1f..0.7f)
            assertTrue("$name stays on the board", t.outline.all { it.x in -0.06f..1.06f && it.y in -0.06f..1.06f })
            if (t.kind.from > 1) assertTrue("$name has no crossing edges", isSimple(t.outline))
            // A cut through the middle splits it into two pieces that add up.
            val c = centroid(t.outline)
            val (p, q) = cut(t.outline, c + Offset(0.03f, -1f), c + Offset(-0.03f, 1f))
            assertEquals(name, a, area(p) + area(q), a * 1e-3f)
            assertTrue(name, area(p) > 0.02f && area(q) > 0.02f)
        }
    }

    @Test fun `every kiss shape holds its centre well inside it`() {
        for (level in listOf(1, 3, 6, 8, 10, 12, 15, 20)) for ((i, t) in treats(level, true, 0L until 300L).withIndex()) {
            val c = centroid(t.outline)
            val name = "${t.kind} at level $level (seed $i)"
            assertTrue(name, contains(t.outline, c))
            assertTrue(name, distanceToPolyline(c, t.outline + t.outline.first()) > 0.02f)
            assertTrue(name, area(t.outline) in 0.1f..0.7f)
        }
    }

    @Test fun `a donut has a real hole`() {
        val donut = (0L until 400L).map { Treat.of(it, difficulty = Difficulty(7)) }.first { it.kind == TreatKind.Donut }
        val o = donut.outline
        val minX = o.minOf { it.x }; val maxX = o.maxOf { it.x }
        val r = (maxX - minX) / 2
        // Less than the whole disc, and the middle (in the hole) is not on the donut.
        assertTrue(area(o) < Math.PI.toFloat() * r * r * 0.95f)
        assertFalse(contains(o, centroid(o)))
        // Cut straight through the hole: the halves still add up.
        val c = centroid(o)
        val (p, q) = cut(o, c + Offset(0f, -1f), c + Offset(0f, 1f))
        assertEquals(area(o), area(p) + area(q), area(o) * 1e-3f)
    }

    @Test fun `every half runs from the fold to the fold, 1 tall, and doesn't cross itself`() {
        for (level in listOf(1, 4, 8, 12, 20)) for (seed in 0L until 300L) {
            val s = HalfShape.of(seed, Difficulty(level))
            val h = s.half
            val name = "${s.name} at level $level ($seed)"
            assertEquals(name, 0f, h.first().x, 1e-6f)
            assertEquals(name, 0f, h.last().x, 1e-6f)
            assertTrue(name, h.all { it.x >= -1e-6f && it.x <= 0.58f && it.y in -0.001f..1.001f })
            assertEquals(name, 1f, h.maxOf { it.y } - h.minOf { it.y }, 1e-4f)
            assertTrue(name, isSimple(h))
            assertTrue(name, area(h) > 0.05f)
            // Drawn true, it scores top marks at any level.
            assertTrue(name, otherHalfScore(listOf(h), h, Difficulty(level)).score >= 97)
        }
    }

    @Test fun `treats get more lopsided as the levels rise`() {
        val one = Lop.of(Difficulty.Start, odd = false)
        assertEquals(1f, one.tilt)
        assertEquals(1f, one.bite)
        assertEquals(0, one.bites)
        val hard = Lop.of(top, odd = false)
        assertTrue(hard.tilt > 1.5f && hard.bite > 1.3f && hard.bites == 2)
        // Fair share's shapes lose more to bites up high: the same kinds, smaller on average.
        fun meanArea(level: Int, kind: TreatKind) = (0L until 800L).map { Treat.of(it, difficulty = Difficulty(level)) }.filter { it.kind == kind }.map { area(it.outline) }.average()
        for (kind in listOf(TreatKind.Cookie, TreatKind.Chocolate)) assertTrue("$kind", meanArea(20, kind) < meanArea(1, kind) * 0.95)
    }

    @Test fun `scoring tightens with the level and level 1 is unchanged`() {
        // Level 1, as always.
        assertEquals(70, fairShareScore(45f, false).score)
        assertEquals(fairShareScore(45f, false), fairShareScore(45f, false, Difficulty.Start))
        val c = Offset(100f, 100f)
        assertEquals(kissScore(c + Offset(-10f, 0f), c, 200f), kissScore(c + Offset(-10f, 0f), c, 200f, Difficulty.Start))
        assertTrue(kissScore(c + Offset(-10f, 0f), c, 200f).score in 68..76)
        val truth = HalfShape.of(3).half
        val wobbly = listOf(truth.map { Offset(it.x * 1.08f + 0.01f, it.y) })
        assertEquals(otherHalfScore(wobbly, truth), otherHalfScore(wobbly, truth, Difficulty.Start))
        // Higher up the same miss scores lower, and perfect is still perfect.
        var fair = 101
        var kiss = 101
        var half = 101
        for (level in 1..Difficulty.RAMP step 3) {
            val d = Difficulty(level)
            val f = fairShareScore(45f, false, d).score
            val k = kissScore(c + Offset(-10f, 0f), c, 200f, d).score
            val h = otherHalfScore(wobbly, truth, d).score
            assertTrue("fair at $level: $f", f < fair)
            assertTrue("kiss at $level: $k", k < kiss)
            assertTrue("half at $level: $h", h < half)
            fair = f; kiss = k; half = h
            assertEquals(100, fairShareScore(50f, true, d).score)
            assertEquals(100, kissScore(c, c, 200f, d).score)
        }
        assertTrue("fair at the top: $fair", fair in 55..65)
        // The notes don't change with the level.
        assertEquals(fairShareScore(45f, true).note, fairShareScore(45f, true, top).note)
    }

    @Test fun `kiss shapes keep still, then turn, then drift, then fade`() {
        for (level in 1..7) assertFalse(KissMotion.of(5, Difficulty(level)).moves)
        val turning = KissMotion.of(5, Difficulty(8))
        assertNotEquals(0f, turning.angle(1000))
        assertEquals(Offset.Zero, turning.offset(3000))
        assertNull(turning.fadeAfterMs)
        assertTrue(kotlin.math.abs(KissMotion.of(5, top).angle(1000)) > kotlin.math.abs(turning.angle(1000)))
        val drifting = KissMotion.of(5, Difficulty(13))
        assertEquals(Offset.Zero, drifting.offset(0))
        assertTrue(drifting.offset(1200).getDistance() > 0.005f)
        assertTrue(drifting.offset(1200).getDistance() < 0.13f)
        assertNull(drifting.fadeAfterMs)
        val fading = KissMotion.of(5, Difficulty(15))
        assertEquals(1f, fading.alpha(1400), 0f)
        assertEquals(0f, fading.alpha(2500), 0f)
        assertTrue(fading.gone(2500))
        // Once faded it stays where it was last seen.
        assertEquals(fading.offset(2000), fading.offset(9000))
        assertEquals(fading.angle(2000), fading.angle(9000))
    }

    private fun fingerprint(shapes: List<List<Offset>>): Double = shapes.withIndex().sumOf { (i, s) ->
        s.withIndex().sumOf { (j, p) -> (p.x * 1.7 + p.y * 0.3) * (1 + (i * 7 + j) % 13) }
    }

    private companion object {
        const val LEVEL_ONE_TREATS = 528154.5018539288
        const val LEVEL_ONE_KISSES = 522728.76983048546
        const val LEVEL_ONE_HALVES = 82122.05374421133
    }
}
