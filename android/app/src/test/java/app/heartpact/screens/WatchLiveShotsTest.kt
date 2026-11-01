package app.heartpact.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import app.heartpact.Bodies
import app.heartpact.data.ExerciseGoal
import app.heartpact.data.ExerciseOccurrence
import app.heartpact.data.ExerciseToday
import app.heartpact.data.OccurrenceStatus
import app.heartpact.exercise.Cheer
import app.heartpact.exercise.CheerShown
import app.heartpact.exercise.CounterState
import app.heartpact.exercise.ExerciseType
import app.heartpact.exercise.LiveCodec
import app.heartpact.exercise.LiveCues
import app.heartpact.exercise.LiveFrame
import app.heartpact.exercise.LivePhase
import app.heartpact.exercise.Pose
import app.heartpact.exercise.SetupIssue
import app.heartpact.i18n.Tr
import app.heartpact.ui.camera.CameraRepContent
import app.heartpact.ui.camera.CameraUi
import app.heartpact.ui.home.HomeScreen
import app.heartpact.ui.theme.Side
import app.heartpact.ui.today.NewDareScreen
import app.heartpact.ui.today.NewDareState
import app.heartpact.ui.today.TodayBuilder
import app.heartpact.ui.today.TodayTab
import app.heartpact.ui.today.TodayUi
import app.heartpact.ui.watch.WatchLiveScreen
import app.heartpact.ui.watch.WatchUi
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.util.Locale

/** Watching live (docs/couple-goals.md): Theo's phone watching Maya's squats, and what Maya's camera shows of it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class WatchLiveShotsTest {
    private val english = Locale.getDefault()

    @After fun back() {
        Locale.setDefault(english)
        Tr.use("en", null)
    }

    private fun frame(body: Map<Int, Bodies.P>, phase: LivePhase, count: Int, cue: String?, yaw: Float = 35f) =
        LiveFrame(count, phase, cue, 0.75f, LiveCodec.figure(Bodies.pose(body, 0, yawDeg = yaw)))

    private val watching = WatchUi("Maya", partnerSide = Side.Left, type = ExerciseType.Squat, target = 10, connected = true, cameraOn = true)

    @Test fun gettingSet() = shot("watch-getting-set") {
        WatchLiveScreen(watching.copy(frame = frame(Bodies.standing(), LivePhase.Setup, 0, LiveCues.setup(SetupIssue.StartPosition), yaw = 0f)), {}, {})
    }

    @Test fun counting() = shot("watch-counting") {
        WatchLiveScreen(watching.copy(count = 7, frame = frame(Bodies.squat(100f), LivePhase.Counting, 7, null), sent = CheerShown(Cheer.Heart, "", 1)), {}, {})
    }

    @Test fun countingNight() = shot("watch-counting-night", night = true) {
        WatchLiveScreen(watching.copy(count = 7, frame = frame(Bodies.squat(140f), LivePhase.Counting, 7, LiveCues.tip(app.heartpact.exercise.FaultKind.Shallow))), {}, {})
    }

    @Test fun countdown() = shot("watch-countdown") {
        WatchLiveScreen(watching.copy(type = ExerciseType.SidePlank, target = 30, frame = frame(Bodies.sidePlank(), LivePhase.Countdown, 0, LiveCues.countdown(2), yaw = 90f)), {}, {})
    }

    @Test fun waiting() = shot("watch-waiting") { WatchLiveScreen(watching.copy(cameraOn = false), {}, {}) }

    @Test fun done() = shot("watch-done") { WatchLiveScreen(watching.copy(count = 10, done = true), {}, {}) }

    @Test fun arabic() {
        val file = File("src/main/assets/i18n/ar.json")
        if (!file.exists()) return
        Locale.setDefault(Locale.forLanguageTag("ar"))
        Tr.use("ar", file.readText())
        shot("ar/watch-counting") {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                WatchLiveScreen(watching.copy(partnerName = "مايا", count = 7, frame = frame(Bodies.lunge(1f, left = true), LivePhase.Counting, 7, LiveCues.tip(app.heartpact.exercise.FaultKind.Shallow)),
                    type = ExerciseType.Lunge, target = 20), {}, {})
            }
        }
    }

    /** Maya's camera while Theo watches and sends "You've got this". */
    @Test fun cameraWatched() {
        val pose = Bodies.pose(Bodies.squat(115f), 0, yawDeg = 35f).let { Pose(it.landmarks, 0, 390f / 844f) }
        val ui = CameraUi(ExerciseType.Squat, 15, CounterState(count = 6, inFrame = true, confidence = 0.9), pose, secondsLeft = 1_420, elapsedSeconds = 51,
            setBy = "Theo", silenced = false, watching = "Theo", cheer = CheerShown(Cheer.GotThis, "Theo", 3))
        shot("camera-watched") { CameraRepContent(ui, {}, {}) { CameraStandIn() } }
    }

    @Composable private fun CameraStandIn() = Box(Modifier.fillMaxSize().background(Color(0xFF6E6A70)))

    /** Today with Maya's dare on camera: "Watch live". */
    @Test fun todayWatchLive() {
        val now = Instant.parse("2026-10-11T07:48:00Z")
        val mine = ExerciseGoal("g1", "theo", "Theo", "maya", ExerciseType.Burpee, "Burpees", 10, scheduledTime = "18:00", timeZoneId = "UTC", nextDueAtUtc = "2026-10-11T18:00:00Z")
        val hers = ExerciseGoal("g2", "maya", "Maya", "theo", ExerciseType.Squat, "Squats", 15, scheduledTime = "07:40", timeZoneId = "UTC", nextDueAtUtc = "2026-10-12T07:40:00Z")
        val onCamera = ExerciseOccurrence("o2", "g2", "maya", "Maya", ExerciseType.Squat, "Squats", 15, localDate = "2026-10-11", achievedCount = 6,
            scheduledAtUtc = "2026-10-11T07:40:00Z", deadlineUtc = "2026-10-11T08:10:00Z", status = OccurrenceStatus.InProgress)
        val view = TodayBuilder.build(ExerciseToday("x", listOf(mine, hers), listOf(onCamera)), null, "theo", now, java.time.ZoneOffset.UTC)
        shot("today-watch-live") {
            app.heartpact.ui.theme.HeartpactTheme(actor = Side.Right) {
                HomeScreen("Theo", "Maya", {}, today = { TodayTab(TodayUi(view = view, loading = false), "Maya", {}, onCallOff = {}, onKeep = {}, onNewDare = {}, now = now) })
            }
        }
    }

    /** The picker with the new exercises: a burpee dare, and a wall sit in seconds. */
    @Test fun newDarePicker() {
        shot("new-dare-burpees") { NewDareScreen(NewDareState(type = ExerciseType.Burpee, target = 10), "Theo", {}, {}, {}, {}, {}, {}) }
        shot("new-dare-wall-sit") { NewDareScreen(NewDareState(type = ExerciseType.WallSit, target = 45), "Theo", {}, {}, {}, {}, {}, {}) }
    }
}
