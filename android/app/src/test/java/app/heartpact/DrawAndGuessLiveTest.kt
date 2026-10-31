package app.heartpact

import androidx.compose.ui.geometry.Offset
import app.heartpact.data.DrawStroke
import app.heartpact.ui.games.LiveSketch
import app.heartpact.ui.games.SketchLive
import app.heartpact.ui.games.TicTacToeInk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Draw and guess, live: the drawer's stroke shows on the guesser's canvas as it's drawn, then becomes the drawing's own (docs/games.md). */
class DrawAndGuessLiveTest {
    private fun line(vararg xy: Float) = xy.toList()
    private fun stroke(vararg xy: Double) = DrawStroke(xy.toList().chunked(2), 0.012)

    /** A sketch watching Maya draw in game "g", stamped by hand. */
    private fun watching(): LiveSketch = LiveSketch(clock = { 0L })

    private fun LiveSketch.live(committed: List<DrawStroke>, nowMs: Long, smooth: Boolean = false) = SketchLive.live(ink, "g", "maya", committed, nowMs, smooth)
    private fun LiveSketch.drawing(committed: List<DrawStroke>, nowMs: Long) = SketchLive.drawing(ink, "g", "maya", committed, nowMs)

    @Test fun the_drawer_sends_only_the_points_new_since_the_last_message() {
        val cursor = SketchLive.Cursor()
        val finger = mutableListOf(Offset(0.1f, 0.1f))
        assertEquals(listOf(Offset(0.1f, 0.1f)), cursor.next(finger))
        assertNull(cursor.next(finger))

        finger += listOf(Offset(0.2f, 0.2f), Offset(0.3f, 0.3f))
        // The last point sent comes first again, so the partner's line joins up.
        assertEquals(listOf(Offset(0.1f, 0.1f), Offset(0.2f, 0.2f), Offset(0.3f, 0.3f)), cursor.next(finger))
        finger += Offset(0.4f, 0.35f)
        assertEquals(listOf(Offset(0.3f, 0.3f), Offset(0.4f, 0.35f)), cursor.next(finger))

        // A burst of more than fits in one message is simplified to fit, its ends kept, never cut short.
        val burst = (1..120).map { Offset(0.4f + it * 0.004f, 0.35f + kotlin.math.sin(it / 6f) * 0.1f) }
        finger += burst
        val sent = cursor.next(finger)!!
        assertTrue(sent.size <= SketchLive.INK_POINTS)
        assertEquals(Offset(0.4f, 0.35f), sent.first())
        assertEquals(burst.last(), sent.last())
        assertTrue(TicTacToeInk.inkNumbers(sent).size <= 64)
    }

    @Test fun a_stroke_builds_up_message_by_message_and_shows_live() {
        val sketch = watching()
        sketch.receive("g", "maya", 3, 41, line(0.1f, 0.1f, 0.2f, 0.2f), 0)
        sketch.receive("g", "maya", 3, 41, line(0.2f, 0.2f, 0.3f, 0.25f), 50)
        sketch.receive("g", "maya", 4, 42, line(0.6f, 0.6f), 60)
        val lines = sketch.ink.lines
        assertEquals(listOf(41, 42), lines.map { it.id })
        // The repeated joining point is kept once.
        assertEquals(listOf(Offset(0.1f, 0.1f), Offset(0.2f, 0.2f), Offset(0.3f, 0.25f)), lines[0].points)

        val committed = List(3) { stroke(0.5, 0.5, 0.55, 0.55) }
        val shown = sketch.live(committed, 100)
        assertEquals(listOf(41, 42), shown.map { it.id })
        assertTrue(shown.all { it.alpha == 1f })
        assertEquals(committed to emptySet<Int>(), sketch.drawing(committed, 100))

        // Another drawer's, or another game's, ink isn't this drawing's.
        assertTrue(SketchLive.live(sketch.ink, "g", "theo", committed, 100, false).isEmpty())
        assertTrue(SketchLive.live(sketch.ink, "other", "maya", committed, 100, false).isEmpty())
        sketch.receive("g", "theo", 0, 1, line(0.5f, 0.5f), 120)
        assertTrue(sketch.live(committed, 130).isEmpty())
    }

    @Test fun the_drawn_stroke_takes_the_live_ones_place_without_drawing_twice() {
        val sketch = watching()
        val before = listOf(stroke(0.5, 0.5, 0.55, 0.55), stroke(0.7, 0.1, 0.7, 0.3))
        sketch.receive("g", "maya", 2, 7, line(0.1f, 0.1f, 0.2f, 0.2f, 0.3f, 0.25f), 0)
        sketch.receive("g", "maya", 3, 8, line(0.8f, 0.8f, 0.85f, 0.9f), 10)
        assertEquals(2, sketch.live(before, 20).size)

        // Stroke 7's move lands: the drawing has it (simplified, rounded), and the live copy goes in the same frame.
        val after = before + stroke(0.1, 0.1, 0.3, 0.25)
        val (drawn, wasLive) = sketch.drawing(after, 30)
        assertEquals(after, drawn)
        assertEquals(setOf(2), wasLive)
        assertEquals(listOf(8), sketch.live(after, 30).map { it.id })
        // Three strokes in the drawing and one live: four on screen, as drawn.
        assertEquals(4, drawn.size + sketch.live(after, 30).size)

        // Pruned, it's gone for good: points for it that come late don't draw it again.
        sketch.prune(after, 40)
        assertEquals(listOf(8), sketch.ink.lines.map { it.id })
        sketch.receive("g", "maya", 2, 7, line(0.3f, 0.25f, 0.35f, 0.3f), 45)
        assertEquals(listOf(8), sketch.live(after, 50).map { it.id })

        // An earlier stroke that starts at the same spot isn't taken for it: only places from the live stroke's on count.
        val twin = watching()
        twin.receive("g", "maya", 2, 9, line(0.5f, 0.5f, 0.52f, 0.6f), 0)
        assertEquals(emptySet<Int>(), twin.drawing(before, 10).second)
        assertEquals(listOf(9), twin.live(before, 10).map { it.id })
    }

