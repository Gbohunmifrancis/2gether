package app.heartpact

import app.heartpact.Bodies.P
import app.heartpact.exercise.CounterState
import app.heartpact.exercise.ExerciseType
import app.heartpact.exercise.Pose.Companion.L_ANKLE
import app.heartpact.exercise.Pose.Companion.L_KNEE
import app.heartpact.exercise.Pose.Companion.R_KNEE
import app.heartpact.exercise.Readiness
import app.heartpact.exercise.RepCounter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos

/**
 * Lunges, glute bridges, mountain climbers, burpees, the wall sit and the side plank: each counted from synthetic bodies
 * moving as people do, measured in space (world landmarks) and across the picture, and each one's usual cheat refused.
 */
class NewExercisesTest {
    private fun play(type: ExerciseType, bodies: List<Map<Int, P>>, yaw: Float = 90f, depth: Boolean = true, stepMs: Long = 100, counter: RepCounter = RepCounter(type)): CounterState {
        var state = CounterState()
        bodies.forEachIndexed { i, b -> state = counter.update(Bodies.pose(b, i * stepMs, yaw, depth)) }
        return state
    }

    /** Down and back up through [shape] (0 at rest, 1 at the bottom), [times] times, with a pause at each end. */
    private fun reps(times: Int, frames: Int = 8, shape: (Float, Int) -> Map<Int, P>): List<Map<Int, P>> = (0 until times).flatMap { rep ->
        val down = (0..frames).map { it / frames.toFloat() }
        (List(3) { 0f } + down + List(2) { 1f } + down.reversed() + List(2) { 0f }).map { shape(it, rep) }
    } + List(4) { shape(0f, 0) }

    // Lunges: each leg's lunge counts.

    @Test fun `alternating lunges count each leg, side-on, from the front and across the picture`() {
        val frames = reps(6) { depth, rep -> Bodies.lunge(depth, left = rep % 2 == 0) }
        assertEquals(6, play(ExerciseType.Lunge, frames, yaw = 90f).count)
        assertEquals(6, play(ExerciseType.Lunge, frames, yaw = 0f).count)
        assertEquals(6, play(ExerciseType.Lunge, frames, yaw = 60f).count)
        assertEquals(6, play(ExerciseType.Lunge, frames, yaw = 90f, depth = false).count)
    }

    @Test fun `a squat is not a lunge, and a half lunge doesn't count`() {
        val squats = reps(3) { depth, _ -> Bodies.squat(178f - 86f * depth) }
        val state = play(ExerciseType.Lunge, squats)
        assertEquals(0, state.count)
        assertEquals("Step one foot forward and lower your back knee.", state.cue)
        // Stepped out, but only half way down.
        val half = play(ExerciseType.Lunge, reps(3) { depth, rep -> Bodies.lunge(depth * 0.45f, left = rep % 2 == 0, split = 1f) })
        assertEquals(0, half.count)
        assertEquals("Go lower: both knees bent toward a right angle.", half.cue)
    }

    @Test fun `standing on one leg and swinging the other back is not a lunge`() {
        val frames = reps(4) { lift, _ ->
            // The left shin swings back and up behind; the right leg stays straight under the hips.
            Bodies.standing() + mapOf(L_ANKLE to P(-0.4f * lift, -0.4f * lift, -0.13f))
        }
        assertEquals(0, play(ExerciseType.Lunge, frames).count)
    }

    // Glute bridges.

    @Test fun `glute bridges count each lift, measured in space or across the picture`() {
        val frames = reps(5) { lift, _ -> Bodies.bridge(lift) }
        assertEquals(5, play(ExerciseType.GluteBridge, frames).count)
        assertEquals(5, play(ExerciseType.GluteBridge, frames, depth = false).count)
    }

