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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.heartpact.ui.graphics.LocalStillLogo
import app.heartpact.ui.play.skills.Difficulty
import app.heartpact.ui.play.skills.DrawShape
import app.heartpact.ui.play.skills.FillPlay
import app.heartpact.ui.play.skills.HeartbeatPlay
import app.heartpact.ui.play.skills.JarKind
import app.heartpact.ui.play.skills.RingPlay
import app.heartpact.ui.play.skills.SkillPlay
import app.heartpact.ui.play.skills.SkillRoundFrame
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
import kotlin.math.cos
import kotlin.math.sin

/**
 * Fill the heart, Heartbeat and Promise ring up the levels: every jar and every shape to draw, and rounds high on the
 * ladder with their twists named in the hint line.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class SkillLevelShotsTest {
    @get:Rule val rule = createComposeRule()

    private var night by mutableStateOf(false)
    private val top = Difficulty(Difficulty.RAMP)

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

    /** A grid of boards, [columns] across. */
    private fun grid(plays: List<SkillPlay>, columns: Int) = show {
        Column(Modifier.fillMaxSize()) {
            plays.chunked(columns).forEach { row ->
                Row(Modifier.weight(1f)) {
                    row.forEach { Box(Modifier.weight(1f)) { it.Board(Modifier.fillMaxSize()) } }
                    repeat(columns - row.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
    }

    @Test fun jars() {
        // One round per jar, high on the ladder, each poured to just under its line.
        val plays = (0L until 3000L).asSequence().map { FillPlay(it, top) }.distinctBy { it.jarKind }.take(JarKind.entries.size).toList().sortedBy { it.jarKind.ordinal }
        assertEquals(JarKind.entries.size, plays.size)
        for (p in plays) {
            p.showLine()
            p.hideLine()
            p.press()
            var t = 0
            while (p.level < p.target - 0.04f && t++ < 400) p.step(1 / 60f)
            p.release()
            t = 0
            while (p.scored == null && t++ < 600) p.step(1 / 60f)
        }
        grid(plays, 4)
        later(800)
        snap("fill-jars")
    }

    @Test fun fillHighLevel() {
        val play = (0L until 200L).map { FillPlay(it, top) }.first { it.jarKind == JarKind.Hourglass }
        round(play)
        snap("fill-level20-1-before")
        rule.onNodeWithText("Show the line").performTouchInput { down(center); up() }
        later(300)
        snap("fill-level20-2-peek", both = false)
        later(play.peekMs + 800)
        assertTrue(play.canPour)
        snap("fill-level20-3-pour-now", both = false)
        rule.onNodeWithText("Hold to pour").performTouchInput { down(center) }
        var t = 0
        while (play.level < play.target - 0.06f && t++ < 300) later(16)
        rule.onNodeWithText("Let go at the line").performTouchInput { up() }
        later(5000)
        assertNotNull(play.result)
        snap("fill-level20-4-result")
    }

    @Test fun heartbeatHighLevel() {
        val seed = (0L until 300L).first { val p = HeartbeatPlay(it, top); p.plan.drift != 0f && p.target == 11 }
        val play = HeartbeatPlay(seed, top)
        round(play)
        snap("heartbeat-level20-1-before")
        rule.onNodeWithText("Start").performClick()
        later(32)
        while (play.now < play.beatTime(play.shown + 1) + 100) later(16)
        snap("heartbeat-level20-2-quiet", both = false)
        while (play.now < play.beatTime(play.target) - 70) later(16)
        rule.onNodeWithText("Tap on beat 11").performTouchInput { down(center); up() }
        later(5000)
        val r = play.result
        assertNotNull(r)
        assertTrue("${r!!.note} ${r.score}", r.note.contains("early"))
        snap("heartbeat-level20-3-result")
    }

    @Test fun shapes() {
        // Every shape to draw, as its guide shows before the first touch.
        val plays = DrawShape.entries.map { shape -> (0L until 500L).asSequence().map { RingPlay(it, Difficulty(12)) }.first { it.shape == shape } }
        grid(plays, 2)
        snap("ring-shapes")
    }

    @Test fun drawAStar() {
        val play = (0L until 200L).map { RingPlay(it, Difficulty(DrawShape.Star.unlock)) }.first { it.shape == DrawShape.Star }
        round(play)
        snap("ring-star-1-before")
        val board = rule.onNodeWithContentDescription("faint guide", substring = true)
        val density = board.fetchSemanticsNode().layoutInfo.density.density
        // Over the guide, a little loose: wider, turned a touch, with a wobble, and past its start.
        val guide = play.guide()
        val c = guide.fold(Offset.Zero) { s, p -> s + p } / guide.size.toFloat()
        val pts = (guide + guide.take(3)).flatMapIndexed { i, p ->
            val q = if (i + 1 < guide.size + 3) (guide + guide.take(3))[i + 1] else p
            List(6) { j -> p + (q - p) * (j / 6f) }
        }.mapIndexed { i, p ->
            val d = p - c
            val a = 0.06f
            c + Offset((d.x * cos(a) - d.y * sin(a)) * 1.08f, d.x * sin(a) + d.y * cos(a)) + Offset(1.5f * sin(i * 0.4f), 1.5f * cos(i * 0.33f))
        }.map { it * density }
        board.performTouchInput { down(pts[0]); pts.subList(1, pts.size / 2).forEach { moveTo(it) } }
        later(32)
        snap("ring-star-2-drawing", both = false)
        board.performTouchInput { pts.subList(pts.size / 2, pts.size).forEach { moveTo(it) }; up() }
        later(5000)
        val r = play.result
        assertNotNull(r)
        assertTrue("${r!!.note} ${r.score}", r.score in 40..97)
        snap("ring-star-3-result")
    }

    @Test fun ringHighLevel() {
        val play = (0L until 300L).map { RingPlay(it, top) }.first { it.shape == DrawShape.Infinity }
        round(play)
        snap("ring-level20-before")
    }
}
