package app.heartpact

import app.heartpact.exercise.ExerciseType
import app.heartpact.exercise.Landmark
import app.heartpact.exercise.Pose
import app.heartpact.exercise.Pose.Companion.L_ANKLE
import app.heartpact.exercise.Pose.Companion.L_ELBOW
import app.heartpact.exercise.Pose.Companion.L_HIP
import app.heartpact.exercise.Pose.Companion.L_KNEE
import app.heartpact.exercise.Pose.Companion.L_SHOULDER
import app.heartpact.exercise.Pose.Companion.L_WRIST
import app.heartpact.exercise.Pose.Companion.R_ANKLE
import app.heartpact.exercise.Pose.Companion.R_HIP
import app.heartpact.exercise.Pose.Companion.R_KNEE
import app.heartpact.exercise.Pose.Companion.R_SHOULDER
import app.heartpact.exercise.Pose.Companion.R_WRIST
import app.heartpact.exercise.RepCounter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * Synthetic skeletons with exact joint angles, played through the counter as real movement would be:
 * smooth cycles, jitter around a threshold, one-frame spikes, and a body half out of frame.
 */
class RepCounterTest {
    private val frameMs = 100L

    /** A standing, front-facing person; tests move the joints they care about. */
    private fun standing(visibility: Float = 0.95f): MutableList<Landmark> = MutableList(Pose.COUNT) { Landmark(0.5f, 0.5f, visibility) }.apply {
        this[L_SHOULDER] = Landmark(0.44f, 0.30f, visibility); this[R_SHOULDER] = Landmark(0.56f, 0.30f, visibility)
        this[L_ELBOW] = Landmark(0.42f, 0.42f, visibility); this[Pose.R_ELBOW] = Landmark(0.58f, 0.42f, visibility)
        this[L_WRIST] = Landmark(0.42f, 0.52f, visibility); this[R_WRIST] = Landmark(0.58f, 0.52f, visibility)
        this[L_HIP] = Landmark(0.46f, 0.55f, visibility); this[R_HIP] = Landmark(0.54f, 0.55f, visibility)
        this[L_KNEE] = Landmark(0.46f, 0.72f, visibility); this[R_KNEE] = Landmark(0.54f, 0.72f, visibility)
        this[L_ANKLE] = Landmark(0.46f, 0.90f, visibility); this[R_ANKLE] = Landmark(0.54f, 0.90f, visibility)
    }

    /** Places [c] so the angle a-b-c is [deg], at [length] from b, bending toward +x. */
    private fun bend(a: Landmark, b: Landmark, deg: Float, length: Float): Landmark {
        val base = Math.atan2((a.y - b.y).toDouble(), (a.x - b.x).toDouble())
        val t = base - Math.toRadians(deg.toDouble())
        return Landmark((b.x + length * cos(t)).toFloat(), (b.y + length * sin(t)).toFloat(), b.visibility)
    }

    private fun play(counter: RepCounter, frames: List<MutableList<Landmark>>) = frames.mapIndexed { i, l -> counter.update(Pose(l, i * frameMs)) }.last()

    private fun angleFrames(values: List<Float>, a: Int, b: Int, c: Int, length: Float = 0.18f, visibility: Float = 0.95f) = values.map { deg ->
        standing(visibility).also { it[c] = bend(it[a], it[b], deg, length) }
    }

    /** Smooth reps with the brief pause at each end that real movement has, then standing still. */
    private fun cycles(rest: Float, active: Float, times: Int): List<Float> {
        val one = (0..6).map { rest + (active - rest) * (it / 6f) } + active + (5 downTo 0).map { rest + (active - rest) * (it / 6f) } + rest
        return List(times) { one }.flatten() + List(3) { rest }
    }

    @Test fun `squats count once per full bend and return`() {
        val state = play(RepCounter(ExerciseType.Squat), angleFrames(cycles(172f, 90f, 5), L_HIP, L_KNEE, L_ANKLE))
        assertEquals(5, state.count)
        assertTrue(state.inFrame)
    }

    @Test fun `jitter around a threshold never counts twice`() {
        // Down into the squat, then hover around the active edge and around the rest edge, then stand.
        val values = listOf(170f, 140f, 110f, 95f) + List(10) { if (it % 2 == 0) 100f else 112f } + listOf(130f, 150f) + List(10) { if (it % 2 == 0) 155f else 165f } + listOf(170f)
        assertEquals(1, play(RepCounter(ExerciseType.Squat), angleFrames(values, L_HIP, L_KNEE, L_ANKLE)).count)
    }

    @Test fun `a one-frame spike is not a rep`() {
        val counter = RepCounter(ExerciseType.Squat)
        val frames = angleFrames(listOf(170f, 170f, 90f, 170f, 170f), L_HIP, L_KNEE, L_ANKLE)
        // 50 ms apart: faster than any real squat.
        val state = frames.mapIndexed { i, l -> counter.update(Pose(l, i * 50L)) }.last()
        assertEquals(0, state.count)
    }

    @Test fun `joints the camera can barely see are ignored, not guessed`() {
        val state = play(RepCounter(ExerciseType.Squat), angleFrames(cycles(172f, 90f, 3), L_HIP, L_KNEE, L_ANKLE, visibility = 0.3f))
        assertEquals(0, state.count)
        assertFalse(state.inFrame)
    }

    @Test fun `no person in frame leaves the count alone`() {
        val counter = RepCounter(ExerciseType.Squat)
        play(counter, angleFrames(cycles(172f, 90f, 2), L_HIP, L_KNEE, L_ANKLE))
        val state = counter.update(null)
        assertEquals(2, state.count)
        assertFalse(state.inFrame)
    }

