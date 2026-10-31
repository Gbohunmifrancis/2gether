package app.heartpact.ui.play.skills

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import app.heartpact.ui.play.BlobColour
import app.heartpact.ui.play.PlayInk
import app.heartpact.ui.theme.Art
import kotlin.math.cos
import kotlin.math.sin

/*
 * How the treats past the first four are coloured and decorated (Art.kt draws the slab they sit on, and the first
 * four). The same sticker world: pastel tops, a deeper side, a white keyline; toppings drawn on the top face.
 */

private val Dough = lerp(BlobColour.Butter.fill, BlobColour.Peach.ink, 0.28f)
private val DoughSide = lerp(BlobColour.Butter.fill, BlobColour.Peach.ink, 0.58f)
private val Crumb = Color(0xFFFCEBCB)
private val Butter = Color(0xFFFFE680)
private val Cream = Color(0xFFFFF7EE)
private val CheeseTop = Color(0xFFFCD874)
private val Seed = Color(0xFF1F2557)
private val Sprinkles get() = listOf(PlayInk.Pen, Art.Right, Color.White, BlobColour.Mint.ink, BlobColour.Butter.ink)

private fun <T> List<T>.pick(i: Int): T = this[i.mod(size)]

private fun BlobColour.deep(f: Float) = lerp(fill, ink, f)

internal fun moreInkOf(t: Treat): TreatInk = when (t.kind) {
    TreatKind.Macaron -> {
        val b = listOf(BlobColour.Rose, BlobColour.Lilac, BlobColour.Mint, BlobColour.Sky, BlobColour.Butter).pick(t.colour)
        TreatInk(b.fill, b.deep(0.45f), Color.Transparent, Color.Transparent, emptyList(), fill = Cream, curve = b.deep(0.28f))
    }
    TreatKind.Toast -> TreatInk(
        lerp(BlobColour.Peach.fill, BlobColour.Peach.ink, 0.42f), lerp(BlobColour.Peach.fill, BlobColour.Peach.ink, 0.7f), Crumb, Color.Transparent,
        listOf(lerp(Crumb, BlobColour.Peach.ink, 0.25f)), fill = Butter, curve = lerp(Butter, BlobColour.Butter.ink, 0.35f),
    )
    TreatKind.Watermelon -> TreatInk(BlobColour.Mint.ink, Color(0xFF17573A), BlobColour.Mint.fill, Seed, emptyList(), fill = Color(0xFFFF7FA0))
    TreatKind.CandyHeart -> {
        val b = listOf(BlobColour.Rose, BlobColour.Lilac, BlobColour.Mint, BlobColour.Butter, BlobColour.Sky).pick(t.colour)
        TreatInk(b.deep(0.12f), b.deep(0.5f), lerp(b.fill, Color.White, 0.4f), Color.Transparent, emptyList())
    }
    TreatKind.Cheese -> TreatInk(
        CheeseTop, lerp(CheeseTop, BlobColour.Butter.ink, 0.5f), Color.Transparent, Color.Transparent,
        listOf(lerp(CheeseTop, BlobColour.Butter.ink, 0.32f), lerp(CheeseTop, BlobColour.Butter.ink, 0.15f)),
        fill = lerp(CheeseTop, BlobColour.Peach.ink, 0.3f),
    )
    TreatKind.Waffle -> TreatInk(Dough, DoughSide, Color.Transparent, lerp(BlobColour.Butter.fill, BlobColour.Peach.ink, 0.5f), emptyList(),
        fill = Butter, curve = lerp(Butter, BlobColour.Butter.ink, 0.35f))
    TreatKind.CakeSlice -> TreatInk(
        Color(0xFFFBE6B9), lerp(Color(0xFFFBE6B9), BlobColour.Peach.ink, 0.4f), Color.Transparent,
        listOf(Cream, BlobColour.Rose.fill, BlobColour.Lilac.fill).pick(t.colour), listOf(PlayInk.Pen), fill = BlobColour.Rose.fill,
    )
    TreatKind.Cupcake -> {
        val b = listOf(BlobColour.Rose, BlobColour.Lilac, BlobColour.Mint, BlobColour.Butter).pick(t.colour)
        val w = if (b == BlobColour.Lilac) BlobColour.Sky else BlobColour.Lilac
        TreatInk(w.fill, w.deep(0.45f), b.fill, w.deep(0.3f), Sprinkles, curve = Color.White.copy(alpha = 0.6f))
    }
    TreatKind.Donut -> TreatInk(Dough, DoughSide, listOf(BlobColour.Rose.fill, BlobColour.Lilac.fill, Color(0xFF2E3577)).pick(t.colour), Color.Transparent, Sprinkles)
    TreatKind.Lollipop -> {
        val b = listOf(BlobColour.Rose, BlobColour.Sky, BlobColour.Lilac, BlobColour.Mint, BlobColour.Butter).pick(t.colour)
        TreatInk(b.deep(0.2f), b.deep(0.55f), Color.Transparent, Color.Transparent, emptyList(), fill = Color(0xFFF2F3FA), curve = Color.White.copy(alpha = 0.85f))
    }
    TreatKind.IceCream -> {
        val b = listOf(BlobColour.Rose, BlobColour.Mint, BlobColour.Lilac, BlobColour.Butter).pick(t.colour)
        TreatInk(Dough, DoughSide, b.fill, lerp(BlobColour.Butter.fill, BlobColour.Peach.ink, 0.5f), Sprinkles.filter { it != Color.White })
    }
    TreatKind.JellyBean -> {
        val b = listOf(BlobColour.Rose, BlobColour.Lime, BlobColour.Teal, BlobColour.Lilac, BlobColour.Peach, BlobColour.Sky).pick(t.colour)
        TreatInk(b.deep(0.22f), b.deep(0.55f), Color.Transparent, Color.Transparent, emptyList(), curve = Color.White.copy(alpha = 0.65f))
    }
    TreatKind.Croissant -> TreatInk(
        lerp(BlobColour.Butter.fill, BlobColour.Peach.ink, 0.32f), lerp(BlobColour.Butter.fill, BlobColour.Peach.ink, 0.62f), Color.Transparent, Color.Transparent,
        emptyList(), curve = lerp(BlobColour.Butter.fill, BlobColour.Peach.ink, 0.58f),
    )
    TreatKind.Gummy -> {
        val b = listOf(BlobColour.Teal, BlobColour.Lime, BlobColour.Rose, BlobColour.Lilac, BlobColour.Butter).pick(t.colour)
        TreatInk(b.fill, b.deep(0.45f), Color.Transparent, Color.Transparent, listOf(Color.White.copy(alpha = 0.85f)))
    }
    TreatKind.Cookie, TreatKind.Chocolate, TreatKind.Pizza, TreatKind.Jelly -> error("Coloured in Art.kt")
}

