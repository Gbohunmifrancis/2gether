package app.heartpact.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Seasonal looks. Whether Christmas is on comes from the server (`GET api/config`, switched on the admin dashboard's
 * Features page) and is remembered for the next start, so the festive look is there from the first frame.
 */
object Season {
    var christmas by mutableStateOf(false)
}

/**
 * Gentle snowfall over the whole app, pointer-transparent: three depths of flakes, the far ones small, faint and slow,
 * the near ones larger and swaying, so it reads as depth rather than dots.
 */
@Composable
fun Snowfall(modifier: Modifier = Modifier) {
    val flakes = remember {
        val random = Random(2025)
        List(54) {
            val depth = it % 3 // 0 far, 1 middle, 2 near
            Flake(random.nextFloat(), random.nextFloat(), radius = 0.9f + depth * 0.9f + random.nextFloat() * 0.8f,
                speed = 0.35f + depth * 0.3f + random.nextFloat() * 0.2f, phase = random.nextFloat() * 6.28f, alpha = 0.35f + depth * 0.22f)
        }
    }
    val t by rememberInfiniteTransition(label = "snow").animateFloat(0f, 1f, infiniteRepeatable(tween(30_000, easing = LinearEasing), RepeatMode.Restart), label = "snow")
    Canvas(modifier.fillMaxSize()) {
        flakes.forEach { f ->
            val progress = (f.y + t * f.speed * 2f) % 1f
            val x = (f.x + sin(t * 6.28f * 3f * f.speed + f.phase) * 0.025f * f.speed) * size.width
            val centre = Offset(x, progress * size.height)
            // A soft halo under each near flake, so it glows a little instead of looking like a hole.
            if (f.radius > 2.2f) drawCircle(Color.White.copy(alpha = f.alpha * 0.25f), f.radius * density * 2f, centre)
            drawCircle(Color.White.copy(alpha = f.alpha), f.radius * density, centre)
        }
    }
}

/** A string of fairy lights along the top of [size]: a sagging wire, gold caps, and bulbs that twinkle out of step. */
@Composable
fun FairyLights(modifier: Modifier = Modifier, bulbs: Int = 11) {
    val t by rememberInfiniteTransition(label = "lights").animateFloat(0f, 1f, infiniteRepeatable(tween(3_000, easing = LinearEasing), RepeatMode.Restart), label = "lights")
    Canvas(modifier) {
        val sag = size.height * 0.45f
        fun y(x: Float): Float {
            val span = size.width / 3f
            val u = (x % span) / span
            return 4.dp.toPx() + sag * 4f * u * (1f - u)
        }
        val wire = androidx.compose.ui.graphics.Path().apply {
            moveTo(0f, y(0f))
            var x = 0f
            while (x <= size.width) { lineTo(x, y(x)); x += 6f }
        }
        drawPath(wire, Color(0xFF2F3B2F), style = androidx.compose.ui.graphics.drawscope.Stroke(1.6.dp.toPx()))
        repeat(bulbs) { i ->
            val x = (i + 0.5f) / bulbs * size.width
            val top = y(x)
            val colour = Festive.Bulbs[i % Festive.Bulbs.size]
            val glow = 0.55f + 0.45f * sin((t * 6.28f) + i * 1.7f).coerceAtLeast(-0.2f)
            drawCircle(colour.copy(alpha = 0.28f * glow), 9.dp.toPx(), Offset(x, top + 9.dp.toPx()))
            drawRoundRect(Festive.Gold, Offset(x - 2.5.dp.toPx(), top), androidx.compose.ui.geometry.Size(5.dp.toPx(), 4.dp.toPx()),
                androidx.compose.ui.geometry.CornerRadius(1.dp.toPx()))
            drawOval(colour.copy(alpha = 0.75f + 0.25f * glow), Offset(x - 3.6.dp.toPx(), top + 3.5.dp.toPx()), androidx.compose.ui.geometry.Size(7.2.dp.toPx(), 10.dp.toPx()))
            drawCircle(Color.White.copy(alpha = 0.55f * glow), 1.4.dp.toPx(), Offset(x - 1.2.dp.toPx(), top + 6.5.dp.toPx()))
        }
    }
}

private data class Flake(val x: Float, val y: Float, val radius: Float, val speed: Float, val phase: Float, val alpha: Float)
