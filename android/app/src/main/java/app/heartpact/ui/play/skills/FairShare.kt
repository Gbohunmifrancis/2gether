package app.heartpact.ui.play.skills

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import app.heartpact.i18n.tr
import app.heartpact.ui.play.PlayInk
import app.heartpact.ui.play.SkillKind
import app.heartpact.ui.play.rememberPlayHaptics
import app.heartpact.ui.theme.Art
import app.heartpact.ui.theme.Heartpact
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Fair share: a treat to split with one swipe. The knife line follows the finger; on release the treat splits along
 * the whole line and the two pieces spring apart, each showing its share. Scored on how close the split is to half.
 */

internal class FairSharePlay(seed: Long) : SkillPlay(SkillKind.FairShare, seed) {
    val treat = Treat.of(seed)

    /** The two pieces after the cut: [first] is the left (or top) one, which the score and note talk about. */
    class Split(val a: Offset, val b: Offset, val first: List<Offset>, val second: List<Offset>, val firstShare: Float, val away: Offset)

    /** The swipe in progress (start, finger), in units. */
    var swipe by mutableStateOf<Pair<Offset, Offset>?>(null)
    var split by mutableStateOf<Split?>(null)
        private set
    /** Bumps when a swipe missed, to wobble the treat. */
    var missed by mutableIntStateOf(0)
        private set

    val describe: String = when (treat.kind) {
        TreatKind.Cookie -> tr("A heart cookie, iced pink.")
        TreatKind.Chocolate -> tr("A chocolate bar, one bite gone.")
        TreatKind.Pizza -> tr("A pizza, a slice already gone.")
        TreatKind.Jelly -> tr("A jelly, very wobbly.")
    }

    init { hint = describe }

    /** Cuts along the line through [a] and [b] (units). False (and a wobble) when it doesn't split the treat. */
    fun cutAlong(a: Offset, b: Offset): Boolean {
        if (split != null) return false
        val (p, q) = if ((b - a).getDistance() < 0.1f) emptyList<Offset>() to emptyList() else cut(treat.outline, a, b)
        val ap = if (p.size >= 3) area(p) else 0f
        val aq = if (q.size >= 3) area(q) else 0f
        if (ap + aq <= 0f || minOf(ap, aq) < (ap + aq) * 0.02f) {
            missed++
            hint = tr("All the way through. One straight swipe.")
            return false
        }
        // A cut that runs side to side splits top from bottom; otherwise it's left and right.
        val across = abs(b.x - a.x) > abs(b.y - a.y)
        val cp = centroid(p)
        val cq = centroid(q)
        val pFirst = if (across) cp.y < cq.y else cp.x < cq.x
        val first = if (pFirst) p else q
        val second = if (pFirst) q else p
        val share = (if (pFirst) ap else aq) / (ap + aq) * 100f
        val dir = (b - a) / (b - a).getDistance()
        var n = Offset(-dir.y, dir.x)
        val apart = centroid(first) - centroid(second)
        if (n.x * apart.x + n.y * apart.y < 0) n = -n
        split = Split(a, b, first, second, share, n)
        hint = ""
        finish(fairShareScore(share, across), 1100)
        return true
    }

