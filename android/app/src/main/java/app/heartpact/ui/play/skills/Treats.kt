package app.heartpact.ui.play.skills

import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.random.Random

/*
 * The treats that come in as the levels rise (Shapes.kt has the first four, and how a treat is picked). Each is built
 * upright in its own units, fitted to the unit box, turned, and bitten. Pure data, the same from the same seed.
 */

/**
 * How lopsided a treat comes out at a level: [tilt] scales how far it's turned, [bite] how big its bites are, and
 * [bites] how many more it gets. All 1, 1 and 0 at level 1, so level 1 treats are exactly as they were.
 */
internal class Lop(val tilt: Float, val bite: Float, val bites: Int) {
    companion object {
        fun of(d: Difficulty, odd: Boolean): Lop =
            // Kiss the center's treats are lopsided already; they get a little odder. Fair share's get much more so.
            if (odd) Lop(d.lerp(1f, 1.5f), d.lerp(1f, 1.15f), when { d.from(12) -> 2; d.from(6) -> 1; else -> 0 })
            else Lop(d.lerp(1f, 2f), d.lerp(1f, 1.45f), d.step(0, 2))
    }
}

private val C = Offset(0.5f, 0.5f)

private fun Random.between(a: Float, b: Float) = a + nextFloat() * (b - a)

private fun dir(t: Float) = Offset(cos(t), sin(t))

private fun deg(d: Float) = d * PI.toFloat() / 180f

/** Points on an ellipse arc from angle [from] to [to] (radians, screen axes), both ends included. */
internal fun arcPoints(c: Offset, rx: Float, ry: Float, from: Float, to: Float, n: Int): List<Offset> =
    List(n + 1) { i -> val t = from + (to - from) * i / n; c + Offset(cos(t) * rx, sin(t) * ry) }

/** A cubic Bézier from [a] to [d] in [n] steps, both ends included. */
internal fun bezier(a: Offset, b: Offset, c: Offset, d: Offset, n: Int = 24): List<Offset> = List(n + 1) { i ->
    val t = i / n.toFloat()
    val u = 1 - t
    a * (u * u * u) + b * (3 * u * u * t) + c * (3 * u * t * t) + d * (t * t * t)
}

/** A closed polygon with each corner rounded off by up to [r] (a quadratic curve through the corner). */
internal fun roundCorners(poly: List<Offset>, r: Float, n: Int = 6): List<Offset> {
    val out = ArrayList<Offset>()
    for (i in poly.indices) {
        val v = poly[i]
        val a = poly[(i - 1 + poly.size) % poly.size]
        val b = poly[(i + 1) % poly.size]
        val la = (a - v).getDistance()
        val lb = (b - v).getDistance()
        if (la < 1e-6f || lb < 1e-6f) { out += v; continue }
        val p = v + (a - v) * (minOf(r, la / 2) / la)
        val q = v + (b - v) * (minOf(r, lb / 2) / lb)
        for (k in 0..n) {
            val t = k / n.toFloat()
            out += p * ((1 - t) * (1 - t)) + v * (2 * (1 - t) * t) + q * (t * t)
        }
    }
    return out
}

/** A superellipse (a squircle when [p] is past 2) centred on [c], [a] by [b] half-sizes. */
private fun superellipse(c: Offset, a: Float, b: Float, p: Float, n: Int): List<Offset> = List(n) { i ->
    val t = TAU * i / n
    val ct = cos(t)
    val st = sin(t)
    c + Offset(a * sign(ct) * abs(ct).pow(2f / p), b * sign(st) * abs(st).pow(2f / p))
}

/** A ring with a hole: round the rim, across to the hole and back round it the other way, so the hole stays open. */
private fun withHole(rim: List<Offset>, hole: List<Offset>): List<Offset> = rim + rim.first() + hole + hole.first()

