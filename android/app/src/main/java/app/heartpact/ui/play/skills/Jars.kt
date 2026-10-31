package app.heartpact.ui.play.skills

import androidx.compose.ui.geometry.Offset
import app.heartpact.i18n.tr
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

/*
 * The jars Fill the heart pours into. Each is an outline in jar units: x 0 to 100 across, y down from the top to the
 * bottom at about 100, open to the tap above at x 50. The pour fills by area, so a jar with a waist, a neck or a belly
 * fills unevenly: the love races up the narrow parts and crawls across the wide ones. Level 1 pours into the simple,
 * even jars; the odd ones join as the levels rise.
 */

/** A jar, the level it first turns up at, and whether its narrow parts make the rise uneven enough to warn about. */
internal enum class JarKind(val unlock: Int, val odd: Boolean = false) {
    Heart(1), Mug(1), Tumbler(1),
    Gift(3), Envelope(4), House(5),
    Bottle(6, odd = true), Star(7, odd = true), Drop(8, odd = true), Balloon(9, odd = true), Cloud(10, odd = true),
    Flask(11, odd = true), WaistCup(12, odd = true), Hourglass(14, odd = true), Vase(16, odd = true);

    /** A name to read out, for TalkBack. */
    val label: String get() = when (this) {
        Heart -> tr("a heart")
        Mug -> tr("a mug")
        Tumbler -> tr("a tumbler")
        Gift -> tr("a gift box")
        Envelope -> tr("a love letter")
        House -> tr("a little house")
        Bottle -> tr("a bottle")
        Star -> tr("a star")
        Drop -> tr("a drop")
        Balloon -> tr("a balloon")
        Cloud -> tr("a cloud")
        Flask -> tr("a round flask")
        WaistCup -> tr("a cup with a waist")
        Hourglass -> tr("an hourglass")
        Vase -> tr("a vase")
    }

    /** Pointed or round at the bottom, so it sits in a little cradle rather than on a shelf. */
    val cradle: Boolean get() = this == Heart || this == Drop || this == Flask
}

/** A jar's outline (a closed polygon, corners in order) and what it is. */
internal class Jar(val kind: JarKind, val outline: List<Offset>) {
    val top: Float = outline.minOf { it.y }
    val bottom: Float = outline.maxOf { it.y }