    @Test fun `a standing hip hinge, or lying flat with straight legs, is not a bridge`() {
        val hinge = play(ExerciseType.GluteBridge, reps(4) { fold, _ -> Bodies.hinge(90f * fold) })
        assertEquals(0, hinge.count)
        assertEquals("Lie on your back, knees bent and feet flat on the floor.", hinge.cue)
        assertEquals(0, play(ExerciseType.GluteBridge, reps(4) { lift, _ -> Bodies.bridge(lift, kneesBent = false) }).count)
    }

    @Test fun `a bridge that stops half way doesn't count`() {
        val state = play(ExerciseType.GluteBridge, reps(3) { lift, _ -> Bodies.bridge(lift * 0.45f) })
        assertEquals(0, state.count)
        assertEquals("Lift your hips higher: shoulders, hips and knees in a line.", state.cue)
    }

    // Mountain climbers: each knee drive counts, even when one comes in as the other goes back.

    private fun climbing(seconds: Float, periodS: Float = 0.8f, stepMs: Long = 33): List<Map<Int, P>> {
        val frames = (seconds * 1000 / stepMs).toInt()
        val half = periodS / 2
        return List(10) { Bodies.climber(175f, 175f) } + (0 until frames).map { i ->
            val t = i * stepMs / 1000f
            val left = 142f + 33f * cos(2 * PI * t / periodS).toFloat()
            // The right leg starts once the left is in: from then on they cross each other mid-air.
            val right = if (t < half) 175f else 142f - 33f * cos(2 * PI * t / periodS).toFloat()
            Bodies.climber(left, right)
        } + (0..10).map { Bodies.climber(175f, 109f + 6.6f * it) } + List(10) { Bodies.climber(175f, 175f) }
    }

    @Test fun `mountain climbers count every knee drive, legs crossing mid-air`() {
        // Five drives each in four seconds: ten.
        val state = play(ExerciseType.MountainClimber, climbing(4f), stepMs = 33)
        assertEquals(10, state.count)
        assertEquals(0, state.fastReps)
        assertEquals(10, play(ExerciseType.MountainClimber, climbing(4f), depth = false, stepMs = 33).count)
    }

    @Test fun `high knees standing up are not mountain climbers`() {
        val frames = reps(6) { lift, rep -> Bodies.standing() + mapOf((if (rep % 2 == 0) L_KNEE else R_KNEE) to P(0.35f * lift, -0.43f - 0.4f * lift, if (rep % 2 == 0) -0.13f else 0.13f)) }
        val state = play(ExerciseType.MountainClimber, frames)
        assertEquals(0, state.count)
        assertEquals("Get into a high plank: hands under your shoulders, body level.", state.cue)
    }

    // Burpees: standing, down to a plank, back up.

    private fun burpee(withPlank: Boolean = true): List<Map<Int, P>> {
        fun go(a: Map<Int, P>, b: Map<Int, P>) = (1..5).map { Bodies.between(a, b, it / 5f) }
        val down = go(Bodies.burpeeStand, Bodies.burpeeCrouch) + if (withPlank) go(Bodies.burpeeCrouch, Bodies.burpeePlank) + List(2) { Bodies.burpeePlank } + go(Bodies.burpeePlank, Bodies.burpeeCrouch) else List(4) { Bodies.burpeeCrouch }
        return List(3) { Bodies.burpeeStand } + down + go(Bodies.burpeeCrouch, Bodies.burpeeStand) + List(3) { Bodies.burpeeStand }
    }

    @Test fun `burpees count from standing, through the plank, back to standing`() {
        val frames = List(3) { burpee() }.flatten()
        assertEquals(3, play(ExerciseType.Burpee, frames).count)
        assertEquals(3, play(ExerciseType.Burpee, frames, depth = false).count)
    }

    @Test fun `a squat thrust without the plank doesn't count`() {
        val state = play(ExerciseType.Burpee, List(3) { burpee(withPlank = false) }.flatten())
        assertEquals(0, state.count)
        assertTrue(state.formFaults >= 3)
        assertEquals("Kick your feet back into a straight plank.", state.cue)
    }

    // Holds.

