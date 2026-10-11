package app.heartpact.screens

import app.heartpact.ui.splash.SplashScene
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Frames of the walk-together splash, for checking the choreography without a device. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class SplashShotsTest {
    @Test fun frames() {
        listOf(700, 1100, 1450, 1650, 1850, 2050, 2300, 3000).forEach { t ->
            shot("splash-%04d".format(t)) { SplashScene(timeMs = t.toFloat()) }
        }
    }
}
