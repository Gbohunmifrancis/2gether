package app.heartpact.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.heartpact.ui.graphics.Character
import app.heartpact.ui.graphics.HalfState
import app.heartpact.ui.graphics.HeartSticker
import app.heartpact.ui.graphics.LiveLogoFrame
import app.heartpact.ui.graphics.LogoMark
import app.heartpact.ui.graphics.LogoMood
import app.heartpact.ui.graphics.Mood
import app.heartpact.ui.graphics.PeeledHeart
import app.heartpact.ui.graphics.Pose
import app.heartpact.ui.graphics.Who
import app.heartpact.ui.theme.Heartpact
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class GraphicsShotsTest {
    @Test
    fun stickersLogoAndCharacters() = shot("graphics") {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                HeartSticker(HalfState.Stuck, HalfState.Stuck, 56.dp)
                HeartSticker(HalfState.Slot, HalfState.Stuck, 56.dp)
                HeartSticker(HalfState.Stuck, HalfState.Slot, 56.dp)
                PeeledHeart(56.dp)
                HeartSticker(HalfState.Slot, HalfState.Slot, 56.dp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                LogoMark(80.dp, night = false)
                LogoMark(40.dp, night = false)
                LogoMark(24.dp, night = false)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                Character(Who.Left, Pose.Wave, 110.dp)
                Character(Who.Right, Pose.Stand, 110.dp)
                Character(Who.Both, Pose.Hug, 130.dp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                Character(Who.Both, Pose.Cheer, 120.dp)
                Character(Who.Left, Pose.Rest, 100.dp, mood = Mood.Sleep)
                Character(Who.Right, Pose.Stand, 100.dp, mood = Mood.Worried)
            }
        }
    }

    /** Each mood at rest, then at a telling moment of its routine, by day and by night. */
    @Test
    fun liveLogoMoods() {
        listOf(false, true).forEach { night ->
            shot(if (night) "live-logo-night" else "live-logo", night = night) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(30.dp)) {
                    LogoMood.entries.forEach { mood ->
                        Row(horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.Bottom) {
                            Text(mood.name, style = Heartpact.type.body, color = Heartpact.colors.ink, modifier = Modifier.padding(bottom = 8.dp).width(72.dp))
                            LiveLogoFrame(56.dp, mood, night, t = 0f)
                            MOMENTS.getValue(mood).forEach { LiveLogoFrame(56.dp, mood, night, t = it) }
                        }
                    }
                }
            }
        }
    }

    /** The welcome logo frame by frame: the drop-in, then each trick (times from its routine), and a tap. */
    @Test
    fun liveLogoTricks() = shot("live-logo-tricks") {
        val frames = listOf(
            "drop" to 150f, "drop" to 330f, "land" to 470f, "bounce" to 700f, "hearts" to 900f,
            "bounce" to 2000f, "bounce" to 2550f, "apart" to 4500f, "apart" to 4700f, "clap" to 4880f,
            "hearts" to 5150f, "hearts" to 5450f, "dance" to 7150f, "dance" to 7400f, "flip" to 9450f,
            "flip" to 9650f, "flip" to 9850f, "land" to 10150f, "hearts" to 10450f,
        )
        Column(Modifier.padding(horizontal = 16.dp, vertical = 40.dp), verticalArrangement = Arrangement.spacedBy(40.dp)) {
            (frames.map { (label, t) -> Triple(label, t, null as Float?) } + listOf(Triple("tap", 9000f, 350f), Triple("tap", 9000f, 600f)))
                .chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
                        row.forEach { (label, t, poke) ->
                            Column(Modifier.width(80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                LiveLogoFrame(52.dp, LogoMood.Happy, night = false, t = t, entrance = true, sincePoke = poke)
                                Text("$label ${t.toInt()}", style = Heartpact.type.small, color = Heartpact.colors.ink2, modifier = Modifier.padding(top = 6.dp))
                            }
                        }
                    }
                }
        }
    }

    private companion object {
        // Without the drop-in: Happy claps from 3150 and somersaults from 8100; Hello waves from 600 and jumps from
        // 3000; and so on through each routine.
        val MOMENTS = mapOf(
            LogoMood.Happy to listOf(3400f, 8550f), LogoMood.Hello to listOf(1100f, 3400f), LogoMood.Worried to listOf(20f, 2300f),
            LogoMood.Waiting to listOf(1400f, 4400f), LogoMood.Cheer to listOf(1480f, 2600f), LogoMood.Sleepy to listOf(3800f, 4000f),
            LogoMood.Idle to listOf(6300f, 13800f),
        )
    }
}