/** The treat moved point by point with [f]; [scale] is how much it grows sizes (a dot's radius). */
private fun Treat.mapped(scale: Float, f: (Offset) -> Offset): Treat = Treat(
    kind,
    outline.map(f),
    inner?.map(f),
    lines.map { (a, b) -> f(a) to f(b) },
    dots.map { (c, r) -> f(c) to r * scale },
    colour,
    fills.map { it.map(f) },
    curves.map { it.map(f) },
)

/** Moved and scaled so the outline's box is centred in the unit box and [size] on its longer side; outlines densified. */
private fun Treat.fitted(size: Float): Treat {
    val minX = outline.minOf { it.x }; val maxX = outline.maxOf { it.x }
    val minY = outline.minOf { it.y }; val maxY = outline.maxOf { it.y }
    val s = size / maxOf(maxX - minX, maxY - minY)
    val mid = Offset((minX + maxX) / 2, (minY + maxY) / 2)
    val t = mapped(s) { C + (it - mid) * s }
    return Treat(t.kind, densify(t.outline, 0.006f), t.inner?.let { densify(it, 0.006f) }, t.lines, t.dots, t.colour, t.fills.map { densify(it, 0.006f) }, t.curves)
}

private fun Treat.turned(radians: Float): Treat = if (radians == 0f) this else mapped(1f) { it.rotateAround(C, radians) }

private fun Treat.mirrored(): Treat = mapped(1f) { Offset(1f - it.x, it.y) }

/**
 * A round bite of radius [r] out of the treat's edge, somewhere well out from its middle, where the bite takes one
 * clean scoop (not two sides of a thin part at once). The icing and the rest are bitten with it; toppings in the bite
 * go. Unchanged when no clean place turns up.
 */
internal fun Treat.bitten(rnd: Random, r: Float): Treat {
    val mid = centroid(outline)
    val far = outline.maxOf { (it - mid).getDistance() }
    val before = area(outline)
    repeat(16) {
        val at = outline[rnd.nextInt(outline.size)]
        val out = at - mid
        val len = out.getDistance()
        if (len < far * 0.55f) return@repeat
        val c = at + out * (r * 0.35f / len)
        val inside = BooleanArray(outline.size) { (outline[it] - c).getDistance() < r }
        if (!oneRun(inside)) return@repeat
        val o = bite(outline, c, r)
        if (area(o) < before * 0.7f || !isSimple(o)) return@repeat
        return Treat(
            kind, o, inner?.let { bite(it, c, r + 0.025f) }, lines,
            dots.filter { (it.first - c).getDistance() > r + it.second }, colour,
            fills.map { bite(it, c, r) }, curves,
        )
    }
    return this
}

/** True when the set corners of a closed outline make one unbroken run (and not all of it). */
private fun oneRun(inside: BooleanArray): Boolean {
    var starts = 0
    for (i in inside.indices) if (inside[i] && !inside[(i - 1 + inside.size) % inside.size]) starts++
    return starts == 1
}

/** No two edges of the closed outline cross (touching and running along each other are allowed). */
fun isSimple(poly: List<Offset>): Boolean {
    val n = poly.size
    fun cross(o: Offset, a: Offset, b: Offset) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
    for (i in 0 until n) {
        val a = poly[i]
        val b = poly[(i + 1) % n]
        if (a == b) continue
        val minX = minOf(a.x, b.x); val maxX = maxOf(a.x, b.x); val minY = minOf(a.y, b.y); val maxY = maxOf(a.y, b.y)
        for (j in i + 2 until n) {
            if (i == 0 && j == n - 1) continue
            val c = poly[j]
            val d = poly[(j + 1) % n]
            if (c == d) continue
            if (maxOf(c.x, d.x) < minX || minOf(c.x, d.x) > maxX || maxOf(c.y, d.y) < minY || minOf(c.y, d.y) > maxY) continue
            val d1 = cross(a, b, c)
            val d2 = cross(a, b, d)
            val d3 = cross(c, d, a)
            val d4 = cross(c, d, b)
            if (((d1 > 1e-7f && d2 < -1e-7f) || (d1 < -1e-7f && d2 > 1e-7f)) && ((d3 > 1e-7f && d4 < -1e-7f) || (d3 < -1e-7f && d4 > 1e-7f))) return false
        }
    }
    return true
}

