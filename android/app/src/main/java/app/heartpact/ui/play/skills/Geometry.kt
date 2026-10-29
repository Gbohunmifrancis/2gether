package app.heartpact.ui.play.skills

import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The geometry under the skill games, kept free of drawing so it can be tested on the JVM: areas and centres of
 * polygons, cutting one along a line, distances to a drawn line, and fitting a circle to a stroke.
 * A polygon is its corners in order (either way round), not closed: the last corner joins the first.
 */

/** Twice-signed area, positive when the corners run anticlockwise in maths axes (clockwise on screen). */
fun signedArea(poly: List<Offset>): Float {
    var s = 0.0
    for (i in poly.indices) {
        val a = poly[i]
        val b = poly[(i + 1) % poly.size]
        s += a.x.toDouble() * b.y - b.x.toDouble() * a.y
    }
    return (s / 2).toFloat()
}

fun area(poly: List<Offset>): Float = abs(signedArea(poly))

/** The centre of area (what a shape balances on), not the average of its corners. */
fun centroid(poly: List<Offset>): Offset {
    var a = 0.0
    var cx = 0.0
    var cy = 0.0
    for (i in poly.indices) {
        val p = poly[i]
        val q = poly[(i + 1) % poly.size]
        val cross = p.x.toDouble() * q.y - q.x.toDouble() * p.y
        a += cross
        cx += (p.x + q.x) * cross
        cy += (p.y + q.y) * cross
    }
    if (abs(a) < 1e-12) return poly.fold(Offset.Zero) { s, p -> s + p } / poly.size.toFloat().coerceAtLeast(1f)
    return Offset((cx / (3 * a)).toFloat(), (cy / (3 * a)).toFloat())
}

/** Which side of the line a to b [p] is on: positive one side, negative the other, 0 on it. */
private fun side(a: Offset, b: Offset, p: Offset): Float = (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)

/**
 * The part of [poly] on the positive side of the line through [a] and [b] (one Sutherland-Hodgman pass). A concave
 * shape cut into several pieces on one side comes back as one polygon joined by zero-width seams, so its area is right.
 */
fun clipToSide(poly: List<Offset>, a: Offset, b: Offset): List<Offset> {
    val out = ArrayList<Offset>(poly.size + 4)
    for (i in poly.indices) {
        val p = poly[i]
        val q = poly[(i + 1) % poly.size]
        val sp = side(a, b, p)
        val sq = side(a, b, q)
        if (sp >= 0f) out += p
        if ((sp >= 0f) != (sq >= 0f)) {
            val t = sp / (sp - sq)
            out += p + (q - p) * t
        }
    }
    return out
}

/** [poly] cut along the line through [a] and [b]: the piece on its positive side, then the other. */
fun cut(poly: List<Offset>, a: Offset, b: Offset): Pair<List<Offset>, List<Offset>> = clipToSide(poly, a, b) to clipToSide(poly, b, a)

/** Even-odd test: is [p] inside [poly]? */
fun contains(poly: List<Offset>, p: Offset): Boolean {
    var inside = false
    var j = poly.size - 1
    for (i in poly.indices) {
        val a = poly[i]
        val b = poly[j]
        if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) inside = !inside
        j = i
    }
    return inside
}

fun distanceToSegment(p: Offset, a: Offset, b: Offset): Float {
    val ab = b - a
    val len2 = ab.x * ab.x + ab.y * ab.y
    if (len2 < 1e-12f) return (p - a).getDistance()
    val t = (((p.x - a.x) * ab.x + (p.y - a.y) * ab.y) / len2).coerceIn(0f, 1f)
    return (p - (a + ab * t)).getDistance()
}

/** The nearest point on an open polyline. */
fun nearestOn(p: Offset, line: List<Offset>): Offset {
    if (line.size == 1) return line[0]
    var best = line[0]
    var bestD = Float.MAX_VALUE
    for (i in 0 until line.size - 1) {
        val a = line[i]
        val ab = line[i + 1] - a
        val len2 = ab.x * ab.x + ab.y * ab.y
        val t = if (len2 < 1e-12f) 0f else (((p.x - a.x) * ab.x + (p.y - a.y) * ab.y) / len2).coerceIn(0f, 1f)
        val q = a + ab * t
        val d = (p - q).getDistanceSquared()
        if (d < bestD) { bestD = d; best = q }
    }
    return best
}

fun distanceToPolyline(p: Offset, line: List<Offset>): Float = (p - nearestOn(p, line)).getDistance()

fun length(line: List<Offset>): Float {
    var s = 0f
    for (i in 1 until line.size) s += (line[i] - line[i - 1]).getDistance()
    return s
}

