package app.heartpact.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.heartpact.ui.graphics.LocalStillLogo
import app.heartpact.ui.play.skills.Difficulty
import app.heartpact.ui.play.skills.FairSharePlay
import app.heartpact.ui.play.skills.Fit
import app.heartpact.ui.play.skills.HalfShape
import app.heartpact.ui.play.skills.KissPlay
import app.heartpact.ui.play.skills.OtherHalfPlay
import app.heartpact.ui.play.skills.SkillPlay
import app.heartpact.ui.play.skills.SkillRoundFrame
import app.heartpact.ui.play.skills.TreatKind
import app.heartpact.ui.play.skills.centroid
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.HeartpactTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The skill games up the levels: every treat and half shape there is (to check the art), and the twists of the high
 * levels played for real on a held clock: a kiss shape turning and fading, an Other half fading to its ghost, a
 * lopsided treat cut at the top level.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class SkillShapeShotsTest {
    @get:Rule val rule = createComposeRule()

    private var night by mutableStateOf(false)

    private fun show(content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(LocalStillLogo provides true) {
                HeartpactTheme(night = night) {
                    Box(Modifier.fillMaxSize().background(Heartpact.colors.page)) { content() }
                }
            }
        }
        rule.mainClock.advanceTimeBy(32)
    }

    private fun round(play: SkillPlay) = show { SkillRoundFrame(play, onDone = {}, onAgain = {}) }

    private fun snap(name: String, both: Boolean = false) {
        night = false
        frame()
        rule.onRoot().captureRoboImage("build/screens/skills/levels/$name.png")
        if (!both) return
        night = true
        frame()
        rule.onRoot().captureRoboImage("build/screens/skills/levels/$name-night.png")
        night = false
        frame()
    }

    private fun frame() {
        Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
    }

    private fun later(ms: Long) {
        Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeBy(ms)
    }

    /** Boards laid out [across] to a row. */
    @Composable
    private fun Grid(plays: List<SkillPlay>, across: Int) {
        Column(Modifier.fillMaxSize()) {
            plays.chunked(across).forEach { row ->
                Row(Modifier.weight(1f)) {
                    row.forEach { Box(Modifier.weight(1f)) { it.Board(Modifier.fillMaxSize()) } }
                    repeat(across - row.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
    }

    /** One Fair share round for each kind of treat at [level], first seed that gives it. */
    private fun everyTreat(level: Int): List<FairSharePlay> = TreatKind.entries.mapNotNull { kind ->
        (0L until 600L).asSequence().map { FairSharePlay(it, Difficulty(level)) }.firstOrNull { it.treat.kind == kind }
    }

    @Test fun everyTreatShared() {
        val plays = everyTreat(12)
        assertEquals(TreatKind.entries.size, plays.size)
        show { Grid(plays.take(9), 3) }
        snap("treats-1")
    }

    @Test fun everyTreatSharedSecondHalf() {
        val plays = everyTreat(12).drop(9)
        show { Grid(plays, 3) }
        snap("treats-2")
    }

    @Test fun everyTreatCut() {
        val plays = everyTreat(12)
        show { Grid(plays.take(9), 3) }
        plays.take(9).forEach { val c = centroid(it.treat.outline); it.cutAlong(c + Offset(0.05f, -1f), c + Offset(-0.02f, 1f)) }
        later(2500)
        snap("treats-cut-1")
    }

    @Test fun everyTreatCutSecondHalf() {
        val plays = everyTreat(12).drop(9)
        show { Grid(plays, 3) }
        plays.forEach { val c = centroid(it.treat.outline); it.cutAlong(c + Offset(-1f, 0.03f), c + Offset(1f, -0.04f)) }
        later(2500)
        snap("treats-cut-2")
    }

    @Test fun kissShapesAtTheTop() {
        // Odd shapes at level 12 (before they move), one of each kind.
        val plays = TreatKind.entries.mapNotNull { kind ->
            (0L until 900L).asSequence().map { KissPlay(it, Difficulty(12)) }.firstOrNull { it.treat.kind == kind }
        }
        show { Grid(plays.take(12), 3) }
        snap("kiss-shapes")
    }

    @Test fun everyHalf() {
        val names = HalfShape.all.map { it.first }
        val plays = names.mapNotNull { name -> (0L until 900L).asSequence().map { OtherHalfPlay(it, Difficulty(12)) }.firstOrNull { it.shape.name == name } }
        assertEquals(names.size, plays.size)
        show { Grid(plays.drop(5).take(16), 4) }
        snap("halves")
    }

    @Test fun fairShareAtTheTop() {
        val play = FairSharePlay(4, Difficulty(20))
        round(play)
        snap("fair-share-20-before")
        val c = centroid(play.treat.outline)
        play.cutAlong(c + Offset(0.04f, -1f), c + Offset(-0.03f, 1f))
        later(5000)
        assertNotNull(play.result)
        snap("fair-share-20-result")
    }

    @Test fun kissTurnsAndFades() {
        val play = KissPlay(9, Difficulty(16))
        round(play)
        snap("kiss-16-start")
        later(900)
        assertTrue("turned", play.motion.angle(play.clock) != 0f)
        snap("kiss-16-turning", both = true)
        later(1400)
        assertEquals("Remember where it was.", play.hint)
        snap("kiss-16-faded")
        val node = rule.onNodeWithContentDescription("Tap once where", substring = true).fetchSemanticsNode()
        val f = Fit.of(Size(node.size.width.toFloat(), node.size.height.toFloat()), 1f, 1f, 20f * node.layoutInfo.density.density)
        val target = play.centreAt(play.clock)
        rule.onNodeWithContentDescription("Tap once where", substring = true).performTouchInput { down(f.px(target + Offset(0.03f, -0.02f))); up() }
        later(5000)
        assertNotNull(play.result)
        snap("kiss-16-result")
    }

    @Test fun kissTurning() {
        val play = KissPlay(3, Difficulty(9))
        round(play)
        later(1500)
        assertEquals("It turns while you aim.", play.hint)
        snap("kiss-9-turning")
    }

    @Test fun otherHalfGhost() {
        val play = OtherHalfPlay(6, Difficulty(14))
        round(play)
        snap("other-half-14-start")
        later(play.showMs + 1200)
        assertTrue(play.faded)
        snap("other-half-14-ghost", both = true)
        val node = rule.onNodeWithContentDescription("Draw its other half", substring = true).fetchSemanticsNode()
        val d = node.layoutInfo.density.density
        val frame = Fit.of(Size(node.size.width.toFloat(), node.size.height.toFloat()), 1.24f, 1.08f, 18f * d)
        val fit = Fit(frame.scale, frame.origin + Offset(0.62f, 0.04f) * frame.scale)
        val half = play.shape.half
        rule.onNodeWithContentDescription("Draw its other half", substring = true).performTouchInput {
            down(fit.px(half[0]))
            for (i in 1 until half.size) moveTo(fit.px(half[i] + Offset(0.015f * kotlin.math.sin(i * 0.4f), 0f)))
            up()
        }
        later(32)
        rule.onNodeWithText("Submit").performClick()
        later(5000)
        assertNotNull(play.result)
        snap("other-half-14-result")
    }
}
