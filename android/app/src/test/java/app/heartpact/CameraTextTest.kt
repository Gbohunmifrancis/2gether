package app.heartpact

import app.heartpact.exercise.CounterState
import app.heartpact.exercise.ExerciseType
import app.heartpact.ui.camera.CameraText
import app.heartpact.ui.camera.CameraUi
import app.heartpact.ui.camera.FinishOutcome
import app.heartpact.ui.camera.finishBackoffMs
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraTextTest {
    @Test fun clocksAreMinutesAndSeconds() {
        assertEquals("0:00", CameraText.clock(0))
        assertEquals("0:42", CameraText.clock(42))
        assertEquals("26:40", CameraText.clock(1_600))
        assertEquals("0:00", CameraText.clock(-5))
    }

    @Test fun titlesReadAsTheDare() {
        assertEquals("15 arm raises", CameraText.title(ExerciseType.ArmRaise, 15))
        assertEquals("Plank, 30 seconds", CameraText.title(ExerciseType.Plank, 30))
        assertEquals("of 15 arm raises", CameraText.of(ExerciseType.ArmRaise, 15))
        assertEquals("of 30 seconds", CameraText.of(ExerciseType.Plank, 30))
    }

    @Test fun whoseDare() {
        assertEquals("Theo's dare", CameraText.whose("Theo"))
        assertEquals("Your dare", CameraText.whose(null))
    }

    @Test fun toGoNeverGoesNegative() {
        assertEquals("6", CameraText.toGo(CameraUi(ExerciseType.ArmRaise, 15, CounterState(count = 9))))
        assertEquals("0", CameraText.toGo(CameraUi(ExerciseType.ArmRaise, 15, CounterState(count = 17))))
        assertEquals("12s", CameraText.toGo(CameraUi(ExerciseType.Plank, 30, CounterState(count = 18))))
    }

    @Test fun aFinishThatFailsOnTheWayRetriesByItself() {
        assertEquals(FinishOutcome.Retry("Busy"), FinishOutcome.of("unknown", 503, "Busy"))
        assertEquals(FinishOutcome.Retry("Raced"), FinishOutcome.of("exercise.version_conflict", 409, "Raced"))
        // The server's clock a second behind the phone's: wait and send again.
        assertEquals(FinishOutcome.Retry("Too fast"), FinishOutcome.of("exercise.too_fast", 422, "Too fast"))
    }

    @Test fun aFinishRefusedForAReasonWaitsForThePerson() {
        assertEquals(FinishOutcome.Refused("Closed"), FinishOutcome.of("exercise.not_active", 409, "Closed"))
        assertEquals(FinishOutcome.Refused("Light"), FinishOutcome.of("exercise.low_confidence", 422, "Light"))
    }

    @Test fun retriesWaitLongerEachTimeUpToHalfAMinute() {
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), (0..5).map(::finishBackoffMs))
    }
}