    @Test fun `push-ups follow the elbow`() =
        assertEquals(4, play(RepCounter(ExerciseType.PushUp), angleFrames(cycles(165f, 80f, 4), L_SHOULDER, L_ELBOW, L_WRIST, 0.12f)).count)

    @Test fun `sit-ups follow the hip`() =
        assertEquals(3, play(RepCounter(ExerciseType.SitUp), angleFrames(cycles(165f, 60f, 3), L_SHOULDER, L_HIP, L_KNEE)).count)

    @Test fun `arm raises count hands going above the head and back`() {
        val frames = List(3) {
            listOf(0.52f, 0.40f, 0.25f, 0.10f, 0.10f, 0.25f, 0.40f, 0.52f, 0.52f).map { y -> standing().also { l -> l[L_WRIST] = Landmark(0.42f, y); l[R_WRIST] = Landmark(0.58f, y) } }
        }.flatten()
        assertEquals(3, play(RepCounter(ExerciseType.ArmRaise), frames).count)
    }

    @Test fun `one arm up is not an arm raise`() {
        val frames = listOf(0.52f, 0.25f, 0.10f, 0.25f, 0.52f).map { y -> standing().also { l -> l[L_WRIST] = Landmark(0.42f, y) } }
        assertEquals(0, play(RepCounter(ExerciseType.ArmRaise), frames).count)
    }

    @Test fun `jumping jacks need arms up and feet apart together`() {
        fun jack(open: Boolean) = standing().also { l ->
            val wy = if (open) 0.12f else 0.52f
            l[L_WRIST] = Landmark(0.40f, wy); l[R_WRIST] = Landmark(0.60f, wy)
            val spread = if (open) 0.10f else 0.04f
            l[L_ANKLE] = Landmark(0.5f - spread, 0.9f); l[R_ANKLE] = Landmark(0.5f + spread, 0.9f)
        }
        val frames = List(4) { listOf(false, false, true, true, true, false, false) }.flatten().map(::jack)
        assertEquals(4, play(RepCounter(ExerciseType.JumpingJack), frames).count)
        val armsOnly = List(4) { listOf(false, true, true, false) }.flatten().map { open -> jack(false).also { if (open) { it[L_WRIST] = Landmark(0.4f, 0.12f); it[R_WRIST] = Landmark(0.6f, 0.12f) } } }
        assertEquals(0, play(RepCounter(ExerciseType.JumpingJack), armsOnly).count)
    }

    @Test fun `high knees count each knee raise`() {
        fun knees(leftUp: Boolean, rightUp: Boolean) = standing().also { l ->
            if (leftUp) l[L_KNEE] = Landmark(0.46f, 0.56f)
            if (rightUp) l[R_KNEE] = Landmark(0.54f, 0.56f)
        }
        val frames = List(5) { listOf(knees(false, false), knees(true, false), knees(true, false), knees(false, false), knees(false, false), knees(false, true), knees(false, true), knees(false, false), knees(false, false)) }.flatten()
        assertEquals(10, play(RepCounter(ExerciseType.HighKnees), frames).count)
    }

    private fun plankFrame(straight: Boolean) = standing().also { l ->
        // Side-on and horizontal: shoulders, hips and ankles along one line (or hips sagging).
        l[L_SHOULDER] = Landmark(0.25f, 0.60f); l[R_SHOULDER] = Landmark(0.25f, 0.60f, 0.2f)
        l[L_HIP] = Landmark(0.50f, if (straight) 0.63f else 0.78f); l[R_HIP] = Landmark(0.50f, 0.63f, 0.2f)
        l[L_ANKLE] = Landmark(0.80f, 0.66f); l[R_ANKLE] = Landmark(0.80f, 0.66f, 0.2f)
    }

    @Test fun `plank counts whole seconds held in a straight line`() {
        val counter = RepCounter(ExerciseType.Plank)
        val held = (0..32).map { plankFrame(true) }
        assertEquals(3, play(counter, held).count)
    }

    @Test fun `sagging hips stop the plank clock`() {
        val counter = RepCounter(ExerciseType.Plank)
        val frames = (0..15).map { plankFrame(true) } + (0..20).map { plankFrame(false) } + (0..15).map { plankFrame(true) }
        // 1.5 s, then 2 s sagging (not counted), then 1.5 s again: 3 whole seconds.
        assertEquals(3, play(counter, frames).count)
    }

    @Test fun `confidence is the visibility of the joints that counted`() {
        val state = play(RepCounter(ExerciseType.Squat), angleFrames(cycles(172f, 90f, 2), L_HIP, L_KNEE, L_ANKLE, visibility = 0.8f))
        assertEquals(0.8, state.confidence, 0.01)
    }

    @Test fun `minimum time matches the server's plausibility rule`() {
        assertEquals(8_000L, ExerciseType.SitUp.minimumMillis(10))
        assertEquals(27_000L, ExerciseType.Plank.minimumMillis(30))
    }

    @Test fun `the counter favours the side the camera sees`() {
        // Right leg hidden, left leg clear: still counts from the left.
        val frames = angleFrames(cycles(172f, 90f, 2), L_HIP, L_KNEE, L_ANKLE).onEach { l ->
            l[R_HIP] = l[R_HIP].copy(visibility = 0.1f); l[R_KNEE] = l[R_KNEE].copy(visibility = 0.1f); l[R_ANKLE] = l[R_ANKLE].copy(visibility = 0.1f)
        }
        assertEquals(2, play(RepCounter(ExerciseType.Squat), frames).count)
    }
}
