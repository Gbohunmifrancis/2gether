package app.heartpact.ui.play.skills

import androidx.compose.ui.geometry.Offset
import app.heartpact.i18n.tr
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/*
 * The flight, the hold and the drawing under Cupid's aim, Slow kiss and Draw a heart, and how each is scored: pure
 * functions, like Scoring.kt, so they can be tested and tuned on the JVM. Scores fall off the same way (falloff).
 */

// Cupid's aim.

/** The board Cupid's aim is played on, in units: 1 wide, [CUPID_H] tall, y down. */
internal const val CUPID_H = 1.5f

/** Where the bow sits (the arrow's tip starts a little above it), and how fast an arrow flies (units a second). */
internal val BOW = Offset(0.5f, 1.16f)
internal const val ARROW_SPEED = 1.7f

/** An arrow's length, tip to tail, in units. */
internal const val ARROW_LENGTH = 0.4f

/** The app's heart (HeartGeometry, a 100-unit box): its centre of area and its size, for placing it by its centre. */
internal object HeartBox {
    private val outline = heartOutline(200)
    val centre = centroid(outline)
    val width = outline.maxOf { it.x } - outline.minOf { it.x }
    val height = outline.maxOf { it.y } - outline.minOf { it.y }
}

/**
 * The target heart's flight, from a seed and a level: where its centre is [at] any moment (seconds since the round
 * began). Level 1 drifts side to side at an easy, steady pace with a gentle float. Up the levels it flies faster and
 * smaller; from level 4 its pace and height wobble, from level 8 it turns back at moments of its own, and from
 * level 14 a decoy heart flies the other way.
 */
internal class CupidFlight(seed: Long, difficulty: Difficulty = Difficulty.Start) {
    private val rnd = Random(seed)

    /** Width of the heart, in units. */
    val size = difficulty.lerp(0.30f, 0.17f)
    val speed = difficulty.lerp(0.28f, 0.62f)
    /** How far it floats up and down. */
    val bob = if (difficulty.from(4)) difficulty.lerp(0.035f, 0.09f) else 0.015f
    /** How much its pace swells and slackens (0 steady). */
    val surge = if (difficulty.from(4)) difficulty.lerp(0.25f, 0.55f) else 0f
    val height = 0.42f
    val decoy = difficulty.from(14)
    /** The arrow's path is dotted in, up to level 9. */
    val aimLine = !difficulty.from(10)
    private val bobRate = 1.6f + rnd.nextFloat() * 0.6f
    private val bobPhase = rnd.nextFloat() * TAU
    private val surgeRate = 1.3f + rnd.nextFloat() * 0.8f
    private val surgePhase = rnd.nextFloat() * TAU
    private val lo = size / 2 + 0.04f
    private val hi = 1f - size / 2 - 0.04f
    /** Where along its track it starts (as distance travelled), so the first pass isn't always from the same side. */
    private val start = rnd.nextFloat() * 2f * (hi - lo)
    /** Moments it turns back on its own (seconds), from level 8. */
    private val turns: List<Float> = if (!difficulty.from(8)) emptyList() else buildList {
        var t = 0.6f + rnd.nextFloat() * 1.4f
        val gap = difficulty.lerp(2.6f, 1.1f)
        while (t < 600f) { add(t); t += gap * (0.6f + rnd.nextFloat() * 0.9f) }
    }

    /** Distance travelled along the track by [t], before it bounces off the sides; turns run it backwards. */
    private fun travelled(t: Float): Float {
        // The pace swells with a sine: the integral of speed * (1 + surge * sin(rate * x + phase)).
        fun f(x: Float) = x - surge / surgeRate * cos(surgeRate * x + surgePhase)
        fun moved(a: Float, b: Float) = speed * (f(b) - f(a))
        var s = start
        var from = 0f
        var dir = 1f
        for (turn in turns) {
            if (turn >= t) break
            s += dir * moved(from, turn)
            from = turn
            dir = -dir
        }
        return s + dir * moved(from, t)
    }

