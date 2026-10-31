package app.heartpact.ui.play.skills

import androidx.compose.ui.geometry.Offset
import app.heartpact.i18n.tr
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/*
 * The shapes Promise ring asks for once the levels rise, and how a drawing of one is scored. A drawing is matched to
 * the shape wherever it was drawn and whatever its size: both are moved to the same centre and scaled to the same
 * spread, turned a little to line up, and then measured both ways (how far the line strays from the shape, and how
 * much of the shape the line missed), so half a star can't score like a whole one. On top of that: a gap left
 * open, corners rounded off, and (where the guide's size counts) the size. Pure, so it can be tested on the JVM.
 */

/** A shape to draw, the level it first turns up at, and whether its line closes on itself. */
enum class DrawShape(val unlock: Int, val closed: Boolean = true) {
    Circle(1), Oval(2), Heart(3), Triangle(4), Square(5), Star(7), Infinity(9), Spiral(12, closed = false);

    /** What to draw, as the round asks for it. */
    val prompt: String get() = when (this) {
        Circle -> tr("Draw a circle.")
        Oval -> tr("Draw an oval.")
        Heart -> tr("Draw a heart.")
        Triangle -> tr("Draw a triangle.")
        Square -> tr("Draw a square.")
        Star -> tr("Draw a star.")
        Infinity -> tr("Draw an infinity loop.")
        Spiral -> tr("Draw a spiral.")
    }

    companion object {
        /** The shapes there are at [difficulty]: just the ring at level 1, more as the levels rise. */
        fun pool(difficulty: Difficulty): List<DrawShape> = entries.filter { difficulty.from(it.unlock) }

        /** The shape for a round. One that has just turned up at this level comes up half the time, so it gets seen. */
        fun pick(rnd: Random, difficulty: Difficulty): DrawShape {
            val pool = pool(difficulty)
            if (pool.size == 1) return pool[0]
            val fresh = pool.filter { it.unlock == difficulty.level }
            return if (fresh.isNotEmpty() && rnd.nextFloat() < 0.5f) fresh[0] else pool[rnd.nextInt(pool.size)]
        }
    }
}

/**
 * A shape in normalised units: its centre (of its line) at 0, its points on average 1 from it (root mean square).
 * [line] is the polyline (closed shapes repeat the first point at the end), [samples] points evenly along it,
 * [corners] the tips a lazy line rounds off. Screen axes: y grows downwards.
 */
class ShapeTemplate(val shape: DrawShape, val line: List<Offset>, val samples: List<Offset>, val corners: List<Offset>) {
    val perimeter: Float = length(line)
    val width: Float = line.maxOf { it.x } - line.minOf { it.x }
    val height: Float = line.maxOf { it.y } - line.minOf { it.y }

    companion object {
        /** The template for [shape]; [turned] stands an oval up on its end. */
        fun of(shape: DrawShape, turned: Boolean = false): ShapeTemplate {
            var corners = emptyList<Offset>()
            fun loop(n: Int, f: (Float) -> Offset) = List(n) { i -> f(TAU * i / n) }
            fun polygon(v: List<Offset>): List<Offset> = v.also { corners = it }
            val raw: List<Offset> = when (shape) {
                DrawShape.Circle -> loop(200) { Offset(cos(it), sin(it)) }
                DrawShape.Oval -> loop(200) { if (turned) Offset(0.6f * cos(it), sin(it)) else Offset(cos(it), 0.6f * sin(it)) }
                DrawShape.Heart -> heartOutline(240).also { h -> corners = listOf(Offset(50f, 100f)) + h.filter { abs(it.x - 50f) < 0.01f && it.y < 50f }.take(1) }
                DrawShape.Triangle -> polygon(List(3) { i -> polar(1f, -90f + 120f * i) })
                DrawShape.Square -> polygon(listOf(Offset(-1f, -1f), Offset(1f, -1f), Offset(1f, 1f), Offset(-1f, 1f)))
                DrawShape.Star -> polygon(List(10) { i -> polar(if (i % 2 == 0) 1f else 0.45f, -90f + 36f * i) })
                DrawShape.Infinity -> loop(240) { Offset(cos(it), 0.5f * sin(2 * it)) }
                // Two and a half turns out from the middle, clockwise on screen.
                DrawShape.Spiral -> List(301) { i ->
                    val t = 5f * PI.toFloat() * i / 300
                    polar(t / (5f * PI.toFloat()), Math.toDegrees(t.toDouble()).toFloat())
                }
            }
            val line = if (shape.closed) raw + raw.first() else raw
            val even = resample(line, length(line) / 400f)
            val c = even.fold(Offset.Zero) { s, p -> s + p } / even.size.toFloat()
            val rms = sqrt(even.sumOf { ((it - c).getDistanceSquared()).toDouble() } / even.size).toFloat()
            fun norm(p: Offset) = (p - c) / rms
            val normLine = line.map(::norm)
            return ShapeTemplate(shape, normLine, resample(normLine, length(normLine) / 160f), corners.map(::norm))
        }

        private fun polar(r: Float, degrees: Float): Offset {
            val t = Math.toRadians(degrees.toDouble()).toFloat()
            return Offset(cos(t), sin(t)) * r
        }
    }
}

