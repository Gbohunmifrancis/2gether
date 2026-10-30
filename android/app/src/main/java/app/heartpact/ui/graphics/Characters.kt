package app.heartpact.ui.graphics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import app.heartpact.ui.theme.Season

/**
 * The two Heartpact characters, ported from design/heartpact-v3/mascots.js: each partner is one
 * half of a heart with a face, arms and legs. The left partner is indigo with a white face, the
 * right partner periwinkle with an ink face. Together they make the whole heart.
 */
enum class Who { Left, Right, Both }
enum class Pose { Stand, Wave, Cheer, Hug, Rest, Walk }
enum class Mood { Smile, Cheer, Worried, Sleep }

/** A walking stride: [phase] in radians drives the legs and arms; [bob] lifts the body (units). */
data class Stride(val phase: Float, val amplitude: Float = 1f)

private val INK = Color(0xFF161A3D)

private data class Palette(val body: Color, val shade: Color, val blush: Color, val face: Color, val catchlight: Color)

private val LEFT_BASE = Palette(Color(0xFF262D66), Color(0xFF1A1F4D), Color(0xFFE58AA8), Color.White, Color(0xFF262D66))
private val RIGHT_BASE = Palette(Color(0xFF7480D4), Color(0xFF5F6BC6), Color(0xFFF2A3BA), INK, Color.White)
private val LEFT_XMAS = Palette(Color(0xFFB3202A), Color(0xFF7E141C), Color(0xFFF4A6A6), Color.White, Color(0xFFB3202A))
private val RIGHT_XMAS = Palette(Color(0xFF2E7D4F), Color(0xFF1F5E3A), Color(0xFFF2A3BA), INK, Color.White)
private val LEFT get() = if (Season.christmas) LEFT_XMAS else LEFT_BASE
private val RIGHT get() = if (Season.christmas) RIGHT_XMAS else RIGHT_BASE

/** Character canvas in units: one half sits in a 100x120 box; this leaves room for arms and keyline. */
const val CHAR_VB_X = -22f
const val CHAR_VB_Y = -36f
const val CHAR_VB_W = 144f
const val CHAR_VB_H = 166f

@Composable
fun Character(who: Who, pose: Pose, size: Dp, modifier: Modifier = Modifier, mood: Mood = if (pose == Pose.Cheer) Mood.Cheer else Mood.Smile, sticker: Boolean = true) {
    Canvas(modifier.size(size, size * (CHAR_VB_H / CHAR_VB_W))) {
        inUnits(CHAR_VB_X, CHAR_VB_Y, CHAR_VB_W, CHAR_VB_H) {
            drawCharacters(who, pose, mood, sticker)
        }
    }
}

/** Draws [who] in character units: the left half at x 0..50, the right half mirrored at 50..100. */
fun DrawScope.drawCharacters(who: Who, pose: Pose, mood: Mood, sticker: Boolean, stride: Stride? = null, alpha: Float = 1f, featureAlpha: Float = alpha, keylineAlpha: Float = alpha) {
    val sides = when (who) {
        Who.Left -> listOf(false)
        Who.Right -> listOf(true)
        Who.Both -> listOf(false, true)
    }
    val parts = sides.map { mirror -> HalfParts(mirror, pose, stride) }
    if (sticker && keylineAlpha > 0f) {
        // A die-cut edge: the silhouette grown by 6 units in white, over a soft indigo lift.
        for ((dy, a) in listOf(7f to 0.06f, 5f to 0.08f, 3f to 0.08f)) translate(0f, dy) { parts.forEach { it.silhouette(this, Color(0xFF1E2350), 8f, a * keylineAlpha) } }
        parts.forEach { it.silhouette(this, Color.White, 6f, keylineAlpha) }
    }
    parts.forEach { it.body(this, alpha, featureAlpha) }
    sides.forEach { mirror -> drawFace(mood, mirror, if (mirror) RIGHT else LEFT, featureAlpha, parts[sides.indexOf(mirror)].bob) }
    if (Season.christmas) parts.forEach { it.santaHat(this, featureAlpha) }
}

