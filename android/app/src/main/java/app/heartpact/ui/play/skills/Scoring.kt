package app.heartpact.ui.play.skills

import androidx.compose.ui.geometry.Offset
import app.heartpact.i18n.tr
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * How each skill round is scored, and the one note on what to fix. Every score falls off the same way, 100 times
 * e^(-miss / k): perfect is rare, a near miss still feels good, and a wild miss sinks towards 0 without a cliff.
 * The k of each game sets how steep it is. Pure functions so they can be tested and tuned on the JVM.
 */

/** A score from 0 to 100 and the note that goes with it. */
data class Scored(val score: Int, val note: String)

/** 100 at a perfect [miss] of 0, about 37 when the miss is [k]. */
internal fun falloff(miss: Float, k: Float): Int = (100.0 * exp(-(miss / k).toDouble())).roundToInt().coerceIn(0, 100)

internal fun oneDecimal(v: Float): String = String.format(Locale.getDefault(), "%.1f", v)
internal fun twoDecimals(v: Float): String = String.format(Locale.getDefault(), "%.2f", v)

/**
 * Fair share. [firstShare] is the share (0 to 100) of the first piece: the left one, or the top one when the cut ran
 * [across]. About 5 points off half scores 70.
 */
fun fairShareScore(firstShare: Float, across: Boolean): Scored {
    val off = abs(firstShare - 50f)
    // The difference between the two shares as the pieces show them (each to a tenth).
    val tenth = (firstShare * 10f).roundToInt() / 10f
    val gap = oneDecimal(abs(100f - 2f * tenth))
    val note = when {
        abs(100f - 2f * tenth) < 0.05f -> tr("Dead even. Perfectly fair.")
        across && firstShare > 50f -> tr("The top got {pct}% more.", "pct" to gap)
        across -> tr("The bottom got {pct}% more.", "pct" to gap)
        firstShare > 50f -> tr("Left side got {pct}% more.", "pct" to gap)
        else -> tr("Right side got {pct}% more.", "pct" to gap)
    }
    return Scored(falloff(off, 14f), note)
}

/**
 * Fill the heart. [level] and [target] are fractions of the jar's height; [secondsOff] is how long the pour would
 * have taken to close the gap, which is what the player can act on. [k] tightens with the level (14 at level 1).
 */
fun fillScore(level: Float, target: Float, secondsOff: Float, k: Float = 14f): Scored {
    val gap = (level - target) * 100f
    val s = twoDecimals(secondsOff)
    val note = when {
        abs(gap) < 0.25f -> tr("Right on the line.")
        gap > 0f -> tr("Held on {s} s too long.", "s" to s)
        else -> tr("Let go {s} s too early.", "s" to s)
    }
    return Scored(falloff(abs(gap), k), note)
}

/**
 * Heartbeat. [errorMs] is the tap minus the beat to tap on: negative is early. Within 15 ms is perfect; 250 ms off is
 * poor at level 1, and [k] tightens with the level (256 at level 1).
 */
fun heartbeatScore(errorMs: Float, k: Float = 256f): Scored {
    val e = abs(errorMs)
    val s = twoDecimals(e / 1000f)
    val note = when {
        e <= 15f -> tr("Right on the beat.")
        errorMs < 0f -> tr("Tapped {s} s early.", "s" to s)
        else -> tr("Tapped {s} s late.", "s" to s)
    }
    return Scored(if (e <= 15f) 100 else falloff(e - 15f, k), note)
}

/** Heartbeat with no tap at all. */
fun heartbeatMissed(): Scored = Scored(0, tr("No tap. The beat slipped by."))

/**
 * Kiss the center. [size] is the shape's size (the square root of its area): 5% of it off scores about 72.
 * Screen axes: y grows downwards.
 */