/** A drawing of a shape, matched to it. */
data class ShapeFit(
    val shape: DrawShape,
    /** Where the drawing's centre is and its spread (root mean square distance from the centre), in the stroke's units. */
    val centre: Offset,
    val scale: Float,
    /** How far the drawing is turned from the shape, in radians. */
    val angle: Float,
    /** The stroke up to where it came back to its start; past that is [overlap], which isn't scored. */
    val kept: List<Offset>,
    val overlap: List<Offset>,
    /** The average distance between the drawing and the shape, both ways, as a fraction of the spread. */
    val miss: Float,
    /** The gap left between the ends, as a fraction of the way round (0 for a spiral). */
    val gap: Float,
    /** How far the shape's corners and tips are from the line, as a fraction of the spread. */
    val cornerMiss: Float,
    /** The drawing's width over height, divided by the shape's: over 1 too wide, under 1 too tall. */
    val stretch: Float,
) {
    /** The shape itself, laid over the drawing: [template] turned, scaled and moved to match it. */
    fun ideal(template: ShapeTemplate): List<Offset> = template.line.map { centre + it.rotateBy(-angle) * scale }
}

private fun Offset.rotateBy(radians: Float): Offset {
    val c = cos(radians)
    val s = sin(radians)
    return Offset(x * c - y * s, x * s + y * c)
}

private fun meanDistance(points: List<Offset>, line: List<Offset>): Float {
    var s = 0f
    for (p in points) s += distanceToPolyline(p, line)
    return s / points.size
}

/**
 * Matches a stroke (in dp) to [shape]. Null when it isn't an attempt yet (too short or tiny), so the round can ask
 * again instead of scoring a slip of the finger. [minSize] is the smallest the drawing may be across.
 */
