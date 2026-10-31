package app.heartpact.ui.play.skills

import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/*
 * The shapes the skill rounds are played on, all picked from the round's seed so both partners in a duel get the
 * same one. Pure data in unit boxes; the games scale them to the screen and draw them. The first few of each are
 * here; the rest, which come in as the levels rise, are in Treats.kt and Halves.kt.
 */

/**
 * What there is to share, in the order they come in as the levels rise: [from] is the first level a treat turns up
 * at. Level 1 has the first four only, exactly as before levels, so a duel shapes up the same on every phone.
 */
enum class TreatKind(val from: Int) {
    Cookie(1), Chocolate(1), Pizza(1), Jelly(1),
    Macaron(2), Toast(2),
    Watermelon(3), CandyHeart(3),
    Cheese(4), Waffle(4),
    CakeSlice(6), Cupcake(6),
    Donut(7), Lollipop(7),
    IceCream(8), JellyBean(8),
    Croissant(10),
    Gummy(12),
    ;

    /** Kiss the center needs a middle that's on the treat: a donut's is in its hole. */
    val hasMiddle: Boolean get() = this != Donut
}

/**
 * A treat in the unit box (0 to 1 both ways): its [outline] and what's drawn on it, which travels with each piece
 * when it's cut. [lines] are grooves (a chocolate bar's squares), [dots] toppings and sprinkles (centre, radius).
 * A treat with a hole (a donut) runs round its rim, across to the hole and back round that the other way, so its
 * area, centre and cuts come out right and it draws with the hole open.
 */
