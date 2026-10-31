package app.heartpact.ui.play.skills

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import app.heartpact.ui.play.BlobColour
import app.heartpact.ui.play.PlayInk
import app.heartpact.ui.theme.Art

/*
 * How the skill games draw their things: stickers with a little depth (a white die-cut keyline, a darker side
 * underneath, a soft lift), the treats to share, the kiss, and the small labels that ride on pieces.
 */

/**
 * Colours of a treat: its top, its side (the depth under it), and what's on it: its [Treat.fills] in [fill], its
 * [Treat.curves] in [curve].
 */
internal class TreatInk(
    val top: Color,
    val side: Color,
    val inner: Color,
    val line: Color,
    val dots: List<Color>,
    val fill: Color = Color.Transparent,
    val curve: Color = Color.Transparent,
)

internal fun inkOf(t: Treat): TreatInk = when (t.kind) {
    TreatKind.Cookie -> TreatInk(BlobColour.Peach.fill, lerp(BlobColour.Peach.fill, BlobColour.Peach.ink, 0.55f), BlobColour.Rose.fill, Color.Transparent,
        listOf(PlayInk.Pen, Art.Right, Color.White, BlobColour.Mint.ink, BlobColour.Butter.ink))
    TreatKind.Chocolate -> TreatInk(Color(0xFF2E3577), Art.Left, Color.Transparent, Color(0xFF1A1F4D), emptyList())
    TreatKind.Pizza -> TreatInk(BlobColour.Peach.fill, lerp(BlobColour.Peach.fill, BlobColour.Peach.ink, 0.55f), BlobColour.Butter.fill, Color.Transparent,
        listOf(PlayInk.Blush))
    TreatKind.Jelly -> {
        val b = listOf(BlobColour.Mint, BlobColour.Sky, BlobColour.Lilac)[(t.colour - 3).coerceIn(0, 2)]
        TreatInk(b.fill, lerp(b.fill, b.ink, 0.45f), Color.Transparent, Color.Transparent, emptyList())
    }
    else -> moreInkOf(t)
}

/**
 * A sticker slab: [path] lifted [depth] px off the page, its side in [side], top in [top], a white keyline round
 * both, and a soft shadow. [details] draws on the top face, clipped to it.
 */
internal fun DrawScope.slab(path: Path, top: Color, side: Color, depth: Float, details: DrawScope.() -> Unit = {}) {
    val keyline = 7.dp.toPx()
    val steps = 6
    translate(0f, depth + 4.dp.toPx()) { drawPath(path, Art.Shadow, alpha = 0.10f, style = Stroke(keyline * 1.8f, join = StrokeJoin.Round)) }
    translate(0f, depth + 2.dp.toPx()) { drawPath(path, Art.Shadow, alpha = 0.10f, style = Stroke(keyline * 1.2f, join = StrokeJoin.Round)) }
    for (i in 0..steps) translate(0f, depth * i / steps) {
        drawPath(path, Art.Keyline, style = Stroke(keyline, join = StrokeJoin.Round))
        drawPath(path, Art.Keyline)
    }
    for (i in 1..steps) translate(0f, depth * i / steps) { drawPath(path, side) }
    drawPath(path, top)
    clipPath(path) { details() }
}

