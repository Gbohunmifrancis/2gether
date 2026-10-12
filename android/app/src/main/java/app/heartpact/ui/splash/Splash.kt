package app.heartpact.ui.splash

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.heartpact.ui.graphics.Mood
import app.heartpact.ui.graphics.Pose
import app.heartpact.ui.graphics.Stride
import app.heartpact.ui.graphics.Who
import app.heartpact.ui.graphics.drawCharacters
import app.heartpact.ui.graphics.drawMark
import app.heartpact.ui.theme.Art
import app.heartpact.ui.theme.Heartpact
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * The launch moment: each half walks in from its side of the screen, they meet in the middle,
 * and together they become the mark; then the tick cuts itself in and the wordmark rises.
 * [SplashScene] is a pure function of time so every frame can be checked; [AnimatedSplash] plays it.
 */
object SplashTimeline {
    const val WALK_START = 150f
    const val WALK_END = 1450f
    const val MEET_END = 1750f
    /** Arms, legs, faces and the white die-cut edge tuck away; the bare halves stay. */
    const val TUCK_END = 1950f
    /** The bare halves become the mark. */
    const val MORPH_END = 2200f
    const val TICK_END = 2450f
    const val WORD_START = 2300f
    const val WORD_END = 2650f
    const val END = 3000f
    /** Strides each half takes on the way in. */
    const val STRIDES = 3.2f
}

/** Plays the splash, then calls [onFinished]. A tap skips it; with animations off it shows the last frame briefly. */
@Composable
fun AnimatedSplash(onFinished: () -> Unit) {
    val context = LocalContext.current
    val finish by rememberUpdatedState(onFinished)
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val scale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        if (scale == 0f) {
            time = SplashTimeline.END
            kotlinx.coroutines.delay(500)
        } else {
            val start = withFrameMillis { it }
            while (time < SplashTimeline.END) {
                time = withFrameMillis { (it - start).toFloat() }.coerceAtMost(SplashTimeline.END)
            }
        }
        finish()
    }
    SplashScene(
        timeMs = time,
        modifier = Modifier.clickable(remember { MutableInteractionSource() }, indication = null) { finish() },
    )
}

@Composable
fun SplashScene(timeMs: Float, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val wordProgress = ease(progress(timeMs, SplashTimeline.WORD_START, SplashTimeline.WORD_END))
    Box(
        modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(Color(0xFF2C3474), Art.SplashIndigo, Color(0xFF1A1F4D)), radius = with(density) { 520.dp.toPx() }))
            .semantics { contentDescription = "Heartpact" },
    ) {
        Canvas(Modifier.fillMaxSize().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
            drawScene(timeMs, unit = 1.27.dp.toPx())
        }
        Text(
            "heartpact",
            style = Heartpact.type.wordmark.copy(fontSize = 24.sp),
            color = Color.White.copy(alpha = 0.92f * wordProgress),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 48.dp)
                .graphicsLayer { translationY = (1f - wordProgress) * 10.dp.toPx() },
        )
    }
}

private fun DrawScope.drawScene(t: Float, unit: Float) {
    // Heart units: the body spans x 0..100, y about 6..100; centre it a little above the middle.
    val origin = Offset(size.width / 2 - 50f * unit, size.height * 0.47f - 53f * unit)
    val walk = progress(t, SplashTimeline.WALK_START, SplashTimeline.WALK_END)
    val arrive = 1f - (1f - walk).pow(2.2f)
    // Start just past the screen edge on each side.
    val startGap = (size.width / 2 + 60f * unit) / unit
    val gap = startGap * (1f - arrive)
    val meet = progress(t, SplashTimeline.WALK_END, SplashTimeline.MEET_END)
    val tuck = progress(t, SplashTimeline.MEET_END, SplashTimeline.TUCK_END)
    val morph = progress(t, SplashTimeline.TUCK_END, SplashTimeline.MORPH_END)
    val hop = if (meet in 0f..1f && t >= SplashTimeline.WALK_END) -6f * sin(PI.toFloat() * meet) else 0f

    val walking = t < SplashTimeline.WALK_END
    val stride = if (walking) Stride(phase = walk * 2f * PI.toFloat() * SplashTimeline.STRIDES, amplitude = 1f - smoothstep(0.8f, 1f, walk)) else null
    val pose = if (walking) Pose.Walk else Pose.Hug
    val mood = if (walking) Mood.Smile else Mood.Cheer
    val bodyAlpha = 1f - morph
    val featureAlpha = 1f - ease(tuck)

    withTransform({ translate(origin.x, origin.y); scale(unit, unit, pivot = Offset.Zero) }) {
        if (bodyAlpha > 0f) {
            withTransform({ translate(-gap, hop) }) { drawCharacters(Who.Left, pose, mood, sticker = true, stride = stride, alpha = bodyAlpha, featureAlpha = featureAlpha, keylineAlpha = featureAlpha) }
            withTransform({ translate(gap, hop) }) { drawCharacters(Who.Right, pose, mood, sticker = true, stride = stride, alpha = bodyAlpha, featureAlpha = featureAlpha, keylineAlpha = featureAlpha) }
        }
        if (morph > 0f) {
            drawMark(Art.MarkNightLeft, Art.MarkNightRight, tickProgress = ease(progress(t, SplashTimeline.MORPH_END, SplashTimeline.TICK_END)), alpha = ease(morph))
        }
    }
}

private fun progress(t: Float, start: Float, end: Float) = ((t - start) / (end - start)).coerceIn(0f, 1f)
private fun ease(p: Float) = 1f - (1f - p).pow(3)
private fun smoothstep(a: Float, b: Float, x: Float): Float {
    val p = ((x - a) / (b - a)).coerceIn(0f, 1f)
    return p * p * (3 - 2 * p)
}