    /** The heart's centre at [t] seconds. */
    fun at(t: Float): Offset {
        val span = hi - lo
        val p = ((travelled(t) % (2 * span)) + 2 * span) % (2 * span)
        val x = lo + if (p < span) p else 2 * span - p
        return Offset(x, height + bob * sin(bobRate * t + bobPhase))
    }

    /** The decoy's centre at [t]: the same kind of flight, mirrored and a little lower, so it crosses the real one. */
    fun decoyAt(t: Float): Offset {
        val p = at(t * 0.83f + 1.7f)
        return Offset(1f - p.x, p.y + 0.26f)
    }

    /** The heart's speed across at [t] (units a second; positive to the right). */
    fun velocityX(t: Float): Float = (at(t + 0.005f).x - at(t - 0.005f).x) / 0.01f

    /** True when an arrow passing [p] at [t] goes through the heart (somewhere across its width, lobes and all). */
    fun hits(t: Float, p: Offset): Boolean = abs(p.x - at(t).x) < size / 2f
}

/** An arrow loosed at [loosed] seconds: when it passes the heart's centre ([crossed]), and how far off it ([dx]). */
internal data class CupidShot(val loosed: Float, val crossed: Float, val heartAt: Offset, val dx: Float, val hit: Boolean, val velocityX: Float)

/** The tip of an arrow loosed at [loosed], at [t]. */
internal fun arrowTip(loosed: Float, t: Float): Offset = Offset(BOW.x, BOW.y - 0.06f - ARROW_SPEED * (t - loosed).coerceAtLeast(0f))

/** Flies the arrow to the moment its tip passes the heart's centre (the heart floats, so this is solved, not assumed). */
internal fun cupidShot(flight: CupidFlight, loosed: Float): CupidShot {
    fun gap(t: Float) = arrowTip(loosed, t).y - flight.at(t).y
    var a = loosed
    var b = loosed + 3f
    repeat(40) {
        val m = (a + b) / 2
        if (gap(m) > 0f) a = m else b = m
    }
    val t = (a + b) / 2
    val c = flight.at(t)
    val tip = Offset(BOW.x, c.y)
    return CupidShot(loosed, t, c, tip.x - c.x, flight.hits(t, tip), flight.velocityX(t))
}

/**
 * Cupid's aim: [miss] is how far from the heart's centre the arrow passed, as a share of the heart's width. Within 2%
 * is perfect; 10% off scores about 77 at level 1, the edge of the heart about 20. [earlySeconds] (negative: late)
 * says how to time the next one, when the heart was moving clearly enough to say; otherwise the note gives the side.
 */
fun cupidScore(miss: Float, hit: Boolean, earlySeconds: Float?, rightOfCentre: Boolean, k: Float = 0.3f): Scored {
    val s = earlySeconds?.let { twoDecimals(abs(it)) }
    val note = when {
        miss < 0.02f -> tr("Right through the heart.")
        earlySeconds != null && hit ->
            if (earlySeconds > 0f) tr("Through the heart, {s} s early.", "s" to s) else tr("Through the heart, {s} s late.", "s" to s)
        earlySeconds != null ->
            if (earlySeconds > 0f) tr("Missed it. Loosed {s} s too early.", "s" to s) else tr("Missed it. Loosed {s} s too late.", "s" to s)
        hit -> if (rightOfCentre) tr("Through the heart, right of center.") else tr("Through the heart, left of center.")
        else -> if (rightOfCentre) tr("Missed it, to the right.") else tr("Missed it, to the left.")
    }
    return Scored(if (miss < 0.02f) 100 else falloff(miss - 0.02f, k), note)
}