private val HALF = parse("M50 22 C 46 10 37 2 26 2 C 11 2 0 15 0 33 C 0 60 25 80 50 106 Z")
private val HIGHLIGHT = parse("M50 20 C 46 10 38 4 29 4 C 22 4 16 7 12 12 C 20 9 34 10 42 24 C 46 30 48 34 50 36 Z")
private val SHADE = parse("M50 104 C 40 93 30 83 22 72 C 32 80 42 86 50 88 Z")

/** Limb paths per pose for the left half: arm, arm, leg (from 30,86), leg (from 42,96). */
private val POSES = mapOf(
    Pose.Stand to listOf("M8 56 q-8 10 -4 22", "M40 66 q8 8 10 18", "M30 86 l-4 22", "M42 96 l2 14"),
    Pose.Wave to listOf("M8 52 q-14 -8 -12 -26", "M40 66 q8 8 10 18", "M30 86 l-4 22", "M42 96 l2 14"),
    Pose.Cheer to listOf("M8 46 q-12 -14 -6 -32", "M44 30 q10 -14 6 -30", "M30 86 l-8 20", "M42 96 l6 14"),
    Pose.Hug to listOf("M8 56 q-8 10 -4 22", "M40 62 q14 2 26 -4", "M30 86 l-4 22", "M42 96 l2 14"),
    Pose.Rest to listOf("M8 56 q-10 4 -10 14", "M40 66 q6 6 6 12", "M30 86 q-6 10 -16 12", "M42 96 q-2 8 -10 12"),
)

private class HalfParts(val mirror: Boolean, pose: Pose, val stride: Stride?) {
    private val limbs = (POSES[if (pose == Pose.Walk) Pose.Stand else pose] ?: POSES.getValue(Pose.Stand))
    private val paths = limbs.map(::parse)
    private val hips = listOf(Offset(8f, 56f), Offset(40f, 66f), Offset(30f, 86f), Offset(42f, 96f))
    private val feet = listOf(end(limbs[2], 30f, 86f), end(limbs[3], 42f, 96f))
    private val palette = if (mirror) RIGHT else LEFT
    // The far leg leads on one side and trails on the other, so the pair walks in step toward each other.
    private val swing = stride?.let { kotlin.math.sin(it.phase) * 20f * it.amplitude } ?: 0f
    val bob = stride?.let { -kotlin.math.abs(kotlin.math.sin(it.phase)) * 2.6f * it.amplitude } ?: 0f
    private val angles = listOf(-swing * 0.7f, swing * 0.7f, swing, -swing)

    private inline fun DrawScope.mirrored(crossinline block: DrawScope.() -> Unit) {
        if (!mirror) translate(0f, bob) { block() } else withTransform({ translate(0f, bob); scale(-1f, 1f, pivot = Offset(50f, 0f)) }) { block() }
    }

    private fun DrawScope.limbs(color: Color, extra: Float, alpha: Float) {
        paths.forEachIndexed { i, path ->
            rotate(angles[i], hips[i]) {
                drawPath(path, color, alpha = alpha, style = Stroke(4.2f + extra * 2, cap = StrokeCap.Round, join = StrokeJoin.Round))
                if (i >= 2) {
                    val (fx, fy) = feet[i - 2]
                    val cx = if (i == 2) fx - 1f else fx + 1f
                    drawOval(color, Offset(cx - 6.5f - extra, fy + 2f - 4f - extra), Size(13f + extra * 2, 8f + extra * 2), alpha = alpha)
                }
            }
        }
    }

    fun silhouette(scope: DrawScope, color: Color, grow: Float, alpha: Float) = with(scope) {
        mirrored {
            limbs(color, grow, alpha)
            drawPath(HALF, color, alpha = alpha)
            drawPath(HALF, color, alpha = alpha, style = Stroke(grow * 2, join = StrokeJoin.Round))
        }
    }