/** A treat (or one piece of it, [piece]) drawn with [fit], shifted by [shift] px. */
internal fun DrawScope.drawTreat(t: Treat, fit: Fit, piece: List<Offset> = t.outline, shift: Offset = Offset.Zero) {
    if (piece.size < 3) return
    val ink = inkOf(t)
    val path = fit.path(piece, shift = shift)
    slab(path, ink.top, ink.side, 6.dp.toPx()) {
        translate(shift.x, shift.y) {
            when (t.kind) {
                TreatKind.Cookie -> {
                    t.inner?.let { drawPath(fit.path(it), ink.inner) }
                    t.dots.forEachIndexed { i, (c, r) ->
                        val p = fit.px(c)
                        val len = fit.px(r) * 1.6f
                        val a = i * 1.3f
                        val d = Offset(kotlin.math.cos(a), kotlin.math.sin(a)) * len
                        drawLine(ink.dots[i % ink.dots.size], p - d, p + d, fit.px(r) * 1.1f, cap = StrokeCap.Round)
                    }
                }
                TreatKind.Chocolate -> {
                    val w = 3.dp.toPx()
                    t.lines.forEach { (a, b) ->
                        drawLine(ink.line, fit.px(a), fit.px(b), w, cap = StrokeCap.Round)
                        drawLine(Color.White.copy(alpha = 0.16f), fit.px(a) + Offset(1.5f, 1.5f).times(w / 2), fit.px(b) + Offset(1.5f, 1.5f).times(w / 2), w / 2, cap = StrokeCap.Round)
                    }
                }
                TreatKind.Pizza -> {
                    t.inner?.let { drawPath(fit.path(it), ink.inner) }
                    t.dots.forEach { (c, r) ->
                        drawCircle(ink.dots[0], fit.px(r), fit.px(c))
                        drawCircle(BlobColour.Rose.ink.copy(alpha = 0.35f), fit.px(r), fit.px(c), style = Stroke(1.5.dp.toPx()))
                        drawCircle(Color.White.copy(alpha = 0.45f), fit.px(r) * 0.22f, fit.px(c) + Offset(-0.35f, -0.35f) * fit.px(r))
                    }
                }
                TreatKind.Jelly -> {
                    val b = t.outline
                    val minX = b.minOf { it.x }; val minY = b.minOf { it.y }; val maxX = b.maxOf { it.x }
                    val glint = Offset(minX + (maxX - minX) * 0.3f, minY + 0.1f)
                    drawCircle(Color.White.copy(alpha = 0.55f), fit.px(0.035f), fit.px(glint))
                    drawCircle(Color.White.copy(alpha = 0.4f), fit.px(0.016f), fit.px(glint + Offset(0.06f, 0.035f)))
                }
                else -> moreTreatDetails(t, fit, ink)
            }
        }
    }
}

/** Pink lips [w] px wide, centred on the origin: an upper lip with a bow, a full lower lip. */
internal fun lipsPath(w: Float): Path = Path().apply {
    moveTo(-0.5f * w, 0f)
    cubicTo(-0.36f * w, -0.1f * w, -0.26f * w, -0.34f * w, -0.13f * w, -0.31f * w)
    cubicTo(-0.06f * w, -0.3f * w, -0.02f * w, -0.24f * w, 0f, -0.2f * w)
    cubicTo(0.02f * w, -0.24f * w, 0.06f * w, -0.3f * w, 0.13f * w, -0.31f * w)
    cubicTo(0.26f * w, -0.34f * w, 0.36f * w, -0.1f * w, 0.5f * w, 0f)
    cubicTo(0.34f * w, 0.32f * w, -0.34f * w, 0.32f * w, -0.5f * w, 0f)
    close()
}

/** The line where the lips meet. */
internal fun lipsSeam(w: Float): Path = Path().apply {
    moveTo(-0.44f * w, 0f)
    quadraticTo(0f, 0.08f * w, 0.44f * w, 0f)
}

/** A small white pill with [text] centred on [at], for a share on a piece or a label on a mark. */
internal fun DrawScope.pill(measurer: TextMeasurer, text: String, at: Offset, style: TextStyle, ink: Color, ground: Color = Color.White, scale: Float = 1f) {
    if (scale <= 0.01f) return
    val layout = measurer.measure(text, style)
    val padX = 9.dp.toPx()
    val padY = 4.dp.toPx()
    val w = layout.size.width + 2 * padX
    val h = layout.size.height + 2 * padY
    val s = scale
    drawRoundRect(Art.Shadow.copy(alpha = 0.12f), Offset(at.x - w * s / 2, at.y - h * s / 2 + 2.dp.toPx()), Size(w * s, h * s), CornerRadius(h * s / 2))
    drawRoundRect(ground, Offset(at.x - w * s / 2, at.y - h * s / 2), Size(w * s, h * s), CornerRadius(h * s / 2))
    if (s > 0.6f) {
        drawText(layout, ink, Offset(at.x - layout.size.width / 2f, at.y - layout.size.height / 2f), alpha = ((s - 0.6f) / 0.4f).coerceIn(0f, 1f))
    }
}