    @Test fun a_live_stroke_that_never_lands_fades_then_goes() {
        val sketch = watching()
        val committed = emptyList<DrawStroke>()
        sketch.receive("g", "maya", 0, 1, line(0.1f, 0.1f, 0.4f, 0.4f), 1_000)
        assertEquals(1f, sketch.live(committed, 1_000 + SketchLive.HOLD_MS, smooth = true).single().alpha)
        val fading = sketch.live(committed, 1_000 + SketchLive.HOLD_MS + SketchLive.FADE_MS / 2, smooth = true).single().alpha
        assertTrue(fading > 0f && fading < 1f)
        // With animations off it doesn't fade: it's there, then it isn't.
        assertEquals(1f, sketch.live(committed, 1_000 + SketchLive.HOLD_MS + SketchLive.FADE_MS / 2).single().alpha)
        assertTrue(sketch.live(committed, 1_000 + SketchLive.HOLD_MS + SketchLive.FADE_MS).isEmpty())

        sketch.prune(committed, 1_000 + SketchLive.HOLD_MS + SketchLive.FADE_MS - 1)
        assertEquals(1, sketch.ink.lines.size)
        sketch.prune(committed, 1_000 + SketchLive.HOLD_MS + SketchLive.FADE_MS)
        assertTrue(sketch.ink.lines.isEmpty())
    }

    @Test fun live_points_draw_on_smoothly_between_messages() {
        val sketch = watching()
        sketch.receive("g", "maya", 0, 1, line(0f, 0.5f, 0.2f, 0.5f), 0)
        val line = sketch.ink.lines.single()
        assertEquals(0f, SketchLive.revealedLength(line, 0), 1e-6f)
        assertEquals(0.1f, SketchLive.revealed(line, SketchLive.SMOOTH_MS / 2).last().x, 1e-4f)
        assertEquals(listOf(Offset(0f, 0.5f), Offset(0.2f, 0.5f)), SketchLive.revealed(line, SketchLive.SMOOTH_MS))

        // The next message carries on from where the pen had got to, never jumping back or ahead.
        sketch.receive("g", "maya", 0, 1, line(0.2f, 0.5f, 0.4f, 0.5f), SketchLive.SMOOTH_MS / 2)
        val next = sketch.ink.lines.single()
        assertEquals(0.1f, SketchLive.revealedLength(next, SketchLive.SMOOTH_MS / 2), 1e-4f)
        assertEquals(0.4f, SketchLive.revealedLength(next, SketchLive.SMOOTH_MS * 2), 1e-4f)
        // Smoothing off (animations off): every point at once.
        assertEquals(3, sketch.live(emptyList(), SketchLive.SMOOTH_MS / 2).single().points.size)
    }

    @Test fun an_undo_or_a_clear_shows_at_once_and_holds_until_the_drawing_catches_up() {
        val sketch = watching()
        val three = listOf(stroke(0.1, 0.1, 0.2, 0.2), stroke(0.3, 0.3, 0.4, 0.4), stroke(0.5, 0.5, 0.6, 0.6))
        sketch.receive("g", "maya", 3, 5, line(0.7f, 0.7f, 0.8f, 0.8f), 0)

        // Maya undoes the stroke at place 3 (still on its way) and then the one at 2: both go at once.
        sketch.receive("g", "maya", -1, 3, emptyList(), 10)
        assertTrue(sketch.live(three, 10).isEmpty())
        sketch.receive("g", "maya", -1, 2, emptyList(), 20)
        assertEquals(three.take(2), sketch.drawing(three, 20).first)

        // Stroke 5 lands before the undos do: still hidden.
        val four = three + stroke(0.7, 0.7, 0.8, 0.8)
        assertEquals(three.take(2), sketch.drawing(four, 30).first)

        // She draws on; once that stroke lands the undos have too, and the drawing shows as the server has it.
        sketch.receive("g", "maya", 2, 6, line(0.9f, 0.1f, 0.9f, 0.3f), 40)
        assertEquals(listOf(6), sketch.live(three.take(2), 40).map { it.id })
        val caughtUp = three.take(2) + stroke(0.9, 0.1, 0.9, 0.3)
        assertEquals(caughtUp to setOf(2), sketch.drawing(caughtUp, 50))
        sketch.prune(caughtUp, 50)
        assertNull(sketch.ink.cut)

        // A clear empties the canvas at once; an undo whose move never comes stops hiding after a while.
        sketch.receive("g", "maya", -1, 0, emptyList(), 100)
        assertTrue(sketch.drawing(caughtUp, 100).first.isEmpty())
        assertEquals(caughtUp, sketch.drawing(caughtUp, 100 + SketchLive.CUT_MS).first)
        sketch.prune(caughtUp, 100 + SketchLive.CUT_MS)
        assertNull(sketch.ink.cut)
        assertFalse(sketch.ink.lines.any())
    }
}