/** Cupid's aim for a [shot] at a heart [size] wide. */
internal fun cupidScore(shot: CupidShot, size: Float, k: Float): Scored {
    val v = shot.velocityX
    // The arrow was early by the time the heart would still have taken to get under it.
    val early = if (abs(v) > 0.05f) shot.dx / v else null
    return cupidScore(abs(shot.dx) / size, shot.hit, early?.takeIf { abs(it) < 3f }, shot.dx > 0f, k)
}

// Slow kiss.

/** The kiss to hold, in seconds: 2.5 to 3.5 by half seconds at level 1, up to 6 to 8 by quarter seconds. */
internal fun slowKissTarget(seed: Long, difficulty: Difficulty = Difficulty.Start): Float {
    val rnd = Random(seed)
    val step = if (difficulty.from(6)) 0.25f else 0.5f
    val lo = (difficulty.lerp(2.5f, 6f) / step).roundToInt() * step
    val hi = (difficulty.lerp(3.5f, 8f) / step).roundToInt() * step
    return lo + step * rnd.nextInt(((hi - lo) / step).roundToInt() + 1)
}

/** How long the timer shows once the kiss begins (seconds): 1.6 s at level 1, less up the levels, never from 12. */
internal fun slowKissTimerSeconds(difficulty: Difficulty = Difficulty.Start): Float =
    if (difficulty.from(12)) 0f else difficulty.lerp(1.6f, 0.4f)

/**
 * Slow kiss: [held] against [target] seconds. Within 20 ms is perfect; past that the miss is a share of the target,
 * so a long kiss forgives a little more (5% off a 3 s kiss scores about 75 at level 1).
 */
fun slowKissScore(held: Float, target: Float, k: Float = 0.15f): Scored {
    val e = held - target
    val s = twoDecimals(abs(e))
    val note = when {
        abs(e) <= 0.02f -> tr("Exactly {s} s. A perfect kiss.", "s" to twoDecimals(target))
        e < 0f -> tr("Let go {s} s too early.", "s" to s)
        else -> tr("Held on {s} s too long.", "s" to s)
    }
    return Scored(if (abs(e) <= 0.02f) 100 else falloff((abs(e) - 0.02f) / target, k), note)
}

// Draw a heart.

/**
 * A drawn heart, measured against the app's heart. Shape measures are in heart heights, with the drawing laid onto
 * the heart (its centre of area at 0,0, its point straight down): moved, sized and turned freely, or, with a guide,
 * laid on the way the guide is.
 */
data class DrawnHeart(
    /** The stroke, laid onto the heart. */
    val aligned: List<Offset>,
    /** How far the stroke and the heart are apart on average, both ways (drawn to heart, heart to drawn). */
    val apart: Float,
    /** The distance between the stroke's ends. */
    val gap: Float,
    /** Where the gap is, as a height: near the point (above 0.2), near the lobes (below -0.2), or on a side. */
    val gapY: Float,
    /** Left lobe over right lobe, by area (1 when they match). */
    val lobes: Float,
    /** The angle at its point, in degrees: the heart's is 90; a round bottom is near 180. */
    val tipAngle: Float,
    /** How far the dip between the lobes sits below their tops (the heart's: about 0.09). */
    val dipDepth: Float,
    /** Small shakes along the line. */
    val shake: Float,
    /** The best-fitting heart on the board: its centre, its height and its lean (radians, clockwise on screen). */
    val centre: Offset,
    val height: Float,
    val turn: Float,
    /** With a guide: the drawing's size over the guide's (1 the same), and how far its centre is off, in guide heights. */
    val sizeRatio: Float = 1f,
    val offset: Float = 0f,
) {
    /** Maps a point of the heart's frame (HEART_FRAME) onto the board, onto the heart that fits the drawing best. */
    fun toBoard(p: Offset): Offset = centre + p.rotateAround(Offset.Zero, turn) * height
}

