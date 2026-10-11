package app.heartpact.ui.graphics

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The one heart Heartpact draws: a square turned 45° with two round lobes, split on its vertical
 * centre line, in a 100-unit space (tip at 50,100). The mark and every sticker share it.
 */
object HeartGeometry {
    private const val TX = 50f
    private const val TY = 100f
    private const val R = 30f
    private val S = 60f / sqrt(2f)

    private val leftCorner = Offset(TX - S, TY - S)
    private val rightCorner = Offset(TX + S, TY - S)
    val leftLobe = Offset(TX - S / 2, TY - 1.5f * S)
    val rightLobe = Offset(TX + S / 2, TY - 1.5f * S)
    const val LOBE_RADIUS = R

    /** The Kept Promise tick: short stroke in the left half, long stroke in the right. */
    val tick = listOf(Offset(28f, 56f), Offset(44f, 72f), Offset(74f, 38f))
    const val TICK_WIDTH = 9.5f

    fun left() = Path().apply {
        moveTo(TX, TY); lineTo(leftCorner.x, leftCorner.y)
        arcTo(Rect(leftLobe, R), 135f, 180f, false)
        close()
    }

    fun right() = Path().apply {
        moveTo(TX, TY); lineTo(rightCorner.x, rightCorner.y)
        arcTo(Rect(rightLobe, R), 45f, -180f, false)
        close()
    }

    fun whole() = Path().apply {
        moveTo(TX, TY); lineTo(leftCorner.x, leftCorner.y)
        arcTo(Rect(leftLobe, R), 135f, 180f, false)
        arcTo(Rect(rightLobe, R), 225f, 180f, false)
        close()
    }

    fun tickPath() = Path().apply {
        moveTo(tick[0].x, tick[0].y)
        tick.drop(1).forEach { lineTo(it.x, it.y) }
    }
}

/** Draws [block] in a unit space ([minX],[minY],[width],[height]) fitted and centred in the canvas. */
inline fun DrawScope.inUnits(minX: Float, minY: Float, width: Float, height: Float, block: DrawScope.() -> Unit) {
    val s = min(size.width / width, size.height / height)
    val dx = (size.width - width * s) / 2 - minX * s
    val dy = (size.height - height * s) / 2 - minY * s
    withTransform({
        translate(dx, dy)
        scale(s, s, pivot = Offset.Zero)
    }, block)
}
