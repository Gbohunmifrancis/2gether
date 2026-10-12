package app.heartpact.exercise

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.sqrt

/** One body landmark in normalised image coordinates (0..1, y down), with the model's visibility. */
data class Landmark(val x: Float, val y: Float, val visibility: Float = 1f)

/**
 * One detected pose: the 33 MediaPipe landmarks in their standard order. [aspect] is image width
 * over height, so angles are measured in real proportions rather than in a squashed square.
 */
class Pose(val landmarks: List<Landmark>, val timestampMs: Long, val aspect: Float = 1f) {
    operator fun get(index: Int): Landmark = landmarks[index]

    private fun px(l: Landmark) = l.x * aspect
    fun distance(a: Int, b: Int) = hypot(px(this[a]) - px(this[b]), this[a].y - this[b].y)

    /** Angle at [b] in degrees, between the segments to [a] and [c]. */
    fun angle(a: Int, b: Int, c: Int): Float {
        val ax = px(this[a]) - px(this[b]); val ay = this[a].y - this[b].y
        val cx = px(this[c]) - px(this[b]); val cy = this[c].y - this[b].y
        val norm = sqrt(ax * ax + ay * ay) * sqrt(cx * cx + cy * cy)
        if (norm < 1e-6f) return 180f
        return Math.toDegrees(acos(((ax * cx + ay * cy) / norm).coerceIn(-1f, 1f)).toDouble()).toFloat()
    }

    fun visibility(vararg indices: Int) = indices.map { this[it].visibility }.average().toFloat()

    /** The body side (left or right joints) the camera sees better. */
    fun betterSide(left: IntArray, right: IntArray): IntArray = if (visibility(*left) >= visibility(*right)) left else right

    fun verticalGap(upper: Int, lower: Int) = this[lower].y - this[upper].y
    fun horizontalSpread(a: Int, b: Int) = abs(px(this[a]) - px(this[b]))

    companion object {
        const val NOSE = 0
        const val L_SHOULDER = 11; const val R_SHOULDER = 12
        const val L_ELBOW = 13; const val R_ELBOW = 14
        const val L_WRIST = 15; const val R_WRIST = 16
        const val L_HIP = 23; const val R_HIP = 24
        const val L_KNEE = 25; const val R_KNEE = 26
        const val L_ANKLE = 27; const val R_ANKLE = 28
        const val COUNT = 33
    }
}