/** The heart in heart heights, centred on its centre of area: the point is at the bottom, the dip at the top. */
internal val HEART_FRAME: List<Offset> by lazy { frame(heartOutline(240)) }
internal val HEART_DIP: Offset by lazy {
    // The lobes meet on the centre line, x = 50, where the two lobe circles cross.
    val side = 60f / sqrt(2f)
    val lobe = Offset(50f - side / 2f, 100f - 1.5f * side)
    val y = lobe.y - sqrt(30f * 30f - (50f - lobe.x) * (50f - lobe.x))
    frame(heartOutline(240), Offset(50f, y))
}

/** The lobes are the parts of the heart above this line (just under the lobes' centres). */
private const val LOBE_LINE = 0.1f

private fun frame(h: List<Offset>): List<Offset> {
    val c = centroid(h)
    val height = h.maxOf { it.y } - h.minOf { it.y }
    return h.map { (it - c) / height }
}

private fun frame(h: List<Offset>, p: Offset): Offset {
    val c = centroid(h)
    return (p - c) / (h.maxOf { it.y } - h.minOf { it.y })
}

/** A similarity (scale, turn, shift) that maps a onto b as closely as it can, by least squares. */
private class Similar(val s: Float, val turn: Float, val shift: Offset) {
    fun apply(p: Offset): Offset = p.rotateAround(Offset.Zero, turn) * s + shift

    companion object {
        fun fit(a: List<Offset>, b: List<Offset>): Similar {
            val ma = a.fold(Offset.Zero) { s, p -> s + p } / a.size.toFloat()
            val mb = b.fold(Offset.Zero) { s, p -> s + p } / b.size.toFloat()
            var sxx = 0.0
            var sxy = 0.0
            var aa = 0.0
            for (i in a.indices) {
                val p = a[i] - ma
                val q = b[i] - mb
                sxx += p.x * q.x + p.y * q.y
                sxy += p.x * q.y - p.y * q.x
                aa += p.x * p.x + p.y * p.y
            }
            val turn = atan2(sxy, sxx).toFloat()
            val s = if (aa < 1e-12) 1f else (sqrt(sxx * sxx + sxy * sxy) / aa).toFloat()
            return Similar(s, turn, mb - ma.rotateAround(Offset.Zero, turn) * s)
        }
    }
}

/** Mean of the distance from each of [from] to the line [to]. */
private fun meanTo(from: List<Offset>, to: List<Offset>): Float = from.sumOf { distanceToPolyline(it, to).toDouble() }.toFloat() / from.size

/**
 * Measures a heart drawn in one stroke (any units). Null when it isn't a heart attempt yet (a dot, a dash, a tiny
 * scribble), so the round can ask again. Without a [guide] the drawing is moved, sized and turned onto the heart
 * (it may lean up to 40° without that counting); with one (its centre and height, in the stroke's units) it is
 * measured where it is, as big as it is.
 */