/** One of the treats past the first four, built, fitted, turned and (most of them) bitten. */
internal fun newTreat(kind: TreatKind, rnd: Random, odd: Boolean, lop: Lop): Treat {
    val raw = when (kind) {
        TreatKind.Macaron -> macaron(rnd)
        TreatKind.Toast -> toast(rnd)
        TreatKind.Watermelon -> watermelon(rnd)
        TreatKind.CandyHeart -> candyHeart(rnd)
        TreatKind.Cheese -> cheese(rnd)
        TreatKind.Waffle -> waffle(rnd)
        TreatKind.CakeSlice -> cakeSlice(rnd)
        TreatKind.Cupcake -> cupcake(rnd)
        TreatKind.Donut -> donut(rnd)
        TreatKind.Lollipop -> lollipop(rnd)
        TreatKind.IceCream -> iceCream(rnd)
        TreatKind.JellyBean -> jellyBean(rnd)
        TreatKind.Croissant -> croissant(rnd)
        TreatKind.Gummy -> gummy(rnd, odd)
        TreatKind.Cookie, TreatKind.Chocolate, TreatKind.Pizza, TreatKind.Jelly -> error("Built in Shapes.kt")
    }
    var t = raw.fitted(0.8f)
    if (kind == TreatKind.CakeSlice || kind == TreatKind.Croissant) { if (rnd.nextBoolean()) t = t.mirrored() }
    val tilt = when (kind) {
        // Any way round: they have no top.
        TreatKind.Cheese, TreatKind.JellyBean, TreatKind.Gummy, TreatKind.Croissant -> rnd.between(0f, TAU)
        TreatKind.Donut -> 0f
        TreatKind.Watermelon -> rnd.between(-0.4f, 0.4f) * lop.tilt + if (rnd.nextBoolean()) PI.toFloat() else 0f
        else -> rnd.between(-0.3f, 0.3f) * lop.tilt
    }
    t = t.turned(tilt)
    // The even ones get a bite, so the middle isn't where it looks; the lopsided ones don't need one.
    val even = kind !in setOf(TreatKind.Cheese, TreatKind.CakeSlice, TreatKind.Croissant, TreatKind.JellyBean, TreatKind.Gummy)
    if (even) t = t.bitten(rnd, (if (odd) rnd.between(0.11f, 0.15f) else rnd.between(0.07f, 0.11f)) * lop.bite)
    return t
}

/** A macaron from the side: two round shells, the filling between, and the ruffled feet. */
private fun macaron(rnd: Random): Treat {
    val w = rnd.between(0.38f, 0.42f)
    val h = rnd.between(0.21f, 0.25f)
    val outline = superellipse(C, w, h, rnd.between(2.4f, 3f), 240)
    val y = 0.5f + rnd.between(-0.015f, 0.015f)
    val band = h * 0.2f
    val filling = listOf(Offset(0f, y - band), Offset(1f, y - band), Offset(1f, y + band), Offset(0f, y + band))
    val phase = rnd.between(0f, TAU)
    val feet = listOf(-1f, 1f).map { s ->
        List(90) { i ->
            val x = 0.5f - w + 2 * w * i / 89f
            Offset(x, y + s * (band + 0.022f) + 0.007f * sin(x * 95f + phase))
        }
    }
    return Treat(TreatKind.Macaron, outline, null, emptyList(), emptyList(), rnd.nextInt(5), listOf(filling), feet)
}

