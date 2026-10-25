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
import app.heartpact.ui.graphics.LiveLogo
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

    /** Each mood at rest, then at a telling moment (mid-hop, the wink, a glance, a shiver), by day and by night. */
    @Test
    fun liveLogoMoods() {
        listOf(false, true).forEach { night ->
            shot(if (night) "live-logo-night" else "live-logo", night = night) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LogoMood.entries.forEach { mood ->
                        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.Bottom) {
                            Text(mood.name, style = Heartpact.type.body, color = Heartpact.colors.ink, modifier = Modifier.padding(bottom = 8.dp).width(72.dp))
                            LiveLogo(64.dp, mood, night, frozenAtMs = 700f)
                            LiveLogo(64.dp, mood, night, frozenAtMs = MOMENT.getValue(mood))
                        }
                    }
                }
            }
        }
    }

    private companion object {
        val MOMENT = mapOf(
            LogoMood.Happy to 350f, LogoMood.Hello to 2800f, LogoMood.Worried to 20f, LogoMood.Waiting to 850f,
            LogoMood.Cheer to 350f, LogoMood.Sleepy to 1500f, LogoMood.Idle to 2900f,
        )
    }
}
