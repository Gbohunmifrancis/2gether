package app.heartpact.ui.games

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Paint as GraphicsPaint
import app.heartpact.ui.theme.Art
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * Spot the difference: drawing the pictures SpotScenes builds. Every prop is a little sticker: its shapes lifted off
 * the page on a soft indigo shadow, a thin white die-cut keyline round them, a darker side underneath for depth, and a
 * gloss on top. The rooms, beaches and rooftops behind are flat and soft, so the props are what the eye goes to.
 * Drawn on a 300 by 200 page; the caller scales it to fit.
 */

/** A paint's colours: its face, its darker side, and a deep shade for small details. */
internal class Ink(val fill: Color, val side: Color, val deep: Color)

internal fun inkOf(paint: Paint): Ink = when (paint) {
    Paint.Rose -> Ink(Color(0xFFF47C9C), Color(0xFFD65A7D), Color(0xFFB03A62))
    Paint.Coral -> Ink(Color(0xFFF79A62), Color(0xFFDB7A41), Color(0xFFA9541F))
    Paint.Butter -> Ink(Color(0xFFF6CF57), Color(0xFFD9AE2F), Color(0xFF9C7A10))
    Paint.Mint -> Ink(Color(0xFF71C995), Color(0xFF4BA772), Color(0xFF2A7A4D))
    Paint.Teal -> Ink(Color(0xFF4DBDB8), Color(0xFF2F9993), Color(0xFF1C6E6A))
    Paint.Sky -> Ink(Color(0xFF72B1EA), Color(0xFF4E8DCB), Color(0xFF2E62A0))
    Paint.Peri -> Ink(Color(0xFF8E98E0), Color(0xFF6C77C9), Color(0xFF4D58BE))
    Paint.Indigo -> Ink(Color(0xFF3A4290), Color(0xFF262D66), Color(0xFF161A3D))
    Paint.Lilac -> Ink(Color(0xFFB791E0), Color(0xFF9670C6), Color(0xFF6B48A0))
    Paint.Cream -> Ink(Color(0xFFFFF4DE), Color(0xFFEAD9B8), Color(0xFFB79E73))
    Paint.White -> Ink(Color(0xFFFFFFFF), Color(0xFFE3E6F5), Color(0xFFA4A8C6))
    Paint.Toast -> Ink(Color(0xFFE3A562), Color(0xFFC2823F), Color(0xFF8E5626))
}

private val Leaf = Ink(Color(0xFF6CC48A), Color(0xFF479F66), Color(0xFF2E7A4A))
private val Wood = Ink(Color(0xFFD9A06C), Color(0xFFB57D4B), Color(0xFF8A5A30))
private val Face = Color(0xFF1F2557)
private val Cheek = Color(0xFFF2A3BA)

/** Which pass of a prop is being drawn: its shadow, its white keyline, or its colours. */
private enum class Pass { Shadow, Keyline, Paint }

/**
 * Draws one prop's shapes for one pass, in the prop's own units (its box is about 1 across, centred on 0). In the
 * shadow and keyline passes every solid shape draws as a silhouette; details only draw with the colours.
 */
private class Pen(val d: DrawScope, val pass: Pass, val k: Float) {
    val painting get() = pass == Pass.Paint

    private fun silhouette(path: Path) {
        when (pass) {
            Pass.Shadow -> d.drawPath(path, Art.Shadow)
            Pass.Keyline -> {
                d.drawPath(path, Color.White, style = Stroke(k * 2f, join = StrokeJoin.Round))
                d.drawPath(path, Color.White)
            }
            Pass.Paint -> Unit
        }
    }

    /** A flat shape. */
    fun flat(path: Path, colour: Color) = if (painting) d.drawPath(path, colour) else silhouette(path)

    /** A shape with depth: its side shows under the face, and an optional pattern on the face. */
    fun solid(path: Path, ink: Ink, pattern: Pattern = Pattern.Plain, depth: Float = 0.06f) {
        if (!painting) return silhouette(path)
        d.drawPath(path, ink.side)
        d.clipPath(path) {
            drawPath(Path().apply { addPath(path, Offset(-depth * 0.7f, -depth)) }, ink.fill)
            drawPattern(path.getBounds(), pattern, ink)
        }
    }

