package app.heartpact.ui.graphics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import app.heartpact.ui.theme.Art
import app.heartpact.ui.theme.Heartpact

/**
 * The sticker vocabulary (DESIGN.md): a stuck half, a dashed slot waiting for a half, the whole
 * heart when both are stuck, and the peeled heart for a pact called off together. State never
 * rests on colour alone: a slot is an outline, a stuck half is a filled sticker with a keyline.
 */
enum class HalfState { Stuck, Slot, Empty }

private const val VB_X = -8f
private const val VB_Y = -2f
private const val VB_W = 116f
private const val VB_H = 112f

@Composable
fun HeartSticker(left: HalfState, right: HalfState, size: Dp, modifier: Modifier = Modifier) {
    val leftSlot = Heartpact.colors.left.action
    val rightSlot = Heartpact.colors.right.action
    val shapes = remember { StickerShapes() }
    Canvas(modifier.size(size, size * (VB_H / VB_W))) {
        inUnits(VB_X, VB_Y, VB_W, VB_H) {
            val keyline = when {
                left == HalfState.Stuck && right == HalfState.Stuck -> shapes.whole
                left == HalfState.Stuck -> shapes.left
                right == HalfState.Stuck -> shapes.right
                else -> null
            }
            keyline?.let { drawKeyline(it) }
            if (left == HalfState.Stuck) drawPath(shapes.left, Art.Left)
            if (right == HalfState.Stuck) drawPath(shapes.right, Art.Right)
            if (left == HalfState.Slot) drawSlot(shapes.left, leftSlot)
            if (right == HalfState.Slot) drawSlot(shapes.right, rightSlot)
        }
    }
}

/** Called off together: both halves faded, one lobe peeled back. */
@Composable
fun PeeledHeart(size: Dp, modifier: Modifier = Modifier) {
    val shapes = remember { StickerShapes() }
    Canvas(modifier.size(size, size * (VB_H / VB_W))) {
        inUnits(VB_X, VB_Y, VB_W, VB_H) {
            drawKeyline(shapes.whole)
            drawPath(shapes.left, Art.Left, alpha = 0.5f)
            drawPath(shapes.right, Art.Right, alpha = 0.5f)
            val c = HeartGeometry.rightLobe
            val r = HeartGeometry.LOBE_RADIUS
            val flap = Path().apply {
                moveTo(c.x, c.y - r)
                arcTo(Rect(c, r), 270f, 90f, false)
                lineTo(c.x, c.y)
                close()
            }
            drawPath(flap, Color(0xFFF2F3FA))
            val fold = Path().apply { moveTo(c.x, c.y - r); lineTo(c.x, c.y); lineTo(c.x + r, c.y) }
            drawPath(fold, Color(0xFFC8CBE3), style = Stroke(3f, join = StrokeJoin.Round))
        }
    }
}

private class StickerShapes {
    val left = HeartGeometry.left()
    val right = HeartGeometry.right()
    val whole = HeartGeometry.whole()
}

/** White die-cut edge with a soft lift under it: two faint passes stand in for a blur. */
private fun DrawScope.drawKeyline(path: Path) {
    for ((dy, alpha) in listOf(3.5f to 0.10f, 2f to 0.12f)) {
        translate(0f, dy) {
            drawPath(path, Art.Shadow, alpha = alpha, style = Stroke(15f, join = StrokeJoin.Round))
        }
    }
    drawPath(path, Art.Keyline, style = Fill)
    drawPath(path, Art.Keyline, style = Stroke(13f, join = StrokeJoin.Round))
}

private fun DrawScope.drawSlot(path: Path, color: Color) {
    drawPath(path, color, style = Stroke(5f, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 8f))))
}