/** What's on the top face of one of the treats past the first four, already clipped to it and shifted with it. */
internal fun DrawScope.moreTreatDetails(t: Treat, fit: Fit, ink: TreatInk) {
    when (t.kind) {
        TreatKind.Macaron -> {
            t.fills.forEach { drawPath(fit.path(it), ink.fill) }
            t.curves.forEach { drawPath(fit.path(it, close = false), ink.curve, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)) }
            glint(t, fit)
        }
        TreatKind.Toast -> {
            t.inner?.let { drawPath(fit.path(it), ink.inner) }
            t.dots.forEach { (c, r) -> drawOval(ink.dots[0], fit.px(c) - Offset(fit.px(r) * 1.3f, fit.px(r)), androidx.compose.ui.geometry.Size(fit.px(r) * 2.6f, fit.px(r) * 2f)) }
            t.fills.forEach {
                drawPath(fit.path(it), ink.fill)
                drawPath(fit.path(it), ink.curve, style = Stroke(1.5.dp.toPx(), join = StrokeJoin.Round))
            }
            t.fills.firstOrNull()?.let { pat ->
                val c = centroid(pat)
                drawLine(Color.White.copy(alpha = 0.7f), fit.px(c + Offset(-0.035f, -0.03f)), fit.px(c + Offset(0.0f, -0.045f)), 3.dp.toPx(), cap = StrokeCap.Round)
            }
        }
        TreatKind.Watermelon -> {
            t.inner?.let { drawPath(fit.path(it), ink.inner) }
            t.fills.forEach { drawPath(fit.path(it), ink.fill) }
            t.lines.forEach { (a, b) ->
                drawLine(ink.line, fit.px(a), fit.px(b), fit.px(0.024f), cap = StrokeCap.Round)
                drawCircle(Color.White.copy(alpha = 0.5f), fit.px(0.005f), fit.px(a + (b - a) * 0.3f))
            }
        }
        TreatKind.CandyHeart -> {
            t.inner?.let { drawPath(fit.path(it), ink.inner) }
            glint(t, fit)
        }
        TreatKind.Cheese -> {
            t.fills.forEach { drawPath(fit.path(it), ink.fill) }
            t.dots.forEach { (c, r) ->
                // A hole: its shadowed wall, and the floor further in.
                drawCircle(ink.dots[0], fit.px(r), fit.px(c))
                drawCircle(ink.dots[1], fit.px(r) * 0.78f, fit.px(c) + Offset(0.18f, 0.22f) * fit.px(r))
            }
        }
        TreatKind.Waffle -> {
            val w = fit.px(0.032f)
            t.lines.forEach { (a, b) ->
                drawLine(ink.line, fit.px(a), fit.px(b), w)
                drawLine(Color.White.copy(alpha = 0.18f), fit.px(a) + Offset(w * 0.45f, w * 0.45f), fit.px(b) + Offset(w * 0.45f, w * 0.45f), w * 0.3f)
            }
            t.fills.forEach {
                drawPath(fit.path(it), ink.fill)
                drawPath(fit.path(it), ink.curve, style = Stroke(1.5.dp.toPx(), join = StrokeJoin.Round))
            }
        }
        TreatKind.CakeSlice -> {
            t.lines.forEach { (a, b) -> drawLine(ink.line, fit.px(a), fit.px(b), fit.px(0.05f)) }
            t.fills.forEach { drawPath(fit.path(it), ink.fill) }
            t.dots.forEach { (c, r) ->
                // The strawberry: red, a few seeds, a leafy cap.
                drawCircle(ink.dots[0], fit.px(r), fit.px(c))
                for (i in 0 until 5) {
                    val a = i * 1.26f + 0.4f
                    drawCircle(BlobColour.Butter.fill, fit.px(r) * 0.09f, fit.px(c + Offset(cos(a), sin(a)) * (r * 0.55f)))
                }
                drawCircle(Color.White.copy(alpha = 0.5f), fit.px(r) * 0.18f, fit.px(c + Offset(-0.4f, -0.4f) * r))
                drawLine(BlobColour.Mint.ink, fit.px(c + Offset(-0.35f, -0.85f) * r), fit.px(c + Offset(0.35f, -0.85f) * r), fit.px(r) * 0.3f, cap = StrokeCap.Round)
            }
        }
        TreatKind.Cupcake -> {
            t.lines.forEach { (a, b) -> drawLine(ink.line, fit.px(a), fit.px(b), 2.5.dp.toPx(), cap = StrokeCap.Round) }
            t.inner?.let { drawPath(fit.path(it), ink.inner) }
            t.curves.forEach { drawPath(fit.path(it, close = false), ink.curve, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)) }
            sprinkles(t, fit, ink.dots)
        }
        TreatKind.Donut -> {
            t.inner?.let { drawPath(fit.path(it), ink.inner) }
            sprinkles(t, fit, ink.dots)
        }
        TreatKind.Lollipop -> {
            t.curves.forEach { drawPath(fit.path(it, close = false), ink.curve, style = Stroke(fit.px(0.05f), cap = StrokeCap.Round, join = StrokeJoin.Round)) }
            t.fills.forEach {
                drawPath(fit.path(it), ink.fill)
                drawPath(fit.path(it), ink.side.copy(alpha = 0.35f), style = Stroke(1.5.dp.toPx()))
            }
            glint(t, fit)
        }
        TreatKind.IceCream -> {
            t.lines.forEach { (a, b) -> drawLine(ink.line, fit.px(a), fit.px(b), 2.5.dp.toPx(), cap = StrokeCap.Round) }
            t.inner?.let { drawPath(fit.path(it), ink.inner) }
            sprinkles(t, fit, ink.dots)
            t.inner?.let { scoop ->
                val top = scoop.minBy { it.y + it.x * 0.4f }
                drawCircle(Color.White.copy(alpha = 0.55f), fit.px(0.026f), fit.px(top + (centroid(scoop) - top) * 0.3f))
            }
        }
        TreatKind.JellyBean -> {
            t.curves.forEach { drawPath(fit.path(it, close = false), ink.curve, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)) }
        }
        TreatKind.Croissant -> {
            t.curves.forEach { drawPath(fit.path(it, close = false), ink.curve, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)) }
            t.curves.forEach { c ->
                drawPath(fit.path(c.map { it + Offset(0.012f, 0.004f) }, close = false), Color.White.copy(alpha = 0.22f), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        TreatKind.Gummy -> {
            glint(t, fit)
            t.dots.forEach { (c, r) -> drawCircle(ink.dots[0], fit.px(r), fit.px(c)) }
        }
        TreatKind.Cookie, TreatKind.Chocolate, TreatKind.Pizza, TreatKind.Jelly -> Unit
    }
}

