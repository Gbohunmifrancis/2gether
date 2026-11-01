package app.heartpact

import app.heartpact.exercise.ChallengeKind
import app.heartpact.exercise.ChallengeUi
import app.heartpact.exercise.Cheer
import app.heartpact.exercise.CoachPhase
import app.heartpact.exercise.CoachState
import app.heartpact.exercise.CounterState
import app.heartpact.exercise.ExerciseType
import app.heartpact.exercise.FaultKind
import app.heartpact.exercise.LiveCodec
import app.heartpact.exercise.LiveCues
import app.heartpact.exercise.LiveFrame
import app.heartpact.exercise.LivePhase
import app.heartpact.exercise.Pose
import app.heartpact.exercise.PoseCoach
import app.heartpact.exercise.SetupIssue
import app.heartpact.exercise.livePhase
import app.heartpact.ui.watch.WatchEnd
import app.heartpact.ui.watch.WatchText
import app.heartpact.ui.watch.WatchUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Watching live: only the figure's joints and a few numbers leave the phone, and the partner's phone reads them back. */
class LiveViewTest {
    @Test fun `only the figure's joints are sent, rounded, and hidden ones stay hidden`() {
        val pose = Bodies.pose(Bodies.squat(120f), 0, yawDeg = 30f)
        val joints = LiveCodec.figure(pose)
        assertEquals(Pose.FIGURE.size, joints.size)
        val wire = LiveCodec.encode(joints)
        // 13 joints, x and y: 26 whole numbers. Nothing about the picture, the world landmarks or the face.
        assertEquals(26, wire.size)
        assertTrue(wire.all { it in -1..1000 })
        val back = LiveCodec.decode(wire)
        joints.zip(back).forEach { (sent, got) ->
            assertEquals(sent!!.x, got!!.x, 0.001f)
            assertEquals(sent.y, got.y, 0.001f)
        }
        // A wrist the camera can barely see goes as -1, and comes back as nothing.
        val hidden = Pose(pose.landmarks.toMutableList().also { it[Pose.L_WRIST] = it[Pose.L_WRIST].copy(visibility = 0.2f) }, 0, pose.aspect)
        val sent = LiveCodec.encode(LiveCodec.figure(hidden))
        val wrist = Pose.FIGURE.indexOf(Pose.L_WRIST)
        assertEquals(listOf(-1, -1), listOf(sent[wrist * 2], sent[wrist * 2 + 1]))
        assertNull(LiveCodec.decode(sent)[wrist])
        // Nobody in the picture: nothing at all.
        assertEquals(0, LiveCodec.encode(LiveCodec.figure(null)).size)
        assertTrue(LiveCodec.decode(IntArray(0)).all { it == null })
        assertEquals(750, LiveCodec.aspect(0.75f))
    }

    @Test fun `the cue travels as a code and is read back in the partner's language`() {
        assertEquals("Turn side-on to the camera.", LiveCues.text(ExerciseType.Squat, LiveCues.setup(SetupIssue.TurnSideways)))
        assertEquals("Lift your hips into a straight line.", LiveCues.text(ExerciseType.Plank, LiveCues.setup(SetupIssue.StartPosition, FaultKind.HipsLow)))
        assertEquals("Go lower: hips down toward knee height.", LiveCues.text(ExerciseType.Squat, LiveCues.tip(FaultKind.Shallow)))
        assertEquals("Kick your feet back into a straight plank.", LiveCues.text(ExerciseType.Burpee, LiveCues.tip(FaultKind.Shallow)))
        assertEquals("Check-in: left hand up!", LiveCues.text(ExerciseType.Lunge, LiveCues.checkIn(ChallengeKind.RaiseLeft)))
        assertEquals("Hold it there.", LiveCues.text(ExerciseType.Squat, LiveCues.countdown(2)))
        assertEquals(2, LiveCues.countdownOf("count.2"))
        assertNull(LiveCues.text(ExerciseType.Squat, "tip.Nonsense"))
        assertNull(LiveCues.text(ExerciseType.Squat, null))
        // Every code is letters, digits and dots, as the server requires.
        val codes = SetupIssue.entries.map { LiveCues.setup(it, FaultKind.HipsHigh) } + FaultKind.entries.map(LiveCues::tip) + ChallengeKind.entries.map(LiveCues::checkIn) + LiveCues.READY + LiveCues.OUT_OF_FRAME
        codes.forEach { assertTrue(it, Regex("^[A-Za-z0-9.]{1,48}$").matches(it)) }
    }