    @Composable
    override fun Board(modifier: Modifier) {
        val colors = Heartpact.colors
        val haptics = rememberPlayHaptics()
        val measurer = rememberTextMeasurer()
        var size by remember { mutableStateOf(Size.Zero) }
        val fit = Fit.of(size, 1f, 1f, with(LocalDensity.current) { 20.dp.toPx() })
        val fitNow by rememberUpdatedState(fit)
        val apart = remember { Animatable(0f) }
        val slash = remember { Animatable(0f) }
        val labels = remember { Animatable(0f) }
        val wobble = remember { Animatable(0f) }
        val springy = moving(spring<Float>(dampingRatio = 0.42f, stiffness = 240f))
        val pop = moving(spring<Float>(dampingRatio = 0.5f, stiffness = 420f))
        val flash = moving(tween<Float>(160, easing = FastOutSlowInEasing))
        val cutNow = split
        LaunchedEffect(cutNow) {
            if (cutNow == null) return@LaunchedEffect
            haptics.thud()
            slash.animateTo(1f, flash)
            apart.animateTo(1f, springy)
        }
        LaunchedEffect(cutNow) {
            if (cutNow == null) return@LaunchedEffect
            kotlinx.coroutines.delay(320)
            labels.animateTo(1f, pop)
            haptics.tick()
        }
        LaunchedEffect(missed) {
            if (missed == 0) return@LaunchedEffect
            haptics.tick()
            wobble.snapTo(0f)
            wobble.animateTo(0f, spring(dampingRatio = 0.22f, stiffness = 700f), initialVelocity = 60f)
        }
        val labelStyle = Heartpact.type.labelSmall.copy(fontFamily = app.heartpact.ui.theme.Sora, fontSize = 13.sp)
        Canvas(
            modifier
                .onSizeChanged { size = it.toSize() }
                .semantics { contentDescription = describe + " " + tr("Swipe across it once to cut it in two.") }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        if (split != null) return@awaitEachGesture
                        val start = fitNow.unit(down.position)
                        var last = start
                        var inside = contains(treat.outline, start)
                        swipe = start to start
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            last = fitNow.unit(ch.position)
                            // A light tick as the knife crosses the edge, in or out.
                            val nowInside = contains(treat.outline, last)
                            if (nowInside != inside) { inside = nowInside; haptics.tick() }
                            swipe = start to last
                            ch.consume()
                        }
                        swipe = null
                        cutAlong(start, last)
                    }
                },
        ) {
            val s = cutNow
            if (s == null) {
                rotate(wobble.value * 0.08f, fit.px(Offset(0.5f, 0.5f))) { drawTreat(treat, fit) }
                swipe?.let { (a, b) ->
                    val pa = fit.px(a)
                    val pb = fit.px(b)
                    val d = pb - pa
                    if (d.getDistance() > 4f) {
                        // Where the cut will run, faint, then the knife itself.
                        val dir = d / d.getDistance()
                        val far = size.width + size.height
                        drawLine(colors.ink3.copy(alpha = 0.5f), pa - dir * far, pa + dir * far, 1.5.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx())))
                    }
                    drawLine(colors.ink, pa, pb, 3.5.dp.toPx(), cap = StrokeCap.Round)
                    drawCircle(PlayInk.Pen, 7.dp.toPx(), pb)
                    drawCircle(Art.Keyline, 3.dp.toPx(), pb)
                }
            } else {
                val gap = fit.px(0.075f) * apart.value
                val turn = 3.5f * apart.value
                val pieces = listOf(Triple(s.first, s.away * gap, -turn), Triple(s.second, -s.away * gap, turn))
                pieces.forEach { (poly, shift, deg) ->
                    rotate(deg, fit.px(centroid(poly)) + shift) { drawTreat(treat, fit, poly, shift) }
                }
                // The knife's line flashes across and fades as the pieces part.
                val pa = fit.px(s.a)
                val dir = (fit.px(s.b) - pa).let { it / it.getDistance() }
                val far = (size.width + size.height) * slash.value
                if (slash.value > 0f && apart.value < 1f) {
                    drawLine(PlayInk.Pen.copy(alpha = (1f - apart.value).coerceIn(0f, 1f)), pa - dir * far, pa + dir * far, 3.dp.toPx(), cap = StrokeCap.Round)
                }
                // Shares to a tenth, adding up to exactly 100.
                val tenth = (s.firstShare * 10f).roundToInt() / 10f
                pill(measurer, oneDecimal(tenth) + "%", fit.px(centroid(s.first)) + s.away * gap, labelStyle, Art.Left, scale = labels.value)
                pill(measurer, oneDecimal(100f - tenth) + "%", fit.px(centroid(s.second)) - s.away * gap, labelStyle, Art.Left, scale = labels.value)
            }
        }
    }
}
