package app.heartpact.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Canvas
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import app.heartpact.ui.graphics.LocalStillLogo
import app.heartpact.ui.play.BlobColour
import app.heartpact.ui.play.SkillBests
import app.heartpact.ui.play.SkillIcon
import app.heartpact.ui.play.SkillKind
import app.heartpact.ui.play.SkillResult
import app.heartpact.ui.play.blobPath
import app.heartpact.ui.play.skills.FairSharePlay
import app.heartpact.ui.play.skills.FillPlay
import app.heartpact.ui.play.skills.Fit
import app.heartpact.ui.play.skills.HeartbeatPlay
import app.heartpact.ui.play.skills.KissPlay
import app.heartpact.ui.play.skills.OtherHalfPlay
import app.heartpact.ui.play.skills.PracticeScreen
import app.heartpact.ui.play.skills.RingPlay
import app.heartpact.ui.play.skills.SkillPlay
import app.heartpact.ui.play.skills.SkillRoundFrame
import app.heartpact.ui.play.skills.centroid
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.HeartpactTheme
import com.github.takahirom.roborazzi.captureRoboImage
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
 * The Heart skills, played for real on a held clock: each game before a touch, mid-play, and with its score, by day
 * and by night. Gestures go through the same input path as a finger, so the shots also prove the games play.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class SkillShotsTest {
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

    /** Captures [name] by day, then by night, at the same moment. */
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

    /** State set from the test applies, then one frame runs. */
    private fun frame() {
        Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
    }

    private fun later(ms: Long) {
        Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeBy(ms)
    }

    /** Maps a board's units to its own pixels, the way the board fits them. */
    private fun boardFit(description: String, unitW: Float, unitH: Float, padDp: Float, shift: Offset = Offset.Zero): (Offset) -> Offset {
        val node = rule.onNodeWithContentDescription(description, substring = true).fetchSemanticsNode()
        val density = node.layoutInfo.density.density
        val f = Fit.of(Size(node.size.width.toFloat(), node.size.height.toFloat()), unitW, unitH, padDp * density)
        return { u -> f.px(u + shift) }
    }

    @Test fun fairShare() {
        val play = FairSharePlay(11)
        round(play)
        snap("fair-share-1-before")
        val px = boardFit("Swipe across", 1f, 1f, 20f)
        val c = centroid(play.treat.outline)
        val a = px(c + Offset(-0.06f, -0.6f))
        val b = px(c + Offset(0.03f, 0.6f))
        val board = rule.onNodeWithContentDescription("Swipe across", substring = true)
        board.performTouchInput { down(a); moveTo(a + (b - a) * 0.6f) }
        later(48)
        snap("fair-share-2-swipe")
        board.performTouchInput { moveTo(b); up() }
        later(260)
        snap("fair-share-3-split", both = false)
        later(4000)
        assertNotNull(play.result)
        snap("fair-share-4-result")
    }

    @Test fun fairShareTreats() {
        // One of each treat, cut, so all four are seen in pieces.
        val seeds = (0L until 60L).map { FairSharePlay(it) }.distinctBy { it.treat.kind }.map { it.seed }
        val plays = seeds.map { FairSharePlay(it) }
        show {
            Column(Modifier.fillMaxSize()) {
                plays.chunked(2).forEach { row ->
                    Row(Modifier.weight(1f)) { row.forEach { Box(Modifier.weight(1f)) { it.Board(Modifier.fillMaxSize()) } } }
                }
            }
        }
        snap("fair-share-treats", both = false)
        plays.forEach { val c = centroid(it.treat.outline); it.cutAlong(c + Offset(0.05f, -1f), c + Offset(-0.02f, 1f)) }
        later(2000)
        snap("fair-share-treats-cut")
    }

    @Test fun fillTheHeart() {
        val play = FillPlay(5)
        round(play)
        snap("fill-1-before")
        val pill = rule.onNodeWithText("Hold to pour")
        pill.performTouchInput { down(center) }
        later(900)
        snap("fill-2-pouring")
        // Let go a little early, as a good player would; what's falling still lands.
        var t = 0
        while (play.level < play.target - 0.05f && t++ < 200) later(16)
        rule.onNodeWithText("Let go at the line").performTouchInput { up() }
        later(120)
        snap("fill-3-settling", both = false)
        later(5000)
        assertNotNull(play.result)
        snap("fill-4-result")
    }

    @Test fun otherHalf() {
        val play = OtherHalfPlay(3)
        round(play)
        snap("other-half-1-before")
        val board = rule.onNodeWithContentDescription("Draw its other half", substring = true)
        val px = boardFit("Draw its other half", 1.24f, 1.08f, 18f, Offset(0.62f, 0.04f))
        val half = play.shape.half
        // Drawn in two strokes, a little off: wider at the top, wobbling.
        val split = half.size * 3 / 5
        board.performTouchInput {
            down(px(wobbly(half[0], 0)))
            for (i in 1 until split) moveTo(px(wobbly(half[i], i)))
        }
        later(32)
        snap("other-half-2-drawing")
        board.performTouchInput { up() }
        board.performTouchInput {
            down(px(wobbly(half[split], split)))
            for (i in split + 1 until half.size) moveTo(px(wobbly(half[i], i)))
            up()
        }
        later(32)
        rule.onNodeWithText("Submit").performClick()
        later(5000)
        assertNotNull(play.result)
        snap("other-half-3-result")
    }

    private fun wobbly(p: Offset, i: Int) = Offset(p.x * 1.06f + 0.012f * sin(i * 0.35f), p.y + 0.006f * cos(i * 0.5f))

    @Test fun heartbeat() {
        val play = HeartbeatPlay(8)
        round(play)
        snap("heartbeat-1-before")
        rule.onNodeWithText("Start").performClick()
        later(32)
        // Just after beat three lands: the heart at its fullest.
        while (play.now < play.beatTime(3) + 50) later(16)
        snap("heartbeat-2-beating")
        while (play.now < play.beatTime(play.shown + 1) + 100) later(16)
        snap("heartbeat-3-quiet", both = false)
        // Tap 90 ms late.
        while (play.now < play.beatTime(8) + 90) later(16)
        rule.onNodeWithText("Tap on beat 8").performTouchInput { down(center); up() }
        later(5000)
        val r = play.result
        assertNotNull(r)
        // The tap and the beats are timed on the same clock: a 90 ms late tap reads as late, not seconds off.
        assertTrue("${r!!.note} ${r.score}", r.score in 60..95 && r.note.contains("late"))
        snap("heartbeat-4-result")
    }

    @Test fun kissTheCenter() {
        val play = KissPlay(21)
        round(play)
        snap("kiss-1-before")
        val px = boardFit("Tap once where", 1f, 1f, 20f)
        val board = rule.onNodeWithContentDescription("Tap once where", substring = true)
        board.performTouchInput { down(px(play.centre + Offset(-0.05f, 0.04f))); up() }
        later(100)
        snap("kiss-2-landing", both = false)
        later(5000)
        assertNotNull(play.result)
        snap("kiss-3-result")
    }

    @Test fun promiseRing() {
        val play = RingPlay(1)
        round(play)
        snap("ring-1-before")
        val board = rule.onNodeWithContentDescription("Draw a circle", substring = true)
        val node = board.fetchSemanticsNode()
        val c = Offset(node.size.width / 2f, node.size.height / 2f)
        val r = node.size.width * 0.3f
        // A slightly squashed ring that runs a little past its start.
        val pts = List(90) { i ->
            val t = -1.4f + 2f * Math.PI.toFloat() * 1.08f * i / 89
            c + Offset(cos(t) * r * 1.06f, sin(t) * r * 0.95f) + Offset(0f, i * 0.15f)
        }
        board.performTouchInput { down(pts[0]); pts.subList(1, 50).forEach { moveTo(it) } }
        later(32)
        snap("ring-2-drawing")
        board.performTouchInput { pts.subList(50, pts.size).forEach { moveTo(it) }; up() }
        later(5000)
        assertNotNull(play.result)
        snap("ring-3-result")
    }

    @Test fun practice() {
        val bests = object : SkillBests {
            var b: Int? = 61
            override fun best(kind: SkillKind) = b
            override fun record(result: SkillResult) { b = maxOf(b ?: 0, result.score) }
        }
        val play = KissPlay(4)
        show { PracticeScreen(SkillKind.KissTheCenter, {}, bests, firstSeed = 4, initial = play) }
        snap("practice-1-start")
        play.kissAt(play.centre + Offset(0.004f, -0.006f))
        later(1700)
        snap("practice-2-reveal", both = false)
        later(2500)
        snap("practice-3-new-best")
    }

    /** Every kind of half shape, and a spread of lopsided kiss shapes, to check their variety. */
    @Test fun shapes() {
        val halves = (0L until 60L).map { OtherHalfPlay(it) }.distinctBy { it.shape.name }
        val kisses = (0L until 4L).map { KissPlay(it * 5 + 1) }
        show {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.weight(1f)) { halves.take(3).forEach { Box(Modifier.weight(1f)) { it.Board(Modifier.fillMaxSize()) } } }
                Row(Modifier.weight(1f)) { halves.drop(3).forEach { Box(Modifier.weight(1f)) { it.Board(Modifier.fillMaxSize()) } } }
                Row(Modifier.weight(1f)) { kisses.take(2).forEach { Box(Modifier.weight(1f)) { it.Board(Modifier.fillMaxSize()) } } }
                Row(Modifier.weight(1f)) { kisses.drop(2).forEach { Box(Modifier.weight(1f)) { it.Board(Modifier.fillMaxSize()) } } }
            }
        }
        snap("shapes", both = false)
    }

    @Test fun icons() {
        show {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SkillKind.entries.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        row.forEachIndexed { i, kind ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
                                    Canvas(Modifier.size(100.dp)) { drawPath(blobPath(kind.ordinal + i, size), kind.colour.fill) }
                                    SkillIcon(kind, kind.colour.ink, 46.dp)
                                }
                                Text(kind.title, style = Heartpact.type.labelSmall, color = Heartpact.colors.ink2)
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SkillKind.entries.forEach { SkillIcon(it, Heartpact.colors.ink, 24.dp) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SkillKind.entries.forEach { SkillIcon(it, BlobColour.Rose.ink, 52.dp) }
                }
            }
        }
        snap("icons")
    }
}
