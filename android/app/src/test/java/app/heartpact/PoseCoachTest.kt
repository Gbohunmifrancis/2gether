package app.heartpact

import app.heartpact.exercise.CoachPhase
import app.heartpact.exercise.CounterState
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
import app.heartpact.exercise.Pose.Companion.R_ELBOW
import app.heartpact.exercise.Pose.Companion.R_HIP
import app.heartpact.exercise.Pose.Companion.R_KNEE
import app.heartpact.exercise.Pose.Companion.R_SHOULDER
import app.heartpact.exercise.Pose.Companion.R_WRIST
import app.heartpact.exercise.PoseCoach
import app.heartpact.exercise.RepCounter
import app.heartpact.exercise.SetupIssue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** The voice coach: getting into position, the countdown, and what it says while counting. */
class PoseCoachTest {
    private val frameMs = 100L

    /** Facing the camera, standing tall, arms by the sides: shoulders as wide as a real body's, about 0.7 of the torso. */
    private fun facing(): MutableList<Landmark> = MutableList(Pose.COUNT) { Landmark(0.5f, 0.5f, 0.95f) }.apply {
        this[L_SHOULDER] = Landmark(0.41f, 0.30f, 0.95f); this[R_SHOULDER] = Landmark(0.59f, 0.30f, 0.95f)
        this[L_ELBOW] = Landmark(0.40f, 0.42f, 0.95f); this[R_ELBOW] = Landmark(0.60f, 0.42f, 0.95f)
        this[L_WRIST] = Landmark(0.40f, 0.53f, 0.95f); this[R_WRIST] = Landmark(0.60f, 0.53f, 0.95f)
        this[L_HIP] = Landmark(0.45f, 0.55f, 0.95f); this[R_HIP] = Landmark(0.55f, 0.55f, 0.95f)
        this[L_KNEE] = Landmark(0.45f, 0.72f, 0.95f); this[R_KNEE] = Landmark(0.55f, 0.72f, 0.95f)
        this[L_ANKLE] = Landmark(0.45f, 0.90f, 0.95f); this[R_ANKLE] = Landmark(0.55f, 0.90f, 0.95f)
    }

    /** Side-on squat with the knee at [knee] degrees (as in RepCounterTest): both sides of the body in one place. */
    private fun sideSquat(knee: Float): MutableList<Landmark> {
        val bend = Math.toRadians(180.0 - knee)
        val (shin, thigh, trunk) = Triple(bend * 0.4, bend * 0.6, bend * 0.4)
        val ankle = Landmark(0.50f, 0.90f, 0.95f)
        val kneeAt = Landmark((ankle.x + 0.2 * sin(shin)).toFloat(), (ankle.y - 0.2 * cos(shin)).toFloat(), 0.95f)
        val hip = Landmark((kneeAt.x - 0.2 * sin(thigh)).toFloat(), (kneeAt.y - 0.2 * cos(thigh)).toFloat(), 0.95f)
        val shoulder = Landmark((hip.x + 0.3 * sin(trunk)).toFloat(), (hip.y - 0.3 * cos(trunk)).toFloat(), 0.95f)
        return facing().apply {
            this[L_ANKLE] = ankle; this[R_ANKLE] = ankle; this[L_KNEE] = kneeAt; this[R_KNEE] = kneeAt
            this[L_HIP] = hip; this[R_HIP] = hip; this[L_SHOULDER] = shoulder; this[R_SHOULDER] = shoulder
        }
    }

    /** A squat seen from the front: the knees come forward toward the camera, so on screen the legs barely bend. */
    private fun frontSquat(depth: Float): MutableList<Landmark> = facing().apply {
        val drop = 0.08f * depth
        for (i in listOf(L_SHOULDER, R_SHOULDER, L_ELBOW, R_ELBOW, L_WRIST, R_WRIST, L_HIP, R_HIP)) this[i] = this[i].copy(y = this[i].y + drop)
        this[L_KNEE] = this[L_KNEE].copy(x = 0.43f); this[R_KNEE] = this[R_KNEE].copy(x = 0.57f)
    }

    private fun pose(points: List<Landmark>?, at: Long) = points?.let { Pose(it, at) }

    /** Plays frames through the coach the way the screen does, counter after "Go!"; returns everything said. */
    private fun play(coach: PoseCoach, counter: RepCounter, frames: List<List<Landmark>?>, startMs: Long = 0): Pair<List<String>, CounterState> {
        val said = mutableListOf<String>()
        var state = CounterState()
        frames.forEachIndexed { i, points ->
            val now = startMs + i * frameMs
            val p = pose(points, now)
            val tick = if (coach.counting) { state = counter.update(p); coach.update(p, state, now) } else coach.update(p, state, now)
            tick.speak?.let { said += it }
        }
        return said to state
    }

    private fun repeat(points: List<Landmark>?, ms: Long) = List((ms / frameMs).toInt()) { points }