/** A slice of toast: a puffed crown over square sides, the crumb inside, air holes and a pat of butter. */
private fun toast(rnd: Random): Treat {
    val w = rnd.between(0.28f, 0.31f)
    val crown = rnd.between(0.2f, 0.26f)
    val pts = ArrayList<Offset>()
    pts += arcPoints(Offset(0.5f, 0.34f), (w + 0.035f) / 0.866f, crown, deg(150f), deg(390f), 80)
    pts += arcPoints(Offset(0.5f + w - 0.05f, 0.84f), 0.05f, 0.05f, 0f, deg(90f), 8)
    pts += arcPoints(Offset(0.5f - w + 0.05f, 0.84f), 0.05f, 0.05f, deg(90f), deg(180f), 8)
    val mid = centroid(pts)
    val crumb = pts.map { mid + (it - mid) * 0.84f }
    val at = mid + Offset(rnd.between(-0.07f, 0.07f), rnd.between(-0.05f, 0.08f))
    val turn = rnd.between(-0.6f, 0.6f)
    val s = 0.075f
    val pat = roundCorners(listOf(Offset(-s, -s), Offset(s, -s), Offset(s, s), Offset(-s, s)), 0.025f).map { at + it.rotateAround(Offset.Zero, turn) }
    val holes = ArrayList<Pair<Offset, Float>>()
    repeat(18) {
        val p = Offset(rnd.between(0.2f, 0.8f), rnd.between(0.15f, 0.85f))
        val r = rnd.between(0.006f, 0.012f)
        if (contains(crumb, p) && !contains(pat, p) && holes.none { (it.first - p).getDistance() < 0.05f }) holes += p to r
    }
    return Treat(TreatKind.Toast, pts, crumb, emptyList(), holes, 0, listOf(pat))
}

/** A half-moon slice of watermelon: green rind, a pale band, the pink flesh and its seeds. */
private fun watermelon(rnd: Random): Treat {
    val r = 0.42f
    val c = Offset(0.5f, 0.32f)
    val outline = arcPoints(c, r, r, 0f, PI.toFloat(), 120)
    val pale = arcPoints(c, r * 0.9f, r * 0.9f, 0f, PI.toFloat(), 110)
    val flesh = arcPoints(c, r * 0.81f, r * 0.81f, 0f, PI.toFloat(), 110)
    val seeds = ArrayList<Pair<Offset, Offset>>()
    var tries = 0
    while (seeds.size < 8 && tries++ < 60) {
        val a = rnd.between(0.35f, PI.toFloat() - 0.35f)
        val p = c + dir(a) * (r * rnd.between(0.4f, 0.66f))
        if (seeds.any { ((it.first + it.second) / 2f - p).getDistance() < 0.075f }) continue
        seeds += (p - dir(a) * 0.013f) to (p + dir(a) * 0.013f)
    }
    return Treat(TreatKind.Watermelon, outline, pale, seeds, emptyList(), 0, listOf(flesh))
}

/** A candy heart: a round, puffy heart (not the app's geometric one), a lighter face. */
private fun candyHeart(rnd: Random): Treat {
    val outline = List(220) { i ->
        val t = TAU * i / 220
        val s = sin(t)
        Offset(16 * s * s * s, -(13 * cos(t) - 5 * cos(2 * t) - 2 * cos(3 * t) - cos(4 * t)))
    }
    val mid = centroid(outline)
    val face = outline.map { mid + (it - mid) * 0.85f }
    return Treat(TreatKind.CandyHeart, outline, face, emptyList(), emptyList(), rnd.nextInt(5))
}