fun measureHeart(stroke: List<Offset>, minSize: Float, guide: Pair<Offset, Float>? = null): DrawnHeart? {
    if (stroke.size < 8) return null
    val extent = maxOf(stroke.maxOf { it.x } - stroke.minOf { it.x }, stroke.maxOf { it.y } - stroke.minOf { it.y })
    if (extent < minSize || length(stroke) < extent * 1.4f) return null
    val pts = resample(stroke, length(stroke) / 160f)
    val ideal = HEART_FRAME
    val sparse = ideal.filterIndexed { i, _ -> i % 2 == 0 }
    // Start centre to centre, with the same spread about the centre, at a few leans.
    val mid = pts.fold(Offset.Zero) { s, p -> s + p } / pts.size.toFloat()
    val spread = sqrt(pts.map { (it - mid).getDistanceSquared() }.average()).toFloat().coerceAtLeast(1e-6f)
    val idealSpread = sqrt(ideal.map { it.getDistanceSquared() }.average()).toFloat()
    val limit = Math.toRadians(40.0).toFloat()
    var best: Triple<Float, List<Offset>, Similar>? = null
    for (deg in listOf(-30.0, -15.0, 0.0, 15.0, 30.0)) {
        val rad = Math.toRadians(deg).toFloat()
        val s0 = idealSpread / spread
        var fit = Similar(s0, rad, -mid.rotateAround(Offset.Zero, rad) * s0)
        var now = pts.map { fit.apply(it) }
        // Closest points both ways, then the similarity that brings them together; a dozen rounds settle it.
        repeat(14) {
            val a = ArrayList<Offset>(now.size + sparse.size)
            val b = ArrayList<Offset>(now.size + sparse.size)
            for (p in now) { a += p; b += nearestOn(p, ideal) }
            for (q in sparse) { a += nearestOn(q, now); b += q }
            val step = Similar.fit(a, b)
            // An upside-down heart is not a heart: the lean stops at 40°.
            fit = Similar(fit.s * step.s, (fit.turn + step.turn).coerceIn(-limit, limit), step.apply(fit.shift))
            now = pts.map { fit.apply(it) }
        }
        val apart = (meanTo(now, ideal) + meanTo(sparse, now)) / 2f
        if (best == null || apart < best.first) best = Triple(apart, now, fit)
    }
    val (freeApart, freeAligned, fit) = best!!
    // The best-fitting heart on the board (the inverse of the fit).
    val height = 1f / fit.s
    val turn = -fit.turn
    val centre = (-fit.shift).rotateAround(Offset.Zero, turn) * height
    // Against a guide, the distance is to the guide: the drawing as it is, laid onto the heart the way the guide is.
    // The shape's own flaws are read from the best fit either way.
    val apart = guide?.let { (g, h) -> pts.map { (it - g) / h }.let { (meanTo(it, ideal) + meanTo(sparse, it)) / 2f } } ?: freeApart
    val aligned = freeAligned
    val gap = (aligned.first() - aligned.last()).getDistance()
    val gapY = (aligned.first().y + aligned.last().y) / 2f
    // The drawing's own centre line runs from its dip to its point; the lobes are its two sides, above the middle.
    // Its point is its lowest point; its dip, the lowest point of its top edge near the middle.
    val tip = aligned.maxBy { it.y }
    val dip = aligned.filter { abs(it.x) < 0.15f && it.y < 0f }.maxByOrNull { it.y } ?: nearestOn(HEART_DIP, aligned)
    val (left, right) = if ((tip - dip).getDistance() < 0.05f) aligned to aligned else cut(aligned, dip, tip)
    fun upper(piece: List<Offset>) = if (piece.size < 3) 0f else area(clipToSide(piece, Offset(1f, LOBE_LINE), Offset(0f, LOBE_LINE)))
    val leftArea = upper(left)
    val rightArea = upper(right)
    val lobes = when {
        leftArea < 1e-6f && rightArea < 1e-6f -> 1f
        rightArea < 1e-6f -> 100f
        else -> (leftArea / rightArea).coerceIn(0.01f, 100f)
    }
    // Shake: how far each point strays from the middle of its neighbours, typically (the median, so the heart's own
    // corners don't count as shakes).
    val w = 1
    val strays = aligned.indices.map { i ->
        val from = (i - w).coerceAtLeast(0)
        val to = (i + w).coerceAtMost(aligned.lastIndex)
        var s = Offset.Zero
        for (j in from..to) s += aligned[j]
        (aligned[i] - s / (to - from + 1f)).getDistance()
    }.sorted()
    // The angle at the point, between the line a little way back each side (round the join when the stroke closed there).
    val n = aligned.size
    val at = aligned.indexOf(tip)
    val reach = (0.12f / (length(aligned) / n).coerceAtLeast(1e-4f)).roundToInt().coerceIn(2, n / 4)
    fun around(i: Int) = if (gap < 0.08f) aligned[((i % n) + n) % n] else aligned[i.coerceIn(0, n - 1)]
    val back = around(at - reach) - tip
    val ahead = around(at + reach) - tip
    val cosine = (back.x * ahead.x + back.y * ahead.y) / (back.getDistance() * ahead.getDistance()).coerceAtLeast(1e-6f)
    // Left open right at the point, there's no angle to read: the open end is the flaw, and gets the note.
    val openAtPoint = gap >= 0.08f && (at < reach || at > n - 1 - reach)
    val tipAngle = when {
        openAtPoint -> 90f
        back.getDistance() < 1e-4f || ahead.getDistance() < 1e-4f -> 180f
        else -> Math.toDegrees(kotlin.math.acos(cosine.coerceIn(-1f, 1f)).toDouble()).toFloat()
    }
    return DrawnHeart(
        aligned = aligned, apart = apart, gap = gap, gapY = gapY, lobes = lobes,
        tipAngle = tipAngle, dipDepth = dip.y - aligned.minOf { it.y },
        shake = strays[strays.size / 2], centre = centre, height = height, turn = turn,
        sizeRatio = guide?.let { height / it.second } ?: 1f,
        offset = guide?.let { (centre - it.first).getDistance() / it.second } ?: 0f,
    )
}

