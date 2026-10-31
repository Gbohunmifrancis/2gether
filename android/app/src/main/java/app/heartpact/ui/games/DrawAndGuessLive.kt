package app.heartpact.ui.games

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import app.heartpact.data.DrawStroke
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.hypot

/*
 * Draw and guess, live (docs/games.md): while the drawer's finger moves, the stroke's new points go to the couple hub
 * every 50 ms (`Ink`: the place the stroke will take in the drawing, the drawer's stroke number, the points as
 * fractions of the canvas), and the guesser's canvas draws them on smoothly. When the stroke's move lands, the
 * drawing's own stroke takes the live one's place in the same frame, so nothing flickers or draws twice. An undo or a
 * clear goes the same way (-1, from a place on), so the guesser sees it at once. A live stroke whose move never lands
 * fades after a few seconds. Without a hub connection nothing is sent and the drawing arrives a stroke at a time.
 */

/** One of the drawer's strokes as it's drawn: [place] is the place it will take in the drawing, [id] the drawer's stroke number. */
data class LiveLine(
    val id: Int,
    val place: Int,
    val points: List<Offset>,
    /** How far along the stroke the smooth draw-on was when the newest points came (a length in canvas fractions), and when. */
    val fromLength: Float,
    val atMs: Long,
    /** Begun after an undo or a clear that the drawing hasn't caught up with yet. */
    val afterCut: Boolean = false,
)

/** An undo or a clear seen live: the drawing from [place] on is gone, though the state on screen may still show it. */
data class SketchCut(val place: Int, val atMs: Long)

/** The drawer's live ink as the guesser has it: one drawer's, in one game. */
data class SketchInk(
    val sessionId: String? = null,
    val drawer: String? = null,
    val lines: List<LiveLine> = emptyList(),
    val cut: SketchCut? = null,
    /** Strokes already in the drawing, by stroke number, and when they landed: late points for them are dropped. */
    val landed: Map<Int, Long> = emptyMap(),
)

/** A live stroke as it's drawn now: the points drawn on so far, and how faded it is. */
data class LiveShown(val id: Int, val points: List<Offset>, val alpha: Float)

/**
 * The guesser's side of the live drawing. Snapshot state, so the canvas redraws as ink arrives (twenty times a second
 * while a finger moves), not the whole screen. [clock] is the wall clock the arrivals are stamped with.
 */
class LiveSketch(val clock: () -> Long = System::currentTimeMillis) {
    var ink by mutableStateOf(SketchInk())
        private set

    /** One `GameInk` message from [userId], the drawer. */
    fun receive(sessionId: String, userId: String, cell: Int, stroke: Int, points: List<Float>, nowMs: Long = clock()) {
        ink = SketchLive.receive(ink, sessionId, userId, cell, stroke, points, nowMs)
    }

    /** Lets go of strokes that have landed in [committed] or faded, and of an undo the drawing has caught up with. */
    fun prune(committed: List<DrawStroke>, nowMs: Long = clock()) {
        val next = SketchLive.prune(ink, committed, nowMs)
        if (next != ink) ink = next
    }
}

/** Pure: what the drawer sends and what the guesser's canvas makes of it. */
object SketchLive {
    /** A message every 50 ms while the finger moves, with at most 32 points (the hub's limits, as Tic-tac-toe). */
    const val INK_EVERY_MS = TicTacToeInk.INK_EVERY_MS
    const val INK_POINTS = TicTacToeInk.INK_POINTS
    /** Each message's points draw on over a little more than the gap to the next, so the pen never stops between them. */
    const val SMOOTH_MS = 80L
    /** A live stroke whose move never lands stays this long after its last points, then fades out over [FADE_MS]. */
    const val HOLD_MS = 2500L
    const val FADE_MS = 500L
    /** An undo or a clear seen live hides what it took away for at most this long while its move is on the way. */
    const val CUT_MS = 5000L
    /** How long a landed stroke's number is remembered, so its late points don't draw it again. */
    const val LANDED_MS = 5000L
    /** The most live strokes kept: a few strokes in flight at once, never a whole drawing. */
    const val MAX_LINES = 12
    /** How near a drawn stroke's first point must be to a live one's to be the same stroke (both rounded to 1/1000). */
    private const val SAME = 0.003f

