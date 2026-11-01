package app.heartpact.exercise

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/** One body landmark in normalised image coordinates (0..1, y down), with the model's visibility. */
data class Landmark(val x: Float, val y: Float, val visibility: Float = 1f)

/**
 * A point in space. MediaPipe's world landmarks are in metres with the origin between the hips, x to the right of the
 * picture, y down and z away from the camera; a picture-only point has z = 0.
 */
data class Point3(val x: Float, val y: Float, val z: Float = 0f) {
    operator fun plus(o: Point3) = Point3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Point3) = Point3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Float) = Point3(x * k, y * k, z * k)
    infix fun dot(o: Point3) = x * o.x + y * o.y + z * o.z
    val length: Float get() = sqrt(x * x + y * y + z * z)
    /** Length along the floor: everything but the up-down part. */
    val across: Float get() = hypot(x, z)
}

/** The angle at [b] in degrees, between the segments to [a] and [c]. 180 when a segment has no length. */
fun angleAt(a: Point3, b: Point3, c: Point3): Float {
    val u = a - b; val v = c - b
    val norm = u.length * v.length
    if (norm < 1e-6f) return 180f
    return Math.toDegrees(acos(((u dot v) / norm).coerceIn(-1f, 1f)).toDouble()).toFloat()
}

/**
 * One detected pose: the 33 MediaPipe landmarks in their standard order, in the picture ([landmarks], for drawing,
 * framing and visibility) and, when the model gave them, in space ([world], metres). [aspect] is image width over
 * height, so picture measurements are in real proportions rather than in a squashed square.
 *
 * Every measurement of the body (angles, lengths, heights, slopes) goes through [at]: the world landmarks when there
 * are some, so a knee bend reads the same seen side-on, at 45 degrees or from the front, and the picture otherwise,
 * where only what lies across the picture can be measured.
 */
class Pose(val landmarks: List<Landmark>, val timestampMs: Long, val aspect: Float = 1f, val world: List<Point3>? = null) {
    operator fun get(index: Int): Landmark = landmarks[index]

    /** Measured in space (any camera angle) rather than across the picture. */
    val hasDepth: Boolean get() = world != null && world.size >= COUNT

    /** Where joint [i] is for measuring: the world landmark, or the picture point in real proportions. */
    fun at(i: Int): Point3 = if (hasDepth) world!![i] else Point3(this[i].x * aspect, this[i].y, 0f)

    fun mid(a: Int, b: Int): Point3 = (at(a) + at(b)) * 0.5f

    fun distance(a: Int, b: Int) = (at(a) - at(b)).length

    /** Distance across the picture, in picture heights: for what the fixed camera sees move (the hips sinking). */
    fun pictureDistance(a: Int, b: Int) = hypot((this[a].x - this[b].x) * aspect, this[a].y - this[b].y)

    /** Angle at [b] in degrees, between the segments to [a] and [c]. */
    fun angle(a: Int, b: Int, c: Int): Float = angleAt(at(a), at(b), at(c))

    fun visibility(vararg indices: Int) = indices.map { this[it].visibility }.average().toFloat()

    /** The body side (left or right joints) the camera sees better. */
    fun betterSide(left: IntArray, right: IntArray): IntArray = if (visibility(*left) >= visibility(*right)) left else right

    /** How far the segment from [base] to [top] leans away from straight up, in degrees. */
    fun lean(base: Int, top: Int): Float = leanOf(at(base), at(top))

    /** How far the segment from [a] to [b] slopes away from level, in degrees (0 flat, 90 upright). */
    fun tilt(a: Int, b: Int): Float = tiltOf(at(a), at(b))

    /** How far [lower] sits beneath [upper] (y grows downward): negative when it is above. */
    fun below(upper: Int, lower: Int) = at(lower).y - at(upper).y

    /** Distance between two joints along the floor (side to side and toward the camera), ignoring height. */
    fun spread(a: Int, b: Int) = (at(a) - at(b)).across

    /**
     * How far [middle] sits below the straight line from [a] to [c], as a share of that line's length: positive when it
     * sags beneath it (a plank's hips dropping), negative above it (hips piked up).
     */
    fun sag(a: Int, middle: Int, c: Int): Float = sagOf(at(a), at(middle), at(c))

    companion object {
        const val NOSE = 0
        const val L_SHOULDER = 11; const val R_SHOULDER = 12
        const val L_ELBOW = 13; const val R_ELBOW = 14
        const val L_WRIST = 15; const val R_WRIST = 16
        const val L_HIP = 23; const val R_HIP = 24
        const val L_KNEE = 25; const val R_KNEE = 26
        const val L_ANKLE = 27; const val R_ANKLE = 28
        const val COUNT = 33

        /** The joints the stick figure is drawn from, and the only ones that leave the phone for the partner's live view. */
        val FIGURE = intArrayOf(NOSE, L_SHOULDER, R_SHOULDER, L_ELBOW, R_ELBOW, L_WRIST, R_WRIST, L_HIP, R_HIP, L_KNEE, R_KNEE, L_ANKLE, R_ANKLE)
    }
}

/** Degrees between the segment [base]→[top] and straight up. */
fun leanOf(base: Point3, top: Point3): Float {
    val d = top - base
    if (d.length < 1e-6f) return 0f
    return Math.toDegrees(atan2(d.across.toDouble(), (-d.y).toDouble())).toFloat()
}

/** Degrees between the segment [a]→[b] and level ground. */
fun tiltOf(a: Point3, b: Point3): Float {
    val d = b - a
    if (d.length < 1e-6f) return 0f
    return Math.toDegrees(atan2(abs(d.y).toDouble(), d.across.toDouble())).toFloat()
}

/** See [Pose.sag]. */
fun sagOf(a: Point3, middle: Point3, c: Point3): Float {
    val line = c - a
    val lengthSq = line dot line
    if (lengthSq < 1e-9f) return 0f
    val t = ((middle - a) dot line) / lengthSq
    val onLine = a + line * t
    return (middle.y - onLine.y) / sqrt(lengthSq)
}