class Treat(
    val kind: TreatKind,
    val outline: List<Offset>,
    /** A second outline drawn on top: the icing of a cookie, the cheese of a pizza. */
    val inner: List<Offset>?,
    val lines: List<Pair<Offset, Offset>>,
    val dots: List<Pair<Offset, Float>>,
    /** Which of the treat's colourways (a macaron's shell, a donut's icing); what it picks is up to the art. */
    val colour: Int,
    /** More areas drawn on top, after [inner]: a watermelon's flesh, a pat of butter, a lollipop's stick. */
    val fills: List<List<Offset>> = emptyList(),
    /** Open lines drawn on top: a lollipop's swirl, a croissant's folds, a macaron's ruffled feet. */
    val curves: List<List<Offset>> = emptyList(),
) {
    companion object {
        /**
         * A treat for a round. [odd] makes it lopsided (Kiss the center wants no obvious middle). [difficulty] widens
         * the choice of treats and, for sharing, makes them more lopsided (more tilt, bigger and more bites); level 1
         * gives exactly the treats there were before levels.
         */
        fun of(seed: Long, odd: Boolean = false, difficulty: Difficulty = Difficulty.Start): Treat {
            var s = seed
            repeat(12) {
                val t = build(s, odd, difficulty)
                if (t.isPlayable(odd)) return t
                // Another treat from the same seed, the same on every phone.
                s = s * 6364136223846793005L + 1442695040888963407L
            }
            return jelly(Random(seed), odd, Lop.of(difficulty, odd))
        }

        /** The treats in play at [level]; [odd] (Kiss the center) leaves out those whose middle isn't on them. */
        fun pool(level: Int, odd: Boolean = false): List<TreatKind> = TreatKind.entries.filter { it.from <= level && (!odd || it.hasMiddle) }

        /** Sane to play: a decent size, on the board, and (for Kiss the center, [odd]) its middle well inside it. */
        fun playable(t: Treat, odd: Boolean): Boolean = t.isPlayable(odd)

        private fun Treat.isPlayable(odd: Boolean): Boolean {
            val a = area(outline)
            if (a < 0.1f || a > 0.7f) return false
            if (outline.any { it.x < -0.06f || it.x > 1.06f || it.y < -0.06f || it.y > 1.06f }) return false
            if (!odd) return true
            val c = centroid(outline)
            return contains(outline, c) && distanceToPolyline(c, outline + outline.first()) > 0.02f
        }

        private fun build(seed: Long, odd: Boolean, difficulty: Difficulty): Treat {
            val rnd = Random(seed)
            val pool = pool(difficulty.level, odd)
            val kind = pool[rnd.nextInt(pool.size)]
            val lop = Lop.of(difficulty, odd)
            var t = when (kind) {
                TreatKind.Cookie -> cookie(rnd, odd, lop)
                TreatKind.Chocolate -> chocolate(rnd, odd, lop)
                TreatKind.Pizza -> pizza(rnd, odd, lop)
                TreatKind.Jelly -> jelly(rnd, odd, lop)
                else -> newTreat(kind, rnd, odd, lop)
            }
            // More bites as the levels rise, from a stream of their own so level 1 treats stay exactly as they were.
            if (lop.bites > 0) {
                val more = Random(seed xor 0x5DEECE66DL)
                repeat(lop.bites) { t = t.bitten(more, more.between(0.08f, 0.12f) * lop.bite) }
            }
            return t
        }

        private val C = Offset(0.5f, 0.5f)

        private fun Random.between(a: Float, b: Float) = a + nextFloat() * (b - a)

        private fun cookie(rnd: Random, odd: Boolean, lop: Lop): Treat {
            val tilt = rnd.between(-0.35f, 0.35f) * lop.tilt
            val heart = fitOutline(heartOutline(200), C, 0.8f).map { it.rotateAround(C, tilt) }
            var outline = densify(heart, 0.006f)
            val icingC = centroid(heart)
            var icing = densify(heart.map { icingC + (it - icingC) * 0.84f }, 0.006f)
            // A bite out of one side, so the middle isn't just the fold of the heart.
            val at = outline[rnd.nextInt(outline.size)]
            val r = (if (odd) rnd.between(0.12f, 0.16f) else rnd.between(0.07f, 0.11f)) * lop.bite
            val c = at + (at - C) * 0.15f
            outline = bite(outline, c, r)
            icing = bite(icing, c, r + 0.035f)
            val dots = List(16) {
                val p = Offset(rnd.between(0.2f, 0.8f), rnd.between(0.2f, 0.8f))
                p to rnd.between(0.008f, 0.013f)
            }.filter { contains(icing, it.first) }
            return Treat(TreatKind.Cookie, outline, icing, emptyList(), dots, 0)
        }

        private fun chocolate(rnd: Random, odd: Boolean, lop: Lop): Treat {
            val tilt = rnd.between(-0.4f, 0.4f) * lop.tilt + if (rnd.nextBoolean()) (PI / 2).toFloat() else 0f
            val w = 0.48f
            val h = 0.72f
            val corner = 0.04f
            val rect = roundRect(w, h, corner).map { (it + C).rotateAround(C, tilt) }
            var outline = densify(rect, 0.006f)
            val lines = ArrayList<Pair<Offset, Offset>>()
            for (i in 1 until 3) {
                val x = -w / 2 + w * i / 3
                lines += (Offset(x, -h / 2) + C).rotateAround(C, tilt) to (Offset(x, h / 2) + C).rotateAround(C, tilt)
            }
            for (j in 1 until 4) {
                val y = -h / 2 + h * j / 4
                lines += (Offset(-w / 2, y) + C).rotateAround(C, tilt) to (Offset(w / 2, y) + C).rotateAround(C, tilt)
            }
            // A bite out of a corner.
            val corners = listOf(Offset(-w / 2, -h / 2), Offset(w / 2, -h / 2), Offset(w / 2, h / 2), Offset(-w / 2, h / 2))
            val at = (corners[rnd.nextInt(4)] + C).rotateAround(C, tilt)
            outline = bite(outline, at, (if (odd) rnd.between(0.2f, 0.26f) else rnd.between(0.12f, 0.17f)) * lop.bite)
            return Treat(TreatKind.Chocolate, outline, null, lines, emptyList(), 1)
        }

        private fun pizza(rnd: Random, odd: Boolean, lop: Lop): Treat {
            val r = 0.4f
            val missing = (if (odd) rnd.between(0.6f, 1.2f) else rnd.between(0.4f, 0.75f)) * lop.bite
            val start = rnd.between(0f, TAU)
            fun wedge(radius: Float): List<Offset> {
                val pts = arrayListOf(C)
                val n = 120
                for (i in 0..n) {
                    val t = start + missing + (TAU - missing) * i / n
                    pts += C + Offset(cos(t), sin(t)) * radius
                }
                return densify(pts, 0.006f)
            }
            val outline = wedge(r)
            val cheese = wedge(r * 0.84f).map { it + Offset(cos(start + missing / 2), sin(start + missing / 2)) * 0.012f }
            val dots = ArrayList<Pair<Offset, Float>>()
            var tries = 0
            while (dots.size < 7 && tries++ < 200) {
                val p = Offset(rnd.between(0.18f, 0.82f), rnd.between(0.18f, 0.82f))
                val s = rnd.between(0.04f, 0.055f)
                if (contains(cheese, p) && (p - C).getDistance() < r * 0.84f - s && dots.none { (it.first - p).getDistance() < it.second + s + 0.02f }) dots += p to s
            }
            return Treat(TreatKind.Pizza, outline, cheese, emptyList(), dots, 2)
        }

        private fun jelly(rnd: Random, odd: Boolean, lop: Lop): Treat {
            val k = (if (odd) 1.7f else 1f) * lop.bite
            val a2 = rnd.between(0.08f, 0.16f) * k
            val a3 = rnd.between(0.05f, 0.1f) * k
            val a5 = rnd.between(0.02f, 0.04f)
            val p2 = rnd.between(0f, TAU); val p3 = rnd.between(0f, TAU); val p5 = rnd.between(0f, TAU)
            val n = 220
            val outline = List(n) { i ->
                val t = TAU * i / n
                val rr = 0.33f * (1f + a2 * sin(2 * t + p2) + a3 * sin(3 * t + p3) + a5 * sin(5 * t + p5))
                C + Offset(cos(t), sin(t)) * rr
            }
            val fitted = fitOutline(outline, C, 0.8f)
            return Treat(TreatKind.Jelly, fitted, null, emptyList(), emptyList(), 3 + rnd.nextInt(3))
        }

        /** A rounded rectangle centred on the origin. */
        private fun roundRect(w: Float, h: Float, r: Float): List<Offset> {
            val pts = ArrayList<Offset>()
            val cs = listOf(Offset(w / 2 - r, -h / 2 + r) to -90f, Offset(w / 2 - r, h / 2 - r) to 0f, Offset(-w / 2 + r, h / 2 - r) to 90f, Offset(-w / 2 + r, -h / 2 + r) to 180f)
            for ((c, from) in cs) for (i in 0..6) {
                val t = Math.toRadians((from + 90.0 * i / 6)).toFloat()
                pts += c + Offset(cos(t), sin(t)) * r
            }
            return pts
        }
    }
}