/**
 * Draw a heart: one score from how far the drawing and the heart are apart (both ways, so half a heart can't score
 * well), an open end, and what makes a heart a heart (a point, a dip, two even lobes), and a note on the biggest
 * flaw. [guided] when a dashed heart showed where to draw: then the size, the place and the lean are in the distance
 * too, and get their own notes.
 */
fun heartScore(m: DrawnHeart, k: Float = 0.075f, guided: Boolean = false): Scored {
    val cost = m.apart +
        0.25f * (m.gap - 0.04f).coerceAtLeast(0f) +
        0.025f * ((m.tipAngle - 105f) / 45f).coerceIn(0f, 1.5f) +
        0.02f * ((0.07f - m.dipDepth) / 0.05f).coerceIn(0f, 1.5f) +
        0.02f * (abs(ln(m.lobes)) / 0.4f).coerceAtMost(1.5f)
    val score = (100.0 * exp(-(cost / k).toDouble())).roundToInt().coerceIn(0, 100)
    // Each flaw as a multiple of where it starts to show: the biggest one gets the note.
    val flaws = buildList {
        add("gap" to m.gap / 0.08f)
        add("lobes" to abs(ln(m.lobes)) / 0.2f)
        add("tip" to (m.tipAngle - 95f) / 25f)
        add("dip" to (0.075f - m.dipDepth) / 0.035f)
        add("shake" to m.shake / 0.0025f)
        if (guided) {
            add("size" to abs(ln(m.sizeRatio.coerceAtLeast(1e-3f))) / 0.12f)
            add("place" to m.offset / 0.08f)
            add("lean" to abs(m.turn) / 0.25f)
        }
    }
    val (worst, how) = flaws.maxBy { it.second }
    val note = when {
        how < 1f -> if (score >= 88) tr("A true heart.") else tr("Close. A little wobbly in places.")
        worst == "gap" -> when {
            m.gapY > 0.2f -> tr("Close the bottom point.")
            m.gapY < -0.2f -> tr("Close the top. The lobes don't meet.")
            else -> tr("Close it up. The ends don't meet.")
        }
        worst == "lobes" -> if (m.lobes < 1f) tr("The left lobe is smaller.") else tr("The right lobe is smaller.")
        worst == "tip" -> tr("Sharpen the point at the bottom.")
        worst == "dip" -> tr("Give it a dip between the lobes.")
        worst == "size" -> if (m.sizeRatio > 1f) tr("Too big. Keep inside the dashed heart.") else tr("Too small. Fill the dashed heart.")
        worst == "place" -> tr("Off the guide. Trace the dashed heart.")
        worst == "lean" -> tr("It leans. Keep the point straight down.")
        else -> tr("A shaky line. Slow and steady.")
    }
    return Scored(score, note)
}