/** A long wedge of cheese from above: a rind round its wide end, and holes. */
private fun cheese(rnd: Random): Treat {
    val span = rnd.between(0.7f, 1f)
    val pts = roundCorners(listOf(Offset.Zero) + arcPoints(Offset.Zero, 1f, 1f, -span / 2, span / 2, 50), 0.05f)
    val rind = arcPoints(Offset.Zero, 1.1f, 1.1f, -span / 2 - 0.1f, span / 2 + 0.1f, 50) +
        arcPoints(Offset.Zero, 0.9f, 0.9f, span / 2 + 0.1f, -span / 2 - 0.1f, 50)
    val holes = ArrayList<Pair<Offset, Float>>()
    repeat(40) {
        if (holes.size >= 7) return@repeat
        val p = dir(rnd.between(-span / 2, span / 2) * 1.1f) * rnd.between(0.22f, 0.86f)
        val hr = rnd.between(0.035f, 0.075f) * (0.6f + p.getDistance() * 0.5f)
        if (holes.none { (it.first - p).getDistance() < it.second + hr + 0.03f }) holes += p to hr
    }
    return Treat(TreatKind.Cheese, pts, null, emptyList(), holes, 0, listOf(rind))
}

/** A waffle, square or round, with its grid and a pat of butter. */
private fun waffle(rnd: Random): Treat {
    val round = rnd.nextBoolean()
    val half = if (round) 0.36f else 0.34f
    val outline = if (round) arcPoints(C, half, half, 0f, TAU, 160).dropLast(1)
    else roundCorners(listOf(Offset(0.5f - half, 0.5f - half), Offset(0.5f + half, 0.5f - half), Offset(0.5f + half, 0.5f + half), Offset(0.5f - half, 0.5f + half)), 0.09f)
    val n = 5
    val grid = ArrayList<Pair<Offset, Offset>>()
    for (i in 1 until n) {
        val v = 0.5f - half + 2 * half * i / n
        grid += Offset(v, 0f) to Offset(v, 1f)
        grid += Offset(0f, v) to Offset(1f, v)
    }
    val at = C + Offset(rnd.between(-0.08f, 0.08f), rnd.between(-0.08f, 0.08f))
    val turn = rnd.between(0f, 1.6f)
    val s = 0.08f
    val pat = roundCorners(listOf(Offset(-s, -s), Offset(s, -s), Offset(s, s), Offset(-s, s)), 0.025f).map { at + it.rotateAround(Offset.Zero, turn) }
    return Treat(TreatKind.Waffle, outline, null, grid, emptyList(), 0, listOf(pat))
}

/** A slice of layer cake from the side: a wedge, cream layers, frosting along the top and a strawberry on it. */
private fun cakeSlice(rnd: Random): Treat {
    val tipH = rnd.between(0.1f, 0.16f)
    val top = rnd.between(0.26f, 0.32f)
    val a = Offset(0.06f, 0.78f)
    val b = Offset(0.94f, 0.78f)
    val c = Offset(0.94f, top)
    val d = Offset(0.06f, 0.78f - tipH)
    val edge = (d - c).getDistance()
    val u = (d - c) / edge
    val up = Offset(u.y, -u.x).let { if (it.y < 0) it else -it }
    val r = 0.075f
    val berry = c + u * (edge * rnd.between(0.15f, 0.22f)) + up * 0.01f
    val a0 = atan2(-u.y, -u.x)
    val sweep = if ((dir(a0 + PI.toFloat() / 2) * r).y < 0f) PI.toFloat() else -PI.toFloat()
    val pts = listOf(a, b, c) + arcPoints(berry, r, r, a0, a0 + sweep, 24) + d
    val outline = roundCorners(pts, 0.03f)
    // Cream layers, each the same share of the way up at both ends, so they fan out like the wedge.
    val layers = listOf(1f / 3, 2f / 3).map { f ->
        val p = Offset(a.x, a.y - tipH * f)
        val q = Offset(b.x, b.y - (b.y - c.y) * f)
        (p - (q - p) * 0.1f) to (q + (q - p) * 0.1f)
    }
    // The frosting: a band along the top edge, its lower side dripping a little.
    val drips = List(41) { i ->
        val t = 1.1f - 1.2f * i / 40
        c + (d - c) * t - up * (0.06f + 0.012f * sin(i * 1.7f) + 0.025f * exp(-((t - 0.55f) / 0.05f).pow(2)))
    }
    val frosting = listOf(c + (c - d) * 0.1f + up * 0.06f, d + (d - c) * 0.1f + up * 0.06f) + drips
    return Treat(TreatKind.CakeSlice, outline, null, layers, listOf(berry to r * 0.9f), rnd.nextInt(3), listOf(frosting))
}

