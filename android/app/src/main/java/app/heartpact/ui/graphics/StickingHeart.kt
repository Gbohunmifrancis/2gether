package app.heartpact.ui.graphics

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import app.heartpact.ui.theme.Side

/**
 * Sticking your half (mockup 20), as a pure function of time so every frame can be tested and drawn.
 * 0 ms: your slot is empty beside your partner's half. 120 ms: your half is in the air, 1.1x, tilted
 * and nudged up-left. Then it presses down past its size and settles: at 240 ms the heart is whole.
 */
object StickTimeline {
    const val DROP_MS = 120f
    const val PRESS_MS = 200f
    const val END_MS = 240f

    /** Where your half is drawn. Offsets are in heart units: 1 = the heart's width. */
    data class Frame(val alpha: Float, val scale: Float, val rotation: Float, val dx: Float, val dy: Float, val whole: Boolean)

    private val easeOut = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    // Measured off the mockup: at 50 dp the half sits 2 dp left and 6 dp up, turned -6°.
    private const val LIFT_X = -2f / 50f
    private const val LIFT_Y = -6f / 50f
    private const val PRESS = 0.97f

    fun at(ms: Float): Frame {
        val t = ms.coerceIn(0f, END_MS)
        return when {
            t >= END_MS -> Frame(1f, 1f, 0f, 0f, 0f, whole = true)
            t <= DROP_MS -> {
                // Arriving: fades in already big and tilted, as if held just above the page.
                val p = easeOut.transform(t / DROP_MS)
                Frame(0.85f * p, 1.1f, -6f, LIFT_X, LIFT_Y, whole = false)
            }
            t <= PRESS_MS -> {
                // Landing: down onto the slot and a touch smaller than itself, the press.
                val p = easeOut.transform((t - DROP_MS) / (PRESS_MS - DROP_MS))
                Frame(0.85f + 0.15f * p, lerp(1.1f, PRESS, p), lerp(-6f, 0f, p), lerp(LIFT_X, 0f, p), lerp(LIFT_Y, 0f, p), whole = false)
            }
            else -> {
                val p = easeOut.transform((t - PRESS_MS) / (END_MS - PRESS_MS))
                Frame(1f, lerp(PRESS, 1f, p), 0f, 0f, 0f, whole = false)
            }
        }
    }

    private fun lerp(from: Float, to: Float, p: Float) = from + (to - from) * p
}

/**
 * Your half landing in the heart beside your partner's. Plays once; with Remove animations on (or
 * [frozenAtMs] set, for previews and screenshots) it shows that frame and stays. [onWhole] runs once
 * when the heart is complete, with a light tap of haptics as it lands.
 */
@Composable
fun StickingHeart(mySide: Side, size: Dp, modifier: Modifier = Modifier, frozenAtMs: Float? = null, onWhole: () -> Unit = {}) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val whole by rememberUpdatedState(onWhole)
    var time by remember { mutableFloatStateOf(frozenAtMs ?: 0f) }
    LaunchedEffect(frozenAtMs) {
        if (frozenAtMs != null) { time = frozenAtMs; return@LaunchedEffect }
        val animationsOff = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        if (!animationsOff) {
            val start = withFrameMillis { it }
            while (time < StickTimeline.END_MS) time = withFrameMillis { (it - start).toFloat() }.coerceAtMost(StickTimeline.END_MS)
        }
        time = StickTimeline.END_MS
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        whole()
    }
    val frame = StickTimeline.at(time)
    val mine = if (mySide == Side.Left) HalfState.Stuck to HalfState.Empty else HalfState.Empty to HalfState.Stuck
    val px = with(LocalDensity.current) { size.toPx() }
    Box(modifier.size(size)) {
        if (frame.whole) {
            HeartSticker(HalfState.Stuck, HalfState.Stuck, size)
        } else {
            // The partner's half is already stuck; yours waits as a dashed slot until it lands.
            HeartSticker(if (mySide == Side.Left) HalfState.Slot else HalfState.Stuck, if (mySide == Side.Right) HalfState.Slot else HalfState.Stuck, size)
            HeartSticker(mine.first, mine.second, size, Modifier.graphicsLayer {
                alpha = frame.alpha
                scaleX = frame.scale
                scaleY = frame.scale
                // Tilt away from the fold: the left half leans left, the right half right.
                rotationZ = if (mySide == Side.Left) frame.rotation else -frame.rotation
                translationX = (if (mySide == Side.Left) frame.dx else -frame.dx) * px
                translationY = frame.dy * px
            })
        }
    }
}