    /** A Santa hat on the top of the lobe: a red cone that flops outward, a white band and a pompom. */
    fun santaHat(scope: DrawScope, alpha: Float) = with(scope) {
        mirrored {
            val cone = Path().apply {
                moveTo(5f, 17f)
                cubicTo(4f, 4f, 2f, -8f, -6f, -14f)
                cubicTo(14f, -16f, 34f, -6f, 47f, 9f)
                close()
            }
            drawPath(cone, Color(0xFFD42B35), alpha = alpha)
            drawPath(cone, Color(0xFF8E1A22), alpha = alpha, style = Stroke(1.2f, join = StrokeJoin.Round))
            rotate(-14f, Offset(26f, 13f)) {
                drawRoundRect(Color.White, Offset(1f, 8f), Size(50f, 10f), androidx.compose.ui.geometry.CornerRadius(5f, 5f), alpha = alpha)
            }
            drawCircle(Color.White, 6.5f, Offset(-6f, -14f), alpha = alpha)
        }
    }

    fun body(scope: DrawScope, alpha: Float, featureAlpha: Float) = with(scope) {
        mirrored {
            limbs(INK, 0f, featureAlpha)
            drawPath(HALF, palette.body, alpha = alpha)
            drawPath(HIGHLIGHT, Color.White, alpha = 0.14f * alpha)
            drawPath(SHADE, palette.shade, alpha = alpha)
            drawLine(Color.White, Offset(46.5f, 26f), Offset(46.5f, 98f), 2.4f, StrokeCap.Round, PathEffect.dashPathEffect(floatArrayOf(5f, 5f)), alpha = 0.6f * alpha)
            drawOval(palette.blush, Offset(13f, 48.8f), Size(10f, 6.4f), alpha = 0.75f * featureAlpha)
            drawOval(palette.blush, Offset(40f, 49f), Size(8f, 6f), alpha = 0.75f * featureAlpha)
        }
    }
}

private fun DrawScope.drawFace(mood: Mood, mirror: Boolean, palette: Palette, alpha: Float, bob: Float) = translate(0f, bob) {
    val face = palette.face
    val ex = if (mirror) listOf(62f, 76f) else listOf(24f, 38f)
    val mx = (ex[0] + ex[1]) / 2
    val line = Stroke(2.6f, cap = StrokeCap.Round)
    when (mood) {
        Mood.Sleep -> drawPath(parse("M${ex[0] - 4} 42 q4 4 8 0 M${ex[1] - 4} 42 q4 4 8 0"), face, alpha = alpha, style = line)
        Mood.Worried -> {
            ex.forEach { drawOval(face, Offset(it - 3f, 38f), Size(6f, 8f), alpha = alpha) }
            drawPath(parse("M${ex[0] - 4} 34 l6 -2 M${ex[1] + 4} 34 l-6 -2"), face, alpha = alpha, style = Stroke(2.2f, cap = StrokeCap.Round))
        }
        else -> ex.forEach {
            drawOval(face, Offset(it - 3.2f, 37.8f), Size(6.4f, 8.4f), alpha = alpha)
            drawCircle(palette.catchlight, 1.1f, Offset(it + 1f, 40.5f), alpha = alpha)
        }
    }
    when (mood) {
        Mood.Worried -> drawPath(parse("M${mx - 5} 56 q5 -4 10 0"), face, alpha = alpha, style = line)
        Mood.Cheer -> {
            drawPath(parse("M${mx - 6} 52 q6 9 12 0 z"), face, alpha = alpha)
            drawPath(parse("M${mx - 3} 56 q3 2 6 0"), Color(0xFFF06A6A), alpha = alpha, style = Stroke(2f, cap = StrokeCap.Round))
        }
        else -> drawPath(parse("M${mx - 5} 53 q5 5 10 0"), face, alpha = alpha, style = line)
    }
}

private fun parse(d: String): Path = PathParser().parsePathString(d).toPath()

/** Where a limb ends: its start plus the final relative pair, as mascots.js computes it. */
private fun end(d: String, x0: Float, y0: Float): Pair<Float, Float> {
    val n = Regex("-?\\d+(\\.\\d+)?").findAll(d).map { it.value.toFloat() }.toList()
    return x0 + n[n.size - 2] to y0 + n[n.size - 1]
}
