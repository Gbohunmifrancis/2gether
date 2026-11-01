package app.heartpact.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import app.heartpact.alarm.AlarmEnded
import app.heartpact.alarm.AlarmReadiness
import app.heartpact.alarm.AlarmRinging
import app.heartpact.alarm.EndReason
import app.heartpact.alarm.HeartComplete
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import app.heartpact.ui.graphics.StickingHeart
import app.heartpact.ui.graphics.StickTimeline
import app.heartpact.data.ExerciseGoal
import app.heartpact.data.ExerciseHistory
import app.heartpact.data.ExerciseOccurrence
import app.heartpact.data.ExerciseToday
import app.heartpact.data.OccurrenceStatus
import app.heartpact.exercise.CounterState
import app.heartpact.exercise.ExerciseType
import app.heartpact.exercise.Landmark
import app.heartpact.exercise.Pose
import app.heartpact.ui.camera.CameraRepContent
import app.heartpact.ui.camera.CameraUi
import app.heartpact.ui.home.HomeScreen
import app.heartpact.ui.theme.Side
import app.heartpact.ui.today.NewDareScreen
import app.heartpact.ui.today.NewDareState
import app.heartpact.ui.today.TodayBuilder
import app.heartpact.ui.today.TodayTab
import app.heartpact.ui.today.TodayUi
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class ExerciseShotsTest {
    private val now = Instant.parse("2026-10-11T07:48:00Z")
    private val goal = ExerciseGoal("g1", "maya", "Maya", "theo", ExerciseType.SitUp, "Sit-ups", 20, scheduledTime = "08:00", timeZoneId = "UTC", nextDueAtUtc = "2026-10-12T08:00:00Z")
    private val ringing = ExerciseOccurrence("o1", "g1", "maya", "Maya", ExerciseType.SitUp, "Sit-ups", 20, localDate = "2026-10-11",
        scheduledAtUtc = "2026-10-11T07:40:00Z", deadlineUtc = "2026-10-11T08:10:00Z", status = OccurrenceStatus.Alarming)
    private val hisGoal = ExerciseGoal("g2", "theo", "Theo", "maya", ExerciseType.PushUp, "Push-ups", 30, scheduledTime = "18:30", timeZoneId = "UTC", nextDueAtUtc = "2026-10-11T18:30:00Z")
    private val history = ExerciseHistory(7, listOf(
        ringing.copy(id = "h1", localDate = "2026-10-05", status = OccurrenceStatus.Completed),
        ringing.copy(id = "h2", localDate = "2026-10-06", status = OccurrenceStatus.Completed),
        ringing.copy(id = "h3", localDate = "2026-10-07", status = OccurrenceStatus.Missed),
        ringing.copy(id = "h4", localDate = "2026-10-08", status = OccurrenceStatus.Completed),
        ringing.copy(id = "h5", localDate = "2026-10-09", status = OccurrenceStatus.Cancelled),
        ringing.copy(id = "h6", localDate = "2026-10-10", status = OccurrenceStatus.Completed),
    ))

    @Test fun todayRinging() {
        val view = TodayBuilder.build(ExerciseToday("x", listOf(goal, hisGoal), listOf(ringing)), history, "maya", now, java.time.ZoneOffset.UTC)
        shot("today-ringing") { HomeScreen("Maya", "Theo", {}, today = { TodayTab(TodayUi(view = view, loading = false), "Theo", {}, onCallOff = {}, onKeep = {}, onNewDare = {}, now = now) }) }
    }

    @Test fun todayUpcomingWithReadinessAndRequest() {
        val upcoming = goal.copy(nextDueAtUtc = "2026-10-11T08:00:00Z")
        val hisRinging = ringing.copy(id = "o2", goalId = "g2", userId = "theo", title = "Push-ups", callOffRequestedAtUtc = "2026-10-11T07:45:00Z", callOffNote = "Knee's sore after yesterday's run. Can I swap it for a walk tonight?")
        val view = TodayBuilder.build(ExerciseToday("x", listOf(upcoming, hisGoal), listOf(hisRinging)), history, "maya", now, java.time.ZoneOffset.UTC)
        shot("today-upcoming") { HomeScreen("Maya", "Theo", {}, today = { TodayTab(TodayUi(view = view, loading = false, readiness = AlarmReadiness(true, false, false)), "Theo", {}, onCallOff = {}, onKeep = {}, onNewDare = {}, now = now) }) }
    }

    @Test fun todayPartnerFinishedWithFlags() {
        val his = ringing.copy(id = "o3", goalId = "g2", userId = "theo", exerciseType = ExerciseType.PushUp, title = "Push-ups", target = 30, achievedCount = 30,
            status = OccurrenceStatus.Completed, completedAtUtc = "2026-10-11T07:30:00Z",
            checks = app.heartpact.data.OccurrenceChecks(formFaults = 2, challengesMissed = 1, phoneMovedReps = 3, flags = listOf("missedCheckIn", "phoneMoved")))
        val view = TodayBuilder.build(ExerciseToday("x", listOf(goal, hisGoal), listOf(his)), history, "maya", now, java.time.ZoneOffset.UTC)
        shot("today-partner-flagged") { HomeScreen("Maya", "Theo", {}, today = { TodayTab(TodayUi(view = view, loading = false), "Theo", {}, onCallOff = {}, onKeep = {}, onNewDare = {}, now = now) }) }
    }

    @Test fun pacts() {
        val goals = listOf(goal, hisGoal)
        val view = app.heartpact.ui.pacts.PactsBuilder.build(goals, history.copy(occurrences = history.occurrences + ringing.copy(id = "t", localDate = "2026-10-11", status = OccurrenceStatus.Completed)), "maya", now, java.time.ZoneOffset.UTC)
        shot("pacts") { HomeScreen("Maya", "Theo", {}, initialTab = app.heartpact.ui.home.Tab.Pacts, pacts = { app.heartpact.ui.pacts.PactsTab(app.heartpact.ui.pacts.PactsUi(view), "Theo", {}, {}) }) }
        shot("pacts-set") { HomeScreen("Maya", "Theo", {}, initialTab = app.heartpact.ui.home.Tab.Pacts, pacts = { app.heartpact.ui.pacts.PactsTab(app.heartpact.ui.pacts.PactsUi(view), "Theo", {}, {}, initialForMe = false) }) }
    }

    @Test fun newDare() = shot("new-dare") { NewDareScreen(NewDareState(type = ExerciseType.PushUp, target = 30), "Theo", {}, {}, {}, {}, {}, {}) }

    @Test fun alarm() {
        shot("alarm") { AlarmRinging(ringing, "Theo", setByPartner = true, mySide = Side.Left, busy = false, note = null, {}, {}, {}, now = { now }) }
        shot("alarm-asked") { AlarmRinging(ringing.copy(callOffRequestedAtUtc = "2026-10-11T07:47:00Z"), "Theo", true, Side.Left, false, null, {}, {}, {}, now = { now }) }
    }

    /** Facing the camera, one arm up: the arm-raise pose from the workout mockup. */
    private val armRaise = MutableList(Pose.COUNT) { Landmark(0.5f, 0.5f, 0.1f) }.apply {
        this[Pose.L_SHOULDER] = Landmark(0.42f, 0.34f, 0.95f); this[Pose.R_SHOULDER] = Landmark(0.58f, 0.34f, 0.95f)
        this[Pose.L_ELBOW] = Landmark(0.38f, 0.44f, 0.95f); this[Pose.L_WRIST] = Landmark(0.41f, 0.35f, 0.95f)
        this[Pose.R_ELBOW] = Landmark(0.63f, 0.23f, 0.95f); this[Pose.R_WRIST] = Landmark(0.60f, 0.10f, 0.95f)
        this[Pose.L_HIP] = Landmark(0.42f, 0.54f, 0.95f); this[Pose.R_HIP] = Landmark(0.59f, 0.54f, 0.95f)
        this[Pose.L_KNEE] = Landmark(0.44f, 0.71f, 0.95f); this[Pose.R_KNEE] = Landmark(0.56f, 0.71f, 0.95f)
        this[Pose.L_ANKLE] = Landmark(0.46f, 0.90f, 0.95f); this[Pose.R_ANKLE] = Landmark(0.54f, 0.90f, 0.95f)
    }
    private val workout = CameraUi(ExerciseType.ArmRaise, 15, CounterState(count = 9, inFrame = true, confidence = 0.9), Pose(armRaise, 0, 844f / 390f),
        secondsLeft = 26 * 60 + 40, elapsedSeconds = 42, setBy = "Theo", silenced = false)

    @Test fun camera() = shot("camera") { CameraRepContent(workout, {}, {}) { CameraStandIn() } }

    @Test fun cameraNight() = shot("camera-night", night = true) { CameraRepContent(workout, {}, {}) { CameraStandIn() } }

    /** Theo's phone on a dare he set himself, silenced, about to finish: his colours, no partner bubble. */
    @Test fun cameraTheo() {
        val ui = workout.copy(counter = CounterState(count = 15, inFrame = false), mySide = Side.Right, setBy = null, silenced = true, sealing = true)
        shot("camera-theo") { app.heartpact.ui.theme.HeartpactTheme(actor = Side.Right) { CameraRepContent(ui, {}, {}) { CameraStandIn() } } }
    }

    /** A surprise check-in on screen, with the tip from a rep that didn't count. */
    @Test fun cameraCheckIn() {
        val ui = workout.copy(
            counter = CounterState(count = 9, inFrame = true, confidence = 0.9, cue = "Both hands all the way above your head."),
            challenge = app.heartpact.exercise.ChallengeUi(app.heartpact.exercise.ChallengeKind.RaiseLeft, msLeft = 4_200, progress = 0.4f),
        )
        shot("camera-check-in") { CameraRepContent(ui, {}, {}) { CameraStandIn() } }
    }

    /** The voice coach getting someone into position before anything counts. */
    @Test fun cameraGetSet() {
        val ui = workout.copy(counter = CounterState(), coach = app.heartpact.exercise.CoachState(app.heartpact.exercise.CoachPhase.Setup, "Stand tall with your arms down by your sides.", inFrame = true), voiceOn = true)
        shot("camera-get-set") { CameraRepContent(ui, {}, {}) { CameraStandIn() } }
    }

    /** Then counting down to "Go!", the voice switched off. */
    @Test fun cameraCountdown() {
        val ui = workout.copy(counter = CounterState(), coach = app.heartpact.exercise.CoachState(app.heartpact.exercise.CoachPhase.Countdown, "Hold it there.", countdown = 2, inFrame = true), voiceOn = false)
        shot("camera-countdown", night = true) { CameraRepContent(ui, {}, {}) { CameraStandIn() } }
    }

    @Test @Config(qualifiers = "w844dp-h390dp-land-xxhdpi") fun cameraLandscape() {
        // A side-on sit-up pose for the overlay.
        val points = MutableList(Pose.COUNT) { Landmark(0.5f, 0.5f, 0.1f) }.apply {
            this[Pose.L_SHOULDER] = Landmark(0.40f, 0.40f, 0.95f); this[Pose.L_ELBOW] = Landmark(0.48f, 0.30f, 0.95f); this[Pose.L_WRIST] = Landmark(0.44f, 0.22f, 0.95f)
            this[Pose.L_HIP] = Landmark(0.55f, 0.70f, 0.95f); this[Pose.L_KNEE] = Landmark(0.72f, 0.48f, 0.95f); this[Pose.L_ANKLE] = Landmark(0.88f, 0.72f, 0.95f)
        }
        val ui = CameraUi(ExerciseType.SitUp, 20, CounterState(count = 14, inFrame = true, confidence = 0.9), Pose(points, 0, 390f / 844f), secondsLeft = 1_600, elapsedSeconds = 75, setBy = "Theo", silenced = false)
        shot("camera-landscape") { CameraRepContent(ui, {}, {}) { CameraStandIn() } }
    }

    @Composable private fun CameraStandIn() = Box(Modifier.fillMaxSize().background(Color(0xFF6E6A70)))

    @Test fun heartComplete() {
        val done = ringing.copy(status = OccurrenceStatus.Completed, achievedCount = 20, completedAtUtc = "2026-10-11T07:56:00Z", scheduledAtUtc = "2026-10-11T08:00:00Z")
        shot("heart-complete") { HeartComplete(done, "Theo", onDone = {}, frozenAtMs = StickTimeline.END_MS) }
        shot("heart-complete-landing") { HeartComplete(done, "Theo", onDone = {}, frozenAtMs = 140f) }
        shot("heart-complete-theo") { HeartComplete(done, "Maya", onDone = {}, mySide = Side.Right, frozenAtMs = StickTimeline.END_MS) }
    }

    /** Mockup 20, frame by frame, for both partners: 0, 120 and 240 ms. */
    @Test fun stickingFrames() = shot("sticking-frames") {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            listOf(Side.Left, Side.Right).forEach { side ->
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    listOf(0f, StickTimeline.DROP_MS, StickTimeline.END_MS).forEach { ms -> StickingHeart(side, 90.dp, frozenAtMs = ms) }
                }
            }
        }
    }

    @Test fun calledOff() = shot("called-off") { AlarmEnded(EndReason.LetOff, "Theo") {} }
}
