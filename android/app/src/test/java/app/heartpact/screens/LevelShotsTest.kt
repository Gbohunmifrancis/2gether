package app.heartpact.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import app.heartpact.ui.graphics.LocalStillLogo
import app.heartpact.ui.play.SkillBests
import app.heartpact.ui.play.SkillKind
import app.heartpact.ui.play.SkillResult
import app.heartpact.ui.play.skills.Difficulty
import app.heartpact.ui.play.skills.KissPlay
import app.heartpact.ui.play.skills.PracticeScreen
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.HeartpactTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Practice levels: the level and its pass mark, a level cleared (card, stars), the heart wipe to the next, a miss. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class LevelShotsTest {
    @get:Rule val rule = createComposeRule()

    private class Bests(var level: Int) : SkillBests {
        var b: Int? = null
        override fun best(kind: SkillKind) = b
        override fun record(result: SkillResult) { b = maxOf(b ?: 0, result.score) }
        override fun level(kind: SkillKind) = level
        override fun reach(kind: SkillKind, level: Int) { this.level = maxOf(this.level, level) }
    }

    private fun show(content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(LocalStillLogo provides true) {
                HeartpactTheme { Box(Modifier.fillMaxSize().background(Heartpact.colors.page)) { content() } }
            }
        }
        rule.mainClock.advanceTimeBy(32)
    }

    private fun later(ms: Long) {
        Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeBy(ms)
    }

    private fun snap(name: String) = rule.onRoot().captureRoboImage("build/screens/skills/levels-$name.png")

    @Test fun clearing() {
        val bests = Bests(level = 5)
        val play = KissPlay(4, Difficulty(5))
        show { PracticeScreen(SkillKind.KissTheCenter, {}, bests, firstSeed = 4, initial = play) }
        snap("1-level-5")
        play.kissAt(play.centre + Offset(0.003f, -0.004f))
        later(4300)
        snap("2-cleared-stars-landing")
        later(1500)
        snap("3-cleared")
        assertEquals(6, bests.level)
        rule.onNodeWithText("On to level 6").performClick()
        later(300)
        snap("4-wipe-closing")
        later(450)
        snap("5-wipe-level-6")
        later(1200)
        snap("6-level-6")
    }

    @Test fun missing() {
        val bests = Bests(level = 3)
        val play = KissPlay(9, Difficulty(3))
        show { PracticeScreen(SkillKind.KissTheCenter, {}, bests, firstSeed = 9, initial = play) }
        play.kissAt(play.centre + Offset(0.3f, 0.2f))
        later(5500)
        snap("7-not-quite")
        assertEquals(3, bests.level)
    }
}
