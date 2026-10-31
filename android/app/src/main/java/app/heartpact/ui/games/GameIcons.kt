package app.heartpact.ui.games

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import app.heartpact.data.GameType
import app.heartpact.ui.play.BlobColour
import app.heartpact.ui.play.unitHeart

/*
 * The couple games' pictures on their picker blobs: plain line icons, one stroke weight, round ends, drawn in the
 * blob's ink. Everything is laid out on a 24 by 24 grid so the icons read as one family.
 */

/** Each couple game's blob, picked so neighbours in the grid differ. */
internal val GameType.blob: BlobColour get() = when (this) {
    GameType.SkillDuel -> BlobColour.Rose
    GameType.CoupleQuiz -> BlobColour.Lilac
    GameType.MemoryMatch -> BlobColour.Sky
    GameType.ICallOn -> BlobColour.Butter
    GameType.Trivia -> BlobColour.Mint
    GameType.GuessMyAnswer -> BlobColour.Peach
    GameType.WouldYouRather -> BlobColour.Teal
    GameType.TruthOrDare -> BlobColour.Rose
    GameType.WordDash -> BlobColour.Lime
    GameType.Handwriting -> BlobColour.Sky
    GameType.TicTacToe -> BlobColour.Butter
    GameType.ConnectFour -> BlobColour.Peach
    GameType.SpotTheDifference -> BlobColour.Lime
    GameType.Unknown -> BlobColour.Indigo
}

/** A couple game's line icon, [size] across, in [tint]. */
@Composable
internal fun GameIcon(type: GameType, tint: Color, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val line = Stroke(1.9f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val icon = IconPen(this, u, tint, line)
        when (type) {
            GameType.MemoryMatch -> icon.cards()
            GameType.TicTacToe -> icon.grid()
            GameType.ConnectFour -> icon.discs()
            GameType.Handwriting -> icon.pencil()
            GameType.Trivia -> icon.question()
            GameType.WouldYouRather -> icon.aOrB()
            GameType.CoupleQuiz -> icon.bubble()
            GameType.ICallOn -> icon.letter()
            GameType.WordDash -> icon.chain()
            GameType.TruthOrDare -> icon.flame()
            GameType.GuessMyAnswer -> icon.magnifier()
            GameType.SkillDuel -> icon.twoHearts()
            GameType.SpotTheDifference -> icon.twoPictures()
            GameType.Unknown -> icon.question()
        }
    }
}

/** Draws on the 24-unit grid: [u] is one unit in pixels. */
private class IconPen(val d: DrawScope, val u: Float, val tint: Color, val line: Stroke) {
    fun p(x: Float, y: Float) = Offset(x * u, y * u)
    fun seg(x1: Float, y1: Float, x2: Float, y2: Float) = d.drawLine(tint, p(x1, y1), p(x2, y2), line.width, StrokeCap.Round)
    fun box(x: Float, y: Float, w: Float, h: Float, r: Float) =
        d.drawRoundRect(tint, p(x, y), Size(w * u, h * u), CornerRadius(r * u), style = line)
    fun path(build: Path.() -> Unit) = d.drawPath(Path().apply(build), tint, style = line)

    /** Two cards, one tipped behind the other, a heart on the front one. */
    fun cards() {
        d.rotate(-14f, p(9f, 13f)) { box(3.5f, 5.5f, 10f, 14f, 2f) }
        d.rotate(10f, p(15f, 12f)) {
            d.drawRoundRect(tint.copy(alpha = 0.22f), p(10f, 4.5f), Size(10f * u, 14f * u), CornerRadius(2f * u))
            box(10f, 4.5f, 10f, 14f, 2f)
            d.translate(15f * u, 11.8f * u) { d.drawPath(unitHeart(6f * u), tint) }
        }
    }

    /** A noughts-and-crosses grid with one heart in the middle. */
    fun grid() {
        seg(9f, 3.5f, 9f, 20.5f); seg(15f, 3.5f, 15f, 20.5f)
        seg(3.5f, 9f, 20.5f, 9f); seg(3.5f, 15f, 20.5f, 15f)
        d.translate(12f * u, 12.3f * u) { d.drawPath(unitHeart(5f * u), tint) }
    }

    /** A frame of holes, some filled: two rows of three. */
    fun discs() {
        box(3f, 5f, 18f, 14f, 3f)
        val xs = listOf(7.5f, 12f, 16.5f)
        xs.forEachIndexed { i, x ->
            d.drawCircle(tint, 1.9f * u, p(x, 9.3f), style = Stroke(1.5f * u))
            d.drawCircle(tint, 2.2f * u, p(x, 14.7f), style = if (i != 1) androidx.compose.ui.graphics.drawscope.Fill else Stroke(1.5f * u))
        }
    }

    /** A pencil at a slant, writing a little squiggle. */
    fun pencil() {
        d.rotate(45f, p(12f, 12f)) {
            box(9.5f, 1.5f, 5f, 15f, 1.2f)
            seg(9.5f, 5f, 14.5f, 5f)
            path { moveTo(9.5f * u, 16.5f * u); lineTo(12f * u, 21f * u); lineTo(14.5f * u, 16.5f * u) }
        }
        path { moveTo(3f * u, 21f * u); cubicTo(5f * u, 19.5f * u, 6f * u, 22.5f * u, 8f * u, 21f * u) }
    }

    /** A question mark. */
    fun question() {
        path {
            moveTo(7.5f * u, 8.5f * u)
            cubicTo(7.5f * u, 2.5f * u, 17f * u, 2.5f * u, 16.5f * u, 8.2f * u)
            cubicTo(16.2f * u, 11.5f * u, 12f * u, 11.5f * u, 12f * u, 15f * u)
        }
        d.drawCircle(tint, 1.4f * u, p(12f, 19.8f))
    }