    /** A line with round ends ([width] in prop units); it has a keyline and a shadow like a shape. */
    fun line(path: Path, colour: Color, width: Float) = when (pass) {
        Pass.Shadow -> d.drawPath(path, Art.Shadow, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
        Pass.Keyline -> d.drawPath(path, Color.White, style = Stroke(width + k * 2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        Pass.Paint -> d.drawPath(path, colour, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    /** Small things on top (eyes, seeds, a gloss): colours only. */
    fun detail(block: DrawScope.() -> Unit) { if (painting) d.block() }

    fun gloss(x: Float, y: Float, rx: Float, ry: Float, alpha: Float = 0.55f) = detail {
        drawOval(Color.White.copy(alpha = alpha), Offset(x - rx, y - ry), androidx.compose.ui.geometry.Size(rx * 2, ry * 2))
    }

    private fun DrawScope.drawPattern(b: Rect, pattern: Pattern, ink: Ink) {
        val light = ink.fill.luminance() > 0.7f
        val mark = if (light) ink.deep.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.7f)
        when (pattern) {
            Pattern.Plain -> Unit
            Pattern.Stripes -> {
                val step = 0.2f
                var x = b.left - b.height
                while (x < b.right + step) {
                    drawLine(mark, Offset(x, b.bottom), Offset(x + b.height, b.top), 0.075f)
                    x += step
                }
            }
            Pattern.Dots -> {
                val step = 0.2f
                var y = b.top + step / 2
                var row = 0
                while (y < b.bottom) {
                    var x = b.left + (if (row % 2 == 0) step / 2 else step)
                    while (x < b.right) { drawCircle(mark, 0.048f, Offset(x, y)); x += step }
                    y += step * 0.86f; row++
                }
            }
        }
    }
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

// Shapes in prop units.
private fun oval(cx: Float, cy: Float, rx: Float, ry: Float) = Path().apply { addOval(Rect(cx - rx, cy - ry, cx + rx, cy + ry)) }
private fun circle(cx: Float, cy: Float, r: Float) = oval(cx, cy, r, r)
private fun box(l: Float, t: Float, r: Float, b: Float, radius: Float = 0f) = Path().apply { addRoundRect(RoundRect(l, t, r, b, CornerRadius(radius))) }
private fun poly(vararg xy: Float) = Path().apply {
    moveTo(xy[0], xy[1])
    var i = 2
    while (i < xy.size) { lineTo(xy[i], xy[i + 1]); i += 2 }
    close()
}
private fun union(vararg paths: Path): Path = paths.reduce { a, b -> Path().apply { op(a, b, PathOperation.Union) } }
private fun minus(a: Path, b: Path): Path = Path().apply { op(a, b, PathOperation.Difference) }
private fun curve(x0: Float, y0: Float, cx: Float, cy: Float, x1: Float, y1: Float) = Path().apply { moveTo(x0, y0); quadraticTo(cx, cy, x1, y1) }

private fun heartPath(s: Float = 1f, dx: Float = 0f, dy: Float = 0f) = Path().apply {
    moveTo(dx, dy + 0.42f * s)
    cubicTo(dx - 0.58f * s, dy + 0.02f * s, dx - 0.52f * s, dy - 0.46f * s, dx - 0.24f * s, dy - 0.44f * s)
    cubicTo(dx - 0.1f * s, dy - 0.43f * s, dx, dy - 0.34f * s, dx, dy - 0.24f * s)
    cubicTo(dx, dy - 0.34f * s, dx + 0.1f * s, dy - 0.43f * s, dx + 0.24f * s, dy - 0.44f * s)
    cubicTo(dx + 0.52f * s, dy - 0.46f * s, dx + 0.58f * s, dy + 0.02f * s, dx, dy + 0.42f * s)
    close()
}

private fun starPath(outer: Float, inner: Float, points: Int = 5, cx: Float = 0f, cy: Float = 0f) = Path().apply {
    for (i in 0 until points * 2) {
        val r = if (i % 2 == 0) outer else inner
        val a = -PI / 2 + i * PI / points
        val x = cx + (r * cos(a)).toFloat()
        val y = cy + (r * sin(a)).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/** The whole picture: the scene behind, then every prop, nearest last. [changed] draws the twin. */
internal fun DrawScope.drawSpotScene(scene: SpotScene, changed: Boolean) {
    val unit = size.width / SpotScenes.WIDTH
    val props = if (changed) scene.changed else scene.original
    withTransform({ scale(unit, unit, Offset.Zero) }) {
        clipRect(0f, 0f, SpotScenes.WIDTH, SpotScenes.HEIGHT) {
            drawBackdrop(scene.layout)
            // One soft shadow layer under every prop, so overlapping shapes don't darken twice.
            drawIntoCanvas { canvas ->
                canvas.saveLayer(Rect(0f, 0f, SpotScenes.WIDTH, SpotScenes.HEIGHT), GraphicsPaint().apply { alpha = 0.16f })
                props.forEach { p -> drawProp(p, Pass.Shadow, Offset(0.6f, 2.2f)) }
                canvas.restore()
            }
            props.forEach { p ->
                drawProp(p, Pass.Keyline)
                drawProp(p, Pass.Paint)
            }
        }
    }
}

private fun DrawScope.drawProp(p: Prop, pass: Pass, shift: Offset = Offset.Zero) {
    withTransform({
        translate(p.x + shift.x, p.y + shift.y)
        rotate(p.tilt, Offset.Zero)
        scale(if (p.flip) -p.size else p.size, p.size, Offset.Zero)
    }) {
        val pen = Pen(this, pass, 1.3f / p.size)
        pen.prop(p)
    }
}

private fun Pen.prop(p: Prop) {
    val ink = inkOf(p.paint)
    val pt = p.pattern
    when (p.kind) {
        PropKind.Heart -> { solid(heartPath(1.05f), ink, pt); gloss(-0.22f, -0.22f, 0.1f, 0.07f) }
        PropKind.Star -> { solid(starPath(0.52f, 0.25f), ink, depth = 0.05f); gloss(-0.08f, -0.16f, 0.06f, 0.04f) }
        PropKind.Flower -> {
            line(curve(0.02f, 0.64f, -0.06f, 0.2f, 0.06f, -0.3f), Leaf.side, 0.07f)
            solid(Path().apply { moveTo(0f, 0.32f); quadraticTo(0.3f, 0.1f, 0.38f, 0.22f); quadraticTo(0.22f, 0.42f, 0f, 0.32f); close() }, Leaf)
            val petals = 5 + p.variant % 2
            for (i in 0 until petals) {
                val a = -PI / 2 + i * 2 * PI / petals
                solid(circle(0.06f + 0.17f * cos(a).toFloat(), -0.34f + 0.17f * sin(a).toFloat(), 0.15f), ink, depth = 0.04f)
            }
            solid(circle(0.06f, -0.34f, 0.12f), inkOf(if (p.paint == Paint.Butter) Paint.Coral else Paint.Butter), depth = 0.03f)
        }
        PropKind.Balloon -> {
            line(Path().apply { moveTo(0.04f, 0.24f); cubicTo(-0.16f, 0.4f, 0.2f, 0.55f, -0.08f, 0.74f) }, Face.copy(alpha = 0.6f), 0.03f)
            flat(poly(-0.04f, 0.27f, 0.12f, 0.27f, 0.04f, 0.17f), ink.side)
            val body = Path().apply { addOval(Rect(-0.36f, -0.74f, 0.36f, 0.22f)) }
            solid(body, ink, pt, depth = 0.07f)
            gloss(-0.16f, -0.44f, 0.07f, 0.13f)
        }
        PropKind.Cloud -> {
            val c = union(circle(-0.42f, 0.1f, 0.27f), circle(-0.1f, -0.08f, 0.36f), circle(0.28f, 0f, 0.3f), circle(0.54f, 0.14f, 0.22f), box(-0.68f, 0.06f, 0.74f, 0.4f, 0.17f))
            solid(c, ink, depth = 0.05f)
        }
        PropKind.Bird -> {
            flat(poly(-0.36f, 0.02f, -0.62f, -0.12f, -0.58f, 0.18f), ink.side)
            solid(union(oval(-0.06f, 0.08f, 0.42f, 0.3f), circle(0.28f, -0.14f, 0.22f)), ink)
            flat(poly(0.48f, -0.2f, 0.64f, -0.1f, 0.47f, -0.06f), inkOf(Paint.Butter).side)
            solid(oval(-0.1f, 0.06f, 0.2f, 0.12f).also { it.transform(androidx.compose.ui.graphics.Matrix().apply { rotateZ(-14f) }) }, Ink(ink.side, ink.deep, ink.deep), depth = 0.03f)
            detail { drawCircle(Face, 0.045f, Offset(0.34f, -0.17f)); drawCircle(Cheek, 0.05f, Offset(0.3f, -0.06f)) }
        }
        PropKind.Butterfly -> {
            fun wing(cx: Float, cy: Float, rx: Float, ry: Float, deg: Float) = oval(cx, cy, rx, ry).also { it.transform(androidx.compose.ui.graphics.Matrix().apply { translate(cx, cy); rotateZ(deg); translate(-cx, -cy) }) }
            solid(union(wing(-0.27f, -0.14f, 0.26f, 0.3f, -18f), wing(0.27f, -0.14f, 0.26f, 0.3f, 18f), wing(-0.19f, 0.22f, 0.18f, 0.19f, 20f), wing(0.19f, 0.22f, 0.18f, 0.19f, -20f)), ink, pt)
            line(curve(-0.03f, -0.28f, -0.1f, -0.48f, -0.2f, -0.48f), Face, 0.035f)
            line(curve(0.03f, -0.28f, 0.1f, -0.48f, 0.2f, -0.48f), Face, 0.035f)
            flat(box(-0.055f, -0.3f, 0.055f, 0.36f, 0.05f), Face)
        }
        PropKind.Apple -> {
            line(curve(0f, -0.28f, 0.02f, -0.4f, 0.06f, -0.48f), Wood.deep, 0.06f)
            solid(oval(0.17f, -0.38f, 0.14f, 0.07f).also { it.transform(androidx.compose.ui.graphics.Matrix().apply { translate(0.17f, -0.38f); rotateZ(-25f); translate(-0.17f, 0.38f) }) }, Leaf, depth = 0.02f)
            solid(union(circle(-0.16f, 0.06f, 0.32f), circle(0.16f, 0.06f, 0.32f), oval(0f, 0.2f, 0.3f, 0.28f)), ink)
            gloss(-0.22f, -0.04f, 0.07f, 0.1f)
        }
        PropKind.Orange -> {
            solid(circle(0f, 0.04f, 0.45f), ink)
            solid(oval(0.14f, -0.42f, 0.14f, 0.07f), Leaf, depth = 0.02f)
            detail {
                for (i in 0 until 6) drawCircle(ink.side.copy(alpha = 0.6f), 0.025f, Offset(-0.2f + 0.08f * i, 0.2f - 0.05f * (i % 3)))
                drawCircle(Leaf.deep, 0.04f, Offset(0f, -0.38f))
            }
            gloss(-0.18f, -0.14f, 0.08f, 0.1f)
        }
        PropKind.Cherries -> {
            line(Path().apply { moveTo(-0.22f, 0.12f); quadraticTo(-0.1f, -0.2f, 0.06f, -0.44f); moveTo(0.2f, 0.02f); quadraticTo(0.14f, -0.2f, 0.06f, -0.44f) }, Wood.deep, 0.05f)
            solid(oval(0.22f, -0.42f, 0.16f, 0.07f), Leaf, depth = 0.02f)
            solid(circle(-0.22f, 0.3f, 0.21f), ink)
            solid(circle(0.2f, 0.2f, 0.21f), ink)
            gloss(-0.29f, 0.22f, 0.05f, 0.06f); gloss(0.13f, 0.12f, 0.05f, 0.06f)
        }
        PropKind.Strawberry -> {
            solid(Path().apply {
                moveTo(-0.38f, -0.22f); cubicTo(-0.42f, 0.12f, -0.12f, 0.46f, 0f, 0.52f)
                cubicTo(0.12f, 0.46f, 0.42f, 0.12f, 0.38f, -0.22f); quadraticTo(0f, -0.4f, -0.38f, -0.22f); close()
            }, ink)
            solid(starPath(0.24f, 0.1f, 5, 0f, -0.3f), Leaf, depth = 0.02f)
            detail { for (i in 0 until 7) drawOval(Color(0xFFFFE9A8), Offset(-0.22f + (i % 4) * 0.14f + (i / 4) * 0.07f - 0.03f, -0.06f + (i / 4) * 0.2f), androidx.compose.ui.geometry.Size(0.05f, 0.07f)) }
        }
        PropKind.Cupcake -> {
            val liner = Ink(Color(0xFFFFF8EC), Color(0xFFEFE2CB), ink.deep)
            solid(poly(-0.36f, 0.04f, 0.36f, 0.04f, 0.26f, 0.54f, -0.26f, 0.54f), liner, pt)
            detail { for (i in -2..2) drawLine(liner.side, Offset(i * 0.12f, 0.1f), Offset(i * 0.09f, 0.5f), 0.025f) }
            solid(union(oval(0f, 0.02f, 0.44f, 0.16f), oval(0f, -0.14f, 0.34f, 0.16f), oval(0f, -0.28f, 0.2f, 0.12f)), ink)
            solid(circle(0.02f, -0.44f, 0.1f), inkOf(if (p.paint == Paint.Rose) Paint.Butter else Paint.Rose), depth = 0.03f)
            gloss(-0.16f, -0.16f, 0.08f, 0.04f)
        }
        PropKind.Donut -> {
            val dough = Path().apply { fillType = PathFillType.EvenOdd; addOval(Rect(-0.54f, -0.36f, 0.54f, 0.38f)); addOval(Rect(-0.14f, -0.07f, 0.14f, 0.09f)) }
            solid(dough, inkOf(Paint.Toast))
            val icing = Path().apply { fillType = PathFillType.EvenOdd; addOval(Rect(-0.45f, -0.33f, 0.45f, 0.24f)); addOval(Rect(-0.17f, -0.1f, 0.17f, 0.11f)) }
            detail {
                drawPath(icing, ink.fill)
                val marks = listOf(Paint.Butter, Paint.Sky, Paint.White, Paint.Mint).map { inkOf(it).fill }
                when (pt) {
                    Pattern.Dots -> for (i in 0 until 9) drawCircle(marks[i % 4], 0.035f, Offset(-0.34f + (i % 5) * 0.17f, if (i < 5) -0.2f else 0.14f))
                    Pattern.Stripes -> for (i in 0 until 9) {
                        val x = -0.34f + (i % 5) * 0.17f; val y = if (i < 5) -0.2f else 0.15f
                        drawLine(marks[i % 4], Offset(x - 0.04f, y + 0.02f * (i % 2)), Offset(x + 0.04f, y - 0.02f * (i % 2)), 0.035f, cap = StrokeCap.Round)
                    }
                    Pattern.Plain -> Unit
                }
            }
            gloss(-0.26f, -0.2f, 0.08f, 0.04f)
        }
        PropKind.Croissant -> {
            val parts = listOf(-0.46f to 0.12f, -0.26f to -0.06f, 0f to -0.12f, 0.26f to -0.06f, 0.46f to 0.12f)
            parts.forEachIndexed { i, (x, y) -> solid(oval(x, y, if (i == 2) 0.22f else 0.17f, if (i == 2) 0.24f else 0.18f), ink, depth = 0.04f) }
            detail { parts.drop(1).forEach { (x, y) -> drawLine(ink.deep.copy(alpha = 0.5f), Offset(x - 0.08f, y - 0.12f), Offset(x - 0.12f, y + 0.12f), 0.025f, cap = StrokeCap.Round) } }
        }
        PropKind.Mug -> {
            line(circle(0.33f, 0.04f, 0.16f), ink.side, 0.09f)
            solid(box(-0.42f, -0.36f, 0.3f, 0.46f, 0.1f), ink, pt)
            detail {
                drawOval(ink.deep, Offset(-0.38f, -0.4f), androidx.compose.ui.geometry.Size(0.64f, 0.1f))
                drawLine(Color.White.copy(alpha = 0.7f), Offset(-0.2f, -0.52f), Offset(-0.18f, -0.62f), 0.035f, cap = StrokeCap.Round)
            }
            gloss(-0.3f, -0.12f, 0.04f, 0.14f)
        }
        PropKind.Teapot -> {
            line(circle(-0.5f, 0.06f, 0.15f), ink.side, 0.08f)
            solid(Path().apply { moveTo(0.3f, 0.02f); quadraticTo(0.52f, 0.04f, 0.66f, -0.22f); lineTo(0.7f, -0.16f); quadraticTo(0.58f, 0.18f, 0.32f, 0.3f); close() }, ink)
            solid(oval(0f, 0.12f, 0.46f, 0.38f), ink, pt)
            solid(oval(0f, -0.22f, 0.26f, 0.08f), Ink(ink.side, ink.deep, ink.deep), depth = 0.02f)
            solid(circle(0f, -0.33f, 0.07f), ink, depth = 0.02f)
            gloss(-0.24f, -0.02f, 0.08f, 0.12f)
        }
        PropKind.Jar -> {
            val glass = Ink(Color(0xFFEAF4FB), Color(0xFFCDE1EE), Color(0xFF9DB9CC))
            solid(box(-0.34f, -0.3f, 0.34f, 0.5f, 0.1f), glass)
            detail { drawRoundRect(ink.fill, Offset(-0.28f, -0.12f), androidx.compose.ui.geometry.Size(0.56f, 0.56f), CornerRadius(0.07f)) }
            detail { drawRoundRect(Color.White, Offset(-0.18f, 0.04f), androidx.compose.ui.geometry.Size(0.36f, 0.2f), CornerRadius(0.04f)) }
            solid(box(-0.38f, -0.48f, 0.38f, -0.28f, 0.06f), inkOf(Paint.Cream))
            gloss(-0.22f, -0.1f, 0.04f, 0.14f, 0.7f)
        }
        PropKind.Bottle -> {
            solid(union(box(-0.24f, -0.16f, 0.24f, 0.68f, 0.1f), poly(-0.24f, -0.06f, -0.09f, -0.34f, 0.09f, -0.34f, 0.24f, -0.06f), box(-0.09f, -0.58f, 0.09f, -0.2f)), ink)
            solid(box(-0.11f, -0.7f, 0.11f, -0.56f, 0.04f), inkOf(Paint.Toast), depth = 0.02f)
            detail { drawRect(Color(0xFFFFF4DE), Offset(-0.24f, 0.12f), androidx.compose.ui.geometry.Size(0.48f, 0.22f)) }
            gloss(-0.13f, -0.02f, 0.035f, 0.12f)
        }
        PropKind.Vase -> {
            val blooms = listOf(Paint.Rose, Paint.Butter, Paint.Lilac, Paint.Coral)
            line(Path().apply { moveTo(0f, 0f); lineTo(-0.18f, -0.42f); moveTo(0f, 0f); lineTo(0.04f, -0.56f); moveTo(0f, 0f); lineTo(0.22f, -0.38f) }, Leaf.side, 0.045f)
            listOf(Triple(-0.18f, -0.44f, 0), Triple(0.04f, -0.58f, 1), Triple(0.22f, -0.4f, 2)).forEach { (x, y, i) ->
                solid(circle(x, y, 0.12f), inkOf(blooms[(i + p.variant) % blooms.size]), depth = 0.03f)
                detail { drawCircle(Color(0xFFFFF4DE), 0.045f, Offset(x, y)) }
            }
            solid(Path().apply {
                moveTo(-0.14f, -0.08f); lineTo(0.14f, -0.08f); cubicTo(0.12f, 0.06f, 0.4f, 0.16f, 0.36f, 0.42f)
                quadraticTo(0.32f, 0.7f, 0f, 0.7f); quadraticTo(-0.32f, 0.7f, -0.36f, 0.42f); cubicTo(-0.4f, 0.16f, -0.12f, 0.06f, -0.14f, -0.08f); close()
            }, ink, pt)
            gloss(-0.2f, 0.3f, 0.05f, 0.12f)
        }
        PropKind.Plant -> {
            fun leaf(x: Float, y: Float, deg: Float, len: Float) = oval(x, y - len / 2f, 0.11f, len / 2f).also {
                it.transform(androidx.compose.ui.graphics.Matrix().apply { translate(x, y); rotateZ(deg); translate(-x, -y) })
            }
            solid(union(leaf(0f, 0.1f, -40f, 0.5f), leaf(0f, 0.1f, -12f, 0.66f), leaf(0f, 0.1f, 14f, 0.72f), leaf(0.02f, 0.1f, 42f, 0.56f), leaf(0.02f, 0.12f, 66f, 0.4f)), Leaf)
            solid(poly(-0.3f, 0.12f, 0.3f, 0.12f, 0.22f, 0.65f, -0.22f, 0.65f), ink, pt)
            solid(box(-0.34f, 0.06f, 0.34f, 0.2f, 0.04f), ink, depth = 0.03f)
        }
        PropKind.Candle -> {
            detail { drawCircle(Color(0xFFFFE08A).copy(alpha = 0.45f), 0.26f, Offset(0f, -0.4f)) }
            solid(box(-0.19f, -0.24f, 0.19f, 0.6f, 0.05f), ink, pt)
            line(curve(0f, -0.24f, 0f, -0.28f, 0f, -0.32f), Face, 0.03f)
            solid(Path().apply { moveTo(0f, -0.6f); quadraticTo(0.15f, -0.38f, 0f, -0.3f); quadraticTo(-0.15f, -0.38f, 0f, -0.6f); close() }, inkOf(Paint.Butter), depth = 0.02f)
            detail { drawOval(inkOf(Paint.Coral).fill, Offset(-0.04f, -0.42f), androidx.compose.ui.geometry.Size(0.08f, 0.1f)) }
            gloss(-0.1f, 0f, 0.03f, 0.14f)
        }
        PropKind.Book -> {
            solid(box(-0.21f, -0.6f, 0.21f, 0.6f, 0.04f), ink, pt)
            detail {
                drawRect(Color(0xFFFFF4DE), Offset(-0.21f, -0.46f), androidx.compose.ui.geometry.Size(0.42f, 0.06f))
                drawRect(Color(0xFFFFF4DE), Offset(-0.21f, 0.4f), androidx.compose.ui.geometry.Size(0.42f, 0.06f))
            }
        }
        PropKind.BookStack -> {
            val under = listOf(Paint.Sky, Paint.Mint, Paint.Butter, Paint.Lilac, Paint.Rose).filter { it != p.paint }
            fun book(l: Float, t: Float, r: Float, b: Float, ink: Ink) {
                solid(box(l, t, r, b, 0.04f), ink, depth = 0.03f)
                detail { drawRect(Color(0xFFFFF4DE), Offset(r - 0.08f, t + 0.04f), androidx.compose.ui.geometry.Size(0.05f, b - t - 0.08f)) }
            }
            book(-0.62f, 0.12f, 0.56f, 0.37f, inkOf(under[p.variant % under.size]))
            book(-0.5f, -0.12f, 0.5f, 0.12f, inkOf(under[(p.variant + 1) % under.size]))
            book(-0.36f, -0.37f, 0.6f, -0.12f, ink)
        }
        PropKind.Frame -> {
            solid(box(-0.5f, -0.42f, 0.5f, 0.42f, 0.05f), ink, depth = 0.04f)
            val inner = box(-0.38f, -0.3f, 0.38f, 0.3f, 0.02f)
            detail {
                drawPath(inner, Color(0xFFEAF4FB))
                clipPath(inner) {
                    when (p.variant) {
                        0 -> drawPath(heartPath(0.42f, 0f, 0.02f), inkOf(Paint.Rose).fill)
                        1 -> {
                            drawPath(poly(-0.42f, 0.3f, -0.12f, -0.12f, 0.08f, 0.14f, 0.2f, 0f, 0.42f, 0.3f), Leaf.fill)
                            drawCircle(inkOf(Paint.Butter).fill, 0.08f, Offset(0.2f, -0.14f))
                        }
                        2 -> {
                            drawRect(inkOf(Paint.Sky).fill.copy(alpha = 0.5f), Offset(-0.4f, -0.32f), androidx.compose.ui.geometry.Size(0.8f, 0.64f))
                            drawCircle(inkOf(Paint.Butter).fill, 0.16f, Offset(0f, 0.02f))
                        }
                        else -> {
                            drawPath(heartPath(0.3f, -0.12f, 0.04f), inkOf(Paint.Indigo).fill)
                            drawPath(heartPath(0.3f, 0.14f, 0.0f), inkOf(Paint.Peri).fill)
                        }
                    }
                }
            }
        }
        PropKind.Clock -> {
            solid(circle(0f, 0f, 0.48f), ink)
            detail {
                drawCircle(Color.White, 0.36f, Offset(0f, -0.02f))
                for (i in 0 until 4) {
                    val a = i * PI / 2
                    drawCircle(Face.copy(alpha = 0.5f), 0.03f, Offset((0.28f * cos(a)).toFloat(), -0.02f + (0.28f * sin(a)).toFloat()))
                }
                drawLine(Face, Offset(0f, -0.02f), Offset(0f, -0.24f), 0.045f, cap = StrokeCap.Round)
                drawLine(Face, Offset(0f, -0.02f), Offset(0.16f, 0.06f), 0.045f, cap = StrokeCap.Round)
                drawCircle(inkOf(Paint.Rose).fill, 0.04f, Offset(0f, -0.02f))
            }
        }
        PropKind.Lamp -> {
            solid(box(-0.04f, -0.1f, 0.04f, 0.56f), Wood, depth = 0.01f)
            solid(oval(0f, 0.6f, 0.3f, 0.07f), Wood, depth = 0.02f)
            solid(poly(-0.24f, -0.66f, 0.24f, -0.66f, 0.44f, -0.06f, -0.44f, -0.06f), ink, pt)
            gloss(-0.14f, -0.44f, 0.04f, 0.12f)
        }
        PropKind.Cushion -> {
            solid(Path().apply {
                moveTo(-0.52f, -0.42f); quadraticTo(0f, -0.3f, 0.52f, -0.42f); quadraticTo(0.42f, 0f, 0.52f, 0.42f)
                quadraticTo(0f, 0.3f, -0.52f, 0.42f); quadraticTo(-0.42f, 0f, -0.52f, -0.42f); close()
            }, ink, pt)
            detail { drawCircle(ink.deep, 0.05f, Offset(0f, 0f)) }
            gloss(-0.24f, -0.2f, 0.1f, 0.05f)
        }
        PropKind.Cat -> {
            line(Path().apply { moveTo(-0.26f, 0.48f); cubicTo(-0.64f, 0.5f, -0.6f, 0.08f, -0.42f, 0.02f) }, ink.side, 0.11f)
            solid(union(oval(-0.02f, 0.22f, 0.36f, 0.36f), circle(0.1f, -0.26f, 0.27f), poly(-0.12f, -0.36f, -0.08f, -0.6f, 0.08f, -0.44f), poly(0.14f, -0.46f, 0.32f, -0.6f, 0.34f, -0.34f)), ink)
            detail {
                val eye = if (ink.fill.luminance() < 0.3f) Color.White else Face
                drawArc(eye, 200f, 140f, false, Offset(-0.06f, -0.34f), androidx.compose.ui.geometry.Size(0.12f, 0.1f), style = Stroke(0.035f, cap = StrokeCap.Round))
                drawArc(eye, 200f, 140f, false, Offset(0.14f, -0.34f), androidx.compose.ui.geometry.Size(0.12f, 0.1f), style = Stroke(0.035f, cap = StrokeCap.Round))
                drawCircle(Cheek, 0.04f, Offset(0.11f, -0.2f))
                drawCircle(Cheek.copy(alpha = 0.7f), 0.045f, Offset(-0.07f, -0.17f))
                drawCircle(Cheek.copy(alpha = 0.7f), 0.045f, Offset(0.3f, -0.17f))
                drawOval(Color.White.copy(alpha = 0.35f), Offset(-0.14f, 0.12f), androidx.compose.ui.geometry.Size(0.24f, 0.3f))
            }
        }
        PropKind.Sun -> {
            for (i in 0 until 10) {
                val a = i * PI / 5
                val c = cos(a).toFloat(); val s = sin(a).toFloat()
                line(Path().apply { moveTo(0.4f * c, 0.4f * s); lineTo(0.54f * c, 0.54f * s) }, ink.side, 0.08f)
            }
            solid(circle(0f, 0f, 0.33f), ink)
            detail {
                drawCircle(Face, 0.035f, Offset(-0.1f, -0.04f)); drawCircle(Face, 0.035f, Offset(0.1f, -0.04f))
                drawArc(Face, 20f, 140f, false, Offset(-0.08f, -0.02f), androidx.compose.ui.geometry.Size(0.16f, 0.12f), style = Stroke(0.03f, cap = StrokeCap.Round))
                drawCircle(Cheek, 0.05f, Offset(-0.18f, 0.06f)); drawCircle(Cheek, 0.05f, Offset(0.18f, 0.06f))
            }
        }
        PropKind.Moon -> {
            solid(minus(circle(0f, 0f, 0.47f), circle(0.24f, -0.12f, 0.38f)), ink, depth = 0.04f)
            detail { drawCircle(ink.side.copy(alpha = 0.6f), 0.05f, Offset(-0.24f, 0.08f)); drawCircle(ink.side.copy(alpha = 0.6f), 0.035f, Offset(-0.12f, 0.28f)) }
        }
        PropKind.Mushroom -> {
            solid(box(-0.17f, -0.04f, 0.17f, 0.48f, 0.12f), inkOf(Paint.Cream))
            solid(Path().apply { moveTo(-0.5f, 0.06f); cubicTo(-0.5f, -0.62f, 0.5f, -0.62f, 0.5f, 0.06f); quadraticTo(0f, 0f, -0.5f, 0.06f); close() }, ink, pt)
            gloss(-0.22f, -0.24f, 0.08f, 0.05f)
        }
        PropKind.Shell -> {
            solid(Path().apply {
                moveTo(0f, 0.42f); lineTo(-0.48f, -0.04f); cubicTo(-0.46f, -0.5f, 0.46f, -0.5f, 0.48f, -0.04f); close()
            }, ink)
            solid(box(-0.14f, 0.3f, 0.14f, 0.44f, 0.04f), ink, depth = 0.02f)
            detail { for (i in -2..2) drawLine(ink.deep.copy(alpha = 0.45f), Offset(0f, 0.36f), Offset(i * 0.17f, -0.3f + (i * i) * 0.03f), 0.03f, cap = StrokeCap.Round) }
        }
        PropKind.Starfish -> {
            solid(starPath(0.52f, 0.24f), ink)
            detail { for (i in 0 until 5) { val a = -PI / 2 + i * 2 * PI / 5; drawCircle(Color.White.copy(alpha = 0.6f), 0.035f, Offset((0.24f * cos(a)).toFloat(), (0.24f * sin(a)).toFloat())) } }
        }
        PropKind.BeachBall -> {
            val ball = circle(0f, 0f, 0.47f)
            solid(ball, inkOf(Paint.White))
            detail {
                clipPath(ball) {
                    drawPath(Path().apply { moveTo(0f, 0f); lineTo(-0.7f, -0.5f); lineTo(-0.2f, -0.8f); close() }, ink.fill)
                    drawPath(Path().apply { moveTo(0f, 0f); lineTo(0.7f, -0.5f); lineTo(0.8f, 0.2f); close() }, inkOf(Paint.Butter).fill)
                    drawPath(Path().apply { moveTo(0f, 0f); lineTo(-0.3f, 0.8f); lineTo(0.3f, 0.8f); close() }, inkOf(Paint.Sky).fill)
                }
                drawCircle(Color.White, 0.07f, Offset.Zero)
            }
            gloss(-0.18f, -0.2f, 0.08f, 0.06f, 0.7f)
        }
        PropKind.Bucket -> {
            line(Path().apply { moveTo(0.1f, -0.2f); lineTo(0.38f, -0.6f) }, Wood.side, 0.06f)
            solid(Path().apply { moveTo(0.34f, -0.56f); lineTo(0.5f, -0.48f); lineTo(0.42f, -0.36f); lineTo(0.3f, -0.46f); close() }, inkOf(Paint.Butter), depth = 0.02f)
            line(Path().apply { moveTo(-0.34f, -0.18f); quadraticTo(0f, -0.62f, 0.34f, -0.18f) }, Face.copy(alpha = 0.7f), 0.035f)
            solid(poly(-0.4f, -0.2f, 0.4f, -0.2f, 0.3f, 0.5f, -0.3f, 0.5f), ink, pt)
            solid(box(-0.43f, -0.26f, 0.43f, -0.14f, 0.04f), ink, depth = 0.02f)
        }
        PropKind.Umbrella -> {
            line(Path().apply { moveTo(-0.14f, -0.3f); lineTo(0.08f, 0.84f) }, Face.copy(alpha = 0.8f), 0.05f)
            val canopy = Path().apply {
                moveTo(-0.84f, -0.06f); cubicTo(-0.76f, -0.84f, 0.5f, -0.96f, 0.6f, -0.24f)
                quadraticTo(0.4f, -0.34f, 0.24f, -0.18f); quadraticTo(0.04f, -0.32f, -0.16f, -0.12f)
                quadraticTo(-0.38f, -0.26f, -0.56f, -0.06f); quadraticTo(-0.72f, -0.2f, -0.84f, -0.06f); close()
            }
            solid(canopy, ink, pt, depth = 0.05f)
            solid(circle(-0.18f, -0.72f, 0.05f), ink, depth = 0.02f)
        }
        PropKind.Window -> {
            solid(box(-0.42f, -0.5f, 0.42f, 0.5f, 0.04f), Ink(Color(0xFF1A1F4D), Color(0xFF12153A), Color(0xFF0C0F2A)), depth = 0.02f)
            detail {
                val lit = ink.fill.luminance() > 0.5f
                if (lit) drawRect(ink.fill.copy(alpha = 0.2f), Offset(-0.5f, -0.58f), androidx.compose.ui.geometry.Size(1f, 1.16f))
                drawRect(ink.fill, Offset(-0.34f, -0.42f), androidx.compose.ui.geometry.Size(0.68f, 0.84f))
                drawLine(Color(0xFF1A1F4D), Offset(0f, -0.42f), Offset(0f, 0.42f), 0.07f)
                drawLine(Color(0xFF1A1F4D), Offset(-0.34f, 0f), Offset(0.34f, 0f), 0.07f)
            }
        }
        PropKind.Bush -> {
            solid(union(circle(-0.38f, 0.12f, 0.3f), circle(-0.06f, -0.06f, 0.38f), circle(0.34f, 0.08f, 0.32f), box(-0.66f, 0.1f, 0.66f, 0.44f, 0.16f)), ink)
            detail { listOf(-0.3f to 0f, 0.06f to -0.2f, 0.3f to 0.12f, -0.1f to 0.22f).forEach { (x, y) -> drawCircle(inkOf(Paint.Rose).fill, 0.05f, Offset(x, y)) } }
        }
        PropKind.Pendant -> {
            line(Path().apply { moveTo(0f, -0.3f); lineTo(0f, -p.y / p.size - 0.5f) }, Face.copy(alpha = 0.7f), 0.03f)
            detail { drawCircle(Color(0xFFFFE08A).copy(alpha = 0.5f), 0.3f, Offset(0f, 0.18f)) }
            solid(circle(0f, 0.1f, 0.13f), inkOf(Paint.Cream), depth = 0.02f)
            solid(Path().apply { moveTo(-0.46f, 0.12f); cubicTo(-0.46f, -0.42f, 0.46f, -0.42f, 0.46f, 0.12f); close() }, ink, pt)
        }
    }
}

/** The scene behind the props: a room, a beach, a garden, rooftops at night. */
private fun DrawScope.drawBackdrop(layout: SceneLayout) {
    val w = SpotScenes.WIDTH
    val h = SpotScenes.HEIGHT
    fun sky(top: Color, bottom: Color, to: Float) = drawRect(Brush.verticalGradient(listOf(top, bottom), 0f, to), Offset.Zero, androidx.compose.ui.geometry.Size(w, to))
    fun band(colour: Color, top: Float, bottom: Float) = drawRect(colour, Offset(0f, top), androidx.compose.ui.geometry.Size(w, bottom - top))
    fun rect(colour: Color, l: Float, t: Float, r: Float, b: Float, radius: Float = 0f) =
        drawRoundRect(colour, Offset(l, t), androidx.compose.ui.geometry.Size(r - l, b - t), CornerRadius(radius))
    fun boards(top: Float, colour: Color, line: Color) {
        band(colour, top, h)
        var y = top + 12f
        var row = 0
        while (y < h) {
            drawLine(line, Offset(0f, y), Offset(w, y), 0.8f)
            var x = if (row % 2 == 0) 40f else 0f
            while (x < w) { drawLine(line, Offset(x, y - 12f), Offset(x, y), 0.8f); x += 80f }
            y += 12f; row++
        }
    }
    fun window(l: Float, t: Float, r: Float, b: Float, night: Boolean = false) {
        rect(Color.White, l - 4f, t - 4f, r + 4f, b + 4f, 4f)
        drawRect(Brush.verticalGradient(if (night) listOf(Color(0xFF2E3577), Color(0xFF4D58BE)) else listOf(Color(0xFFA9D6F5), Color(0xFFE0F1FC)), t, b), Offset(l, t), androidx.compose.ui.geometry.Size(r - l, b - t))
        drawLine(Color.White, Offset((l + r) / 2f, t), Offset((l + r) / 2f, b), 3f)
        drawLine(Color.White, Offset(l, (t + b) / 2f), Offset(r, (t + b) / 2f), 3f)
    }
    fun plank(l: Float, y: Float, r: Float) {
        rect(Wood.side, l, y + 2f, r, y + 6f, 1.5f)
        rect(Wood.fill, l, y, r, y + 3.5f, 1.5f)
        drawLine(Wood.side, Offset(l + 8f, y + 5f), Offset(l + 8f, y + 14f), 3f, cap = StrokeCap.Round)
        drawLine(Wood.side, Offset(r - 8f, y + 5f), Offset(r - 8f, y + 14f), 3f, cap = StrokeCap.Round)
    }
    fun checks(quad: Path, base: Color, check: Color, cell: Float = 14f) {
        drawPath(quad, base)
        clipPath(quad) {
            val b = quad.getBounds()
            var y = b.top
            var row = 0
            while (y < b.bottom) {
                drawRect(check, Offset(b.left, y), androidx.compose.ui.geometry.Size(b.width, cell / 2f))
                var x = b.left + (row % 2) * cell
                while (x < b.right) { drawRect(check, Offset(x, b.top), androidx.compose.ui.geometry.Size(cell / 2f, b.height)); x += cell }
                y += cell; row++
            }
        }
    }
    val tones = listOf(Paint.Rose, Paint.Sky, Paint.Mint)
    val tone = inkOf(tones[layout.tone % tones.size])
    val soft = lerp(tone.fill, Color.White, 0.55f)
    when (layout.kind) {
        SceneKind.Picnic -> {
            sky(Color(0xFFA9D6F5), Color(0xFFE7F4FD), 130f)
            drawPath(Path().apply { moveTo(0f, 118f); quadraticTo(90f, 84f, 190f, 112f); quadraticTo(250f, 96f, w, 104f); lineTo(w, h); lineTo(0f, h); close() }, Color(0xFFB5E2BE))
            drawRect(Brush.verticalGradient(listOf(Color(0xFF9DDAA9), Color(0xFF7CC690)), 120f, h), Offset(0f, 122f), androidx.compose.ui.geometry.Size(w, h - 122f))
            drawPath(Path().apply { moveTo(0f, 126f); quadraticTo(150f, 112f, w, 124f); lineTo(w, 132f); lineTo(0f, 132f); close() }, Color(0xFF9DDAA9))
            rect(Wood.side, 26f, 70f, 38f, 150f, 3f)
            rect(Wood.fill, 26f, 70f, 34f, 150f, 3f)
            listOf(Triple(16f, 52f, 24f), Triple(44f, 46f, 26f), Triple(30f, 30f, 26f), Triple(54f, 70f, 16f), Triple(8f, 74f, 16f)).forEach { (x, y, r) -> drawCircle(Color(0xFF57A873), r, Offset(x, y + 3f)) }
            listOf(Triple(16f, 52f, 24f), Triple(44f, 46f, 26f), Triple(30f, 30f, 26f), Triple(54f, 70f, 16f), Triple(8f, 74f, 16f)).forEach { (x, y, r) -> drawCircle(Color(0xFF6FC08A), r, Offset(x, y)) }
            val blanket = Path().apply { moveTo(74f, 140f); lineTo(226f, 140f); lineTo(248f, 198f); lineTo(52f, 198f); close() }
            drawPath(Path().apply { addPath(blanket, Offset(0f, 3f)) }, Color(0x22000000))
            checks(blanket, Color.White, soft)
        }
        SceneKind.Bedroom -> {
            band(Color(0xFFE9E6F8), 0f, 142f)
            var y = 10f
            var row = 0
            while (y < 140f) {
                var x = if (row % 2 == 0) 10f else 25f
                while (x < w) { drawCircle(soft.copy(alpha = 0.55f), 2f, Offset(x, y)); x += 30f }
                y += 18f; row++
            }
            boards(142f, Color(0xFFEFCDA9), Color(0xFFDDB48B))
            band(Color.White, 138f, 143f)
            drawOval(soft, Offset(158f, 176f), androidx.compose.ui.geometry.Size(136f, 20f))
            window(222f, 22f, 282f, 96f, night = layout.tone == 2)
            rect(lerp(tone.fill, Color.White, 0.3f), 212f, 14f, 226f, 106f, 6f)
            rect(lerp(tone.fill, Color.White, 0.3f), 278f, 14f, 292f, 106f, 6f)
            rect(Color.White, 216f, 98f, 288f, 103f, 2f)
            plank(18f, 58f, 122f)
            // The bed: headboard, mattress, a striped blanket.
            rect(Wood.side, 8f, 84f, 150f, 130f, 14f)
            rect(Wood.fill, 10f, 84f, 148f, 126f, 14f)
            rect(Color.White, 4f, 124f, 152f, 142f, 6f)
            rect(lerp(inkOf(Paint.Sky).fill, Color.White, 0.25f), 4f, 132f, 152f, 166f, 6f)
            for (i in 0 until 6) drawLine(Color.White.copy(alpha = 0.6f), Offset(16f + i * 26f, 134f), Offset(16f + i * 26f, 164f), 3f)
            rect(Wood.side, 8f, 166f, 14f, 180f); rect(Wood.side, 142f, 166f, 148f, 180f)
        }
        SceneKind.Kitchen -> {
            band(Color(0xFFDDEEFA), 0f, 128f)
            var y = 0f
            while (y < 128f) { drawLine(Color.White, Offset(0f, y), Offset(w, y), 1.2f); y += 16f }
            var x = 0f
            while (x < w) { drawLine(Color.White, Offset(x, 0f), Offset(x, 128f), 1.2f); x += 16f }
            window(26f, 16f, 104f, 82f)
            rect(lerp(tone.fill, Color.White, 0.3f), 18f, 8f, 112f, 18f, 4f)
            plank(22f, 86f, 108f)
            plank(148f, 52f, 296f)
            rect(Wood.side, 0f, 126f, w, 136f)
            rect(Wood.fill, 0f, 126f, w, 132f)
            band(soft, 136f, h)
            listOf(4f, 78f, 152f, 226f).forEach { l ->
                rect(lerp(soft, Color.White, 0.35f), l + 4f, 144f, l + 66f, 194f, 6f)
                drawCircle(Wood.side, 3f, Offset(l + 58f, 152f))
            }
        }
        SceneKind.Beach -> {
            sky(Color(0xFF9FD2F3), Color(0xFFE2F2FC), 100f)
            drawRect(Brush.verticalGradient(listOf(Color(0xFF6CC8CC), Color(0xFF4DB3BC)), 96f, 132f), Offset(0f, 96f), androidx.compose.ui.geometry.Size(w, 36f))
            for (i in 0 until 7) {
                val cx = 20f + i * 44f + (i % 2) * 10f
                val cy = 104f + (i % 3) * 8f
                drawArc(Color.White.copy(alpha = 0.7f), 200f, 140f, false, Offset(cx - 9f, cy - 3f), androidx.compose.ui.geometry.Size(18f, 8f), style = Stroke(1.6f, cap = StrokeCap.Round))
            }
            drawPath(Path().apply { moveTo(0f, 130f); quadraticTo(80f, 122f, 150f, 130f); quadraticTo(220f, 138f, w, 126f); lineTo(w, h); lineTo(0f, h); close() }, Color(0xFFF8E3A8))
            drawPath(Path().apply { moveTo(0f, 130f); quadraticTo(80f, 122f, 150f, 130f); quadraticTo(220f, 138f, w, 126f) }, Color.White.copy(alpha = 0.8f), style = Stroke(3f, cap = StrokeCap.Round))
            for (i in 0 until 26) drawCircle(Color(0xFFE6C77A), 0.9f, Offset(8f + (i * 53 % 290).toFloat(), 142f + (i * 37 % 54).toFloat()))
        }
        SceneKind.Garden -> {
            sky(Color(0xFFB4DDF7), Color(0xFFEAF5FD), 124f)
            band(Color(0xFF9DDAA9), 118f, h)
            drawRect(Brush.verticalGradient(listOf(Color(0xFF9DDAA9), Color(0xFF7CC690)), 118f, h), Offset(0f, 118f), androidx.compose.ui.geometry.Size(w, h - 118f))
            rect(Color(0xFFDDE0EE), 0f, 93f, w, 99f)
            rect(Color(0xFFDDE0EE), 0f, 112f, w, 118f)
            var x = 2f
            while (x < w) {
                drawPath(poly(x, 128f, x, 86f, x + 6f, 79f, x + 12f, 86f, x + 12f, 128f), Color(0xFFDDE0EE))
                drawPath(poly(x, 126f, x, 86f, x + 6f, 79f, x + 11f, 86f, x + 11f, 126f), Color.White)
                x += 19f
            }
            rect(Color.White, 0f, 92f, w, 96f)
            for (i in 0 until 14) {
                val gx = 10f + i * 21f
                drawLine(Color(0xFF6CB880), Offset(gx, 132f + (i % 3) * 20f), Offset(gx + 2f, 126f + (i % 3) * 20f), 1.4f, cap = StrokeCap.Round)
            }
        }
        SceneKind.NightSky -> {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF181C46), Color(0xFF2F3780), Color(0xFF4A4F9C)), 0f, h), Offset.Zero, androidx.compose.ui.geometry.Size(w, h))
            for (i in 0 until 40) drawCircle(Color.White.copy(alpha = 0.35f + 0.1f * (i % 4)), 0.7f + (i % 3) * 0.3f, Offset((i * 71 % 296 + 2).toFloat(), (i * 43 % 108 + 2).toFloat()))
            val walls = listOf(Color(0xFF2A3070), Color(0xFF353D88), Color(0xFF242A62))
            layout.houses.forEach { house ->
                val wall = walls[house.tone % walls.size]
                if (house.roof == 1) {
                    val peak = house.top - house.w * 0.32f
                    rect(Color(0xFF1A1F4D), house.x + house.w * 0.68f, peak + 4f, house.x + house.w * 0.68f + 9f, house.top - 4f)
                    drawPath(poly(house.x - 4f, house.top + 2f, house.x + house.w / 2f, peak, house.x + house.w + 4f, house.top + 2f), Color(0xFF1A1F4D))
                }
                rect(wall, house.x, house.top, house.x + house.w, h)
                if (house.roof == 0) rect(lerp(wall, Color.White, 0.12f), house.x - 2f, house.top - 3f, house.x + house.w + 2f, house.top + 1f, 1f)
            }
        }
        SceneKind.Cafe -> {
            band(Color(0xFFF7E8DA), 0f, 140f)
            band(soft, 114f, 140f)
            var x = 6f
            while (x < w) { drawLine(Color.White.copy(alpha = 0.7f), Offset(x, 116f), Offset(x, 138f), 2f); x += 12f }
            window(158f, 18f, 284f, 102f)
            plank(154f, 108f, 288f)
            band(Color(0xFFEFDCCB), 0f, 6f)
            val table = Path().apply { moveTo(34f, 144f); lineTo(266f, 144f); lineTo(296f, 200f); lineTo(4f, 200f); close() }
            drawPath(Path().apply { addPath(table, Offset(0f, 3f)) }, Color(0x22000000))
            checks(table, Color.White, lerp(tone.fill, Color.White, 0.65f), 16f)
        }
        SceneKind.LivingRoom -> {
            band(lerp(tone.fill, Color.White, 0.8f), 0f, 146f)
            var x = 0f
            while (x < w) { drawRect(Color.White.copy(alpha = 0.35f), Offset(x, 0f), androidx.compose.ui.geometry.Size(10f, 146f)); x += 24f }
            boards(146f, Color(0xFFEFCDA9), Color(0xFFDDB48B))
            band(Color.White, 142f, 147f)
            drawOval(lerp(tone.fill, Color.White, 0.4f), Offset(30f, 176f), androidx.compose.ui.geometry.Size(220f, 22f))
            plank(202f, 56f, 296f)
            val sofa = listOf(Color(0xFF9EA6E6), Color(0xFF8FD3CF), Color(0xFFF2A9C0))[(layout.tone + 1) % 3]
            val sofaSide = lerp(sofa, Color(0xFF1F2557), 0.18f)
            rect(sofaSide, 34f, 80f, 180f, 132f, 16f)
            rect(sofa, 34f, 78f, 180f, 128f, 16f)
            rect(sofaSide, 26f, 124f, 188f, 150f, 8f)
            rect(sofa, 26f, 122f, 188f, 146f, 8f)
            rect(sofaSide, 14f, 100f, 40f, 160f, 12f)
            rect(sofa, 14f, 98f, 40f, 156f, 12f)
            rect(sofaSide, 174f, 100f, 200f, 160f, 12f)
            rect(sofa, 174f, 98f, 200f, 156f, 12f)
            rect(Wood.side, 22f, 158f, 28f, 168f); rect(Wood.side, 186f, 158f, 192f, 168f)
            rect(Wood.side, 206f, 124f, 270f, 130f, 2f)
            rect(Wood.fill, 206f, 123f, 270f, 127f, 2f)
            rect(Wood.side, 212f, 128f, 216f, 172f); rect(Wood.side, 260f, 128f, 264f, 172f)
        }
    }
}
