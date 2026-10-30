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
import kotlin.math.sin
import kotlin.random.Random

/**
 * Seasonal looks. Whether Christmas is on comes from the server (`GET api/config`, switched on the admin dashboard's
 * Features page) and is remembered for the next start, so the festive look is there from the first frame.
 */
object Season {
    var christmas by mutableStateOf(false)
}

/** Gentle snowfall over the whole app: pointer-transparent, a few dozen flakes drifting on a loop. */
@Composable
fun Snowfall(modifier: Modifier = Modifier) {
    val flakes = remember {
        val random = Random(2025)
        List(46) { Flake(random.nextFloat(), random.nextFloat(), 1.2f + random.nextFloat() * 2.6f, 0.4f + random.nextFloat() * 0.9f, random.nextFloat() * 6.28f) }
    }
    val t by rememberInfiniteTransition(label = "snow").animateFloat(0f, 1f, infiniteRepeatable(tween(26_000, easing = LinearEasing), RepeatMode.Restart), label = "snow")
    Canvas(modifier.fillMaxSize()) {
        flakes.forEach { f ->
            val progress = (f.y + t * f.speed) % 1f
            val x = (f.x + sin(t * 6.28f * f.speed + f.phase) * 0.03f) * size.width
            drawCircle(Color.White.copy(alpha = 0.55f + 0.3f * (f.radius / 3.8f)), f.radius * density, Offset(x, progress * size.height))
        }
    }
}

private data class Flake(val x: Float, val y: Float, val radius: Float, val speed: Float, val phase: Float)