    /** "A / B": two letters either side of a slash. */
    fun aOrB() {
        // A
        path { moveTo(2.5f * u, 17.5f * u); lineTo(5.75f * u, 6.5f * u); lineTo(9f * u, 17.5f * u) }
        seg(3.8f, 13.6f, 7.7f, 13.6f)
        // the slash
        seg(13.5f, 5f, 10.5f, 19f)
        // B
        path {
            moveTo(15f * u, 6.5f * u); lineTo(15f * u, 17.5f * u); lineTo(18.6f * u, 17.5f * u)
            cubicTo(22f * u, 17.5f * u, 22f * u, 11.8f * u, 18.6f * u, 11.8f * u); lineTo(15f * u, 11.8f * u)
            moveTo(15f * u, 6.5f * u); lineTo(18.2f * u, 6.5f * u)
            cubicTo(21f * u, 6.5f * u, 21f * u, 11.8f * u, 18.2f * u, 11.8f * u)
        }
    }

    /** A speech bubble with an equals sign: the same answer. */
    fun bubble() {
        path {
            moveTo(6f * u, 4f * u); lineTo(18f * u, 4f * u)
            quadraticTo(21f * u, 4f * u, 21f * u, 7f * u); lineTo(21f * u, 13.5f * u)
            quadraticTo(21f * u, 16.5f * u, 18f * u, 16.5f * u); lineTo(11f * u, 16.5f * u)
            lineTo(6.5f * u, 20.5f * u); lineTo(7f * u, 16.5f * u); lineTo(6f * u, 16.5f * u)
            quadraticTo(3f * u, 16.5f * u, 3f * u, 13.5f * u); lineTo(3f * u, 7f * u)
            quadraticTo(3f * u, 4f * u, 6f * u, 4f * u); close()
        }
        seg(8.5f, 8.6f, 15.5f, 8.6f); seg(8.5f, 12f, 15.5f, 12f)
    }

    /** A letter tile, the way a letter is called. */
    fun letter() {
        box(3.5f, 3.5f, 17f, 17f, 3.5f)
        path { moveTo(8f * u, 17f * u); lineTo(12f * u, 7f * u); lineTo(16f * u, 17f * u) }
        seg(9.6f, 13.2f, 14.4f, 13.2f)
    }

    /** Two chain links. */
    fun chain() {
        d.rotate(-45f, p(12f, 12f)) {
            box(2.5f, 9f, 11f, 6f, 3f)
            box(10.5f, 9f, 11f, 6f, 3f)
        }
    }

    /** A flame with a small one inside. */
    fun flame() {
        path {
            moveTo(12f * u, 21f * u)
            cubicTo(6.5f * u, 21f * u, 4.5f * u, 16.5f * u, 6.5f * u, 12.5f * u)
            cubicTo(7.5f * u, 10.5f * u, 8.5f * u, 11.5f * u, 9f * u, 12.5f * u)
            cubicTo(9f * u, 8f * u, 11f * u, 5f * u, 13.5f * u, 3f * u)
            cubicTo(13.5f * u, 7f * u, 19.5f * u, 9.5f * u, 18f * u, 15.5f * u)
            cubicTo(17.2f * u, 18.8f * u, 15f * u, 21f * u, 12f * u, 21f * u)
            close()
        }
        path {
            moveTo(12f * u, 18.5f * u)
            cubicTo(10f * u, 18.5f * u, 9.4f * u, 16.4f * u, 10.6f * u, 14.8f * u)
            cubicTo(11.4f * u, 13.8f * u, 12.4f * u, 13f * u, 12.6f * u, 12f * u)
            cubicTo(14f * u, 13.4f * u, 15f * u, 15.2f * u, 14.2f * u, 16.8f * u)
            cubicTo(13.8f * u, 17.8f * u, 13f * u, 18.5f * u, 12f * u, 18.5f * u)
        }
    }

    /** A magnifying glass with a heart in the lens. */
    fun magnifier() {
        d.drawCircle(tint, 6.5f * u, p(10f, 10f), style = line)
        d.drawLine(tint, p(14.8f, 14.8f), p(20.5f, 20.5f), line.width * 1.4f, StrokeCap.Round)
        d.translate(10f * u, 10.4f * u) { d.drawPath(unitHeart(5.2f * u), tint) }
    }

    /** Two little pictures side by side, the second with one thing circled: spot the difference. */
    fun twoPictures() {
        box(2f, 6f, 9.5f, 12f, 2f)
        box(12.5f, 6f, 9.5f, 12f, 2f)
        d.drawCircle(tint, 1.7f * u, p(5.4f, 10f))
        d.drawCircle(tint, 1.7f * u, p(15.9f, 10f))
        d.translate(7.6f * u, 14.6f * u) { d.drawPath(unitHeart(4f * u), tint) }
        // The difference: the heart is gone from the second picture, circled where it was.
        d.drawCircle(tint, 3.4f * u, p(18.1f, 14.4f), style = line)
    }

    /** Two hearts leaning in: heart to heart. */
    fun twoHearts() {
        d.rotate(-12f, p(8.5f, 12f)) { d.translate(8.5f * u, 12.5f * u) { d.drawPath(unitHeart(14f * u), tint, style = line) } }
        d.rotate(12f, p(15.5f, 12f)) { d.translate(15.5f * u, 12.5f * u) { d.drawPath(unitHeart(14f * u), tint.copy(alpha = 0.3f)) } }
        d.rotate(12f, p(15.5f, 12f)) { d.translate(15.5f * u, 12.5f * u) { d.drawPath(unitHeart(14f * u), tint, style = line) } }
    }
}