    private val strokes = AtomicInteger((System.currentTimeMillis() / 1000 % 1_000_000).toInt())

    /** The next stroke number: counted up for the whole app, so strokes from before a restart are never taken for new ones. */
    fun nextStroke(): Int = strokes.updateAndGet { if (it >= Int.MAX_VALUE - 1) 1 else it + 1 }

    fun receive(ink: SketchInk, sessionId: String, userId: String, cell: Int, stroke: Int, points: List<Float>, nowMs: Long): SketchInk {
        val base = if (ink.sessionId == sessionId && ink.drawer == userId) ink else SketchInk(sessionId, userId)
        if (cell < 0) {
            // An undo (from the undone stroke's place) or a clear (from 0): gone at once, with the live strokes there.
            val place = stroke.coerceAtLeast(0)
            return base.copy(
                lines = base.lines.filter { it.place < place }.map { it.copy(afterCut = false) },
                cut = SketchCut(minOf(place, base.cut?.place ?: place), nowMs),
            )
        }
        if (base.landed[stroke]?.let { nowMs - it < LANDED_MS } == true) return base
        val fresh = points.chunked(2).filter { it.size == 2 }.map { Offset(it[0].coerceIn(0f, 1f), it[1].coerceIn(0f, 1f)) }
        if (fresh.isEmpty()) return base
        val before = base.lines.firstOrNull { it.id == stroke && it.place == cell }
        val line = if (before == null) LiveLine(stroke, cell, fresh, 0f, nowMs, afterCut = base.cut != null)
        else {
            // Each message repeats the last point sent, so the line joins up; it's only kept once.
            val added = if (near(before.points.last(), fresh.first(), 0.0005f)) fresh.drop(1) else fresh
            if (added.isEmpty()) return base
            before.copy(points = before.points + added, fromLength = revealedLength(before, nowMs), atMs = nowMs)
        }
        val lines = if (before == null) base.lines.filterNot { it.id == stroke } + line else base.lines.map { if (it === before) line else it }
        return base.copy(lines = lines.takeLast(MAX_LINES))
    }

    /** Which live strokes are already in [committed], by stroke number, and at which index there. */
    fun landedIn(ink: SketchInk, committed: List<DrawStroke>): Map<Int, Int> {
        val found = mutableMapOf<Int, Int>()
        for (line in ink.lines.sortedWith(compareBy({ it.place }, { it.id }))) {
            val start = line.points.firstOrNull() ?: continue
            for (i in line.place until committed.size) {
                if (i in found.values) continue
                val first = committed[i].points.firstOrNull() ?: continue
                if (near(start, Offset(first.getOrElse(0) { -1.0 }.toFloat(), first.getOrElse(1) { -1.0 }.toFloat()), SAME)) {
                    found[line.id] = i
                    break
                }
            }
        }
        return found
    }

    /**
     * The drawing as the guesser sees it: the server's strokes, less any an undo or a clear has just taken away (until
     * a stroke drawn after it lands, which says the undo has too). [wasLive] holds the indexes of strokes that were
     * already on screen live, which arrive without being drawn on again.
     */
    fun drawing(ink: SketchInk, sessionId: String, drawer: String?, committed: List<DrawStroke>, nowMs: Long): Pair<List<DrawStroke>, Set<Int>> {
        if (ink.sessionId != sessionId || ink.drawer != drawer) return committed to emptySet()
        val landed = landedIn(ink, committed)
        val cut = ink.cut?.takeIf { cut -> nowMs - cut.atMs < CUT_MS && ink.lines.none { it.afterCut && it.id in landed } }
        val shown = if (cut != null && committed.size > cut.place) committed.take(cut.place) else committed
        return shown to landed.values.toSet()
    }

