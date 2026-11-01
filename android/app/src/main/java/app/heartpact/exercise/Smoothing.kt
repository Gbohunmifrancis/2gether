package app.heartpact.exercise

import kotlin.math.PI
import kotlin.math.abs

/**
 * The One Euro filter (Casiez, Roussel and Vogel, CHI 2012): a low-pass filter whose cutoff rises with speed. Still,
 * it smooths hard (the model's frame-to-frame jitter disappears); moving fast, it barely lags (the bottom of a quick
 * rep isn't shaved off). Time comes from the frames, so it works the same at 10 or 30 frames a second.
 *
 * [minCutoff] is the cutoff at rest in Hz; [beta] how much it rises per unit of speed (units per second); [dCutoff]
 * smooths the speed estimate itself.
 */
class OneEuroFilter(private val minCutoff: Double, private val beta: Double, private val dCutoff: Double = 1.0) {
    private var last: Double? = null
    private var lastSpeed = 0.0
    private var lastMs = 0L

    fun filter(value: Double, atMs: Long): Double {
        val previous = last
        if (previous == null || atMs <= lastMs) {
            if (previous == null) { last = value; lastMs = atMs }
            return previous ?: value
        }
        val dt = (atMs - lastMs) / 1000.0
        val speed = smooth((value - previous) / dt, lastSpeed, alpha(dt, dCutoff))
        val result = smooth(value, previous, alpha(dt, minCutoff + beta * abs(speed)))
        last = result; lastSpeed = speed; lastMs = atMs
        return result
    }

    fun reset() { last = null; lastSpeed = 0.0 }

    private fun smooth(value: Double, previous: Double, alpha: Double) = previous + alpha * (value - previous)

    private fun alpha(dt: Double, cutoff: Double): Double {
        val tau = 1.0 / (2 * PI * cutoff)
        return 1.0 / (1.0 + tau / dt)
    }
}

/**
 * Smooths a stream of poses joint by joint with [OneEuroFilter]s, in the picture and in space, before anything is
 * measured: jitter can then neither fake a rep (a knee angle flickering across a zone edge) nor drop one. Visibility
 * is passed through as the model gave it. After a gap (nobody in frame for a moment) the filters start afresh, so a
 * person stepping back in isn't dragged from where they were.
 */
class PoseSmoother(
    private val imageCutoff: Double = 1.5,
    private val imageBeta: Double = 4.0,
    private val worldCutoff: Double = 1.5,
    private val worldBeta: Double = 3.0,
    private val gapMs: Long = 600,
) {
    private val image = Array(Pose.COUNT * 2) { OneEuroFilter(imageCutoff, imageBeta) }
    private val world = Array(Pose.COUNT * 3) { OneEuroFilter(worldCutoff, worldBeta) }
    private var lastMs: Long? = null
    private var hadDepth = false

    fun smooth(pose: Pose?): Pose? {
        if (pose == null || pose.landmarks.size < Pose.COUNT) return pose
        val t = pose.timestampMs
        val last = lastMs
        if (last == null || t - last > gapMs || t < last || pose.hasDepth != hadDepth) reset()
        lastMs = t
        hadDepth = pose.hasDepth
        val points = pose.landmarks.mapIndexed { i, l ->
            if (i >= Pose.COUNT) l
            else Landmark(image[i * 2].filter(l.x.toDouble(), t).toFloat(), image[i * 2 + 1].filter(l.y.toDouble(), t).toFloat(), l.visibility)
        }
        val space = pose.world?.takeIf { pose.hasDepth }?.mapIndexed { i, p ->
            if (i >= Pose.COUNT) p
            else Point3(world[i * 3].filter(p.x.toDouble(), t).toFloat(), world[i * 3 + 1].filter(p.y.toDouble(), t).toFloat(), world[i * 3 + 2].filter(p.z.toDouble(), t).toFloat())
        }
        return Pose(points, t, pose.aspect, space ?: pose.world)
    }

    fun reset() {
        image.forEach { it.reset() }
        world.forEach { it.reset() }
    }
}