fun measureShape(stroke: List<Offset>, shape: DrawShape, turned: Boolean = false, minSize: Float = 56f): ShapeFit? {
    if (stroke.size < 6 || length(stroke) < 120f) return null
    val w = stroke.maxOf { it.x } - stroke.minOf { it.x }
    val h = stroke.maxOf { it.y } - stroke.minOf { it.y }
    if (maxOf(w, h) < minSize) return null
    val template = ShapeTemplate.of(shape, turned)
    var pts = resample(stroke, 2f)
    if (pts.size < 12) return null
    var overlap = emptyList<Offset>()
    var gapLength = 0f
    if (shape.closed) {
        // Where the line comes back nearest its start, past the first part: the rest is overlap, or the gap is left.
        val from = (pts.size * 0.6f).toInt()
        var best = pts.lastIndex
        var bestD = Float.MAX_VALUE
        for (i in from until pts.size) {
            val d = (pts[i] - pts[0]).getDistance()
            if (d < bestD) { bestD = d; best = i }
        }
        overlap = pts.subList(best, pts.size)
        pts = pts.subList(0, best + 1)
        gapLength = bestD
    }
    val even = resample(pts, length(pts) / 240f)
    val c = even.fold(Offset.Zero) { s, p -> s + p } / even.size.toFloat()
    val rms = sqrt(even.sumOf { ((it - c).getDistanceSquared()).toDouble() } / even.size).toFloat()
    if (rms < 1e-3f) return null
    val drawn = even.map { (it - c) / rms }
    // Turned a little to line up (any way round for a spiral, which can start anywhere; not at all for a circle).
    val angles = when (shape) {
        DrawShape.Circle -> listOf(0f)
        DrawShape.Spiral -> List(72) { it * 5f }
        else -> List(17) { -20f + it * 2.5f }
    }
    var bestMiss = Float.MAX_VALUE
    var bestDeg = 0f
    var bestDrawn = drawn
    fun tryAngle(deg: Float) {
        val a = Math.toRadians(deg.toDouble()).toFloat()
        val turnedDrawn = if (a == 0f) drawn else drawn.map { it.rotateBy(a) }
        val there = meanDistance(turnedDrawn, template.line)
        if (there >= bestMiss * 2f) return
        val back = meanDistance(template.samples, turnedDrawn)
        val miss = (there + back) / 2f
        if (miss < bestMiss) { bestMiss = miss; bestDeg = deg; bestDrawn = turnedDrawn }
    }
    angles.forEach(::tryAngle)
    // Then finer, around the best of those.
    if (angles.size > 1) {
        val coarse = bestDeg
        val step = angles[1] - angles[0]
        for (i in -4..4) if (i != 0) tryAngle(coarse + step * i / 5f)
    }
    val bestAngle = Math.toRadians(bestDeg.toDouble()).toFloat()
    val gap = if (shape.closed) gapLength / rms / template.perimeter else 0f
    // Corners against the whole line, not the evenly spaced points, which can step over a sharp tip.
    val cornerMiss = if (template.corners.isEmpty()) 0f else meanDistance(template.corners, pts.map { ((it - c) / rms).rotateBy(bestAngle) })
    val dw = bestDrawn.maxOf { it.x } - bestDrawn.minOf { it.x }
    val dh = bestDrawn.maxOf { it.y } - bestDrawn.minOf { it.y }
    val stretch = (dw / dh.coerceAtLeast(1e-3f)) / (template.width / template.height)
    return ShapeFit(shape, c, rms, bestAngle, pts, overlap, bestMiss, gap, cornerMiss, stretch)
}

/** The cost of drawing [ratio] times the guide's size: 10% off costs about a fifth of the score. */
internal fun sizeCost(ratio: Float): Float = 2f * abs(ln(ratio.coerceAtLeast(1e-3f)))

internal fun sizeNote(ratio: Float): String =
    if (ratio >= 1f) tr("{pct}% too big for the guide.", "pct" to ((ratio - 1f) * 100f).roundToInt())
    else tr("{pct}% too small for the guide.", "pct" to ((1f - ratio) * 100f).roundToInt())

/**
 * A drawn shape's score, and a note on its biggest flaw. [k] tightens with the level (1 at first); with a
 * [guideScale] (the guide's spread, in the stroke's units, from the levels where size counts) the size counts too.
 */
fun shapeScore(fit: ShapeFit, k: Float = 1f, guideScale: Float? = null): Scored {
    val matchCost = 5.5f * fit.miss
    val gapCost = 1.5f * fit.gap
    val cornerCost = 4f * (fit.cornerMiss - fit.miss).coerceAtLeast(0f)
    val ratio = if (guideScale != null && guideScale > 0f) fit.scale / guideScale else 1f
    val sizeCost = if (guideScale != null && guideScale > 0f) sizeCost(ratio) else 0f
    val cost = matchCost + gapCost + cornerCost + sizeCost
    val worst = maxOf(matchCost, gapCost, cornerCost, sizeCost)
    val note = when {
        cost < 0.06f -> tr("Spot on. Just like the guide.")
        // A gap also pulls the shape out of true, so it comes first once it's plain to see.
        (worst == gapCost && fit.gap > 0.02f) || fit.gap > 0.06f -> tr("Left a gap. Join the ends.")
        worst == sizeCost -> sizeNote(ratio)
        // Rounded-off corners also cost in the match, so they count as the flaw once they're a good part of it.
        cornerCost >= 0.6f * matchCost && cornerCost >= sizeCost -> if (fit.shape == DrawShape.Star) tr("Make the points sharper.") else tr("Sharpen the corners.")
        fit.stretch > 1.12f -> tr("Too wide for its height.")
        fit.stretch < 0.89f -> tr("Too tall for its width.")
        fit.miss < 0.035f -> tr("Close. A little wobbly in places.")
        else -> tr("Not quite the shape. Look at the guide again.")
    }
    return Scored(falloff(cost, k), note)
}