    companion object {
        /** The jars there are to pour into at [difficulty]: the even ones at level 1, more (and odder) as it rises. */
        fun pool(difficulty: Difficulty): List<JarKind> = JarKind.entries.filter { difficulty.from(it.unlock) }

        /**
         * The jar for a round. A jar that has just turned up at this level comes up half the time, so it gets seen;
         * otherwise any jar from the pool.
         */
        fun pick(rnd: Random, difficulty: Difficulty): JarKind {
            val pool = pool(difficulty)
            val fresh = pool.filter { it.unlock == difficulty.level && it.unlock > 1 }
            val roll = rnd.nextFloat()
            return if (fresh.isNotEmpty() && roll < 0.5f) fresh[rnd.nextInt(fresh.size)] else pool[rnd.nextInt(pool.size)]
        }

        fun of(kind: JarKind): Jar = Jar(kind, outlineOf(kind))

        fun outlineOf(kind: JarKind): List<Offset> = when (kind) {
            JarKind.Heart -> heartOutline(240)
            JarKind.Mug -> rounded(listOf(o(24f, 22f) to 2f, o(76f, 22f) to 2f, o(76f, 100f) to 11f, o(24f, 100f) to 11f))
            JarKind.Tumbler -> rounded(listOf(o(15f, 14f) to 2f, o(85f, 14f) to 2f, o(74f, 100f) to 9f, o(26f, 100f) to 9f))
            JarKind.Gift -> rounded(listOf(
                o(12f, 24f) to 3f, o(88f, 24f) to 3f, o(88f, 38f) to 1.5f, o(81f, 38f) to 0f,
                o(81f, 100f) to 4f, o(19f, 100f) to 4f, o(19f, 38f) to 0f, o(12f, 38f) to 1.5f,
            ))
            JarKind.Envelope -> rounded(listOf(o(6f, 42f) to 5f, o(94f, 42f) to 5f, o(94f, 100f) to 5f, o(6f, 100f) to 5f))
            JarKind.House -> rounded(listOf(
                o(50f, 12f) to 4f, o(91f, 55f) to 2.5f, o(81f, 55f) to 0f, o(81f, 100f) to 3f,
                o(19f, 100f) to 3f, o(19f, 55f) to 0f, o(9f, 55f) to 2.5f,
            ))
            JarKind.Bottle -> rounded(listOf(
                o(41f, 5f) to 1.5f, o(59f, 5f) to 1.5f, o(59f, 11f) to 0.5f, o(56f, 11f) to 0f, o(56f, 28f) to 4f,
                o(75f, 28f) to 14f, o(75f, 100f) to 7f, o(25f, 100f) to 7f, o(25f, 28f) to 14f,
                o(44f, 28f) to 4f, o(44f, 11f) to 0f, o(41f, 11f) to 0.5f,
            ))
            JarKind.Star -> {
                val c = o(50f, 58.5f)
                rounded(List(10) { i ->
                    val r = if (i % 2 == 0) 50f else 21f
                    val t = (-PI / 2 + PI / 5 * i).toFloat()
                    (c + Offset(cos(t), sin(t)) * r) to (if (i % 2 == 0) 3.5f else 2f)
                })
            }
            JarKind.Drop -> {
                // A point at the top, a round bottom: two straight sides tangent to a circle, like the app's heart.
                val c = o(50f, 66f)
                val r = 34f
                val tip = o(50f, 5f)
                val a = asin(r / (c.y - tip.y))
                val from = -PI / 2 + (PI / 2 - a)
                val to = -PI / 2 - (PI / 2 - a) + 2 * PI
                listOf(tip) + arc(c, r, from.toFloat(), to.toFloat(), 90)
            }
            JarKind.Balloon -> {
                val c = o(50f, 45f)
                val rx = 38f
                val ry = 40f
                val d = asin(5f / rx)
                val pts = ArrayList<Offset>()
                val n = 120
                for (i in 0..n) {
                    val t = PI / 2 + d + (2 * PI - 2 * d) * i / n
                    pts += c + Offset((rx * cos(t)).toFloat(), (ry * sin(t)).toFloat())
                }
                // The knot.
                pts += o(56.5f, 94f)
                pts += o(43.5f, 94f)
                pts
            }
            JarKind.Cloud -> cloud()
            JarKind.Flask -> {
                val c = o(50f, 68f)
                val r = 32f
                val neck = 7f
                val joinY = c.y - sqrt(r * r - neck * neck)
                val from = atan2(joinY - c.y, neck)
                val to = atan2(joinY - c.y, -neck) + 2 * PI.toFloat()
                listOf(o(40f, 6f), o(60f, 6f), o(60f, 11f), o(50f + neck, 11f)) + arc(c, r, from, to, 100) + listOf(o(50f - neck, 11f), o(40f, 11f))
            }
            JarKind.WaistCup -> profile(listOf(8f to 36f, 20f to 33f, 38f to 21f, 54f to 12f, 70f to 18f, 86f to 27f, 100f to 30f))
            JarKind.Hourglass -> profile(listOf(10f to 31f, 18f to 33f, 32f to 29f, 46f to 14f, 55f to 5f, 64f to 14f, 78f to 29f, 92f to 33f, 100f to 31f))
            JarKind.Vase -> profile(listOf(8f to 16f, 13f to 11f, 24f to 8.5f, 40f to 18f, 62f to 32f, 80f to 27f, 92f to 16f, 97f to 15f, 100f to 17f))
        }

        private fun o(x: Float, y: Float) = Offset(x, y)

        /** Points on a circle from angle [from] to [to] (radians, screen axes), both included. */
        private fun arc(c: Offset, r: Float, from: Float, to: Float, n: Int): List<Offset> = List(n + 1) { i ->
            val t = from + (to - from) * i / n
            c + Offset(cos(t), sin(t)) * r
        }

        /**
         * A polygon with each corner rounded off to its own radius (0 keeps it sharp): the arc meets both edges, so
         * the jar keeps its size and only loses its sharp tips.
         */
        fun rounded(corners: List<Pair<Offset, Float>>): List<Offset> {
            val out = ArrayList<Offset>()
            for (i in corners.indices) {
                val (v, r) = corners[i]
                val a = corners[(i - 1 + corners.size) % corners.size].first
                val b = corners[(i + 1) % corners.size].first
                if (r <= 0f) { out += v; continue }
                val u = (a - v) / (a - v).getDistance()
                val w = (b - v) / (b - v).getDistance()
                val half = acos((u.x * w.x + u.y * w.y).coerceIn(-1f, 1f)) / 2f
                if (half < 1e-3f || half > PI.toFloat() / 2 - 1e-3f) { out += v; continue }
                val t = r / tan(half)
                val p1 = v + u * t
                val p2 = v + w * t
                val bis = (u + w) / (u + w).getDistance()
                val c = v + bis * (r / sin(half))
                val s = atan2(p1.y - c.y, p1.x - c.x)
                // The short way round.
                var sweep = atan2(p2.y - c.y, p2.x - c.x) - s
                while (sweep > PI) sweep -= TAU
                while (sweep < -PI) sweep += TAU
                val n = 8
                for (k in 0..n) {
                    val ang = s + sweep * k / n
                    out += c + Offset(cos(ang), sin(ang)) * r
                }
            }
            return out
        }

        /**
         * A symmetric jar from its half-widths down the height, (y, half-width) from the top to the bottom, smoothed
         * through those points: open across the top, flat across the bottom.
         */
        fun profile(points: List<Pair<Float, Float>>): List<Offset> {
            val right = ArrayList<Offset>()
            val p = points.map { Offset(it.second, it.first) }
            for (i in 0 until p.size - 1) {
                val p0 = p[(i - 1).coerceAtLeast(0)]
                val p1 = p[i]
                val p2 = p[i + 1]
                val p3 = p[(i + 2).coerceAtMost(p.size - 1)]
                val n = 16
                for (k in 0 until n) {
                    val t = k / n.toFloat()
                    val t2 = t * t
                    val t3 = t2 * t
                    val q = (p1 * 2f + (p2 - p0) * t + (p0 * 2f - p1 * 5f + p2 * 4f - p3) * t2 + (p1 * 3f - p0 - p2 * 3f + p3) * t3) * 0.5f
                    right += Offset(50f + q.x, q.y)
                }
            }
            right += Offset(50f + p.last().x, p.last().y)
            return right + right.reversed().map { Offset(100f - it.x, it.y) }
        }

        /** A cloud: round puffs over a flat bottom, traced as the outline of their union, slice by slice. */
        private fun cloud(): List<Offset> {
            val puffs = listOf(Triple(25f, 76f, 15f), Triple(42f, 61f, 21f), Triple(63f, 56f, 23f), Triple(80f, 72f, 15f))
            // The base: a bar with round ends, from x 14 to 86, y 70 to 100.
            val baseTop = 72f
            val baseBottom = 100f
            val baseR = 13f
            fun base(x: Float): Pair<Float, Float>? {
                if (x < 14f || x > 86f) return null
                val cx = x.coerceIn(14f + baseR, 86f - baseR)
                val dx = x - cx
                if (dx * dx > baseR * baseR) return null
                val dy = baseR - sqrt(baseR * baseR - dx * dx)
                return (baseTop + dy) to (baseBottom - dy)
            }
            val xs = puffs.minOf { it.first - it.third }
            val xe = puffs.maxOf { it.first + it.third }
            val top = ArrayList<Offset>()
            val bottom = ArrayList<Offset>()
            val n = 160
            for (i in 0..n) {
                val x = xs + (xe - xs) * i / n
                var lo = Float.MAX_VALUE
                var hi = -Float.MAX_VALUE
                for ((cx, cy, r) in puffs) {
                    val dx = x - cx
                    if (dx * dx >= r * r) continue
                    val h = sqrt(r * r - dx * dx)
                    lo = minOf(lo, cy - h)
                    hi = maxOf(hi, cy + h)
                }
                base(x)?.let { (t, b) -> lo = minOf(lo, t); hi = maxOf(hi, b) }
                if (lo == Float.MAX_VALUE) continue
                top += Offset(x, lo)
                bottom += Offset(x, hi)
            }
            return top + bottom.reversed()
        }

        /** The left and right edges inside [poly] along the line at [y]: where a level line across it should reach. */
        fun spanAt(poly: List<Offset>, y: Float): Pair<Float, Float> {
            var lo = Float.MAX_VALUE
            var hi = -Float.MAX_VALUE
            for (i in poly.indices) {
                val a = poly[i]
                val b = poly[(i + 1) % poly.size]
                if ((a.y > y) != (b.y > y)) {
                    val x = a.x + (y - a.y) / (b.y - a.y) * (b.x - a.x)
                    lo = minOf(lo, x)
                    hi = maxOf(hi, x)
                }
            }
            return if (lo > hi) 50f to 50f else lo to hi
        }
    }
}
