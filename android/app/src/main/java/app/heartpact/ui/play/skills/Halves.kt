package app.heartpact.ui.play.skills

import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/*
 * The Other half shapes that come in as the levels rise (Shapes.kt has level 1's, and the order they come in). Each
 * is the right half of a symmetric shape, from the fold (x = 0) at the top round to the fold at the bottom, 1 tall.
 */

/** The right half of the shape called [name], its sizes picked with [rnd]. */
internal fun newHalf(name: String, rnd: Random): List<Offset> {
    fun between(a: Float, b: Float) = a + rnd.nextFloat() * (b - a)
    val pts: List<Offset> = when (name) {
        "leaf" -> {
            // Pointed both ends, fullest a little below the middle, with a slight curl.
            val w = between(0.24f, 0.32f)
            val q = between(0.8f, 1.25f)
            val curl = between(-0.08f, 0.08f)
            List(121) { i ->
                val y = i / 120f
                Offset(w * sin(PI.toFloat() * y.pow(q)).coerceAtLeast(0f).pow(0.9f) * (1f + curl * sin(TAU * y)), y)
            }
        }
        "gem" -> {
            // A cut gem: a flat table, sloping crown facets, a band at the girdle, then down to the point.
            val table = between(0.16f, 0.24f)
            val girdle = between(0.22f, 0.3f)
            val wide = between(0.4f, 0.46f)
            listOf(Offset(0f, 0f), Offset(table, 0f), Offset(wide, girdle), Offset(wide, girdle + between(0.03f, 0.05f)), Offset(0f, 1f))
        }
        "mushroom" -> {
            // A round cap, its underside curling in to a sturdy stem.
            val cap = between(0.42f, 0.48f)
            val capH = between(0.38f, 0.46f)
            val stem = between(0.1f, 0.14f)
            List(31) { i -> val t = PI.toFloat() / 2 * i / 30; Offset(cap * sin(t), capH * (1 - cos(t))) } +
                bezier(Offset(cap, capH), Offset(cap - 0.02f, capH + 0.07f), Offset(stem + 0.12f, capH + 0.05f), Offset(stem, capH + 0.06f)) +
                bezier(Offset(stem, capH + 0.06f), Offset(stem - 0.01f, 0.75f), Offset(stem + 0.05f, 0.92f), Offset(stem + 0.02f, 1f)) +
                Offset(0f, 1f)
        }
        "shield" -> {
            val wide = between(0.36f, 0.42f)
            val notch = between(0f, 0.06f)
            val shoulder = between(0.4f, 0.55f)
            bezier(Offset(0f, notch), Offset(wide * 0.4f, notch), Offset(wide * 0.7f, 0f), Offset(wide, 0f)) +
                bezier(Offset(wide, shoulder), Offset(wide, 0.75f), Offset(wide * 0.5f, 0.9f), Offset(0f, 1f))
        }
        "house" -> {
            val eave = between(0.42f, 0.48f)
            val roof = between(0.38f, 0.46f)
            val wall = eave - between(0.06f, 0.1f)
            fun roofY(x: Float) = roof * x / eave
            val out = arrayListOf(Offset(0f, 0f))
            if (rnd.nextBoolean()) {
                // A chimney on the roof.
                val x0 = eave * 0.5f
                val x1 = eave * 0.68f
                val top = roofY(x0) - between(0.12f, 0.16f)
                out += listOf(Offset(x0, roofY(x0)), Offset(x0, top), Offset(x1, top), Offset(x1, roofY(x1)))
            }
            out + listOf(Offset(eave, roof), Offset(eave, roof + 0.035f), Offset(wall, roof + 0.035f), Offset(wall, 1f), Offset(0f, 1f))
        }
        "apple" -> {
            val s = between(0.92f, 1.08f)
            (listOf(Offset(0f, 0f), Offset(0.035f, 0f), Offset(0.035f, 0.15f)) +
                bezier(Offset(0.035f, 0.15f), Offset(0.07f, 0.07f), Offset(0.22f, 0.05f), Offset(0.33f, 0.11f)) +
                bezier(Offset(0.33f, 0.11f), Offset(0.46f, 0.19f), Offset(0.49f, 0.42f), Offset(0.45f, 0.62f)) +
                bezier(Offset(0.45f, 0.62f), Offset(0.41f, 0.86f), Offset(0.28f, 1f), Offset(0.16f, 0.985f)) +
                bezier(Offset(0.16f, 0.985f), Offset(0.1f, 0.98f), Offset(0.05f, 0.95f), Offset(0f, 0.95f))).map { Offset(it.x * s, it.y) }
        }
        "star" -> {
            // Five points, one at the top; slimmed a little so the wide points fit beside the fold.
            val k = between(0.4f, 0.5f)
            listOf(-90f, -54f, -18f, 18f, 54f, 90f).mapIndexed { i, d ->
                val a = d * PI.toFloat() / 180f
                val r = if (i % 2 == 0) 1f else k
                Offset(cos(a) * r * 0.82f, sin(a) * r)
            }
        }
        "keyhole" -> {
            val r = between(0.24f, 0.28f)
            val slot = between(0.08f, 0.11f)
            val foot = between(0.17f, 0.22f)
            val a1 = atan2(sqrt(r * r - slot * slot), slot)
            arcPoints(Offset(0f, r), r, r, -PI.toFloat() / 2, a1, 40) + listOf(Offset(foot, 1f), Offset(0f, 1f))
        }
        "tulip" -> {
            val s = between(0.92f, 1.08f)
            (bezier(Offset(0f, 0f), Offset(0.04f, 0.08f), Offset(0.1f, 0.16f), Offset(0.14f, 0.22f)) +
                bezier(Offset(0.14f, 0.22f), Offset(0.2f, 0.12f), Offset(0.27f, 0.06f), Offset(0.33f, 0.02f)) +
                bezier(Offset(0.33f, 0.02f), Offset(0.4f, 0.22f), Offset(0.38f, 0.5f), Offset(0.04f, 0.62f)) +
                listOf(Offset(0.03f, 0.63f), Offset(0.03f, 0.76f)) +
                bezier(Offset(0.03f, 0.76f), Offset(0.15f, 0.68f), Offset(0.26f, 0.62f), Offset(0.34f, 0.6f)) +
                bezier(Offset(0.34f, 0.6f), Offset(0.26f, 0.76f), Offset(0.14f, 0.86f), Offset(0.03f, 0.88f)) +
                listOf(Offset(0.03f, 1f), Offset(0f, 1f))).map { Offset(it.x * s, it.y) }
        }
        "crown" -> {
            val valley = between(0.3f, 0.36f)
            listOf(
                Offset(0f, between(0.02f, 0.08f)), Offset(0.13f, valley), Offset(0.27f, between(0.06f, 0.12f)), Offset(0.36f, valley + 0.02f),
                Offset(0.47f, between(0f, 0.04f)), Offset(0.42f, 0.78f), Offset(0.45f, 0.8f), Offset(0.45f, 1f), Offset(0f, 1f),
            )
        }
        "tree" -> {
            // A pine: three tiers and a trunk.
            val s = between(0.9f, 1.05f)
            val t1 = between(0.25f, 0.3f)
            val t2 = between(0.52f, 0.58f)
            listOf(
                Offset(0f, 0f), Offset(0.2f, t1), Offset(0.11f, t1), Offset(0.32f, t2), Offset(0.19f, t2),
                Offset(0.45f, 0.84f), Offset(0.08f, 0.84f), Offset(0.08f, 1f), Offset(0f, 1f),
            ).map { Offset(it.x * s, it.y) }
        }
        "lightbulb" -> {
            val r = between(0.3f, 0.34f)
            val neck = between(0.13f, 0.15f)
            val a1 = 48f * PI.toFloat() / 180f
            val bulb = arcPoints(Offset(0f, r), r, r, -PI.toFloat() / 2, a1, 40)
            val end = bulb.last()
            val ridges = (0..3).flatMap { k -> arcPoints(Offset(neck, 0.745f + 0.045f * k), 0.022f, 0.022f, -PI.toFloat() / 2, PI.toFloat() / 2, 6) }
            bulb + bezier(end, end + Offset(-0.74f, 0.67f) * 0.06f, Offset(neck + 0.01f, 0.66f), Offset(neck, 0.72f)) + ridges +
                bezier(Offset(neck, 0.902f), Offset(neck, 0.96f), Offset(0.06f, 1f), Offset(0f, 1f))
        }
        "cat" -> {
            val tip = Offset(between(0.26f, 0.32f), 0f)
            bezier(Offset(0f, 0.24f), Offset(0.05f, 0.24f), Offset(0.09f, 0.22f), Offset(0.12f, 0.2f)) +
                listOf(tip, Offset(0.42f, 0.3f)) +
                bezier(Offset(0.42f, 0.3f), Offset(0.5f, 0.45f), Offset(0.48f, 0.68f), Offset(0.36f, 0.84f)) +
                bezier(Offset(0.36f, 0.84f), Offset(0.26f, 0.95f), Offset(0.12f, 1f), Offset(0f, 1f))
        }
        "umbrella" -> {
            val edge = between(0.4f, 0.45f)
            val canopy = listOf(Offset(0f, 0f), Offset(0.025f, 0f), Offset(0.025f, 0.07f)) +
                bezier(Offset(0.025f, 0.07f), Offset(0.22f, 0.07f), Offset(0.44f, 0.18f), Offset(0.5f, edge))
            // Three scallops back to the shaft.
            val scallops = (0..2).flatMap { k ->
                val x0 = 0.5f - 0.16f * k
                val x1 = x0 - 0.16f
                bezier(Offset(x0, edge), Offset(x0 - 0.03f, edge - 0.06f), Offset(x1 + 0.03f, edge - 0.06f), Offset(x1, edge), 12)
            }
            canopy + scallops + listOf(Offset(0.02f, 1f), Offset(0f, 1f))
        }
        "butterfly" -> {
            val s = between(0.92f, 1.05f)
            (listOf(Offset(0f, 0.1f)) +
                bezier(Offset(0.04f, 0.12f), Offset(0.15f, -0.02f), Offset(0.45f, -0.05f), Offset(0.47f, 0.12f)) +
                bezier(Offset(0.47f, 0.12f), Offset(0.49f, 0.3f), Offset(0.3f, 0.45f), Offset(0.08f, 0.48f)) +
                bezier(Offset(0.08f, 0.48f), Offset(0.3f, 0.5f), Offset(0.42f, 0.7f), Offset(0.33f, 0.86f)) +
                bezier(Offset(0.33f, 0.86f), Offset(0.25f, 0.98f), Offset(0.08f, 0.9f), Offset(0.04f, 0.78f)) +
                bezier(Offset(0.04f, 0.78f), Offset(0.035f, 0.85f), Offset(0.02f, 0.92f), Offset(0f, 0.95f))).map { Offset(it.x * s, it.y) }
        }
        "bow" -> {
            // A loop out from the knot, then a tail with a notched end, back to the knot.
            listOf(Offset(0f, 0.36f), Offset(0.07f, 0.36f)) +
                bezier(Offset(0.07f, 0.36f), Offset(0.18f, 0.1f), Offset(0.46f, 0f), Offset(0.48f, 0.2f)) +
                bezier(Offset(0.48f, 0.2f), Offset(0.5f, 0.4f), Offset(0.3f, 0.5f), Offset(0.08f, 0.5f)) +
                bezier(Offset(0.08f, 0.5f), Offset(0.16f, 0.66f), Offset(0.26f, 0.85f), Offset(0.32f, 1f)) +
                listOf(Offset(0.22f, 0.93f)) +
                bezier(Offset(0.14f, 1f), Offset(0.12f, 0.85f), Offset(0.07f, 0.7f), Offset(0.03f, 0.56f)) +
                Offset(0f, 0.56f)
        }
        else -> error("No half shape called $name")
    }
    return unitTall(pts)
}

/** Scaled to 1 tall from y 0, the fold kept at x 0, both ends on it. */
private fun unitTall(pts: List<Offset>): List<Offset> {
    val minY = pts.minOf { it.y }
    val h = pts.maxOf { it.y } - minY
    val out = pts.map { Offset(it.x.coerceAtLeast(0f) / h, (it.y - minY) / h) }.toMutableList()
    out[0] = Offset(0f, out[0].y)
    out[out.lastIndex] = Offset(0f, out.last().y)
    return out
}
