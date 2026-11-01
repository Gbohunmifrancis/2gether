package app.heartpact

import app.heartpact.data.OccurrenceChecks
import app.heartpact.data.PlayIntegrity
import app.heartpact.exercise.ChallengeKind
import app.heartpact.exercise.ChallengeRunner
import app.heartpact.exercise.CounterState
import app.heartpact.exercise.ExerciseType
import app.heartpact.exercise.Landmark
import app.heartpact.exercise.MotionLog
import app.heartpact.exercise.Pose
import app.heartpact.exercise.SessionChecks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SessionChecksTest {
    private val frameMs = 100L

    /** A standing person, shifted sideways by [dx] (a body that keeps moving). */
    private fun body(dx: Float = 0f, at: Long = 0) = Pose(MutableList(Pose.COUNT) { Landmark(0.5f + dx, 0.5f, 0.95f) }.apply {
        this[Pose.L_SHOULDER] = Landmark(0.44f + dx, 0.30f); this[Pose.R_SHOULDER] = Landmark(0.56f + dx, 0.30f)
        this[Pose.L_ELBOW] = Landmark(0.42f + dx, 0.42f); this[Pose.R_ELBOW] = Landmark(0.58f + dx, 0.42f)
        this[Pose.L_WRIST] = Landmark(0.42f + dx, 0.52f); this[Pose.R_WRIST] = Landmark(0.58f + dx, 0.52f)
        this[Pose.L_HIP] = Landmark(0.46f + dx, 0.55f); this[Pose.R_HIP] = Landmark(0.54f + dx, 0.55f)
        this[Pose.L_KNEE] = Landmark(0.46f + dx, 0.72f); this[Pose.R_KNEE] = Landmark(0.54f + dx, 0.72f)
        this[Pose.L_ANKLE] = Landmark(0.46f + dx, 0.90f); this[Pose.R_ANKLE] = Landmark(0.54f + dx, 0.90f)
    }, at)

    /** Plays frames until the first check-in opens; returns the time it opened. */
    private fun untilOpen(runner: ChallengeRunner, moving: Boolean): Long {
        var t = 0L
        while (t < 120_000) {
            val ui = runner.update(body(if (moving) ((t / frameMs) % 2) * 0.1f else 0f, t), t)
            if (ui != null && ui.passed == null) return t
            t += frameMs
        }
        error("No check-in opened")
    }

    @Test fun `a check-in opens mid-set, never in the first seconds`() {
        repeat(20) { seed ->
            val opened = untilOpen(ChallengeRunner(ExerciseType.Squat, 15, Random(seed)), moving = true)
            assertTrue("opened at $opened", opened >= ChallengeRunner.FIRST_AFTER_MS)
            // 15 squats: about 24 s expected, so between 30 and 70 percent of it.
            assertTrue("opened at $opened", opened <= 17_000)
        }
    }

    /** The standing body with one hand (by the model's landmarks) or both raised above the head. */
    private fun raising(left: Boolean, right: Boolean, at: Long) = Pose(body(at = at).landmarks.toMutableList().apply {
        if (left) this[Pose.L_WRIST] = Landmark(0.42f, 0.12f)
        if (right) this[Pose.R_WRIST] = Landmark(0.58f, 0.12f)
    }, at)

    /** Opens the first check-in, then plays [frame] for up to the whole window; returns the last thing shown. */
    private fun answer(runner: ChallengeRunner, mirrored: Boolean = false, frame: (ChallengeKind, Long) -> Pose): app.heartpact.exercise.ChallengeUi? {
        var t = untilOpen(runner, moving = true)
        var kind = runner.update(body(at = t), t)!!.kind
        var last: app.heartpact.exercise.ChallengeUi? = null
        repeat(80) { t += frameMs; last = runner.update(frame(kind, t), t, mirrored = mirrored) ?: last; kind = last?.kind ?: kind }
        return last
    }

    @Test fun `raising the asked-for hand passes the check-in`() {
        repeat(6) { seed ->
            val runner = ChallengeRunner(ExerciseType.Squat, 15, Random(seed))
            val last = answer(runner) { kind, t -> raising(left = kind == ChallengeKind.RaiseLeft, right = kind == ChallengeKind.RaiseRight, at = t) }
            assertEquals(true, last?.passed)
            assertEquals("Check-in passed", last?.title)
            assertEquals(1, runner.passed)
            assertEquals(0, runner.missed)
        }
    }

    @Test fun `the cue picks left or right at random`() {
        val kinds = (0..20).map { seed ->
            val runner = ChallengeRunner(ExerciseType.Squat, 15, Random(seed))
            val t = untilOpen(runner, moving = true)
            runner.update(body(at = t), t)!!.kind
        }.toSet()
        assertEquals(setOf(ChallengeKind.RaiseLeft, ChallengeKind.RaiseRight), kinds)
    }

    @Test fun `on the front camera the picture is mirrored, so the model's other hand is the one asked for`() {
        val runner = ChallengeRunner(ExerciseType.Squat, 15, Random(1))
        val last = answer(runner, mirrored = true) { kind, t -> raising(left = kind == ChallengeKind.RaiseRight, right = kind == ChallengeKind.RaiseLeft, at = t) }
        assertEquals(true, last?.passed)
    }

    @Test fun `a paused recording misses the check-in`() {
        val runner = ChallengeRunner(ExerciseType.Squat, 15, Random(2))
        val last = answer(runner) { _, t -> body(at = t) }
        assertEquals(0, runner.passed)
        assertEquals(1, runner.missed)
        assertEquals("Missed the check-in", last?.title)
    }

    @Test fun `the wrong hand, or both hands up, misses the check-in`() {
        val wrong = ChallengeRunner(ExerciseType.Squat, 15, Random(3))
        answer(wrong) { kind, t -> raising(left = kind == ChallengeKind.RaiseRight, right = kind == ChallengeKind.RaiseLeft, at = t) }
        assertEquals(1, wrong.missed)
        // A clip of arm raises or jumping jacks lifts both hands.
        val both = ChallengeRunner(ExerciseType.Squat, 15, Random(4))
        answer(both) { _, t -> raising(left = true, right = true, at = t) }
        assertEquals(1, both.missed)
    }

    @Test fun `a hand flicked up for one frame isn't enough`() {
        val runner = ChallengeRunner(ExerciseType.Squat, 15, Random(5))
        answer(runner) { kind, t -> if ((t / frameMs) % 5 == 0L) raising(kind == ChallengeKind.RaiseLeft, kind == ChallengeKind.RaiseRight, t) else body(at = t) }
        assertEquals(1, runner.missed)
    }

    @Test fun `leaving the frame during a check-in misses it`() {
        val runner = ChallengeRunner(ExerciseType.Squat, 15, Random(3))
        var t = untilOpen(runner, moving = true)
        repeat(70) { t += frameMs; runner.update(null, t) }
        assertEquals(1, runner.missed)
    }

    @Test fun `long sets get two check-ins, plank gets the hips-up one`() {
        val runner = ChallengeRunner(ExerciseType.SitUp, 60, Random(4))
        var t = 0L
        var opened = 0
        var wasOpen = false
        while (t < 200_000) {
            val ui = runner.update(body(((t / frameMs) % 2) * 0.1f, t), t)
            val open = ui != null && ui.passed == null
            if (open && !wasOpen) opened++
            wasOpen = open
            t += frameMs
        }
        assertEquals(2, opened)
    }

    /** Side-on plank; [hipY] below 0.63 lifts the hips into a pike. */
    private fun plank(hipY: Float, at: Long) = Pose(MutableList(Pose.COUNT) { Landmark(0.5f, 0.5f, 0.95f) }.apply {
        this[Pose.L_SHOULDER] = Landmark(0.25f, 0.60f); this[Pose.L_HIP] = Landmark(0.50f, hipY); this[Pose.L_ANKLE] = Landmark(0.80f, 0.66f)
        this[Pose.R_SHOULDER] = Landmark(0.25f, 0.60f, 0.2f); this[Pose.R_HIP] = Landmark(0.50f, hipY, 0.2f); this[Pose.R_ANKLE] = Landmark(0.80f, 0.66f, 0.2f)
    }, at)

    @Test fun `plank check-in passes with hips up then back into line`() {
        val runner = ChallengeRunner(ExerciseType.Plank, 60, Random(5))
        var t = 0L
        var ui = runner.update(plank(0.63f, t), t)
        while (ui?.kind == null) { t += frameMs; ui = runner.update(plank(0.63f, t), t) }
        assertEquals(ChallengeKind.HipsUp, ui.kind)
        repeat(5) { t += frameMs; runner.update(plank(0.40f, t), t) }
        repeat(3) { t += frameMs; ui = runner.update(plank(0.63f, t), t) }
        assertEquals(true, ui?.passed)
        assertEquals(1, runner.passed)
    }

    @Test fun `no check-in opens once the set is done`() {
        val runner = ChallengeRunner(ExerciseType.Squat, 15, Random(6))
        var t = 0L
        while (t < 60_000) { assertNull(runner.update(body(at = t), t, opening = false)); t += frameMs }
    }

    @Test fun `the motion log tells shaking from a nudge`() {
        val log = MotionLog()
        (0..20).forEach { log.add(it * 50L, if (it == 5) 3f else 0.1f) }
        assertFalse(log.movedBetween(0, 1_000))
        (21..40).forEach { log.add(it * 50L, if (it % 2 == 0) 2f else 0.5f) }
        assertTrue(log.movedBetween(1_050, 2_000))
        assertFalse(log.movedBetween(5_000, 6_000))
    }

    @Test fun `session checks count reps made while the phone moved and time out of frame`() {
        val log = MotionLog()
        (0..100).forEach { log.add(it * 20L, if (it * 20L in 1_000L..2_000L) 2f else 0.1f) }
        val checks = SessionChecks(ExerciseType.Squat, 15, log, Random(7))
        checks.update(body(at = 0), CounterState(inFrame = true), 0)
        checks.update(body(at = 1_200), CounterState(count = 1, inFrame = true, lastRepStartMs = 1_000, lastRepEndMs = 1_900), 1_200)
        checks.update(body(at = 1_300), CounterState(count = 2, inFrame = true, lastRepStartMs = 3_000, lastRepEndMs = 3_900), 1_300)
        // Out of frame for 3 seconds, in 100 ms frames.
        (1..30).forEach { checks.update(null, CounterState(count = 2), 1_300 + it * 100L) }
        val report = checks.report(CounterState(count = 2, formFaults = 3, fastReps = 1))
        assertEquals(1, report.phoneMovedReps)
        assertEquals(3, report.outOfFrameSeconds)
        assertEquals(3, report.formFaults)
        assertEquals(1, report.fastReps)
    }

    @Test fun `the integrity request hash matches the server's`() {
        // Both pinned in the server's ExerciseChecksTests.
        assertEquals("9ab43e1d6c898e55542d5ee870522a80eed659a67120a09dc2ce9da4a4c9e7ca", PlayIntegrity.requestHash("6F1C2A52-6A3C-4C76-9B1E-2F0A0D5B9C11", 15))
        val checks = app.heartpact.exercise.ExerciseChecks(formFaults = 2, fastReps = 1, challengesPassed = 1, challengesMissed = 0, phoneMovedReps = 3, outOfFrameSeconds = 4)
        assertEquals("b23443d94197b9521b5e553d54e7e8ec6f8a8e77a33df3d6d56d81295f3d9a87", PlayIntegrity.requestHash("6f1c2a52-6a3c-4c76-9b1e-2f0a0d5b9c11", 15, checks))
    }

    @Test fun `the partner reads flags first, then reps that didn't count`() {
        assertEquals("Worth a look: missed a camera check-in, the phone was moving during 3 reps.",
            OccurrenceChecks(challengesMissed = 1, phoneMovedReps = 3, flags = listOf("missedCheckIn", "phoneMoved", "somethingNew")).partnerLine())
        assertEquals("2 reps didn't count", OccurrenceChecks(formFaults = 1, fastReps = 1, challengesPassed = 1).partnerLine())
        assertEquals("Every camera check-in passed", OccurrenceChecks(challengesPassed = 2).partnerLine())
        assertNull(OccurrenceChecks().partnerLine())
        assertNotNull(OccurrenceChecks(flags = listOf("unverifiedApp")).partnerLine())
    }

    @Test fun `side plank asks for a hip dip, mountain climbers for hips up`() {
        val runner = ChallengeRunner(ExerciseType.SidePlank, 60, Random(5))
        var t = 0L
        fun at(sag: Float) = Bodies.pose(Bodies.sidePlank(hipSag = sag), t)
        var ui = runner.update(at(0f), t)
        while (ui?.kind == null) { t += frameMs; ui = runner.update(at(0f), t) }
        assertEquals(ChallengeKind.HipDip, ui.kind)
        assertEquals("Check-in: hip dip!", ui.title)
        // Hips up first is the wrong way: nothing yet.
        repeat(5) { t += frameMs; ui = runner.update(at(-0.15f), t) }
        repeat(3) { t += frameMs; ui = runner.update(at(0f), t) }
        assertEquals(null, ui?.passed)
        repeat(5) { t += frameMs; runner.update(at(0.22f), t) }
        repeat(3) { t += frameMs; ui = runner.update(at(0f), t) }
        assertEquals(true, ui?.passed)

        val climber = ChallengeRunner(ExerciseType.MountainClimber, 60, Random(5))
        var c = 0L
        var opened = climber.update(Bodies.pose(Bodies.climber(175f, 175f), c), c)
        while (opened?.kind == null) { c += frameMs; opened = climber.update(Bodies.pose(Bodies.climber(175f, 175f), c), c) }
        assertEquals(ChallengeKind.HipsUp, opened.kind)
    }
}
