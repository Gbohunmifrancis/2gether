package app.heartpact.ui.graphics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import app.heartpact.ui.theme.Art

/**
 * The Kept Promise mark: the folded heart with a tick cut clean through both halves. The cut is a
 * clear blend in an offscreen layer, so whatever is behind the mark shows through it. Only the
 * mark carries the tick; stickers never do.
 */
@Composable
fun LogoMark(size: Dp, night: Boolean, modifier: Modifier = Modifier, tickProgress: Float = 1f) {
    Canvas(
        modifier
            .size(size, size * (MARK_H / MARK_W))
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen),
    ) {
        inUnits(MARK_X, MARK_Y, MARK_W, MARK_H) {
            drawMark(
                left = if (night) Art.MarkNightLeft else Art.Left,
                right = if (night) Art.MarkNightRight else Art.Right,
                tickProgress = tickProgress,
            )
        }
    }
}

private const val MARK_X = -2f
private const val MARK_Y = 4.4f
private const val MARK_W = 104f
private const val MARK_H = 97.6f

/**
 * Draws the mark in heart units. The caller must be in an offscreen layer for the cut to show
 * the background rather than black. [tickProgress] draws the cut along the tick, for the splash.
 */
fun DrawScope.drawMark(left: Color, right: Color, tickProgress: Float = 1f, alpha: Float = 1f) {
    drawPath(HeartGeometry.left(), left, alpha = alpha)
    drawPath(HeartGeometry.right(), right, alpha = alpha)
    if (tickProgress <= 0f) return
    val tick = HeartGeometry.tickPath()
    val cut = if (tickProgress >= 1f) tick else Path().also { segment ->
        val measure = PathMeasure().apply { setPath(tick, false) }
        measure.getSegment(0f, measure.length * tickProgress, segment, true)
    }
    drawPath(cut, Color.Black, style = Stroke(HeartGeometry.TICK_WIDTH, cap = StrokeCap.Round, join = StrokeJoin.Round), blendMode = BlendMode.Clear)
}
