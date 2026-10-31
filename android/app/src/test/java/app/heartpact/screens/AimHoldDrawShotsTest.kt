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
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import app.heartpact.ui.graphics.LocalStillLogo
import app.heartpact.ui.play.skills.CupidPlay
import app.heartpact.ui.play.skills.Difficulty
import app.heartpact.ui.play.skills.DrawHeartPlay
import app.heartpact.ui.play.skills.Fit
import app.heartpact.ui.play.skills.HEART_FRAME
import app.heartpact.ui.play.skills.SkillPlay
import app.heartpact.ui.play.skills.SkillRoundFrame
import app.heartpact.ui.play.skills.SlowKissPlay
import app.heartpact.ui.play.skills.cupidShot
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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Cupid's aim, Slow kiss and Draw a heart, played for real on a held clock: before a touch, mid-play and scored, by
 * day and by night, and a few of their harder levels. Touches go through the same input path as a finger.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class AimHoldDrawShotsTest {
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

    private fun snap(name: String, both: Boolean = true) {
        night = false
        frame()
        rule.onRoot().captureRoboImage("build/screens/skills/$name.png")
        if (!both) return
        night = true
        frame()
        rule.onRoot().captureRoboImage("build/screens/skills/$name-night.png")
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

    @Test fun cupidsAim() {
        val play = CupidPlay(4)
        round(play)
        snap("cupid-1-before")
        later(900)
        snap("cupid-2-flying", both = false)
        // Loose a touch early, the way a good shot is: the best moment from here, less 40 ms.
        val from = play.now
        val best = (0 until 6000).map { from + 0.2f + it * 0.0005f }.minBy { abs(cupidShot(play.flight, it).dx) }
        while (play.now < best - 0.04f) later(16)
        val board = rule.onNodeWithContentDescription("A heart floats", substring = true)
        val at = play.now
        board.performTouchInput { down(center); up() }
        later(16)
        val shot = play.shot
        assertNotNull(shot)
        // The tap and the flight run on the same clock.
        assertEquals(at, shot!!.loosed, 0.04f)
        while (play.now < shot.loosed + 0.22f) later(16)
        snap("cupid-3-arrow")
        while (play.now < play.stuckAt(shot) + 0.06f) later(16)
        snap("cupid-4-thunk", both = false)
        later(4000)
        val r = play.result
        assertNotNull(r)
        assertTrue("${r!!.score} ${r.note}", shot.hit && r.score in 60..99)
        snap("cupid-5-result")
    }

    @Test fun cupidsAimMissed() {
        val play = CupidPlay(9)
        round(play)
        later(500)
        // Loose with the heart far from the line: the arrow sails past.
        val from = play.now
        val wide = (0 until 400).map { from + it * 0.01f }.maxBy { abs(cupidShot(play.flight, it).dx) }
        while (play.now < wide) later(16)
        rule.onNodeWithContentDescription("A heart floats", substring = true).performTouchInput { down(center); up() }
        later(5000)
        val r = play.result
        assertNotNull(r)
        assertTrue("${r!!.score} ${r.note}", r.score < 20 && r.note.startsWith("Missed"))
        snap("cupid-6-missed", both = false)
    }

    @Test fun slowKiss() {
        val play = SlowKissPlay(2)
        round(play)
        snap("kiss-hold-1-before")
        val lips = rule.onNodeWithContentDescription("Lips to kiss", substring = true)
        lips.performTouchInput { down(center) }
        later(700)
        snap("kiss-hold-2-holding")
        while (play.since < play.timerFor + 0.6f) later(16)
        snap("kiss-hold-3-counting", both = false)
        // Let go 120 ms late.
        while (play.since < play.target + 0.12f) later(16)
        lips.performTouchInput { up() }
        later(160)
        snap("kiss-hold-4-let-go", both = false)
        later(4000)
        val r = play.result
        assertNotNull(r)
        assertTrue("${r!!.score} ${r.note}", r.score in 70..97 && r.note.contains("too long"))
        snap("kiss-hold-5-result")
    }

    @Test fun slowKissPeck() {
        val play = SlowKissPlay(2)
        round(play)
        val lips = rule.onNodeWithContentDescription("Lips to kiss", substring = true)
        lips.performTouchInput { down(center) }
        later(100)
        lips.performTouchInput { up() }
        later(300)
        // A peck doesn't count: the round is still waiting.
        assertEquals(SlowKissPlay.Phase.Ready, play.phase)
        assertEquals(null, play.scored)
        snap("kiss-hold-6-peck", both = false)
    }

    /** A hand-drawn heart: the app's heart, a little wobbly, the right lobe a touch big, started at the dip. */
    private fun handHeart(centre: Offset, height: Float): List<Offset> {
        val h = HEART_FRAME
        val start = h.indices.minBy { (h[it] - Offset(0f, -0.31f)).getDistance() }
        val loop = h.drop(start) + h.take(start + 1)
        return loop.mapIndexed { i, p ->
            val q = if (p.x > 0f && p.y < 0.1f) Offset(p.x * 1.07f, p.y * 1.05f) else p
            centre + (q + Offset(0.008f * sin(i * 0.13f), 0.007f * cos(i * 0.09f))) * height
        }
    }

    private fun drawOn(description: String, points: List<Offset>, liftAt: Int = points.size) {
        val node = rule.onNodeWithContentDescription(description, substring = true).fetchSemanticsNode()
        val density = node.layoutInfo.density.density
        val fit = Fit.of(Size(node.size.width.toFloat(), node.size.height.toFloat()), 1f, 1.2f, 16f * density)
        val board = rule.onNodeWithContentDescription(description, substring = true)
        board.performTouchInput {
            down(fit.px(points[0]))
            for (i in 1 until liftAt) moveTo(fit.px(points[i]))
        }
        if (liftAt < points.size) return
        board.performTouchInput { up() }
    }

    @Test fun drawAHeart() {
        val play = DrawHeartPlay(3)
        round(play)
        snap("heart-1-before")
        val pts = handHeart(Offset(0.5f, 0.6f), 0.62f)
        drawOn("Draw a heart", pts, liftAt = pts.size * 2 / 3)
        later(32)
        snap("heart-2-drawing")
        rule.onNodeWithContentDescription("Draw a heart", substring = true).performTouchInput {
            val node = rule.onNodeWithContentDescription("Draw a heart", substring = true).fetchSemanticsNode()
            val fit = Fit.of(Size(node.size.width.toFloat(), node.size.height.toFloat()), 1f, 1.2f, 16f * node.layoutInfo.density.density)
            for (i in pts.size * 2 / 3 until pts.size) moveTo(fit.px(pts[i]))
            up()
        }
        later(5000)
        val r = play.result
        assertNotNull(r)
        assertTrue("${r!!.score} ${r.note}", r.score in 60..95)
        snap("heart-3-result")
    }

    @Test fun drawAHeartGuided() {
        val play = DrawHeartPlay(5, Difficulty(6))
        val (c, h) = play.guide!!
        round(play)
        snap("heart-4-guide", both = false)
        // A bit big and low for the guide, and started and ended either side of the point, a little open.
        val hand = handHeart(c + Offset(0.02f, 0.05f), h * 1.12f)
        val tip = hand.indices.maxBy { hand[it].y }
        drawOn("Draw a heart", hand.drop(tip + 6) + hand.take(tip - 6))
        later(5000)
        val r = play.result
        assertNotNull(r)
        assertTrue("${r!!.score} ${r.note}", r.score < 80)
        snap("heart-5-guided-result", both = false)
    }

    /** The harder levels: the decoy and a small fast heart, the timer that never shows, the guide. */
    @Test fun levels() {
        val cupid = CupidPlay(6, Difficulty(16))
        val kiss = SlowKissPlay(6, Difficulty(14))
        val heart = DrawHeartPlay(6, Difficulty(9))
        show {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.weight(1f)) {
                    Box(Modifier.weight(1f)) { cupid.Board(Modifier.fillMaxSize()) }
                    Box(Modifier.weight(1f)) { kiss.Board(Modifier.fillMaxSize()) }
                }
                Row(Modifier.weight(1f)) { Box(Modifier.weight(1f)) { heart.Board(Modifier.fillMaxSize()) } }
            }
        }
        later(1500)
        assertEquals(0f, kiss.timerFor)
        assertTrue(cupid.flight.decoy)
        snap("aim-hold-draw-levels")
    }
}