/** A cupcake: a pleated wrapper, a high swirl of frosting, sprinkles. */
private fun cupcake(rnd: Random): Treat {
    val wrapTop = 0.62f
    val bottom = 0.94f
    val topW = 0.27f
    val bottomW = 0.2f
    val dome = rnd.between(0.3f, 0.36f)
    val wide = 0.34f
    val phase = rnd.between(0f, TAU)
    fun domeAt(a: Float): Offset = Offset(
        0.5f + wide * cos(a),
        0.6f - dome * sin(a).coerceAtLeast(0f).pow(0.85f) - 0.022f * sin(a) * sin(4 * a + phase) - 0.07f * exp(-((a - PI.toFloat() / 2) / 0.17f).pow(2)),
    )
    val top = List(81) { i -> domeAt(PI.toFloat() * i / 80) }
    val outline = roundCorners(
        listOf(Offset(0.5f - bottomW, bottom), Offset(0.5f + bottomW, bottom), Offset(0.5f + topW, wrapTop), Offset(0.5f + wide, wrapTop)) +
            top + listOf(Offset(0.5f - wide, wrapTop), Offset(0.5f - topW, wrapTop)),
        0.03f, 4,
    )
    val frosting = top + List(41) { i ->
        val x = 0.5f - wide + 2 * wide * i / 40
        Offset(x, wrapTop + 0.012f + 0.018f * abs(sin((x - 0.5f) * 26f)))
    }
    val pleats = (1..6).map { i ->
        Offset(0.5f - topW + 2 * topW * i / 7, wrapTop) to Offset(0.5f - bottomW + 2 * bottomW * i / 7, bottom)
    }
    val tiers = (1..2).map { k ->
        val w = wide * (0.9f - 0.28f * k)
        val y = 0.6f - dome * 0.36f * k
        List(30) { i -> val s = -1f + 2f * i / 29; Offset(0.5f + w * s, y + 0.035f * (1 - s * s)) }
    }
    val sprinkles = ArrayList<Pair<Offset, Float>>()
    repeat(30) {
        if (sprinkles.size >= 9) return@repeat
        val p = Offset(rnd.between(0.2f, 0.8f), rnd.between(0.2f, 0.6f))
        if (contains(frosting, p) && sprinkles.none { (it.first - p).getDistance() < 0.06f }) sprinkles += p to rnd.between(0.008f, 0.012f)
    }
    return Treat(TreatKind.Cupcake, outline, frosting, pleats, sprinkles, rnd.nextInt(4), emptyList(), tiers)
}

/** A donut with a real hole (a little off centre), icing with a wavy edge, sprinkles. */
private fun donut(rnd: Random): Treat {
    val r = 0.38f
    val hole = rnd.between(0.12f, 0.145f)
    val off = Offset(rnd.between(-0.035f, 0.035f), rnd.between(-0.035f, 0.035f))
    val wob = rnd.between(0f, TAU)
    val start = PI.toFloat() / 2
    val rim = List(180) { i -> val t = start + TAU * i / 180; C + dir(t) * (r * (1 + 0.012f * sin(5 * t + wob))) }
    val gap = List(80) { i -> val t = start - TAU * i / 80; C + off + dir(t) * hole }
    val p1 = rnd.between(0f, TAU)
    val p2 = rnd.between(0f, TAU)
    val icingRim = List(180) { i -> val t = start + TAU * i / 180; C + dir(t) * (r * (0.85f + 0.035f * sin(6 * t + p1) + 0.02f * sin(11 * t + p2))) }
    val icingGap = List(80) { i -> val t = start - TAU * i / 80; C + off + dir(t) * (hole + 0.03f) }
    val icing = withHole(icingRim, icingGap)
    val sprinkles = ArrayList<Pair<Offset, Float>>()
    repeat(60) {
        if (sprinkles.size >= 16) return@repeat
        val p = C + dir(rnd.between(0f, TAU)) * rnd.between(hole + 0.05f, r * 0.8f)
        if (contains(icing, p) && sprinkles.none { (it.first - p).getDistance() < 0.05f }) sprinkles += p to rnd.between(0.008f, 0.012f)
    }
    return Treat(TreatKind.Donut, withHole(rim, gap), icing, emptyList(), sprinkles, rnd.nextInt(3))
}

