package app.heartpact.ui.play.skills

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import app.heartpact.i18n.tr
import app.heartpact.ui.play.BlobColour
import app.heartpact.ui.play.PlayInk
import app.heartpact.ui.play.SkillKind
import app.heartpact.ui.play.rememberPlayHaptics
import app.heartpact.ui.theme.Art
import app.heartpact.ui.theme.Heartpact
import kotlinx.coroutines.delay
import kotlin.math.sqrt

/*
 * Kiss the center: an odd shape, and one tap where you think its middle is (its centre of area, where it would
 * balance on a fingertip). The kiss drops in and squashes as it lands; then the true centre shows, with a line
 * between the two.
 */

internal class KissPlay(seed: Long) : SkillPlay(SkillKind.KissTheCenter, seed) {
    // Its own shapes, even from the same seed as a Fair share round: lopsided ones, with no obvious middle.
    val treat = Treat.of(seed * 31 + 7, odd = true)
    val centre = centroid(treat.outline)
    val size = sqrt(area(treat.outline))
    var kiss by mutableStateOf<Offset?>(null)
        private set

    init { hint = tr("Where would it balance on a fingertip?") }

    fun kissAt(u: Offset) {
        if (kiss != null) return
        kiss = u
        hint = ""
        finish(kissScore(u, centre, size), 1500)
    }

    @Composable
    override fun Board(modifier: Modifier) {
        val colors = Heartpact.colors
        val haptics = rememberPlayHaptics()
        var box by remember { mutableStateOf(Size.Zero) }
        val fit = Fit.of(box, 1f, 1f, with(LocalDensity.current) { 20.dp.toPx() })
        val fitNow by rememberUpdatedState(fit)
        val drop = remember { Animatable(0f) }
        val land = remember { Animatable(1f) }
        val mark = remember { Animatable(0f) }
        val line = remember { Animatable(0f) }
        val fall = moving(tween<Float>(190, easing = FastOutSlowInEasing))
        val squash = moving(spring<Float>(dampingRatio = 0.3f, stiffness = 520f))
        val pop = moving(spring<Float>(dampingRatio = 0.45f, stiffness = 400f))
        val draw = moving(tween<Float>(420, easing = LinearOutSlowInEasing))
        val placed = kiss
        LaunchedEffect(placed) {
            if (placed == null) return@LaunchedEffect
            drop.animateTo(1f, fall)
            haptics.thud()
            land.snapTo(0f)
            land.animateTo(1f, squash)
        }
        LaunchedEffect(placed) {
            if (placed == null) return@LaunchedEffect
            delay(560)
            haptics.tick()
            mark.animateTo(1f, pop)
            line.animateTo(1f, draw)
        }
        Canvas(
            modifier
                .onSizeChanged { box = it.toSize() }
                .semantics { contentDescription = tr("An odd shape. Tap once where you think its center is.") }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        kissAt(fitNow.unit(down.position))
                    }
                },
        ) {
            drawTreat(treat, fit)
            val k = placed ?: return@Canvas
            val at = fit.px(k)
            val c = fit.px(centre)
            drawKiss(at, drop.value, land.value)
            // The dashed line from the kiss to the true centre, drawn on.
            if (line.value > 0f) {
                drawLine(colors.ink.copy(alpha = 0.85f), at, at + (c - at) * line.value, 2.5.dp.toPx(), cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 5.dp.toPx())))
            }
            if (mark.value > 0f) {
                val r = 10.dp.toPx() * mark.value
                drawCircle(Art.Keyline, r + 3.dp.toPx(), c)
                drawCircle(colors.left.art.takeIf { !colors.isNight } ?: Art.Left, r, c, style = Stroke(2.5.dp.toPx()))
                drawCircle(colors.left.art.takeIf { !colors.isNight } ?: Art.Left, 3.dp.toPx() * mark.value, c)
            }
        }
    }

    /** The kiss at [at]: drops in from a little above, big and faint ([d] 0 to 1), then squashes flat and springs back ([l]). */
    private fun DrawScope.drawKiss(at: Offset, d: Float, l: Float) {
        val w = 44.dp.toPx()
        val sx = (1.7f - 0.7f * d) * (1f + 0.3f * (1f - l))
        val sy = (1.7f - 0.7f * d) * (1f - 0.3f * (1f - l))
        val lift = (1f - d) * 36.dp.toPx()
        withTransform({
            translate(at.x, at.y - lift)
            scale(sx, sy, Offset.Zero)
            rotate(-8f, Offset.Zero)
        }) {
            val lips = lipsPath(w)
            drawPath(lips, Art.Keyline.copy(alpha = d), style = Stroke(5.dp.toPx() / sx, join = StrokeJoin.Round))
            drawPath(lips, PlayInk.Pen.copy(alpha = d))
            drawPath(lipsSeam(w), BlobColour.Rose.ink.copy(alpha = d), style = Stroke(2.dp.toPx() / sx, cap = StrokeCap.Round))
            drawCircle(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.6f * d), w * 0.05f, Offset(-w * 0.2f, -w * 0.2f))
        }
    }
}
