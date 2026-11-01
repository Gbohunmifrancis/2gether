package app.heartpact

import app.heartpact.exercise.Landmark
import app.heartpact.exercise.Point3
import app.heartpact.exercise.Pose
import app.heartpact.exercise.Pose.Companion.L_ANKLE
import app.heartpact.exercise.Pose.Companion.L_ELBOW
import app.heartpact.exercise.Pose.Companion.L_HIP
import app.heartpact.exercise.Pose.Companion.L_KNEE
import app.heartpact.exercise.Pose.Companion.L_SHOULDER
import app.heartpact.exercise.Pose.Companion.L_WRIST
import app.heartpact.exercise.Pose.Companion.NOSE
import app.heartpact.exercise.Pose.Companion.R_ANKLE
import app.heartpact.exercise.Pose.Companion.R_ELBOW
import app.heartpact.exercise.Pose.Companion.R_HIP
import app.heartpact.exercise.Pose.Companion.R_KNEE
import app.heartpact.exercise.Pose.Companion.R_SHOULDER
import app.heartpact.exercise.Pose.Companion.R_WRIST
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * Synthetic people in a room, seen by a fixed camera, as MediaPipe would report them: the picture's landmarks (an
 * orthographic view, the floor across the lower part of a portrait picture) and the world landmarks (metres, centred
 * between the hips). Bodies are built in their own frame: forward (toward the head when lying), down (y grows downward,
 * the floor at 0) and side (the person's right positive); [yawDeg] turns them on the spot: 0 faces the camera, 90 is
 * side-on.
 */
object Bodies {
    /** A joint in the body's frame: metres forward, down (floor 0, so up is negative) and to the right. */
    data class P(val f: Float, val d: Float, val s: Float = 0f)

    private const val ASPECT = 0.75f
    /** Picture heights per metre. */
    private const val SCALE = 0.42f

    /**
     * [joints] maps landmark indexes to body positions; joints left out sit at the hips, unseen. [depth] false leaves
     * out the world landmarks (an older pose, or a test of the picture-only path).
     */
    fun pose(joints: Map<Int, P>, atMs: Long, yawDeg: Float = 90f, depth: Boolean = true, visibility: Float = 0.95f, noise: Float = 0f, random: Random? = null): Pose {
        val yaw = Math.toRadians(yawDeg.toDouble())
        // Forward and right in the room: z grows away from the camera, so facing it is forward = -z.
        val fx = sin(yaw).toFloat(); val fz = -cos(yaw).toFloat()
        val rx = cos(yaw).toFloat(); val rz = sin(yaw).toFloat()
        val hip = listOf(L_HIP, R_HIP).mapNotNull { joints[it] }.let { h -> P(h.map { it.f }.average().toFloat(), h.map { it.d }.average().toFloat(), h.map { it.s }.average().toFloat()) }
        fun room(p: P): Point3 {
            fun jitter() = if (random != null && noise > 0) (random.nextFloat() * 2 - 1) * noise else 0f
            return Point3(fx * p.f + rx * p.s + jitter(), p.d + jitter(), fz * p.f + rz * p.s + jitter())
        }
        val rooms = List(Pose.COUNT) { i -> joints[i]?.let(::room) ?: room(hip) }
        // MediaPipe's world origin: the middle of the hips.
        val center = (rooms[L_HIP] + rooms[R_HIP]) * 0.5f
        val landmarks = rooms.mapIndexed { i, r -> Landmark(0.5f + r.x * SCALE / ASPECT, 0.8f + r.y * SCALE, if (joints.containsKey(i)) visibility else 0.1f) }
        val world = rooms.map { it - center }
        return Pose(landmarks, atMs, ASPECT, if (depth) world else null)
    }

    /** Both sides of a body part at once, [width] apart. */
    fun pair(left: Int, right: Int, at: P, width: Float): Map<Int, P> = mapOf(left to at.copy(s = at.s - width / 2), right to at.copy(s = at.s + width / 2))

    private const val THIGH = 0.43f
    private const val SHIN = 0.43f

    /** Upright, arms hanging: the start of most standing exercises. */
    fun standing(): Map<Int, P> = squat(178f)

    /**
     * A squat with the knees at [knee] degrees, as one moves: feet planted hip-width apart, the shins tip forward, the
     * hips sink back and down, the trunk leans a little, the arms reach forward for balance.
     */
    fun squat(knee: Float): Map<Int, P> {
        val bend = Math.toRadians(180.0 - knee)
        val shin = bend * 0.4; val thigh = bend * 0.6; val trunk = bend * 0.4
        val kneeAt = P((SHIN * sin(shin)).toFloat(), (-SHIN * cos(shin)).toFloat())
        val hipAt = P((kneeAt.f - THIGH * sin(thigh)).toFloat(), (kneeAt.d - THIGH * cos(thigh)).toFloat())
        val shoulder = P((hipAt.f + 0.5 * sin(trunk)).toFloat(), (hipAt.d - 0.5 * cos(trunk)).toFloat())
        val reach = (bend / Math.PI * 2).toFloat().coerceIn(0f, 1f)
        val elbow = P(shoulder.f + 0.28f * reach, shoulder.d + 0.28f * (1 - reach))
        val wrist = P(elbow.f + 0.26f * reach, elbow.d + 0.26f * (1 - reach))
        return pair(L_ANKLE, R_ANKLE, P(0f, 0f), 0.26f) + pair(L_KNEE, R_KNEE, kneeAt, 0.26f) + pair(L_HIP, R_HIP, hipAt, 0.26f) +
            pair(L_SHOULDER, R_SHOULDER, shoulder, 0.38f) + pair(L_ELBOW, R_ELBOW, elbow, 0.44f) + pair(L_WRIST, R_WRIST, wrist, 0.44f) +
            mapOf(NOSE to P(shoulder.f + 0.08f, shoulder.d - 0.22f))
    }

    /**
     * Sitting on a chair swinging the lower legs: the hips stay on the seat, the thighs level, only the knees move
     * ([knee] 90 hanging down, near 180 kicked out straight).
     */
    fun chair(knee: Float): Map<Int, P> {
        val a = Math.toRadians(knee - 90.0)
        val hipAt = P(-0.2f, -0.46f); val kneeAt = P(0.23f, -0.46f)
        val ankle = P((kneeAt.f + SHIN * sin(a)).toFloat(), (kneeAt.d + SHIN * cos(a)).toFloat())
        val shoulder = P(-0.24f, -0.96f)
        return pair(L_ANKLE, R_ANKLE, ankle, 0.26f) + pair(L_KNEE, R_KNEE, kneeAt, 0.26f) + pair(L_HIP, R_HIP, hipAt, 0.26f) +
            pair(L_SHOULDER, R_SHOULDER, shoulder, 0.38f) + pair(L_ELBOW, R_ELBOW, P(-0.2f, -0.7f), 0.44f) + pair(L_WRIST, R_WRIST, P(0.0f, -0.55f), 0.44f) +
            mapOf(NOSE to P(-0.18f, -1.18f))
    }

    /** The knee of a leg from its hip [h] to its ankle [a] (each segment [THIGH]/[SHIN]), bent forward. */
    private fun knee(h: P, a: P): P {
        val dx = a.f - h.f; val dy = a.d - h.d
        val l = hypot(dx, dy).coerceAtMost(THIGH + SHIN - 1e-4f)
        val toAnkle = atan2(dy, dx)
        val alpha = acos(((THIGH * THIGH + l * l - SHIN * SHIN) / (2 * THIGH * l)).coerceIn(-1f, 1f))
        // Forward of the hip-to-ankle line: turn toward +f, which is counter-clockwise here (y down).
        val dir = toAnkle - alpha
        return P(h.f + THIGH * cos(dir), h.d + THIGH * sin(dir), h.s)
    }

    /**
     * A lunge [depth] of the way down (0 standing, 1 at the bottom, about 95 degrees at both knees), the [left] leg in
     * front: the feet split along the floor ([split] of a full step, with the depth unless given) and the hips sink between them.
     */
    fun lunge(depth: Float, left: Boolean, split: Float = depth): Map<Int, P> {
        val apart = 0.36f * split
        val hip = P(0f, -(THIGH + SHIN - 0.33f * depth) + 0.002f)
        val front = P(apart, 0f); val back = P(-apart, 0f)
        val (l, r) = if (left) front to back else back to front
        val lHip = hip.copy(s = -0.13f); val rHip = hip.copy(s = 0.13f)
        val shoulder = P(hip.f + 0.02f, hip.d - 0.5f)
        return mapOf(L_HIP to lHip, R_HIP to rHip, L_ANKLE to l.copy(s = -0.13f), R_ANKLE to r.copy(s = 0.13f),
            L_KNEE to knee(lHip, l.copy(s = -0.13f)), R_KNEE to knee(rHip, r.copy(s = 0.13f))) +
            pair(L_SHOULDER, R_SHOULDER, shoulder, 0.38f) + pair(L_ELBOW, R_ELBOW, P(shoulder.f, shoulder.d + 0.28f), 0.46f) +
            pair(L_WRIST, R_WRIST, P(shoulder.f, shoulder.d + 0.54f), 0.46f) + mapOf(NOSE to P(shoulder.f + 0.08f, shoulder.d - 0.22f))
    }

    /**
     * A high plank (forward is toward the head), the hips on the line from shoulders to ankles, with each leg's hip angle
     * (shoulder, hip, knee) at [leftHip] and [rightHip]: 175 extended, about 100 with the knee driven to the chest.
     */
    fun climber(leftHip: Float, rightHip: Float): Map<Int, P> {
        val shoulder = P(0.6f, -0.55f); val hip = P(0f, -0.32f); val ankle = P(-0.7f, -0.05f)
        val back = atan2(ankle.d - hip.d, ankle.f - hip.f)
        val towardShoulder = atan2(shoulder.d - hip.d, shoulder.f - hip.f)
        fun leg(angle: Float, side: Float): Pair<P, P> {
            // Rotate the thigh from straight back (in line with the body) toward the chest, under the body.
            val extended = (Math.toDegrees((towardShoulder - back).toDouble()) + 360) % 360
            val turn = Math.toRadians(extended - angle).toFloat()
            val dir = back - turn
            val kneeAt = P(hip.f + THIGH * cos(dir), hip.d + THIGH * sin(dir), side)
            val ankleAt = if (angle > 165) ankle.copy(s = side) else P(kneeAt.f - 0.3f, (kneeAt.d + 0.3f).coerceAtMost(0f), side)
            return kneeAt to ankleAt
        }
        val (lk, la) = leg(leftHip, -0.13f); val (rk, ra) = leg(rightHip, 0.13f)
        return pair(L_SHOULDER, R_SHOULDER, shoulder, 0.38f) + pair(L_ELBOW, R_ELBOW, P(0.6f, -0.28f), 0.4f) + pair(L_WRIST, R_WRIST, P(0.6f, 0f), 0.4f) +
            pair(L_HIP, R_HIP, hip, 0.26f) + mapOf(L_KNEE to lk, R_KNEE to rk, L_ANKLE to la, R_ANKLE to ra, NOSE to P(0.82f, -0.62f))
    }

    /** Lying on the back, knees bent and feet flat (forward is toward the feet); [lift] 0 hips on the floor, 1 up in line. */
    fun bridge(lift: Float, kneesBent: Boolean = true): Map<Int, P> {
        val shoulder = P(-0.55f, -0.05f)
        val kneeAt = if (kneesBent) P(0.25f, -0.4f) else P(0.38f, -0.06f)
        val ankle = if (kneesBent) P(0.45f, 0f) else P(0.8f, -0.05f)
        val hip = P(-0.05f, -0.08f - 0.19f * lift)
        return pair(L_SHOULDER, R_SHOULDER, shoulder, 0.38f) + pair(L_ELBOW, R_ELBOW, P(-0.3f, -0.03f), 0.5f) + pair(L_WRIST, R_WRIST, P(-0.05f, -0.02f), 0.5f) +
            pair(L_HIP, R_HIP, hip, 0.26f) + pair(L_KNEE, R_KNEE, kneeAt, 0.26f) + pair(L_ANKLE, R_ANKLE, ankle, 0.26f) + mapOf(NOSE to P(-0.8f, -0.1f))
    }

    /** Standing and folding forward at the hips to [fold] degrees with straight legs (a "good morning"). */
    fun hinge(fold: Float): Map<Int, P> {
        val t = Math.toRadians(fold.toDouble())
        val hip = P(0f, -0.86f)
        val shoulder = P((0.5 * sin(t)).toFloat(), (hip.d - 0.5 * cos(t)).toFloat())
        return pair(L_ANKLE, R_ANKLE, P(0f, 0f), 0.26f) + pair(L_KNEE, R_KNEE, P(0f, -0.43f), 0.26f) + pair(L_HIP, R_HIP, hip, 0.26f) +
            pair(L_SHOULDER, R_SHOULDER, shoulder, 0.38f) + pair(L_ELBOW, R_ELBOW, P(shoulder.f, shoulder.d + 0.28f), 0.44f) +
            pair(L_WRIST, R_WRIST, P(shoulder.f, shoulder.d + 0.54f), 0.44f) + mapOf(NOSE to P(shoulder.f + 0.2f, shoulder.d - 0.1f))
    }

    /** A burpee's key positions: standing, crouched with hands down, and the plank. */
    val burpeeStand: Map<Int, P> get() = standing()
    val burpeeCrouch: Map<Int, P> get() = pair(L_ANKLE, R_ANKLE, P(0f, 0f), 0.26f) + pair(L_KNEE, R_KNEE, P(0.25f, -0.35f), 0.26f) +
        pair(L_HIP, R_HIP, P(-0.15f, -0.5f), 0.26f) + pair(L_SHOULDER, R_SHOULDER, P(0.2f, -0.85f), 0.38f) + pair(L_ELBOW, R_ELBOW, P(0.27f, -0.45f), 0.4f) +
        pair(L_WRIST, R_WRIST, P(0.3f, 0f), 0.4f) + mapOf(NOSE to P(0.35f, -1.0f))
    val burpeePlank: Map<Int, P> get() = climber(175f, 175f)

    /** In between two positions, joint by joint. */
    fun between(a: Map<Int, P>, b: Map<Int, P>, t: Float): Map<Int, P> = a.mapValues { (i, p) ->
        val q = b[i] ?: p
        P(p.f + (q.f - p.f) * t, p.d + (q.d - p.d) * t, p.s + (q.s - p.s) * t)
    }

    /** Sitting against a wall: hips [hipDrop] metres below the knee's height (0 level), the back [lean] degrees from upright. */
    fun wallSit(hipDrop: Float = 0f, lean: Float = 0f, standing: Boolean = false): Map<Int, P> {
        if (standing) return standing()
        val hip = P(0f, -0.45f + hipDrop); val kneeAt = P(0.42f, -0.45f)
        val t = Math.toRadians(lean.toDouble())
        val shoulder = P((hip.f + 0.5 * sin(t)).toFloat(), (hip.d - 0.5 * cos(t)).toFloat())
        return pair(L_ANKLE, R_ANKLE, P(0.43f, 0f), 0.26f) + pair(L_KNEE, R_KNEE, kneeAt, 0.26f) + pair(L_HIP, R_HIP, hip, 0.26f) +
            pair(L_SHOULDER, R_SHOULDER, shoulder, 0.38f) + pair(L_ELBOW, R_ELBOW, P(shoulder.f + 0.05f, shoulder.d + 0.28f), 0.44f) +
            pair(L_WRIST, R_WRIST, P(shoulder.f + 0.3f, shoulder.d + 0.4f), 0.44f) + mapOf(NOSE to P(shoulder.f + 0.08f, shoulder.d - 0.22f))
    }

    /**
     * A side plank seen from the front (yaw 0): lying on the left side, up on the left forearm, the body along the
     * picture with the head to the left; [hipSag] lowers the hips in metres. Without [propped], lying flat on the side.
     */
    fun sidePlank(hipSag: Float = 0f, propped: Boolean = true): Map<Int, P> {
        // Here "forward" runs along the body to the feet, "down" is down and "side" toward the camera's left. Lying on
        // the left side, the left joints are the lower ones, the right ones stacked above them.
        val lift = if (propped) 1f else 0.15f
        fun stacked(left: Int, right: Int, f: Float, lowerD: Float, gap: Float) = mapOf(left to P(f, lowerD), right to P(f, lowerD - gap))
        return stacked(L_SHOULDER, R_SHOULDER, -0.6f, -0.42f * lift - 0.03f, 0.35f) + stacked(L_HIP, R_HIP, 0f, -0.2f * lift - 0.03f + hipSag, 0.3f) +
            stacked(L_ANKLE, R_ANKLE, 0.75f, -0.05f, 0.1f) + stacked(L_KNEE, R_KNEE, 0.38f, -0.12f * lift - 0.04f + hipSag / 2, 0.2f) +
            mapOf(L_ELBOW to P(-0.6f, -0.03f), L_WRIST to P(-0.32f, -0.02f), R_ELBOW to P(-0.35f, -0.62f * lift), R_WRIST to P(-0.1f, -0.5f * lift),
                NOSE to P(-0.85f, -0.62f * lift))
    }
}