/** A round lollipop on a stick, with a swirl. */
private fun lollipop(rnd: Random): Treat {
    val c = Offset(0.5f, 0.36f)
    val r = 0.3f
    val stick = 0.026f
    val end = 0.98f
    val d = asin(stick / r)
    val join = c.y + r * cos(d)
    val round = arcPoints(Offset(0.5f, end - stick), stick, stick, 0f, PI.toFloat(), 8)
    val outline = arcPoints(c, r, r, PI.toFloat() / 2 + d, PI.toFloat() / 2 - d + TAU, 200) + round
    val stickFill = listOf(Offset(0.5f - stick, join - 0.004f), Offset(0.5f + stick, join - 0.004f)) + round
    val turns = rnd.between(2.6f, 3.3f)
    val phase = rnd.between(0f, TAU)
    val swirl = List(220) { i ->
        val f = i / 219f
        c + dir(phase + TAU * turns * f) * (r * 0.97f * f)
    }
    return Treat(TreatKind.Lollipop, outline, null, emptyList(), emptyList(), rnd.nextInt(5), listOf(stickFill), listOf(swirl))
}

/** A scoop of ice cream on a cone, melting down the cone's rim. */
private fun iceCream(rnd: Random): Treat {
    val sc = Offset(0.5f, 0.33f)
    val sr = rnd.between(0.23f, 0.26f)
    val coneTop = 0.53f
    val coneW = 0.17f
    val lip = sr * 0.93f
    val arc = arcPoints(sc, sr, sr, deg(155f), deg(385f), 90)
    val outline = roundCorners(arc + listOf(Offset(0.5f + lip, coneTop - 0.005f), Offset(0.5f + coneW, coneTop + 0.01f), Offset(0.5f, 0.97f), Offset(0.5f - coneW, coneTop + 0.01f), Offset(0.5f - lip, coneTop - 0.005f)), 0.02f)
    val dripsAt = List(3) { rnd.between(-coneW * 0.8f, coneW * 0.8f) to rnd.between(0.03f, 0.08f) }
    val melt = List(50) { i ->
        val x = 0.5f + lip - 2 * lip * i / 49
        Offset(x, coneTop + 0.012f + dripsAt.sumOf { (at, depth) -> (depth * exp(-((x - 0.5f - at) / 0.022f).pow(2))).toDouble() }.toFloat())
    }
    val scoop = arc + melt
    val hatch = ArrayList<Pair<Offset, Offset>>()
    for (k in -4..4) {
        val o = Offset(0.5f + k * 0.075f, 0.75f)
        hatch += (o + Offset(-0.4f, -0.4f)) to (o + Offset(0.4f, 0.4f))
        hatch += (o + Offset(0.4f, -0.4f)) to (o + Offset(-0.4f, 0.4f))
    }
    val sprinkles = ArrayList<Pair<Offset, Float>>()
    repeat(30) {
        if (sprinkles.size >= 8) return@repeat
        val p = sc + dir(rnd.between(0f, TAU)) * (sr * rnd.between(0.15f, 0.8f))
        if (p.y < coneTop - 0.02f && sprinkles.none { (it.first - p).getDistance() < 0.06f }) sprinkles += p to rnd.between(0.008f, 0.012f)
    }
    return Treat(TreatKind.IceCream, outline, scoop, hatch, sprinkles, rnd.nextInt(4))
}