fun kissScore(kiss: Offset, centre: Offset, size: Float): Scored {
    val d = kiss - centre
    val off = d.getDistance() / size
    val pct = oneDecimal(off * 100f)
    val note = if (off < 0.004f) tr("Right on the center.") else {
        // Eight directions, each a whole sentence so it translates naturally.
        val sector = ((atan2(-d.y, d.x) / (PI / 4)).roundToInt() + 8) % 8
        when (sector) {
            0 -> tr("Off center by {pct}%, to the right.", "pct" to pct)
            1 -> tr("Off center by {pct}%, up and to the right.", "pct" to pct)
            2 -> tr("Off center by {pct}%, too high.", "pct" to pct)
            3 -> tr("Off center by {pct}%, up and to the left.", "pct" to pct)
            4 -> tr("Off center by {pct}%, to the left.", "pct" to pct)
            5 -> tr("Off center by {pct}%, down and to the left.", "pct" to pct)
            6 -> tr("Off center by {pct}%, too low.", "pct" to pct)
            else -> tr("Off center by {pct}%, down and to the right.", "pct" to pct)
        }
    }
    return Scored(falloff(off, 0.15f), note)
}

/**
 * Other half. [drawn] is what the player drew (strokes) and [truth] the real half, both in units where the shape is 1
 * tall and the fold is x = 0. Scored on the distance both ways: drawn to true (did you draw it right?) and true to
 * drawn (did you draw all of it?), so half a drawing can't score well.
 */
fun otherHalfScore(drawn: List<List<Offset>>, truth: List<Offset>): Scored {
    val strokes = drawn.filter { it.isNotEmpty() }.map { resample(it, 0.005f) }
    if (strokes.isEmpty()) return Scored(0, tr("Nothing drawn yet."))
    val mine = strokes.flatten()
    val real = resample(truth, 0.005f)
    var there = 0f
    var bias = 0f
    for (p in mine) {
        val q = nearestOn(p, truth)
        there += (p - q).getDistance()
        bias += abs(p.x) - abs(q.x)
    }
    there /= mine.size
    bias /= mine.size
    var back = 0f
    val missed = ArrayList<Offset>()
    // Missing means much further from the drawing than the drawing is from the truth, not just drawn a bit off.
    val far = maxOf(0.06f, there * 2.5f)
    for (q in real) {
        val d = strokes.minOf { distanceToPolyline(q, it) }
        back += d
        if (d > far) missed += q
    }
    back /= real.size
    val miss = (there + back) / 2f
    val note = when {
        missed.size > real.size * 0.15f ->
            if (missed.map { it.y }.average() < 0.5) tr("Part of the top half is missing.") else tr("Part of the bottom half is missing.")
        bias > 0.015f -> tr("A bit too wide. Hug the fold closer.")
        bias < -0.015f -> tr("A bit too narrow. Swing out wider.")
        miss < 0.008f -> tr("A mirror image. Beautiful.")
        else -> tr("Close. A little wobbly in places.")
    }
    return Scored(falloff(miss, 0.08f), note)
}

/** A drawn ring, measured: the best circle, where the stroke was cut off past a full turn, and the flaws. */
data class Ring(
    val centre: Offset,
    val radius: Float,
    /** The stroke up to one full turn. */
    val kept: List<Offset>,
    /** The overlap past a full turn, which isn't scored. */
    val overlap: List<Offset>,
    /** How much of a full turn is missing, in degrees (0 when it closed). */
    val gapDegrees: Float,
    /** Spread of the radius, as a fraction of it. */
    val spread: Float,
    /** How oval: the amplitude of the two-lobed wobble of the radius, as a fraction of it. */
    val oval: Float,
    /** Small shakes along the line, as a fraction of the radius. */
    val shake: Float,
    /** Which way round it was drawn: 1 clockwise on screen, -1 anticlockwise. */
    val turn: Int,
)

/**
 * Measures a ring drawn in dp. Null when it isn't a ring attempt yet (too short, under half a turn, or tiny), so the
 * round can ask again instead of scoring a slip of the finger.
 */