    @Test fun `the screen's current instruction becomes the frame's cue, first thing first`() {
        val coach = CoachState(CoachPhase.Setup, "Turn side-on to the camera.", code = LiveCues.setup(SetupIssue.TurnSideways))
        assertEquals("setup.TurnSideways", LiveCues.of(coach, CounterState(), null))
        val counting = CoachState(CoachPhase.Counting)
        val checkIn = ChallengeUi(ChallengeKind.RaiseRight, 4_000, 0.2f)
        assertEquals("checkIn.RaiseRight", LiveCues.of(counting, CounterState(inFrame = true, fault = FaultKind.Shallow), checkIn))
        assertEquals("tip.Shallow", LiveCues.of(counting, CounterState(inFrame = true, fault = FaultKind.Shallow), null))
        assertEquals("out", LiveCues.of(counting, CounterState(inFrame = false), null))
        assertNull(LiveCues.of(counting, CounterState(inFrame = true), checkIn.copy(passed = true)))
        // The coach says what it codes.
        val tick = PoseCoach(ExerciseType.Squat, 10).update(null, CounterState(), 0)
        assertEquals(LiveCues.setup(SetupIssue.NoBody), tick.state.code)
    }

    @Test fun `the phase follows the coach, then the count`() {
        assertEquals(LivePhase.Setup, livePhase(CoachState(CoachPhase.Setup), 0, 10))
        assertEquals(LivePhase.Countdown, livePhase(CoachState(CoachPhase.Countdown), 0, 10))
        assertEquals(LivePhase.Counting, livePhase(CoachState(CoachPhase.Counting), 4, 10))
        assertEquals(LivePhase.Done, livePhase(CoachState(CoachPhase.Counting), 10, 10))
        assertEquals(LivePhase.Counting, LivePhase.of("counting"))
        assertNull(LivePhase.of("dancing"))
        assertEquals(Cheer.GotThis, Cheer.of("gotThis"))
        assertNull(Cheer.of("anything"))
    }

    @Test fun `the live view says where things are`() {
        val waiting = WatchUi("Maya", type = ExerciseType.Squat, target = 10, connected = true)
        assertEquals("Waiting for Maya to start the camera", WatchText.status(waiting))
        assertEquals("Maya's squats", WatchText.title(waiting))
        val frame = LiveFrame(3, LivePhase.Setup, LiveCues.setup(SetupIssue.TurnSideways), 0.75f, LiveCodec.figure(Bodies.pose(Bodies.standing(), 0)))
        val setting = waiting.copy(frame = frame)
        assertEquals("Getting set", WatchText.status(setting))
        assertEquals("Turn side-on to the camera.", WatchText.cue(setting))
        assertEquals("Counting", WatchText.status(setting.copy(frame = frame.copy(phase = LivePhase.Counting, cue = null))))
        assertNull(WatchText.cue(setting.copy(frame = frame.copy(phase = LivePhase.Counting, cue = null))))
        assertEquals("Maya's camera paused", WatchText.status(setting.copy(stale = true)))
        assertNull(WatchText.cue(setting.copy(stale = true)))
        assertEquals("Connecting…", WatchText.status(setting.copy(connected = false)))
        assertEquals("Done!", WatchText.status(setting.copy(done = true)))
        assertEquals("Time ran out", WatchText.status(setting.copy(ended = WatchEnd.Missed)))
        assertFalse(LiveFrame(0, LivePhase.Setup, null, 0.75f, LiveCodec.figure(null)).hasBody)
    }
}
