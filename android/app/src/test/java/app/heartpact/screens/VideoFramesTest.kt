package app.heartpact.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.heartpact.ui.graphics.LiveLogoFrame
import app.heartpact.ui.graphics.LogoMood
import app.heartpact.ui.splash.SplashScene
import app.heartpact.ui.splash.SplashTimeline
import app.heartpact.ui.theme.HeartpactTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The app's own animations, frame by frame at 30 fps, for the launch video (marketing/video): the splash as the
 * phone shows it, and the live logo on black and on white so the video tool can recover its exact transparency.
 * Only runs when recording: `./gradlew testDebugUnitTest --tests '*VideoFramesTest*' -Proborazzi.test.record=true`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class VideoFramesTest {
    @Test fun splash() {
        if (!recording()) return
        var frame = 0
        while (frame * FRAME_MS <= SplashTimeline.END) {
            val t = frame * FRAME_MS
            captureRoboImage("build/screens/video/splash/%04d.png".format(frame)) { HeartpactTheme { SplashScene(timeMs = t) } }
            frame++
        }
    }

    /** The welcome logo's drop-in, then its clap and its somersault (each from rest to rest), night colours. */
    @Test fun logo() {
        if (!recording()) return
        val clips = listOf("drop" to 0f..1300f, "clap" to 4150f..5700f, "flip" to 9100f..10700f)
        clips.forEach { (name, range) ->
            var frame = 0
            while (range.start + frame * FRAME_MS <= range.endInclusive) {
                val t = range.start + frame * FRAME_MS
                listOf("black" to Color.Black, "white" to Color.White).forEach { (ground, colour) ->
                    captureRoboImage("build/screens/video/logo-$name/$ground/%04d.png".format(frame)) {
                        HeartpactTheme(night = true) {
                            Box(Modifier.size(390.dp, 460.dp).background(colour), contentAlignment = Alignment.BottomCenter) {
                                LiveLogoFrame(200.dp, LogoMood.Happy, night = true, t = t, entrance = true, modifier = Modifier.padding(bottom = 60.dp))
                            }
                        }
                    }
                }
                frame++
            }
        }
    }

    private fun recording() = System.getProperty("roborazzi.test.record") == "true"

    private companion object {
        const val FRAME_MS = 1000f / 30f
    }
}