/** Points every [step] along an open polyline (both ends kept), so slow and fast drawing weigh the same. */
fun resample(line: List<Offset>, step: Float): List<Offset> {
    if (line.size < 2) return line
    val out = arrayListOf(line[0])
    var carry = 0f
    for (i in 1 until line.size) {
        val a = line[i - 1]
        val b = line[i]
        val seg = (b - a).getDistance()
        if (seg <= 0f) continue
        var d = step - carry
        while (d <= seg) {
            out += a + (b - a) * (d / seg)
            d += step
        }
        carry = seg - (d - step)
    }
    if (out.last() != line.last()) out += line.last()
    return out
}

/** A closed outline resampled to corners about [step] apart, so a bite can be taken anywhere. */
fun densify(poly: List<Offset>, step: Float): List<Offset> = resample(poly + poly.first(), step).dropLast(1)

/** Takes a round bite (centre [c], radius [r]) out of a densely sampled outline: corners inside move to its rim. */
fun bite(poly: List<Offset>, c: Offset, r: Float): List<Offset> = poly.map { p ->
    val d = p - c
    val len = d.getDistance()
    if (len < r && len > 1e-6f) c + d * (r / len) else p
}

/** A best-fit circle through [points] (Kåsa's least squares): centre and radius. */
fun fitCircle(points: List<Offset>): Pair<Offset, Float> {
    val n = points.size.toDouble()
    val mx = points.sumOf { it.x.toDouble() } / n
    val my = points.sumOf { it.y.toDouble() } / n
    var suu = 0.0; var svv = 0.0; var suv = 0.0; var suuu = 0.0; var svvv = 0.0; var suvv = 0.0; var svuu = 0.0
    for (p in points) {
        val u = p.x - mx
        val v = p.y - my
        suu += u * u; svv += v * v; suv += u * v
        suuu += u * u * u; svvv += v * v * v; suvv += u * v * v; svuu += v * u * u
    }
    val det = suu * svv - suv * suv
    if (abs(det) < 1e-9) {
        val r = points.map { hypot(it.x - mx, it.y - my) }.average()
        return Offset(mx.toFloat(), my.toFloat()) to r.toFloat()
    }
    val b1 = 0.5 * (suuu + suvv)
    val b2 = 0.5 * (svvv + svuu)
    val uc = (b1 * svv - b2 * suv) / det
    val vc = (suu * b2 - suv * b1) / det
    val r = sqrt(uc * uc + vc * vc + (suu + svv) / n)
    return Offset((uc + mx).toFloat(), (vc + my).toFloat()) to r.toFloat()
}

/** The angle of [p] around [c], in radians. */
fun angleAround(c: Offset, p: Offset): Float = atan2(p.y - c.y, p.x - c.x)

/** Rotates [p] about [c] by [radians]. */
fun Offset.rotateAround(c: Offset, radians: Float): Offset {
    val s = sin(radians)
    val co = cos(radians)
    val d = this - c
    return Offset(c.x + d.x * co - d.y * s, c.y + d.x * s + d.y * co)
}

const val TAU = (2 * PI).toFloat()

/** The heart (HeartGeometry's, tip at 50,100 in a 100-unit box) as an outline with about [n] corners. */
fun heartOutline(n: Int = 160): List<Offset> {
    val s = 60f / sqrt(2f)
    val r = 30f
    val tip = Offset(50f, 100f)
    val leftCorner = Offset(50f - s, 100f - s)
    val leftLobe = Offset(50f - s / 2, 100f - 1.5f * s)
    val rightLobe = Offset(50f + s / 2, 100f - 1.5f * s)
    val pts = ArrayList<Offset>()
    val edge = n / 6
    for (i in 0 until edge) pts += tip + (leftCorner - tip) * (i / edge.toFloat())
    val arc = n / 3
    // Each lobe is a half circle: the left from 135° round to 315°, the right from 225° to 405° (screen angles).
    for (i in 0..arc) {
        val t = Math.toRadians(135.0 + 180.0 * i / arc).toFloat()
        pts += leftLobe + Offset(cos(t), sin(t)) * r
    }
    for (i in 1..arc) {
        val t = Math.toRadians(225.0 + 180.0 * i / arc).toFloat()
        pts += rightLobe + Offset(cos(t), sin(t)) * r
    }
    val rightCorner = Offset(50f + s, 100f - s)
    for (i in 1 until edge) pts += rightCorner + (tip - rightCorner) * (i / edge.toFloat())
    return pts
}

/** Moves and scales an outline so its bounding box is centred on [centre] and [size] on its longer side. */
fun fitOutline(poly: List<Offset>, centre: Offset, size: Float): List<Offset> {
    val minX = poly.minOf { it.x }; val maxX = poly.maxOf { it.x }
    val minY = poly.minOf { it.y }; val maxY = poly.maxOf { it.y }
    val s = size / maxOf(maxX - minX, maxY - minY)
    val mid = Offset((minX + maxX) / 2, (minY + maxY) / 2)
    return poly.map { centre + (it - mid) * s }
}
