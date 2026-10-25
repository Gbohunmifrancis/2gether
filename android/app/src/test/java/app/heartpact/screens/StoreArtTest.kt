package app.heartpact.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.heartpact.ui.graphics.Character
import app.heartpact.ui.graphics.HalfState
import app.heartpact.ui.graphics.HeartSticker
import app.heartpact.ui.graphics.LogoMark
import app.heartpact.ui.graphics.PeeledHeart
import app.heartpact.ui.graphics.Pose
import app.heartpact.ui.graphics.Who
import app.heartpact.ui.theme.HeartpactTheme
import com.github.takahirom.roborazzi.captureRoboImage
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import app.heartpact.R
import org.junit.Test
import java.io.File
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The app's own stickers, characters and mark, large and on transparent ground, for the store art (marketing/play-store). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h400dp-xxhdpi")
class StoreArtTest {
    private fun art(name: String, content: @Composable () -> Unit) =
        captureRoboImage("build/screens/store/art/$name.png") { HeartpactTheme { Box(Modifier.padding(12.dp)) { content() } } }

    @Test fun art() {
        art("heart-full") { HeartSticker(HalfState.Stuck, HalfState.Stuck, 360.dp) }
        art("heart-left") { HeartSticker(HalfState.Stuck, HalfState.Slot, 360.dp) }
        art("heart-right") { HeartSticker(HalfState.Slot, HalfState.Stuck, 360.dp) }
        art("heart-empty") { HeartSticker(HalfState.Slot, HalfState.Slot, 360.dp) }
        art("heart-peeled") { PeeledHeart(360.dp) }
        art("logo") { LogoMark(360.dp, night = false) }
        art("logo-night") { LogoMark(360.dp, night = true) }
        art("maya-wave") { Character(Who.Left, Pose.Wave, 360.dp) }
        art("theo-stand") { Character(Who.Right, Pose.Stand, 360.dp) }
        art("theo-walk") { Character(Who.Right, Pose.Walk, 360.dp) }
        art("maya-walk") { Character(Who.Left, Pose.Walk, 360.dp) }
        art("both-hug") { Character(Who.Both, Pose.Hug, 360.dp) }
        art("both-cheer") { Character(Who.Both, Pose.Cheer, 360.dp) }
    }

    /** The Play icon: the launcher's own background and foreground layers, full bleed at 512 px (Play rounds the corners). */
    @Test fun playIcon() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        listOf(R.drawable.ic_launcher_background, R.drawable.ic_launcher_foreground).forEach { id ->
            ContextCompat.getDrawable(context, id)!!.apply { setBounds(0, 0, 512, 512); draw(canvas) }
        }
        File("build/screens/store/art").mkdirs()
        File("build/screens/store/art/play-icon-512.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