    @Test fun `a wall sit counts seconds with the thighs level and the back upright`() {
        assertEquals(3, play(ExerciseType.WallSit, List(33) { Bodies.wallSit() }).count)
        assertEquals(3, play(ExerciseType.WallSit, List(33) { Bodies.wallSit() }, depth = false).count)
        assertEquals(3, play(ExerciseType.WallSit, List(33) { Bodies.wallSit() }, yaw = 30f).count)
    }

    @Test fun `standing, or leaning off the wall, stops the wall sit clock`() {
        val standing = play(ExerciseType.WallSit, List(30) { Bodies.wallSit(standing = true) })
        assertEquals(0, standing.count)
        assertEquals("Slide down until your thighs are level.", standing.cue)
        val leaning = play(ExerciseType.WallSit, List(30) { Bodies.wallSit(lean = 45f) })
        assertEquals(0, leaning.count)
        assertEquals("Keep your back flat against the wall.", leaning.cue)
        val sunk = play(ExerciseType.WallSit, List(30) { Bodies.wallSit(hipDrop = 0.25f) })
        assertEquals(0, sunk.count)
        assertEquals("Come up a little: thighs level, not below.", sunk.cue)
    }

    @Test fun `a side plank counts seconds held in a straight line, facing the camera`() {
        // Lying along the picture, shoulders stacked: the body's frame turned 90 degrees puts it across the camera.
        assertEquals(3, play(ExerciseType.SidePlank, List(33) { Bodies.sidePlank() }).count)
        assertEquals(3, play(ExerciseType.SidePlank, List(33) { Bodies.sidePlank() }, depth = false).count)
    }

    @Test fun `sagging hips stop the side plank, and lying on the side isn't one`() {
        val counter = RepCounter(ExerciseType.SidePlank)
        // 1.5 s, then 2 s sagging (not counted), then 1.5 s again: 3 whole seconds.
        val state = play(ExerciseType.SidePlank, List(16) { Bodies.sidePlank() } + List(20) { Bodies.sidePlank(hipSag = 0.18f) } + List(16) { Bodies.sidePlank() }, counter = counter)
        assertEquals(3, state.count)
        assertEquals(1, state.formFaults)
        val sag = play(ExerciseType.SidePlank, List(15) { Bodies.sidePlank() } + List(4) { Bodies.sidePlank(hipSag = 0.18f) })
        assertEquals("Lift your hips into a straight line.", sag.cue)
        val lying = play(ExerciseType.SidePlank, List(30) { Bodies.sidePlank(propped = false) })
        assertEquals(0, lying.count)
        assertEquals("Lie on your side facing the camera, up on one forearm.", lying.cue)
    }

    @Test fun `each new exercise's start position is ready, and calibrates where it can`() {
        val starts = mapOf(
            ExerciseType.Lunge to Bodies.standing(), ExerciseType.GluteBridge to Bodies.bridge(0f), ExerciseType.MountainClimber to Bodies.climber(175f, 175f),
            ExerciseType.Burpee to Bodies.standing(), ExerciseType.WallSit to Bodies.wallSit(), ExerciseType.SidePlank to Bodies.sidePlank(),
        )
        starts.forEach { (type, body) ->
            val pose = Bodies.pose(body, 0)
            assertEquals(type.name, Readiness.Ready, RepCounter(type).readiness(pose).readiness)
            val start = RepCounter(type).startReading(pose)
            if (type.measure == app.heartpact.exercise.Measure.Seconds || type == ExerciseType.Burpee) assertEquals(type.name, null, start)
            else assertTrue(type.name, start != null)
        }
        // A bridge's hips lying at 136 degrees: the rest edge follows them to 146.
        val bridge = RepCounter(ExerciseType.GluteBridge).apply { calibrate(RepCounter(ExerciseType.GluteBridge).startReading(Bodies.pose(Bodies.bridge(0f), 0))) }
        assertEquals(146.6f, bridge.zones.rest, 1f)
        assertEquals(165f, bridge.zones.active, 0.01f)
    }
}