    @Test fun `facing the camera, squats can't count, which is why the coach turns you side-on`() {
        // The counter alone: ten front-on squats, not one counted.
        val counter = RepCounter(ExerciseType.Squat)
        val frames = List(10) { (0..8).map { frontSquat(it / 8f) } + (8 downTo 0).map { frontSquat(it / 8f) } }.flatten()
        frames.forEachIndexed { i, l -> counter.update(Pose(l, i * frameMs)) }
        assertEquals(0, counter.state.count)
        // The coach says so before it starts.
        val coach = PoseCoach(ExerciseType.Squat, 10)
        assertEquals(SetupIssue.TurnSideways, coach.issue(Pose(facing(), 0)))
        assertNull(coach.issue(Pose(sideSquat(172f), 0)))
    }

    @Test fun `push-ups and planks are side-on, arm raises and jumping jacks face the camera`() {
        assertEquals(SetupIssue.FaceCamera, PoseCoach(ExerciseType.ArmRaise, 10).issue(Pose(sideSquat(172f), 0)))
        assertNull(PoseCoach(ExerciseType.ArmRaise, 10).issue(Pose(facing(), 0)))
        assertNull(PoseCoach(ExerciseType.JumpingJack, 10).issue(Pose(facing(), 0)))
        assertEquals(SetupIssue.NoBody, PoseCoach(ExerciseType.PushUp, 10).issue(null))
    }

    @Test fun `out of the picture, it asks you to step back`() {
        val cut = facing().also { l -> listOf(L_WRIST, R_WRIST, L_HIP, R_HIP).forEach { l[it] = l[it].copy(visibility = 0.1f) } }
        assertEquals(SetupIssue.StepBack, PoseCoach(ExerciseType.ArmRaise, 10).issue(Pose(cut, 0)))
    }

    @Test fun `arms up is not the start of an arm raise`() {
        val up = facing().also { l -> l[L_WRIST] = Landmark(0.40f, 0.10f, 0.95f); l[R_WRIST] = Landmark(0.60f, 0.10f, 0.95f) }
        assertEquals(SetupIssue.StartPosition, PoseCoach(ExerciseType.ArmRaise, 10).issue(Pose(up, 0)))
    }

    @Test fun `it guides, counts down once you hold the position, then says go`() {
        val coach = PoseCoach(ExerciseType.Squat, 10)
        val (said, _) = play(coach, RepCounter(ExerciseType.Squat), repeat(null, 1_000) + repeat(facing(), 1_500) + repeat(sideSquat(172f), 6_000))
        assertEquals(coach.message(SetupIssue.NoBody, null), said[0])
        assertEquals(coach.message(SetupIssue.TurnSideways, null), said[1])
        assertEquals(listOf("Perfect. Hold it there.", "3", "2", "1", "Go!"), said.drop(2))
        assertTrue(coach.counting)
    }

    @Test fun `an instruction not followed is said again, not every frame`() {
        val coach = PoseCoach(ExerciseType.Squat, 10)
        val (said, _) = play(coach, RepCounter(ExerciseType.Squat), repeat(facing(), 14_000))
        assertEquals(3, said.size)
        assertTrue(said.all { it == coach.message(SetupIssue.TurnSideways, null) })
    }

    @Test fun `nothing counts before go, then reps count and are said out loud`() {
        val coach = PoseCoach(ExerciseType.Squat, 3)
        val counter = RepCounter(ExerciseType.Squat)
        val rep = (0..6).map { sideSquat(172f - 82f * it / 6f) } + listOf(sideSquat(90f)) + (5 downTo 0).map { sideSquat(172f - 82f * it / 6f) } + repeat(sideSquat(172f), 300)
        // Squatting while still getting set: the countdown never finishes, nothing counts.
        val early = List(3) { rep }.flatten()
        val (saidEarly, stateEarly) = play(coach, counter, early)
        assertEquals(0, stateEarly.count)
        assertTrue("Go!" !in saidEarly)
        // Hold still, go, then three squats.
        val (said, state) = play(coach, counter, repeat(sideSquat(172f), 5_500) + List(3) { rep }.flatten(), startMs = early.size * frameMs)
        assertEquals(3, state.count)
        assertEquals(listOf("Go!", "1", "2", "Done! Well done."), said.dropWhile { it != "Go!" })
    }

    @Test fun `moving out of position during the countdown starts getting set again`() {
        val coach = PoseCoach(ExerciseType.Squat, 10)
        val (said, _) = play(coach, RepCounter(ExerciseType.Squat), repeat(sideSquat(172f), 2_500) + repeat(facing(), 1_500) + repeat(sideSquat(172f), 6_000))
        val firstGo = said.indexOf("Go!")
        assertTrue(said.indexOf(coach.message(SetupIssue.TurnSideways, null)) in 1 until firstGo)
        assertEquals(2, said.count { it == "Perfect. Hold it there." })
    }

    @Test fun `a plank says every ten seconds`() {
        val coach = PoseCoach(ExerciseType.Plank, 30)
        coach.skipToCounting()
        val said = (0..31).mapNotNull { s -> coach.update(null, CounterState(count = s, inFrame = true), s * 1_000L).speak }
        assertEquals(listOf("10", "20", "Done! Well done."), said)
        assertEquals(CoachPhase.Counting, coach.update(null, CounterState(count = 31), 40_000).state.phase)
    }

