package app.heartpact.ui.play.skills

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import app.heartpact.ui.graphics.HeartGeometry
import app.heartpact.ui.play.SkillKind
import app.heartpact.ui.play.unitHeart

/*
 * The skill games' line icons, for the picker blobs: drawn on a 24-unit grid in one colour with round ends, so they
 * scale to any size and sit with the app's other line icons.
 */

internal fun DrawScope.skillIcon(kind: SkillKind, tint: Color) {
    val u = size.minDimension / 24f
    val line = Stroke(1.9f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val thin = Stroke(1.5f * u, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.6f * u, 2.4f * u)))
    translate((size.width - 24f * u) / 2, (size.height - 24f * u) / 2) {
        fun p(x: Float, y: Float) = Offset(x * u, y * u)
        when (kind) {
            SkillKind.FairShare -> {
                // A heart split down the middle, its halves a little apart and tipped.
                val k = 0.17f * u
                for ((half, dx, deg) in listOf(Triple(HeartGeometry.left(), -1.3f, -6f), Triple(HeartGeometry.right(), 1.3f, 6f))) {
                    rotate(deg, p(12f + dx, 20f)) {
                        translate(p(12f + dx, 0f).x - 50f * k, p(0f, 3.4f).y) { scale(k, k, Offset.Zero) { drawPath(half, tint, style = Stroke(line.width / k, join = StrokeJoin.Round)) } }
                    }
                }
                drawLine(tint, p(12f, 1.5f), p(12f, 22.5f), thin.width, cap = StrokeCap.Round, pathEffect = thin.pathEffect)
            }
            SkillKind.FillTheHeart -> {
                // A jar with a heart of liquid in it, and a drop falling in.
                val jar = Path().apply {
                    moveTo(8.5f * u, 8f * u); lineTo(8.5f * u, 9.8f * u)
                    cubicTo(6f * u, 11f * u, 5.5f * u, 12.5f * u, 5.5f * u, 14.5f * u)
                    lineTo(5.5f * u, 19f * u)
                    quadraticTo(5.5f * u, 21f * u, 7.5f * u, 21f * u)
                    lineTo(16.5f * u, 21f * u)
                    quadraticTo(18.5f * u, 21f * u, 18.5f * u, 19f * u)
                    lineTo(18.5f * u, 14.5f * u)
                    cubicTo(18.5f * u, 12.5f * u, 18f * u, 11f * u, 15.5f * u, 9.8f * u)
                    lineTo(15.5f * u, 8f * u)
                }
                drawPath(jar, tint, style = line)
                drawLine(tint, p(7.5f, 8f), p(16.5f, 8f), line.width, cap = StrokeCap.Round)
                translate(p(12f, 16.6f).x, p(12f, 16.6f).y) { drawPath(unitHeart(7.5f * u), tint) }
                val drop = Path().apply {
                    moveTo(12f * u, 1.2f * u)
                    cubicTo(13.4f * u, 3f * u, 13.6f * u, 3.6f * u, 13.6f * u, 4.3f * u)
                    cubicTo(13.6f * u, 5.2f * u, 12.9f * u, 5.9f * u, 12f * u, 5.9f * u)
                    cubicTo(11.1f * u, 5.9f * u, 10.4f * u, 5.2f * u, 10.4f * u, 4.3f * u)
                    cubicTo(10.4f * u, 3.6f * u, 10.6f * u, 3f * u, 12f * u, 1.2f * u)
                    close()
                }
                drawPath(drop, tint)
            }
            SkillKind.OtherHalf -> {
                // Half a heart, the dashed fold, and the other half only hinted.
                val k = 0.19f * u
                translate(12f * u - 50f * k, 2.6f * u) {
                    scale(k, k, Offset.Zero) {
                        val left = Path().apply {
                            moveTo(50f, 100f); lineTo(50f - 42.43f, 100f - 42.43f)
                            arcTo(androidx.compose.ui.geometry.Rect(HeartGeometry.leftLobe, HeartGeometry.LOBE_RADIUS), 135f, 180f, false)
                        }
                        drawPath(left, tint, style = Stroke(line.width / k, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        val right = Path().apply {
                            moveTo(50f, 100f); lineTo(50f + 42.43f, 100f - 42.43f)
                            arcTo(androidx.compose.ui.geometry.Rect(HeartGeometry.rightLobe, HeartGeometry.LOBE_RADIUS), 45f, -180f, false)
                        }
                        drawPath(right, tint.copy(alpha = tint.alpha * 0.4f), style = Stroke(thin.width / k, cap = StrokeCap.Round, join = StrokeJoin.Round,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f * u / k, 3.2f * u / k))))
                    }
                }
                drawLine(tint, p(12f, 1.2f), p(12f, 22.8f), thin.width, cap = StrokeCap.Round, pathEffect = thin.pathEffect)
            }
            SkillKind.Heartbeat -> {
                // A heart with a pulse running through it.
                translate(p(12f, 13.2f).x, p(12f, 13.2f).y) { drawPath(unitHeart(23f * u), tint, style = line) }
                val pulse = Path().apply {
                    moveTo(1.2f * u, 13f * u); lineTo(7f * u, 13f * u); lineTo(9f * u, 9.5f * u); lineTo(11.4f * u, 17f * u)
                    lineTo(13.6f * u, 10.5f * u); lineTo(15.2f * u, 13f * u); lineTo(22.8f * u, 13f * u)
                }
                drawPath(pulse, tint, style = line)
            }
            SkillKind.KissTheCenter -> {
                // A target with a kiss on its centre.
                drawCircle(tint, 9.6f * u, p(12f, 12f), style = line)
                drawCircle(tint, 5.6f * u, p(12f, 12f), style = Stroke(1.5f * u))
                translate(p(12f, 12.4f).x, p(12f, 12.4f).y) { drawPath(lipsPath(6.4f * u), tint) }
            }
            SkillKind.PromiseRing -> {
                // A ring with a heart set on top.
                drawCircle(tint, 7.2f * u, p(12f, 14.6f), style = line)
                translate(p(12f, 5.4f).x, p(12f, 5.4f).y) { drawPath(unitHeart(8.4f * u), tint) }
                drawArc(tint.copy(alpha = tint.alpha * 0.45f), 200f, 70f, false, p(7.4f, 10f), Size(9.2f * u, 9.2f * u), style = Stroke(1.2f * u, cap = StrokeCap.Round))
            }
        }
    }
}