/** Sprinkles: short rods, each turned its own way, in the sprinkle colours. */
private fun DrawScope.sprinkles(t: Treat, fit: Fit, colours: List<Color>) {
    if (colours.isEmpty()) return
    t.dots.forEachIndexed { i, (c, r) ->
        val p = fit.px(c)
        val a = i * 1.3f
        val d = Offset(cos(a), sin(a)) * (fit.px(r) * 1.6f)
        drawLine(colours[i % colours.size], p - d, p + d, fit.px(r) * 1.1f, cap = StrokeCap.Round)
    }
}

/**
 * A soft shine towards the top left of the treat, the same on every piece. Placed by its box, not its centre of
 * area, so it gives nothing away in Kiss the center.
 */
private fun DrawScope.glint(t: Treat, fit: Fit) {
    val o = t.outline
    val minX = o.minOf { it.x }; val maxX = o.maxOf { it.x }; val minY = o.minOf { it.y }; val maxY = o.maxOf { it.y }
    val at = listOf(0.25f, 0.33f, 0.4f).map { f -> Offset(minX + (maxX - minX) * f, minY + (maxY - minY) * f) }.firstOrNull { contains(o, it) } ?: return
    val s = minOf(maxX - minX, maxY - minY)
    drawCircle(Color.White.copy(alpha = 0.55f), fit.px(s * 0.045f), fit.px(at))
    drawCircle(Color.White.copy(alpha = 0.4f), fit.px(s * 0.02f), fit.px(at + Offset(s * 0.08f, s * 0.04f)))
}