fun measureRing(stroke: List<Offset>, minRadius: Float = 28f): Ring? {
    if (stroke.size < 6 || length(stroke) < 2f * PI.toFloat() * minRadius * 0.5f) return null
    val pts = resample(stroke, 2f)
    if (pts.size < 12) return null
    var (c, _) = fitCircle(pts)
    // Unwrap the angle around the first fit to find where one full turn ends; the rest is overlap.
    var sweep = 0f
    var prev = angleAround(c, pts[0])
    var end = pts.size
    for (i in 1 until pts.size) {
        val a = angleAround(c, pts[i])
        var d = a - prev
        if (d > PI) d -= TAU
        if (d < -PI) d += TAU
        sweep += d
        prev = a
        if (abs(sweep) >= TAU) { end = i + 1; break }
    }
    if (abs(sweep) < PI.toFloat()) return null
    val kept = pts.subList(0, end)
    val overlap = if (end < pts.size) pts.subList(end - 1, pts.size) else emptyList()
    val fit = fitCircle(kept)
    c = fit.first
    val r = fit.second
    if (r < minRadius) return null
    val radii = kept.map { (it - c).getDistance() }
    val mean = radii.average().toFloat()
    val spread = sqrt(radii.map { (it - mean) * (it - mean) }.average()).toFloat() / mean
    // The two-lobed (oval) part of the radius, by projecting onto cos 2θ and sin 2θ.
    var a2 = 0f
    var b2 = 0f
    for ((i, p) in kept.withIndex()) {
        val t = angleAround(c, p)
        a2 += (radii[i] - mean) * cos(2 * t)
        b2 += (radii[i] - mean) * sin(2 * t)
    }
    val oval = 2f * sqrt(a2 * a2 + b2 * b2) / kept.size / mean
    // Shake: what's left after a short moving average smooths the radius.
    val w = 5
    var shake = 0f
    for (i in radii.indices) {
        val from = (i - w).coerceAtLeast(0)
        val to = (i + w).coerceAtMost(radii.lastIndex)
        var s = 0f
        for (j in from..to) s += radii[j]
        val smooth = s / (to - from + 1)
        shake += (radii[i] - smooth) * (radii[i] - smooth)
    }
    shake = sqrt(shake / radii.size) / mean
    val gap = if (abs(sweep) >= TAU) 0f else (360f - Math.toDegrees(abs(sweep).toDouble()).toFloat())
    return Ring(c, mean, kept, overlap, gap, spread, oval, shake, if (sweep > 0) 1 else -1)
}

/**
 * Promise ring: one score from closure, an even radius and a steady line, and a note on the biggest flaw. [k] tightens
 * with the level (1 at level 1); with a [guideRadius] (dp, from the levels where size counts) the size counts too.
 */
fun ringScore(ring: Ring, k: Float = 1f, guideRadius: Float? = null): Scored {
    val gapCost = 2.5f * ring.gapDegrees / 360f
    val ovalCost = 3.5f * ring.oval / sqrt(2f)
    // The spread left over once the oval is accounted for: lumps and a spiral.
    val lumpCost = 3.5f * sqrt((ring.spread * ring.spread - ring.oval * ring.oval / 2f).coerceAtLeast(0f))
    val shakeCost = 8f * ring.shake
    val sizeCost = if (guideRadius != null && guideRadius > 0f) sizeCost(ring.radius / guideRadius) else 0f
    val cost = gapCost + ovalCost + lumpCost + shakeCost + sizeCost
    val worst = maxOf(gapCost, ovalCost, lumpCost, shakeCost, sizeCost)
    val note = when {
        cost < 0.03f -> tr("Round as a promise.")
        worst == sizeCost -> sizeNote(ring.radius / guideRadius!!)
        worst == gapCost -> tr("Left {deg}° open.", "deg" to ring.gapDegrees.roundToInt())
        worst == ovalCost -> if (ring.oval < 0.06f) tr("A bit oval.") else tr("Quite oval. Keep the sides even.")
        worst == lumpCost -> tr("Lumpy in places. Keep the same distance from the middle.")
        else -> tr("A shaky line. Slow and steady.")
    }
    return Scored(falloff(cost, k), note)
}
