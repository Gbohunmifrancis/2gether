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
import org.junit.Assert.assertNull
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

    /** Both sides of a side-on body at the same place (the far side hidden behind the near one). */
    private fun MutableList<Landmark>.both(left: Int, right: Int, at: Landmark) { this[left] = at; this[right] = at }

    /**
     * Side-on squat with the knee at [knee] degrees, as a real one moves: feet planted, the shins tip
     * forward, the hips sink back and down, the trunk leans a little.
     */
    private fun squatting(knee: Float, visibility: Float = 0.95f): MutableList<Landmark> {
        val bend = Math.toRadians(180.0 - knee)
        val (shin, thigh, trunk) = Triple(bend * 0.4, bend * 0.6, bend * 0.4)
        val ankle = Landmark(0.50f, 0.90f, visibility)
        val kneeAt = Landmark((ankle.x + 0.2 * sin(shin)).toFloat(), (ankle.y - 0.2 * cos(shin)).toFloat(), visibility)
        val hip = Landmark((kneeAt.x - 0.2 * sin(thigh)).toFloat(), (kneeAt.y - 0.2 * cos(thigh)).toFloat(), visibility)
        val shoulder = Landmark((hip.x + 0.3 * sin(trunk)).toFloat(), (hip.y - 0.3 * cos(trunk)).toFloat(), visibility)
        return standing(visibility).apply { both(L_ANKLE, R_ANKLE, ankle); both(L_KNEE, R_KNEE, kneeAt); both(L_HIP, R_HIP, hip); both(L_SHOULDER, R_SHOULDER, shoulder) }
    }

    private fun squats(values: List<Float>, visibility: Float = 0.95f) = values.map { squatting(it, visibility) }

    /**
     * Side-on push-up with the elbow at [elbow] degrees: hands planted under the shoulders, the body one
     * straight line down to the feet, the shoulders lowering as the elbows bend.
     */
    private fun pushingUp(elbow: Float): MutableList<Landmark> {
        val half = Math.toRadians((180.0 - elbow) / 2)
        val wrist = Landmark(0.30f, 0.80f)
        val elbowAt = Landmark((wrist.x + 0.15 * sin(half)).toFloat(), (wrist.y - 0.15 * cos(half)).toFloat())
        val shoulder = Landmark((elbowAt.x - 0.15 * sin(half)).toFloat(), (elbowAt.y - 0.15 * cos(half)).toFloat())
        val ankle = Landmark(0.85f, 0.80f)
        val hip = Landmark((shoulder.x + ankle.x) / 2, (shoulder.y + ankle.y) / 2)
        return standing().apply { both(L_WRIST, R_WRIST, wrist); both(L_ELBOW, Pose.R_ELBOW, elbowAt); both(L_SHOULDER, R_SHOULDER, shoulder); both(L_HIP, R_HIP, hip); both(L_ANKLE, R_ANKLE, ankle) }
    }

    /** Side-on sit-up: lying on the back, knees bent up, the trunk rising to [hip] degrees at the hip. */
    private fun sittingUp(hip: Float): MutableList<Landmark> = standing().apply {
        both(L_HIP, R_HIP, Landmark(0.50f, 0.80f)); both(L_KNEE, R_KNEE, Landmark(0.65f, 0.68f)); both(L_ANKLE, R_ANKLE, Landmark(0.78f, 0.80f))
        both(L_SHOULDER, R_SHOULDER, bend(this[L_KNEE], this[L_HIP], hip, 0.3f))
    }

    @Test fun `squats count once per full bend and return`() {
        val state = play(RepCounter(ExerciseType.Squat), squats(cycles(172f, 90f, 5)))
        assertEquals(5, state.count)
        assertTrue(state.inFrame)
    }

    @Test fun `a shallow real squat still counts`() =
        // Down to 100 degrees: the hips sink about a fifth of a leg, twice what the check asks for.
        assertEquals(3, play(RepCounter(ExerciseType.Squat), squats(cycles(170f, 100f, 3))).count)

    @Test fun `sitting in a chair and swinging a leg is not a squat`() {
        // The hip stays put on the seat; only the knee bends and straightens.
        val frames = cycles(172f, 90f, 4).map { knee ->
            standing().also { l ->
                val hip = Landmark(0.45f, 0.62f); val kneeAt = Landmark(0.65f, 0.62f)
                val t = Math.toRadians(knee.toDouble())
                l.both(L_HIP, R_HIP, hip); l.both(L_KNEE, R_KNEE, kneeAt)
                l.both(L_ANKLE, R_ANKLE, Landmark((kneeAt.x - 0.2 * cos(t)).toFloat(), (kneeAt.y + 0.2 * sin(t)).toFloat()))
                l.both(L_SHOULDER, R_SHOULDER, Landmark(0.43f, 0.32f))
            }
        }
        val state = play(RepCounter(ExerciseType.Squat), frames)
        assertEquals(0, state.count)
        assertEquals("Go lower: hips down toward knee height.", state.cue)
    }

    @Test fun `standing on one leg and bending the other back is not a squat`() {
        val frames = cycles(172f, 90f, 4).map { knee ->
            standing().also { l ->
                val t = Math.toRadians(knee.toDouble())
                // Thigh straight down from the hip; the shin swings back and up behind it.
                l.both(L_ANKLE, R_ANKLE, Landmark((l[L_KNEE].x - 0.18 * sin(t)).toFloat(), (l[L_KNEE].y - 0.18 * cos(t)).toFloat()))
            }
        }
        assertEquals(0, play(RepCounter(ExerciseType.Squat), frames).count)
    }

    @Test fun `jitter around a threshold never counts twice`() {
        // Down into the squat, then hover around the active edge and around the rest edge, then stand.
        val values = listOf(170f, 140f, 110f, 95f) + List(10) { if (it % 2 == 0) 100f else 112f } + listOf(130f, 150f) + List(10) { if (it % 2 == 0) 155f else 165f } + listOf(170f)
        assertEquals(1, play(RepCounter(ExerciseType.Squat), squats(values)).count)
    }

    @Test fun `a one-frame spike is not a rep`() {
        val counter = RepCounter(ExerciseType.Squat)
        val frames = squats(listOf(170f, 170f, 90f, 170f, 170f))
        // 50 ms apart: faster than any real squat.
        val state = frames.mapIndexed { i, l -> counter.update(Pose(l, i * 50L)) }.last()
        assertEquals(0, state.count)
    }

    @Test fun `joints the camera can barely see are ignored, not guessed`() {
        val state = play(RepCounter(ExerciseType.Squat), squats(cycles(172f, 90f, 3), visibility = 0.3f))
        assertEquals(0, state.count)
        assertFalse(state.inFrame)
    }

    @Test fun `no person in frame leaves the count alone`() {
        val counter = RepCounter(ExerciseType.Squat)
        play(counter, squats(cycles(172f, 90f, 2)))
        val state = counter.update(null)
        assertEquals(2, state.count)
        assertFalse(state.inFrame)
    }

    @Test fun `push-ups follow the elbow`() =
        assertEquals(4, play(RepCounter(ExerciseType.PushUp), cycles(165f, 80f, 4).map(::pushingUp)).count)

    @Test fun `incline push-ups with the hands on a bench still count`() {
        // Feet lower than the hands: the body slopes up to about 40 degrees, still a push-up.
        val frames = cycles(165f, 80f, 3).map { pushingUp(it).also { l ->
            l.both(L_ANKLE, R_ANKLE, Landmark(0.85f, 0.95f)); l.both(L_HIP, R_HIP, Landmark((l[L_SHOULDER].x + 0.85f) / 2, (l[L_SHOULDER].y + 0.95f) / 2))
        } }
        assertEquals(3, play(RepCounter(ExerciseType.PushUp), frames).count)
    }

    @Test fun `standing side-on and bending the elbows is not a push-up`() {
        val state = play(RepCounter(ExerciseType.PushUp), angleFrames(cycles(165f, 80f, 4), L_SHOULDER, L_ELBOW, L_WRIST, 0.12f))
        assertEquals(0, state.count)
        assertEquals("Get down into a push-up: body level, hands under your shoulders.", state.cue)
    }

    @Test fun `lying flat and moving the arms is not a push-up`() {
        // Face down (or on the back), shoulders on the floor: straight arms lie along it, never under raised shoulders.
        fun flat(face: Float, elbow: Float) = standing().also { l ->
            l.both(L_SHOULDER, R_SHOULDER, Landmark(0.30f, 0.80f)); l.both(L_HIP, R_HIP, Landmark(0.58f, 0.80f)); l.both(L_ANKLE, R_ANKLE, Landmark(0.85f, 0.81f))
            l.both(L_ELBOW, Pose.R_ELBOW, Landmark(0.18f, 0.80f + face * 0.02f))
            l.both(L_WRIST, R_WRIST, bend(l[L_SHOULDER], l[L_ELBOW], elbow, 0.12f))
        }
        assertEquals(0, play(RepCounter(ExerciseType.PushUp), cycles(170f, 80f, 4).map { flat(1f, it) }).count)
        // On the back, pushing the arms up at the ceiling: the hands end above the shoulders.
        val press = cycles(170f, 80f, 4).map { deg -> standing().also { l ->
            l.both(L_SHOULDER, R_SHOULDER, Landmark(0.30f, 0.80f)); l.both(L_HIP, R_HIP, Landmark(0.58f, 0.80f)); l.both(L_ANKLE, R_ANKLE, Landmark(0.85f, 0.81f))
            l.both(L_ELBOW, Pose.R_ELBOW, Landmark(0.32f, 0.65f)); l.both(L_WRIST, R_WRIST, bend(l[L_SHOULDER], l[L_ELBOW], deg, 0.15f))
        } }
        assertEquals(0, play(RepCounter(ExerciseType.PushUp), press).count)
    }

    @Test fun `sit-ups follow the hip`() =
        assertEquals(3, play(RepCounter(ExerciseType.SitUp), cycles(150f, 60f, 3).map(::sittingUp)).count)

    @Test fun `bending forward standing up is not a sit-up`() {
        val state = play(RepCounter(ExerciseType.SitUp), angleFrames(cycles(172f, 60f, 3), L_KNEE, L_HIP, L_SHOULDER, length = 0.25f))
        assertEquals(0, state.count)
        assertEquals("Lie on your back with your knees bent.", state.cue)
    }

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

    private fun plankFrame(straight: Boolean, hipY: Float = if (straight) 0.63f else 0.78f) = standing().also { l ->
        // Side-on forearm plank: elbows under the shoulders, shoulders, hips and ankles along one line (or hips sagging).
        l[L_SHOULDER] = Landmark(0.25f, 0.60f); l[R_SHOULDER] = Landmark(0.25f, 0.60f, 0.2f)
        l[L_ELBOW] = Landmark(0.25f, 0.74f); l[Pose.R_ELBOW] = Landmark(0.25f, 0.74f, 0.2f)
        l[L_WRIST] = Landmark(0.12f, 0.75f); l[R_WRIST] = Landmark(0.12f, 0.75f, 0.2f)
        l[L_HIP] = Landmark(0.50f, hipY); l[R_HIP] = Landmark(0.50f, 0.63f, 0.2f)
        l[L_ANKLE] = Landmark(0.80f, 0.66f); l[R_ANKLE] = Landmark(0.80f, 0.66f, 0.2f)
    }

    @Test fun `plank counts whole seconds held in a straight line`() {
        val counter = RepCounter(ExerciseType.Plank)
        val held = (0..32).map { plankFrame(true) }
        assertEquals(3, play(counter, held).count)
    }

    @Test fun `a high plank on the hands counts too`() {
        val high = (0..32).map { plankFrame(true).also { l -> l[L_ELBOW] = Landmark(0.25f, 0.70f); l[L_WRIST] = Landmark(0.25f, 0.80f) } }
        assertEquals(3, play(RepCounter(ExerciseType.Plank), high).count)
    }

    @Test fun `lying flat is not a plank`() {
        // Face down or on the back, the body is straight and level, but nothing holds the shoulders up.
        val flat = (0..32).map { standing().also { l ->
            l.both(L_SHOULDER, R_SHOULDER, Landmark(0.25f, 0.80f)); l.both(L_HIP, R_HIP, Landmark(0.50f, 0.81f)); l.both(L_ANKLE, R_ANKLE, Landmark(0.80f, 0.82f))
            l.both(L_ELBOW, Pose.R_ELBOW, Landmark(0.36f, 0.81f)); l.both(L_WRIST, R_WRIST, Landmark(0.46f, 0.81f))
        } }
        val state = play(RepCounter(ExerciseType.Plank), flat)
        assertEquals(0, state.count)
        assertEquals("Get down into a straight plank.", state.cue)
    }

    @Test fun `sagging hips stop the plank clock`() {
        val counter = RepCounter(ExerciseType.Plank)
        val frames = (0..15).map { plankFrame(true) } + (0..20).map { plankFrame(false) } + (0..15).map { plankFrame(true) }
        // 1.5 s, then 2 s sagging (not counted), then 1.5 s again: 3 whole seconds.
        assertEquals(3, play(counter, frames).count)
    }

    @Test fun `confidence is the visibility of the joints that counted`() {
        val state = play(RepCounter(ExerciseType.Squat), squats(cycles(172f, 90f, 2), visibility = 0.8f))
        assertEquals(0.8, state.confidence, 0.01)
    }

    @Test fun `minimum time matches the server's plausibility rule`() {
        assertEquals(8_000L, ExerciseType.SitUp.minimumMillis(10))
        assertEquals(27_000L, ExerciseType.Plank.minimumMillis(30))
    }

    @Test fun `the counter favours the side the camera sees`() {
        // Right leg hidden, left leg clear: still counts from the left.
        val frames = squats(cycles(172f, 90f, 2)).onEach { l ->
            l[R_HIP] = l[R_HIP].copy(visibility = 0.1f); l[R_KNEE] = l[R_KNEE].copy(visibility = 0.1f); l[R_ANKLE] = l[R_ANKLE].copy(visibility = 0.1f)
        }
        assertEquals(2, play(RepCounter(ExerciseType.Squat), frames).count)
    }

    // Form: what doesn't count, and the tip it leaves.

    @Test fun `a squat that stops halfway doesn't count and says go lower`() {
        val counter = RepCounter(ExerciseType.Squat)
        val state = play(counter, squats(cycles(172f, 125f, 2)))
        assertEquals(0, state.count)
        assertEquals(2, state.formFaults)
        assertEquals("Go lower: hips down toward knee height.", state.cue)
    }

    @Test fun `small wobbles standing still are not faults`() {
        val state = play(RepCounter(ExerciseType.Squat), squats(List(30) { if (it % 2 == 0) 172f else 150f }))
        assertEquals(0, state.formFaults)
        assertNull(state.cue)
    }

    @Test fun `bouncing at the bottom without standing up counts one rep, not three`() {
        // Down, half up, down, half up, down, then all the way up.
        val values = listOf(172f, 150f, 120f, 95f, 90f, 90f, 120f, 140f, 140f, 110f, 95f, 90f, 90f, 120f, 140f, 140f, 100f, 90f, 90f, 120f, 150f, 165f, 172f, 172f, 172f)
        val state = play(RepCounter(ExerciseType.Squat), squats(values))
        assertEquals(1, state.count)
        assertEquals(2, state.formFaults)
    }

    @Test fun `a squat with the chest dropped doesn't count`() {
        val frames = squats(cycles(172f, 90f, 2)).onEach { l ->
            val knee = l[L_KNEE]; val hip = l[L_HIP]
            // At the bottom, fold the trunk forward to about 70 degrees from upright.
            if (Pose(l, 0).angle(L_HIP, L_KNEE, L_ANKLE) < 120f) l[L_SHOULDER] = Landmark(hip.x + 0.235f, hip.y - 0.085f)
            l[L_KNEE] = knee
        }
        val state = play(RepCounter(ExerciseType.Squat), frames)
        assertEquals(0, state.count)
        assertEquals(2, state.formFaults)
        assertEquals("Keep your chest up as you go down.", state.cue)
    }

    @Test fun `push-ups with sagging hips don't count`() {
        val frames = cycles(165f, 80f, 3).map(::pushingUp).onEach { l ->
            // Hips sagging well out of the shoulder-to-ankle line.
            l.both(L_HIP, R_HIP, Landmark(l[L_HIP].x, l[L_HIP].y + 0.1f))
        }
        val state = play(RepCounter(ExerciseType.PushUp), frames)
        assertEquals(0, state.count)
        assertEquals("Keep your body in one straight line.", state.cue)
    }

    @Test fun `reps quicker than the exercise allows don't count`() {
        val counter = RepCounter(ExerciseType.Squat)
        // Full squats, but each one in about 300 ms: under 60 percent of the 0.8 s minimum.
        val state = squats(cycles(172f, 90f, 3)).mapIndexed { i, l -> counter.update(Pose(l, i * 20L)) }.last()
        assertEquals(0, state.count)
        assertEquals(3, state.fastReps)
        assertEquals("Slow down: that one was too quick to count.", state.cue)
    }

    @Test fun `the tip fades after a few seconds`() {
        val counter = RepCounter(ExerciseType.Squat)
        play(counter, squats(cycles(172f, 125f, 1)))
        val later = squats(List(40) { 172f }).mapIndexed { i, l -> counter.update(Pose(l, 2_000L + i * frameMs)) }.last()
        assertNull(later.cue)
        assertEquals(1, later.formFaults)
    }

    @Test fun `a counted rep reports when it began and ended`() {
        val state = play(RepCounter(ExerciseType.Squat), squats(cycles(172f, 90f, 1)))
        assertEquals(1, state.count)
        val start = state.lastRepStartMs!!; val end = state.lastRepEndMs!!
        assertTrue(end - start in 900L..1_700L)
    }

    @Test fun `a broken plank says which way to move the hips`() {
        val counter = RepCounter(ExerciseType.Plank)
        val sag = play(counter, (0..15).map { plankFrame(true) } + (0..3).map { plankFrame(false) })
        assertEquals("Lift your hips into a straight line.", sag.cue)
        assertEquals(1, sag.formFaults)
        val pike = plankFrame(true, hipY = 0.48f)
        assertEquals("Lower your hips into a straight line.", counter.update(Pose(pike, 5_000L)).cue)
    }
}
