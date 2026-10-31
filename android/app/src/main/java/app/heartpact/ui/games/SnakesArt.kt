package app.heartpact.ui.games

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import app.heartpact.data.SnakesBoardLayout
import app.heartpact.data.SnakesHeart
import app.heartpact.data.SnakesLink
import app.heartpact.ui.graphics.Mood
import app.heartpact.ui.graphics.Pose
import app.heartpact.ui.graphics.Stride
import app.heartpact.ui.graphics.Who
import app.heartpact.ui.graphics.drawCharacters
import app.heartpact.ui.play.PlayInk
import app.heartpact.ui.play.unitHeart
import app.heartpact.ui.theme.Art
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/*
 * Snakes and ladders, drawn: a sticker board of soft pastel rows (a hue a row, alternating light and lighter), wooden
 * ladders with a ribbon bow, friendly pastel snakes with faces that sway, heart squares with their little signs, and
 * Maya and Theo as the tokens. Everything is drawn in board units (one unit a square, SnakesGeometry) times [u].
 * Sticker art keeps its colours by night, framed by its white keyline (DESIGN.md).
 */

/** The rows' pastel hues, bottom to top: each row a hue, its squares alternating deeper and lighter. */
private val RowTints = listOf(
    Color(0xFFD9F2E3) to Color(0xFFEDF9F1), // mint
    Color(0xFFD9EBF8) to Color(0xFFEDF5FC), // sky
    Color(0xFFE2E4FA) to Color(0xFFF1F2FD), // lilac
    Color(0xFFFADDE8) to Color(0xFFFDEEF3), // rose
    Color(0xFFFCE2D2) to Color(0xFFFEF0E7), // peach
    Color(0xFFFAEFC9) to Color(0xFFFDF7E4), // butter
)

/** Each snake's pastel body and its stripes, picked by its place on the board. */
internal val SnakeColours = listOf(
    Color(0xFF8EDBB4) to Color(0xFF5FBF8F),
    Color(0xFFBBA8F2) to Color(0xFF9480DA),
    Color(0xFFF9B98F) to Color(0xFFE9956A),
    Color(0xFF93CDF2) to Color(0xFF64AEDD),
    Color(0xFFF5A3BF) to Color(0xFFE07A9E),
    Color(0xFFF4D57A) to Color(0xFFDDB54D),
    Color(0xFF86D8CF) to Color(0xFF52BBB0),
    Color(0xFFC5E486) to Color(0xFF9CC55A),
)

private val NumberInk = Color(0xFF6A6F92)
private val LadderWood = Color(0xFFC98A4F)
private val LadderLight = Color(0xFFE9B983)
private val LadderDark = Color(0xFF8E5A2B)
private val SnakeInk = Color(0xFF161A3D)
private val SnakeCheek = Color(0xFFF59AB5)
private val SnakeTongue = Color(0xFFFF6F91)

/** Each heart square's colour. */
internal fun heartColour(kind: String): Color = when (kind) {
    "swap" -> PlayInk.Pen
    "boost" -> Color(0xFFFFA94D)
    "shield" -> Color(0xFF5DB7EE)
    "heartbreak" -> Color(0xFF9C95D3)
    else -> PlayInk.PenSoft
}