    /** The live strokes to draw at [nowMs]: those not yet in [committed], drawn on smoothly ([smooth]) and fading when stale. */
    fun live(ink: SketchInk, sessionId: String, drawer: String?, committed: List<DrawStroke>, nowMs: Long, smooth: Boolean): List<LiveShown> {
        if (ink.sessionId != sessionId || ink.drawer != drawer) return emptyList()
        val landed = landedIn(ink, committed)
        return ink.lines.filter { it.id !in landed }.sortedWith(compareBy({ it.place }, { it.id })).mapNotNull { line ->
            val age = nowMs - line.atMs
            if (age >= HOLD_MS + FADE_MS) return@mapNotNull null
            val alpha = if (!smooth || age <= HOLD_MS) 1f else 1f - (age - HOLD_MS).toFloat() / FADE_MS
            LiveShown(line.id, if (smooth) revealed(line, nowMs) else line.points, alpha)
        }
    }

    /** Lets go of strokes that have landed or faded, and of an undo or a clear that's done its work. */
    fun prune(ink: SketchInk, committed: List<DrawStroke>, nowMs: Long): SketchInk {
        if (ink.lines.isEmpty() && ink.cut == null && ink.landed.isEmpty()) return ink
        val landed = landedIn(ink, committed)
        val cutDone = ink.cut != null && (nowMs - ink.cut.atMs >= CUT_MS || ink.lines.any { it.afterCut && it.id in landed })
        return ink.copy(
            lines = ink.lines.filter { it.id !in landed && nowMs - it.atMs < HOLD_MS + FADE_MS },
            cut = if (cutDone) null else ink.cut,
            landed = (ink.landed + landed.keys.associateWith { nowMs }).filterValues { nowMs - it < LANDED_MS },
        )
    }

    /** How much of [line] is drawn on at [nowMs], as a length along it. */
    fun revealedLength(line: LiveLine, nowMs: Long): Float {
        val total = length(line.points)
        val t = ((nowMs - line.atMs).toFloat() / SMOOTH_MS).coerceIn(0f, 1f)
        return (line.fromLength + (total - line.fromLength) * t).coerceIn(0f, total)
    }

    /** [line]'s points up to where the smooth draw-on has reached at [nowMs], the last one part way along its segment. */
    fun revealed(line: LiveLine, nowMs: Long): List<Offset> {
        val points = line.points
        if (points.size < 2) return points
        var left = revealedLength(line, nowMs)
        val out = mutableListOf(points[0])
        for (i in 1 until points.size) {
            val step = (points[i] - points[i - 1]).getDistance()
            if (step >= left) {
                if (left > 0f && step > 0f) out += points[i - 1] + (points[i] - points[i - 1]) * (left / step)
                return out
            }
            out += points[i]
            left -= step
        }
        return out
    }

    /** The drawer's side: the points to send now, from those under the finger. */
    class Cursor {
        private var sent = 0

        /**
         * The points new since the last message, with that message's last point first so the line joins up; a burst of
         * more than [INK_POINTS] is simplified to fit, never cut short. Null when nothing is new.
         */
        fun next(finger: List<Offset>): List<Offset>? {
            if (finger.size <= sent) return null
            val fresh = finger.subList(maxOf(0, sent - 1), finger.size).toList()
            sent = finger.size
            return if (fresh.size <= INK_POINTS) fresh else Sketching.simplify(fresh, INK_POINTS)
        }
    }

    private fun length(points: List<Offset>) = points.zipWithNext { a, b -> (b - a).getDistance() }.sum()

    private fun near(a: Offset, b: Offset, within: Float) = hypot(a.x - b.x, a.y - b.y) <= within
}