/** A symmetric shape for Other half: its right half, from the fold at the top round to the fold at the bottom. */
class HalfShape(val name: String, val half: List<Offset>) {
    /** The half the player is given, mirrored to the left of the fold. */
    val given: List<Offset> get() = half.map { Offset(-it.x, it.y) }

    companion object {
        /**
         * The shapes in play at each level, in the order they come in: the first five are level 1's, as before
         * levels; the rest are in Halves.kt.
         */
        val all: List<Pair<String, Int>> = listOf(
            "heart" to 1, "vase" to 1, "wave" to 1, "drop" to 1, "bell" to 1,
            "leaf" to 2, "gem" to 2,
            "mushroom" to 3, "shield" to 3, "house" to 3,
            "apple" to 4, "star" to 4,
            "keyhole" to 5, "tulip" to 5,
            "crown" to 6, "tree" to 6,
            "lightbulb" to 8, "cat" to 8,
            "umbrella" to 10, "butterfly" to 10,
            "bow" to 12,
        )

        /** The names of the shapes in play at [level]. */
        fun pool(level: Int): List<String> = all.filter { it.second <= level }.map { it.first }

        /** Unit height (0 at the top, 1 at the bottom), the fold at x = 0. Level 1 gives the shapes there always were. */
        fun of(seed: Long, difficulty: Difficulty = Difficulty.Start): HalfShape {
            val rnd = Random(seed)
            fun between(a: Float, b: Float) = a + rnd.nextFloat() * (b - a)
            val pool = pool(difficulty.level)
            return when (val name = pool[rnd.nextInt(pool.size)]) {
                "heart" -> HalfShape("heart", heartHalf())
                "vase" -> {
                    // A vase: a rim, a neck, a round belly, a foot.
                    val neck = between(0.08f, 0.13f)
                    val belly = between(0.3f, 0.4f)
                    val rim = neck + between(0.04f, 0.09f)
                    val low = between(1.3f, 1.8f)
                    profile(rim, 0.14f) { y ->
                        val t = (y - 0.14f) / 0.86f
                        neck + (belly - neck) * sin(PI.toFloat() * t.pow(low)).coerceAtLeast(0f) + 0.12f * t * t * (1 - t)
                    }
                }
                "wave" -> {
                    // A wave that touches the fold at both ends, like a pair of lips or a curl of ribbon.
                    val a = between(0.2f, 0.28f)
                    val b = between(0.09f, 0.14f)
                    val f = if (rnd.nextBoolean()) 1f else 1.5f
                    val phase = between(0f, TAU)
                    HalfShape("wave", List(121) { i ->
                        val y = i / 120f
                        Offset(sin(PI.toFloat() * y) * (a + b * sin(TAU * f * y + phase)), y)
                    })
                }
                "drop" -> {
                    // A drop: a point at the top, round at the bottom.
                    val w = between(0.34f, 0.42f)
                    val p = between(0.8f, 1.2f)
                    HalfShape("drop", List(121) { i ->
                        val y = i / 120f
                        Offset(w * 2.1f * y.pow(p) * sqrt(1f - y) / (1f + 0.4f * y), y)
                    })
                }
                "bell" -> {
                    // A bell: a knob on top, sides that flare to a wide lip.
                    val knob = between(0.06f, 0.09f)
                    val lip = between(0.38f, 0.46f)
                    val half = ArrayList<Offset>()
                    for (i in 0..20) {
                        val t = PI.toFloat() * i / 20
                        half += Offset(knob * sin(t), knob - knob * cos(t))
                    }
                    for (i in 1..80) {
                        val t = i / 80f
                        val y = 2 * knob + (0.9f - 2 * knob) * t
                        half += Offset(0.1f + (lip - 0.14f) * t.pow(2.2f) + 0.06f * sin(PI.toFloat() * t), y)
                    }
                    half += Offset(lip, 1f)
                    half += Offset(0f, 1f)
                    HalfShape("bell", half)
                }
                else -> HalfShape(name, newHalf(name, rnd))
            }
        }

        /** A profile x(y) from a flat top of half-width [top] (at y [topY]) to a flat bottom at y 1. */
        private fun profile(top: Float, topY: Float, x: (Float) -> Float): HalfShape {
            val half = arrayListOf(Offset(0f, topY), Offset(top, topY))
            for (i in 1..100) {
                val y = topY + (1f - topY) * i / 100f
                half += Offset(x(y), y)
            }
            half += Offset(0f, 1f)
            val minY = half.minOf { it.y }
            val h = half.maxOf { it.y } - minY
            return HalfShape("vase", half.map { Offset(it.x / h, (it.y - minY) / h) })
        }

        /** The right half of the app's heart, from the dip between the lobes round to the tip. */
        private fun heartHalf(): List<Offset> {
            val whole = heartOutline(240)
            val dip = whole.indices.filter { whole[it].x in 49.9f..50.1f && whole[it].y < 50f }.first()
            val half = whole.subList(dip, whole.size) + Offset(50f, 100f)
            val minY = whole.minOf { it.y }
            val h = 100f - minY
            return half.map { Offset((it.x - 50f) / h, (it.y - minY) / h) }
        }
    }
}