/** The squares, their numbers (laid out once, [numbers][n - 1] for square n), the start strip and the finish. */
internal fun DrawScope.drawSquares(u: Float, numbers: List<TextLayoutResult>, start: TextLayoutResult?, highlightRow: Int?) {
    val side = SnakesGeometry.SIDE
    for (square in 1..side * side) {
        val row = SnakesGeometry.row(square)
        val col = SnakesGeometry.column(square)
        val (deep, light) = RowTints[row % RowTints.size]
        val topLeft = Offset(col * u, (side - 1 - row) * u)
        drawRect(if ((row + col) % 2 == 0) deep else light, topLeft, Size(u, u))
        if (square == side * side) drawFinish(topLeft, u)
    }
    // The row in play glows a little, so the eye finds it.
    if (highlightRow != null) {
        val y = (side - 1 - highlightRow) * u
        drawRect(Color.White.copy(alpha = 0.32f), Offset(0f, y), Size(side * u, u))
    }
    // Faint perforations between rows, like a page.
    for (r in 1 until side) drawLine(Color.White.copy(alpha = 0.7f), Offset(0f, r * u), Offset(side * u, r * u), u * 0.025f)
    numbers.forEachIndexed { i, layout ->
        val square = i + 1
        val c = SnakesGeometry.center(square)
        val left = c.x - 0.5f
        val top = c.y - 0.5f
        drawText(layout, color = if (square == side * side) Color(0xFF8A6512) else NumberInk, topLeft = Offset(left * u + u * 0.07f, top * u + u * 0.04f))
    }
    // The start strip: a dashed lane under row one with a little flag.
    val y0 = side * u
    drawRect(Color(0xFFF2F3FA), Offset(0f, y0), Size(side * u, SnakesGeometry.START_STRIP * u))
    drawLine(Color(0xFFC8CBE3), Offset(u * 0.2f, y0 + 0.02f * u), Offset(side * u - u * 0.2f, y0 + 0.02f * u), u * 0.03f,
        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(u * 0.12f, u * 0.1f)))
    if (start != null) drawText(start, color = NumberInk, topLeft = Offset(u * 1.45f, y0 + (SnakesGeometry.START_STRIP * u - start.size.height) / 2f))
}

/** Square 100: butter gold with a crown of hearts. */
private fun DrawScope.drawFinish(topLeft: Offset, u: Float) {
    drawRect(Brush.verticalGradient(listOf(Color(0xFFFFE7A3), Color(0xFFF8D46E)), topLeft.y, topLeft.y + u), topLeft, Size(u, u))
    val c = topLeft + Offset(u * 0.58f, u * 0.66f)
    drawPath(unitHeart(u * 0.54f).also { it.translate(c) }, Color.White)
    drawPath(unitHeart(u * 0.44f).also { it.translate(c) }, PlayInk.Pen)
    // Three little crown points over it.
    val crown = Path().apply {
        val y = c.y - u * 0.2f
        moveTo(c.x - u * 0.2f, y)
        lineTo(c.x - u * 0.2f, y - u * 0.13f); lineTo(c.x - u * 0.1f, y - u * 0.05f)
        lineTo(c.x, y - u * 0.17f); lineTo(c.x + u * 0.1f, y - u * 0.05f)
        lineTo(c.x + u * 0.2f, y - u * 0.13f); lineTo(c.x + u * 0.2f, y)
        close()
    }
    drawPath(crown, Color.White, style = Stroke(u * 0.06f, join = StrokeJoin.Round))
    drawPath(crown, Color(0xFFE0A526))
}

