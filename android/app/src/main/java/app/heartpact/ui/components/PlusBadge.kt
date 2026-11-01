package app.heartpact.ui.components

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.heartpact.ui.graphics.LocalStillLogo
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.PlusGold
import app.heartpact.ui.theme.Sora

/*
 * The little premium touches for couples on Heartpact Plus: a gold "PLUS" chip by a screen's title, with a soft shine
 * passing over it now and then, and a thin gold ring around the two of you. Free couples see none of it.
 */

/** Held still when animations are off in the system settings, in previews and in screenshot tests. */
@Composable
fun rememberStill(): Boolean {
    val context = LocalContext.current
    val still = LocalStillLogo.current || LocalInspectionMode.current
    return still || remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

/** The gold "PLUS" chip. A band of light crosses it every few seconds; still, it is just the gold. */
@Composable
fun PlusBadge(modifier: Modifier = Modifier, height: Dp = 22.dp) {
    val still = rememberStill()
    // 0..1 over one cycle: the shine crosses during the first third and rests for the rest.
    val cycle = if (still) -1f else {
        val loop = rememberInfiniteTransition(label = "plus-shine")
        val p by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(3600, easing = LinearEasing)), label = "shine")
        p
    }
    val fontSize = (height.value * 0.5f).sp
    Text(
        "PLUS",
        style = Heartpact.type.labelSmall.copy(fontFamily = Sora, fontWeight = FontWeight.ExtraBold, fontSize = fontSize, lineHeight = fontSize, letterSpacing = (fontSize.value * 0.12f).sp),
        color = PlusGold.OnGold,
        modifier = modifier
            .semantics { contentDescription = "Heartpact Plus" }
            .clip(CircleShape)
            .drawWithContent {
                drawRect(Brush.linearGradient(listOf(PlusGold.Pale, PlusGold.Bright, PlusGold.Deep), Offset.Zero, Offset(size.width, size.height)))
                if (cycle in 0f..0.34f) {
                    // The shine: a slanted pale band sweeping from the start edge to the end.
                    val x = -size.width * 0.4f + (cycle / 0.34f) * size.width * 1.8f
                    drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.7f), Color.Transparent),
                        Offset(x - size.height, 0f), Offset(x + size.height * 0.2f, size.height)))
                }
                drawRoundRect(PlusGold.Deep.copy(alpha = 0.55f), cornerRadius = CornerRadius(size.height / 2), style = Stroke(1.dp.toPx()))
                drawContent()
            }
            .padding(horizontal = height * 0.42f, vertical = height * 0.2f),
    )
}

/** A thin gold ring just outside a round picture: draw it on a box [ring] wider than the picture on each side. */
fun Modifier.goldRing(ring: Dp = 3.dp): Modifier = drawBehind {
    val stroke = ring.toPx()
    drawCircle(
        Brush.sweepGradient(listOf(PlusGold.Bright, PlusGold.Pale, PlusGold.Deep, PlusGold.Bright, PlusGold.Pale, PlusGold.Bright), center),
        radius = size.minDimension / 2 - stroke / 2,
        style = Stroke(stroke),
    )
}

/** A small gold four-pointed sparkle: the Plus row's accent and the celebration's bullets. */
@Composable
fun GoldSparkle(size: Dp, modifier: Modifier = Modifier) =
    Canvas(modifier.size(size)) { drawSparkle(center, this.size.minDimension / 2, PlusGold.Bright, PlusGold.Deep) }

/** A four-pointed sparkle centred on [c], [r] from the centre to each point, with a darker edge. */
fun DrawScope.drawSparkle(c: Offset, r: Float, fill: Color, edge: Color) {
    if (r <= 0f) return
    val k = r * 0.1f
    val star = Path().apply {
        moveTo(c.x, c.y - r)
        quadraticTo(c.x + k, c.y - k, c.x + r, c.y)
        quadraticTo(c.x + k, c.y + k, c.x, c.y + r)
        quadraticTo(c.x - k, c.y + k, c.x - r, c.y)
        quadraticTo(c.x - k, c.y - k, c.x, c.y - r)
        close()
    }
    drawPath(star, fill)
    drawPath(star, edge, style = Stroke(r * 0.12f))
}