    @Test fun `while counting, leaving the picture is pointed out once`() {
        val coach = PoseCoach(ExerciseType.ArmRaise, 10)
        coach.skipToCounting()
        val said = (0..60).mapNotNull { coach.update(null, CounterState(count = 2, inFrame = false), it * 100L).speak }
        assertEquals(listOf("2", coach.message(SetupIssue.StepBack, null)), said)
    }

    // Measured in space (world landmarks), the camera angle matters less.

    @Test fun `measured in space, a squat or a lunge can be done facing the camera, floor exercises still turn side-on`() {
        val front = Bodies.pose(Bodies.standing(), 0, yawDeg = 0f)
        assertNull(PoseCoach(ExerciseType.Squat, 10).issue(front))
        assertNull(PoseCoach(ExerciseType.Lunge, 10).issue(front))
        // Across the picture alone, the squat still asks to turn.
        assertEquals(SetupIssue.TurnSideways, PoseCoach(ExerciseType.Squat, 10).issue(Bodies.pose(Bodies.standing(), 0, yawDeg = 0f, depth = false)))
        // A bridge seen from its feet, a plank seen head-on: turn side-on.
        assertEquals(SetupIssue.TurnSideways, PoseCoach(ExerciseType.GluteBridge, 10).issue(Bodies.pose(Bodies.bridge(0f), 0, yawDeg = 0f)))
        assertNull(PoseCoach(ExerciseType.GluteBridge, 10).issue(Bodies.pose(Bodies.bridge(0f), 0, yawDeg = 90f)))
        assertNull(PoseCoach(ExerciseType.MountainClimber, 10).issue(Bodies.pose(Bodies.climber(175f, 175f), 0, yawDeg = 90f)))
        // A side plank faces the camera.
        assertNull(PoseCoach(ExerciseType.SidePlank, 20).issue(Bodies.pose(Bodies.sidePlank(), 0, yawDeg = 90f)))
        assertNull(PoseCoach(ExerciseType.SidePlank, 20).issue(Bodies.pose(Bodies.sidePlank(), 0, yawDeg = 90f, depth = false)))
        assertEquals(SetupIssue.FaceCamera, PoseCoach(ExerciseType.SidePlank, 20).issue(Bodies.pose(Bodies.sidePlank(), 0, yawDeg = 0f)))
    }

    @Test fun `the coach reads the start position while the person holds still, and the counter fits it`() {
        // Soft knees: 152 degrees standing, under the default rest edge, still a fine start.
        val coach = PoseCoach(ExerciseType.Squat, 4)
        val counter = RepCounter(ExerciseType.Squat)
        val soft = { at: Long -> Bodies.pose(Bodies.squat(152f), at, yawDeg = 30f) }
        var t = 0L
        while (!coach.counting && t < 10_000) { coach.update(soft(t), CounterState(), t); t += frameMs }
        assertTrue(coach.counting)
        assertEquals(152f, coach.calibration!!, 0.5f)
        counter.calibrate(coach.calibration)
        // Then four squats from that stance count.
        val knees = List(4) { (0..8).map { 152f - 60f * it / 8f } + listOf(92f, 92f) + (8 downTo 0).map { 152f - 60f * it / 8f } + listOf(152f, 152f) }.flatten()
        knees.forEach { knee -> counter.update(Bodies.pose(Bodies.squat(knee), t, yawDeg = 30f)); t += frameMs }
        assertEquals(4, counter.state.count)
    }

    @Test fun `quick exercises are counted aloud every five, holds every ten seconds`() {
        val coach = PoseCoach(ExerciseType.MountainClimber, 12)
        coach.skipToCounting()
        val said = (1..12).mapNotNull { coach.update(null, CounterState(count = it, inFrame = true), it * 300L).speak }
        assertEquals(listOf("5", "10", "Done! Well done."), said)
        val wall = PoseCoach(ExerciseType.WallSit, 25)
        wall.skipToCounting()
        assertEquals(listOf("10", "20", "Done! Well done."), (0..26).mapNotNull { wall.update(null, CounterState(count = it, inFrame = true), it * 1_000L).speak })
    }

    @Test fun `every new exercise has its own start line, and hold faults say which way to move`() {
        val lines = ExerciseType.entries.map { PoseCoach(it, 10).message(SetupIssue.StartPosition) }
        assertEquals(lines.size, lines.toSet().size)
        assertEquals("Slide down until your thighs are level.", PoseCoach(ExerciseType.WallSit, 30).message(SetupIssue.StartPosition, app.heartpact.exercise.FaultKind.HipsHigh))
        assertEquals("Keep your back flat against the wall.", PoseCoach(ExerciseType.WallSit, 30).message(SetupIssue.StartPosition, app.heartpact.exercise.FaultKind.Posture))
        assertEquals("Lift your hips into a straight line.", PoseCoach(ExerciseType.SidePlank, 30).message(SetupIssue.StartPosition, app.heartpact.exercise.FaultKind.HipsLow))
    }
}