/** A heart square's sticker: a heart in its colour with a white keyline and its sign (arrows, a boost, a bubble, a crack). */
internal fun DrawScope.drawHeartSquare(heart: SnakesHeart, u: Float, pulse: Float = 0f) {
    val c = SnakesGeometry.center(heart.square) * u + Offset(u * 0.06f, u * 0.08f)
    val colour = heartColour(heart.kind)
    val size = u * (0.56f + 0.05f * pulse)
    translate(c.x, c.y) {
        if (heart.kind == "shield") {
            drawCircle(Color.White, size * 0.62f, Offset.Zero)
            drawCircle(colour.copy(alpha = 0.22f), size * 0.56f, Offset.Zero)
            drawCircle(colour, size * 0.56f, Offset.Zero, style = Stroke(u * 0.03f))
        }
        translate(0f, u * 0.02f) { drawPath(unitHeart(size * 1.18f), Art.Shadow.copy(alpha = 0.16f)) }
        drawPath(unitHeart(size * 1.18f), Color.White)
        drawPath(unitHeart(size), colour)
        val w = u * 0.045f
        val white = Color.White
        when (heart.kind) {
            "swap" -> {
                // Two little arrows passing each other.
                drawLine(white, Offset(-size * 0.2f, -size * 0.1f), Offset(size * 0.2f, -size * 0.1f), w, StrokeCap.Round)
                drawLine(white, Offset(size * 0.1f, -size * 0.18f), Offset(size * 0.2f, -size * 0.1f), w, StrokeCap.Round)
                drawLine(white, Offset(size * 0.2f, size * 0.06f), Offset(-size * 0.2f, size * 0.06f), w, StrokeCap.Round)
                drawLine(white, Offset(-size * 0.1f, size * 0.14f), Offset(-size * 0.2f, size * 0.06f), w, StrokeCap.Round)
            }
            "boost" -> {
                // A double chevron, pointing the way the row runs.
                val dir = if (SnakesGeometry.row(heart.square) % 2 == 0) 1f else -1f
                for (k in 0..1) {
                    val x = (-size * 0.12f + k * size * 0.16f) * dir
                    drawLine(white, Offset(x - size * 0.08f * dir, -size * 0.14f), Offset(x + size * 0.04f * dir, -size * 0.02f), w, StrokeCap.Round)
                    drawLine(white, Offset(x + size * 0.04f * dir, -size * 0.02f), Offset(x - size * 0.08f * dir, size * 0.1f), w, StrokeCap.Round)
                }
            }
            "shield" -> {
                // A tiny hug: two arcs wrapping round.
                drawArc(white, 120f, 120f, false, Offset(-size * 0.2f, -size * 0.2f), Size(size * 0.4f, size * 0.32f), style = Stroke(w, cap = StrokeCap.Round))
                drawArc(white, -60f, 120f, false, Offset(-size * 0.2f, -size * 0.2f), Size(size * 0.4f, size * 0.32f), style = Stroke(w, cap = StrokeCap.Round))
            }
            "heartbreak" -> {
                // A zigzag crack down the middle.
                val crack = Path().apply {
                    moveTo(-size * 0.02f, -size * 0.26f)
                    lineTo(size * 0.08f, -size * 0.1f); lineTo(-size * 0.06f, 0f); lineTo(size * 0.05f, size * 0.12f); lineTo(0f, size * 0.3f)
                }
                drawPath(crack, white, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

/** A ladder as a die-cut sticker: a white keyline, then wooden rails and rungs, and a pink bow at the top. */
internal fun DrawScope.drawLadder(link: SnakesLink, u: Float) {
    val (railA, railB) = SnakesGeometry.rails(link)
    val n = SnakesGeometry.rungs(link)
    val a = SnakesGeometry.center(link.from)
    val b = SnakesGeometry.center(link.to)
    val nrm = SnakesGeometry.normal(b - a)
    fun rung(i: Int): Pair<Offset, Offset> {
        val t = (i + 0.5f) / n
        val m = SnakesGeometry.lerp(a, b, t)
        return (m + nrm * 0.17f) * u to (m - nrm * 0.17f) * u
    }
    val rail = u * 0.085f
    val rungW = u * 0.06f
    // Keyline and lift.
    translate(0f, u * 0.05f) {
        listOf(railA, railB).forEach { (p, q) -> drawLine(Art.Shadow.copy(alpha = 0.14f), p * u, q * u, rail + u * 0.12f, StrokeCap.Round) }
    }
    listOf(railA, railB).forEach { (p, q) -> drawLine(Color.White, p * u, q * u, rail + u * 0.1f, StrokeCap.Round) }
    for (i in 0 until n) rung(i).let { (p, q) -> drawLine(Color.White, p, q, rungW + u * 0.1f, StrokeCap.Round) }
    for (i in 0 until n) rung(i).let { (p, q) -> drawLine(LadderLight, p, q, rungW, StrokeCap.Round) }
    listOf(railA, railB).forEach { (p, q) ->
        drawLine(LadderDark, p * u, q * u, rail, StrokeCap.Round)
        drawLine(LadderWood, p * u, q * u, rail * 0.62f, StrokeCap.Round)
    }
    // The bow tied at the top.
    val top = b * u + (b - a).let { it / it.getDistance().coerceAtLeast(0.01f) } * (u * 0.05f)
    translate(top.x, top.y) {
        val r = u * 0.11f
        drawOval(Color.White, Offset(-r * 2.3f, -r * 1.2f), Size(r * 4.6f, r * 2.4f))
        drawOval(PlayInk.PenSoft, Offset(-r * 2.0f, -r * 0.8f), Size(r * 1.9f, r * 1.6f))
        drawOval(PlayInk.PenSoft, Offset(r * 0.1f, -r * 0.8f), Size(r * 1.9f, r * 1.6f))
        drawCircle(PlayInk.Pen, r * 0.5f, Offset.Zero)
    }
}

/**
 * A friendly snake: a tapering pastel body along its S-curve with stripes, a white keyline, and a round face with big
 * eyes, rosy cheeks and a smile at the head. [sway] wiggles the body; [gulp] (0 to 1) opens its mouth wide as it
 * swallows a token and laughs; [tongue] (0 to 1) flicks its tongue.
 */
internal fun DrawScope.drawSnake(link: SnakesLink, index: Int, u: Float, sway: Float, gulp: Float = 0f, tongue: Float = 0f) {
    val (body, stripe) = SnakeColours[index % SnakeColours.size]
    val steps = 36
    val pts = List(steps + 1) { i -> SnakesGeometry.onSnake(link, index, i / steps.toFloat(), sway) * u }
    fun width(t: Float) = u * (0.27f - 0.2f * t * t + 0.04f * sin(t * PI.toFloat()))
    val left = ArrayList<Offset>(steps + 1)
    val right = ArrayList<Offset>(steps + 1)
    for (i in 0..steps) {
        val t = i / steps.toFloat()
        val d = pts[(i + 1).coerceAtMost(steps)] - pts[(i - 1).coerceAtLeast(0)]
        val n = SnakesGeometry.normal(d) * (width(t) / 2f)
        left += pts[i] + n
        right += pts[i] - n
    }
    val outline = Path().apply {
        moveTo(left[0].x, left[0].y)
        left.drop(1).forEach { lineTo(it.x, it.y) }
        // A rounded tip at the tail.
        val tip = pts[steps] + (pts[steps] - pts[steps - 1]).let { it / it.getDistance().coerceAtLeast(0.01f) } * (u * 0.05f)
        quadraticTo(tip.x, tip.y, right[steps].x, right[steps].y)
        right.reversed().drop(1).forEach { lineTo(it.x, it.y) }
        close()
    }
    translate(0f, u * 0.06f) { drawPath(outline, Art.Shadow.copy(alpha = 0.14f)) }
    drawPath(outline, Color.White, style = Stroke(u * 0.1f, join = StrokeJoin.Round))
    drawPath(outline, body)
    // Belly stripes across the body, a soft shine along the back.
    for (i in 3 until steps - 1 step 3) drawLine(stripe, left[i], right[i], u * 0.055f, StrokeCap.Round)
    val shine = Path().apply {
        moveTo(pts[2].x, pts[2].y)
        for (i in 3..steps - 4) { val p = (pts[i] + left[i]) / 2f; lineTo(p.x, p.y) }
    }
    drawPath(shine, Color.White.copy(alpha = 0.45f), style = Stroke(u * 0.035f, cap = StrokeCap.Round))

    // The head, turned the way the body leaves it.
    val head = pts[0]
    val away = pts[3] - pts[0]
    val angle = Math.toDegrees(atan2(away.y, away.x).toDouble()).toFloat() + 180f
    val r = u * 0.24f
    withTransform({ translate(head.x, head.y); rotate(angle, Offset.Zero) }) {
        // Facing out from the body, along +x; the face is drawn upright-ish by flipping when it would be upside down.
        val flip = if (cos(Math.toRadians(angle.toDouble())) < 0) -1f else 1f
        scale(1f, flip, Offset.Zero) {
            drawOval(Color.White, Offset(-r * 1.1f, -r * 1.05f), Size(r * 2.4f, r * 2.1f))
            drawOval(body, Offset(-r * 0.95f, -r * 0.9f), Size(r * 2.1f, r * 1.8f))
            // SnakeTongue, flicking out.
            if (tongue > 0.05f && gulp < 0.1f) {
                val len = r * 0.7f * tongue
                drawLine(SnakeTongue, Offset(r * 1.05f, r * 0.15f), Offset(r * 1.05f + len, r * 0.15f), u * 0.03f, StrokeCap.Round)
                drawLine(SnakeTongue, Offset(r * 1.05f + len, r * 0.15f), Offset(r * 1.2f + len, r * 0.05f), u * 0.025f, StrokeCap.Round)
                drawLine(SnakeTongue, Offset(r * 1.05f + len, r * 0.15f), Offset(r * 1.2f + len, r * 0.25f), u * 0.025f, StrokeCap.Round)
            }
            // Eyes on top, big and kind.
            for (e in listOf(-1f, 1f)) {
                val eye = Offset(r * 0.3f, -r * 0.42f + e * r * 0.02f) + Offset(e * r * 0.38f - r * 0.1f, 0f)
                drawCircle(Color.White, r * 0.36f, eye)
                drawCircle(SnakeInk, r * 0.2f, eye + Offset(r * 0.07f, r * 0.04f))
                drawCircle(Color.White, r * 0.07f, eye + Offset(r * 0.12f, -r * 0.04f))
            }
            drawCircle(SnakeCheek.copy(alpha = 0.8f), r * 0.17f, Offset(r * 0.75f, r * 0.12f))
            // The smile, or a wide-open laughing mouth while it gulps.
            if (gulp > 0.05f) {
                drawOval(SnakeInk, Offset(r * 0.3f, r * 0.05f), Size(r * 0.75f, r * 0.55f * gulp + r * 0.1f))
                drawOval(SnakeTongue, Offset(r * 0.42f, r * 0.12f + r * 0.35f * gulp), Size(r * 0.45f, r * 0.18f))
            } else {
                drawArc(SnakeInk, 10f, 120f, false, Offset(r * 0.25f, -r * 0.2f), Size(r * 0.8f, r * 0.6f), style = Stroke(u * 0.028f, cap = StrokeCap.Round))
            }
        }
    }
}

/** How a token looks: which half, and whether it cheers, worries or just smiles. */
internal fun tokenPose(cheer: Boolean, worried: Boolean): Pair<Pose, Mood> = when {
    cheer -> Pose.Cheer to Mood.Cheer
    worried -> Pose.Rest to Mood.Worried
    else -> Pose.Stand to Mood.Smile
}

/**
 * Maya or Theo as a token, standing on [at] (units), [height] units tall. [together] draws the two halves meeting as
 * one heart on a shared square (both anchored on the fold). A hug shield shows as a bubble round the token.
 */
internal fun DrawScope.drawToken(who: Who, at: Offset, u: Float, height: Float, pose: Pose, mood: Mood, together: Boolean, shield: Boolean,
                                 squash: Float = 0f, tilt: Float = 0f, spin: Float = 0f, stride: Stride? = null, alpha: Float = 1f) {
    val s = height * u / 116f
    val anchorX = if (together) 50f else if (who == Who.Left) 26f else 74f
    val p = at * u
    if (shield) {
        drawCircle(Color(0xFF5DB7EE).copy(alpha = 0.18f * alpha), height * u * 0.62f, p + Offset(0f, -height * u * 0.05f))
        drawCircle(Color.White.copy(alpha = 0.9f * alpha), height * u * 0.62f, p + Offset(0f, -height * u * 0.05f), style = Stroke(u * 0.035f))
        drawArc(Color.White.copy(alpha = 0.9f * alpha), 200f, 60f, false, p + Offset(-height * u * 0.45f, -height * u * 0.5f), Size(height * u * 0.9f, height * u * 0.9f), style = Stroke(u * 0.04f, cap = StrokeCap.Round))
    }
    withTransform({
        translate(p.x, p.y)
        rotate(tilt + spin, Offset.Zero)
        scale(1f + 0.18f * squash, 1f - 0.18f * squash, Offset(0f, height * u * 0.5f))
        // Character units: feet at about y 112, the body's middle at about y 55.
        translate(-anchorX * s, -58f * s)
        scale(s, s, Offset.Zero)
    }) {
        drawCharacters(who, pose, mood, sticker = true, stride = stride, alpha = alpha)
    }
}

/** A puff of speed lines behind a boosted token. */
internal fun DrawScope.drawTrail(at: Offset, from: Offset, u: Float, p: Float) {
    val d = at - from
    val l = d.getDistance().takeIf { it > 0.01f } ?: return
    val back = d / l
    val n = SnakesGeometry.normal(back)
    for (k in -1..1) {
        val start = at * u - back * (u * (0.35f + 0.1f * k * k)) + n * (u * 0.14f * k)
        drawLine(Color(0xFFFFA94D).copy(alpha = 0.7f * (1f - p)), start, start - back * (u * 0.45f), u * 0.05f, StrokeCap.Round)
    }
}

/** A small heart, cracked in two, popping up over a heartbreak. */
internal fun DrawScope.drawCrackedHeart(at: Offset, u: Float, p: Float) {
    if (p <= 0f || p >= 1f) return
    val lift = u * 0.8f * p
    val spread = u * 0.12f * p
    val c = at * u - Offset(0f, lift)
    val a = (1f - p).coerceIn(0f, 1f)
    val heart = unitHeart(u * 0.5f)
    for (side in listOf(-1f, 1f)) {
        withTransform({ translate(c.x + side * spread, c.y); rotate(side * 14f * p, Offset.Zero) }) {
            clipRectHalf(side, u) { drawPath(heart, heartColour("heartbreak").copy(alpha = a)) }
        }
    }
}

private inline fun DrawScope.clipRectHalf(side: Float, u: Float, block: DrawScope.() -> Unit) {
    val r = if (side < 0) Rect(-u, -u, 0f, u) else Rect(0f, -u, u, u)
    clipRect(r.left, r.top, r.right, r.bottom) { block() }
}

/** Hearts kissing up off a square: a few small pink hearts rising and fading. */
internal fun DrawScope.drawKisses(at: Offset, u: Float, p: Float) {
    if (p <= 0f || p >= 1f) return
    repeat(3) { i ->
        val q = (p * 1.3f - i * 0.15f).coerceIn(0f, 1f)
        if (q <= 0f) return@repeat
        val c = at * u + Offset(u * (0.25f * (i - 1)), -u * (0.4f + 0.7f * q))
        translate(c.x, c.y) { drawPath(unitHeart(u * (0.22f + 0.05f * i)), PlayInk.Pen.copy(alpha = (1f - q).coerceIn(0f, 1f))) }
    }
}

/** The die: a white cube with rounded edges and rose pips (a heart for the 1), turned by [x], [y], [z] and tilted toward you. */
internal fun DrawScope.drawDie(x: Float, y: Float, z: Float, colour: Color = Color.White, pip: Color = PlayInk.Pen) {
    val half = size.minDimension * 0.27f
    val c = center
    val corners = listOf(
        DieMath.V(-1f, -1f, -1f), DieMath.V(1f, -1f, -1f), DieMath.V(1f, 1f, -1f), DieMath.V(-1f, 1f, -1f),
        DieMath.V(-1f, -1f, 1f), DieMath.V(1f, -1f, 1f), DieMath.V(1f, 1f, 1f), DieMath.V(-1f, 1f, 1f),
    )
    // A resting tilt toward the viewer: a little from above and to the side.
    fun place(v: DieMath.V): DieMath.V {
        val t = DieMath.turn(v, x, y, z)
        return DieMath.turn(t, 0.42f, -0.5f, 0f)
    }
    fun project(v: DieMath.V): Offset {
        val f = 5.2f / (5.2f - v.z)
        return c + Offset(v.x * half * f, -v.y * half * f)
    }
    // Faces: value, corner indices (counter-clockwise from outside), and the face's own axes for the pips.
    val faces = listOf(
        DieFace(1, listOf(4, 5, 6, 7), DieMath.V(0f, 0f, 1f), DieMath.V(1f, 0f, 0f), DieMath.V(0f, 1f, 0f)),
        DieFace(6, listOf(1, 0, 3, 2), DieMath.V(0f, 0f, -1f), DieMath.V(-1f, 0f, 0f), DieMath.V(0f, 1f, 0f)),
        DieFace(2, listOf(7, 6, 2, 3), DieMath.V(0f, 1f, 0f), DieMath.V(1f, 0f, 0f), DieMath.V(0f, 0f, -1f)),
        DieFace(5, listOf(0, 1, 5, 4), DieMath.V(0f, -1f, 0f), DieMath.V(1f, 0f, 0f), DieMath.V(0f, 0f, 1f)),
        DieFace(3, listOf(5, 1, 2, 6), DieMath.V(1f, 0f, 0f), DieMath.V(0f, 0f, -1f), DieMath.V(0f, 1f, 0f)),
        DieFace(4, listOf(0, 4, 7, 3), DieMath.V(-1f, 0f, 0f), DieMath.V(0f, 0f, 1f), DieMath.V(0f, 1f, 0f)),
    )
    val placed = corners.map(::place)
    val visible = faces.map { f -> f to place(f.normal) }.filter { it.second.z > 0.02f }.sortedBy { it.second.z }
    // The die's silhouette: a white keyline and a soft shadow under it, as a sticker would have.
    val hull = visible.flatMap { (f, _) -> f.corners.map { project(placed[it]) } }
    val outline = Path().apply { convexHull(hull).forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }; close() }
    drawPath(outline, Color.White, style = Stroke(half * 0.32f, join = StrokeJoin.Round))
    drawPath(outline, Color(0xFFE4E6F3), style = Stroke(half * 0.16f, join = StrokeJoin.Round))
    visible.forEach { (f, n) ->
        val quad = Path().apply {
            f.corners.map { project(placed[it]) }.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
            close()
        }
        // Lit from above and the front: brighter as a face turns to you.
        val light = (0.78f + 0.22f * n.z + 0.06f * n.y).coerceIn(0f, 1f)
        val shade = Color(colour.red * light, colour.green * (light * 0.98f + 0.02f), colour.blue * (light * 0.96f + 0.04f))
        drawPath(quad, shade, style = Fill)
        drawPath(quad, shade, style = Stroke(half * 0.14f, join = StrokeJoin.Round))
        drawPath(quad, Color(0xFFD7DAEF).copy(alpha = 0.5f), style = Stroke(half * 0.025f, join = StrokeJoin.Round))
        // The pips, laid on the face and projected so they turn with it.
        val faceCentre = f.normal
        val alpha = ((n.z - 0.02f) / 0.25f).coerceIn(0f, 1f)
        if (f.value == 1) {
            val pts = (0..40).map { k ->
                val t = k / 40f * 2f * PI.toFloat()
                // A heart curve in the face's plane.
                val hx = 16f * sin(t) * sin(t) * sin(t) / 17f
                val hy = (13f * cos(t) - 5f * cos(2 * t) - 2f * cos(3 * t) - cos(4 * t)) / 17f
                project(place(faceCentre + f.u * (hx * 0.5f) + f.v * (hy * 0.5f + 0.06f)))
            }
            val heart = Path().apply { pts.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }; close() }
            drawPath(heart, pip.copy(alpha = alpha))
        } else {
            pipsOf(f.value).forEach { (pu, pv) ->
                val ring = (0..14).map { k ->
                    val t = k / 14f * 2f * PI.toFloat()
                    project(place(faceCentre + f.u * (pu + 0.17f * cos(t)) + f.v * (pv + 0.17f * sin(t))))
                }
                val dot = Path().apply { ring.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }; close() }
                drawPath(dot, (if (f.value % 2 == 0) SnakeInk else pip).copy(alpha = alpha))
            }
        }
    }
}

private class DieFace(val value: Int, val corners: List<Int>, val normal: DieMath.V, val u: DieMath.V, val v: DieMath.V)

/** Where a face's pips sit, in its own units (-1 to 1). */
private fun pipsOf(value: Int): List<Pair<Float, Float>> {
    val a = 0.5f
    return when (value) {
        2 -> listOf(-a to a, a to -a)
        3 -> listOf(-a to a, 0f to 0f, a to -a)
        4 -> listOf(-a to a, a to a, -a to -a, a to -a)
        5 -> listOf(-a to a, a to a, 0f to 0f, -a to -a, a to -a)
        6 -> listOf(-a to a, a to a, -a to 0f, a to 0f, -a to -a, a to -a)
        else -> listOf(0f to 0f)
    }
}

/** The convex hull of [points] (gift wrapping; a die has at most 12 corners in view). */
private fun convexHull(all: List<Offset>): List<Offset> {
    val points = all.distinct()
    if (points.size < 3) return points
    val start = points.minBy { it.x }
    val hull = mutableListOf<Offset>()
    var p = start
    do {
        hull += p
        var q = points.first { it != p }
        for (r in points) {
            val cross = (q.x - p.x) * (r.y - p.y) - (q.y - p.y) * (r.x - p.x)
            if (cross < 0 || (cross == 0f && (r - p).getDistance() > (q - p).getDistance())) q = r
        }
        p = q
    } while (p != start && hull.size <= points.size)
    return hull
}
