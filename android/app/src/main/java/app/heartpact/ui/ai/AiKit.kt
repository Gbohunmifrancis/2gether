package app.heartpact.ui.ai

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.heartpact.ui.graphics.LocalStillLogo
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.Shapes

/** Whether to hold still: animations off in the system, a preview, or a screenshot test (which waits for the screen to settle). */
@Composable
internal fun holdStill(): Boolean {
    val context = LocalContext.current
    return LocalStillLogo.current || LocalInspectionMode.current ||
        remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

/**
 * A line of text still being written: a soft band drifts across it, slowly. With animations off it is a plain line, so
 * nothing on screen pulses at someone who asked for stillness.
 */
@Composable
fun ShimmerLine(widthFraction: Float, modifier: Modifier = Modifier, height: Dp = 12.dp) {
    val colors = Heartpact.colors
    // Ink at low strength, so the lines read on a white card, a tinted well or the night page alike.
    val base = colors.ink.copy(alpha = 0.10f)
    val shine = colors.ink.copy(alpha = 0.03f)
    val still = holdStill()
    val sweep = if (still) null else rememberInfiniteTransition(label = "shimmer")
        .animateFloat(-0.6f, 1.6f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "sweep")
    Box(
        modifier.fillMaxWidth(widthFraction).height(height).clip(Shapes.Pill).drawBehind {
            drawRect(base)
            // Read while drawing, so the sweep redraws the line without recomposing it.
            val at = sweep?.value
            if (at != null) {
                val band = size.width * 0.5f
                val x = size.width * at
                drawRect(Brush.linearGradient(listOf(base, shine, base), start = Offset(x - band, 0f), end = Offset(x, 0f)))
            }
        },
    )
}

/** A few placeholder lines, ragged like real text. */
@Composable
fun ShimmerLines(count: Int, modifier: Modifier = Modifier) {
    val widths = listOf(0.94f, 0.82f, 0.88f, 0.56f)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(count) { ShimmerLine(widths[it % widths.size].let { width -> if (it == count - 1) minOf(width, 0.6f) else width }) }
    }
}