/** A jelly bean: a bent oval with a dimple, and a shine along its back. */
private fun jellyBean(rnd: Random): Treat {
    val a = 0.4f
    val b = rnd.between(0.17f, 0.2f)
    val bend = rnd.between(0.08f, 0.13f)
    fun at(t: Float): Offset {
        val x = a * cos(t)
        var y = b * sin(t) - bend * (x / a) * (x / a)
        if (sin(t) < 0f) y += 0.025f * exp(-(x / 0.09f).pow(2))
        return Offset(0.5f + x, 0.5f + y)
    }
    val outline = List(220) { i -> at(TAU * i / 220) }
    val shine = List(30) { i ->
        val x = a * (-0.62f + 0.62f * i / 29)
        val t = TAU - acosSafe(x / a)
        val p = at(t)
        Offset(p.x, p.y + 0.05f)
    }
    return Treat(TreatKind.JellyBean, outline, null, emptyList(), emptyList(), rnd.nextInt(6), emptyList(), listOf(shine))
}

private fun acosSafe(v: Float): Float = kotlin.math.acos(v.coerceIn(-1f, 1f))

/** A croissant: a fat crescent (the middle still well inside it), tapering to its tips, with its folds. */
private fun croissant(rnd: Random): Treat {
    val r = 0.42f
    val span = rnd.between(2.3f, 2.6f)
    val thick = rnd.between(0.12f, 0.145f)
    val cc = Offset(0.5f, 0.85f)
    fun t(s: Float) = 0.035f + thick * sin(PI.toFloat() * s).coerceAtLeast(0f).pow(0.8f)
    fun phi(s: Float) = -PI.toFloat() / 2 - span / 2 + span * s
    val n = 90
    val outer = List(n + 1) { i -> val s = i / n.toFloat(); cc + dir(phi(s)) * (r + t(s)) }
    val inside = List(n + 1) { i -> val s = 1f - i / n.toFloat(); cc + dir(phi(s)) * (r - t(s)) }
    val outline = roundCorners(outer + inside, 0.02f, 3)
    val folds = listOf(0.17f, 0.33f, 0.5f, 0.67f, 0.83f).map { s ->
        List(14) { i ->
            val u = -1f + 2f * i / 13
            cc + dir(phi(s) + 0.07f * (1 - u * u) * (s - 0.5f) * 2.5f) * (r + u * t(s) * 0.92f)
        }
    }
    return Treat(TreatKind.Croissant, outline, null, emptyList(), emptyList(), 0, emptyList(), folds)
}

/** A gummy blob: lumpier than a jelly, with sugar on it. */
private fun gummy(rnd: Random, odd: Boolean): Treat {
    val k = if (odd) 1.4f else 1f
    val amps = listOf(2 to rnd.between(0.06f, 0.11f) * k, 3 to rnd.between(0.05f, 0.09f) * k, 4 to rnd.between(0.03f, 0.06f) * k, 6 to rnd.between(0.015f, 0.03f))
    val phases = amps.map { rnd.between(0f, TAU) }
    val outline = List(240) { i ->
        val t = TAU * i / 240
        var f = 1f
        amps.forEachIndexed { j, (h, amp) -> f += amp * sin(h * t + phases[j]) }
        C + dir(t) * (0.33f * f)
    }
    val sugar = ArrayList<Pair<Offset, Float>>()
    repeat(60) {
        if (sugar.size >= 14) return@repeat
        val p = Offset(rnd.between(0.15f, 0.85f), rnd.between(0.15f, 0.85f))
        if (contains(outline, p) && sugar.none { (it.first - p).getDistance() < 0.07f }) sugar += p to rnd.between(0.005f, 0.008f)
    }
    return Treat(TreatKind.Gummy, outline, null, emptyList(), sugar, rnd.nextInt(5))
}
