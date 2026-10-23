package app.heartpact

import app.heartpact.exercise.CounterState
import app.heartpact.exercise.ExerciseType
import app.heartpact.ui.camera.CameraText
import app.heartpact.ui.camera.CameraUi
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
}
