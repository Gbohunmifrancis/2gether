package app.heartpact.ui.pair

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.heartpact.i18n.tr
import app.heartpact.ui.components.HeartButton
import app.heartpact.ui.components.rememberStill
import app.heartpact.ui.graphics.CHAR_VB_H
import app.heartpact.ui.graphics.CHAR_VB_W
import app.heartpact.ui.graphics.CHAR_VB_X
import app.heartpact.ui.graphics.CHAR_VB_Y
import app.heartpact.ui.graphics.Mood
import app.heartpact.ui.graphics.Pose
import app.heartpact.ui.graphics.Stride
import app.heartpact.ui.graphics.Who
import app.heartpact.ui.graphics.drawCharacters
import app.heartpact.ui.graphics.inUnits
import app.heartpact.ui.play.skills.rememberLevelSounds
import app.heartpact.ui.theme.Heartpact
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The celebration's clock, in ms: the halves walk in, join, the text arrives, and the app moves on. */
object LinkedTimeline {
    const val WALK_MS = 1_000
    const val TEXT_MS = 1_250
    const val END_MS = 3_200
    /** Held still (animations off): the whole heart at once, a moment to read it, then on. */
    const val STILL_END_MS = 2_400

    /** How far apart the halves are, 1 at the start to 0 when they meet; eased so they slow as they arrive. */
    fun apart(ms: Int): Float {
        val t = (ms.toFloat() / WALK_MS).coerceIn(0f, 1f)
        return 1f - (1f - (1f - t) * (1f - t))
    }

    fun joined(ms: Int) = ms >= WALK_MS
}

/**
 * Linked: the two halves walk in from either side and become the whole heart, with a soft tap and the level-up chime
 * (at its quieter volume, through the app's one gate), then "You're linked with {name}" and on into the app. With
 * animations off it shows the whole heart straight away. A tap after they've met moves on sooner.
 */
@Composable
fun LinkedCelebration(name: String, onDone: () -> Unit, modifier: Modifier = Modifier, frozenAtMs: Int? = null) {
    val colors = Heartpact.colors
    val still = rememberStill() || frozenAtMs != null
    val haptics = LocalHapticFeedback.current
    val sounds = rememberLevelSounds()
    val done by rememberUpdatedState(onDone)
    var ms by remember { mutableStateOf(frozenAtMs ?: if (still) LinkedTimeline.END_MS else 0) }
    val pop = remember { Animatable(if (still) 1f else 0.82f) }
    var left by remember { mutableStateOf(false) }
    fun leave() { if (!left) { left = true; done() } }

    LaunchedEffect(Unit) {
        if (frozenAtMs != null) return@LaunchedEffect
        if (still) {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            sounds?.cleared()
            delay(LinkedTimeline.STILL_END_MS.toLong())
            leave()
            return@LaunchedEffect
        }
        val start = System.nanoTime()
        var chimed = false
        while (ms < LinkedTimeline.END_MS) {
            delay(16)
            ms = ((System.nanoTime() - start) / 1_000_000).toInt()
            if (!chimed && LinkedTimeline.joined(ms)) {
                chimed = true
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                sounds?.cleared()
                launch { pop.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 260f)) }
            }
        }
        leave()
    }

    val bob = if (still) 0f else rememberInfiniteTransition(label = "linked-bob")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(1_400, easing = LinearEasing), RepeatMode.Reverse), label = "bob").value
    val joined = LinkedTimeline.joined(ms)
    val textAlpha = if (still) 1f else ((ms - LinkedTimeline.TEXT_MS) / 450f).coerceIn(0f, 1f)

    Column(
        modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { if (joined) leave() }
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxWidth().size(260.dp)) {
                val unit = size.height / CHAR_VB_H
                val drawW = CHAR_VB_W * unit
                val apart = LinkedTimeline.apart(ms)
                val travel = size.width * 0.42f * apart
                val ox = (size.width - drawW) / 2
                if (!joined) {
                    // Walking in step toward each other, each from its own side.
                    val stride = Stride(phase = ms / 1000f * 13f, amplitude = apart.coerceAtLeast(0.15f))
                    translate(ox - travel, 0f) { inUnits(CHAR_VB_X, CHAR_VB_Y, CHAR_VB_W, CHAR_VB_H) { drawCharacters(Who.Left, Pose.Walk, Mood.Smile, true, stride) } }
                    translate(ox + travel, 0f) { inUnits(CHAR_VB_X, CHAR_VB_Y, CHAR_VB_W, CHAR_VB_H) { drawCharacters(Who.Right, Pose.Walk, Mood.Smile, true, stride) } }
                } else {
                    val lift = -bob * 6.dp.toPx()
                    scale(pop.value, pivot = center) {
                        translate(ox, lift) { inUnits(CHAR_VB_X, CHAR_VB_Y, CHAR_VB_W, CHAR_VB_H) { drawCharacters(Who.Both, Pose.Cheer, Mood.Cheer, true) } }
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().alpha(textAlpha).semantics { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (name.isNotBlank()) tr("You're linked with {name}", "name" to name) else tr("You're linked"),
                style = Heartpact.type.headline, color = colors.ink, textAlign = TextAlign.Center)
            Text(tr("Your page is ready for two. Set your first dare whenever you like."), style = Heartpact.type.bodyLarge, color = colors.ink2, textAlign = TextAlign.Center)
        }
        HeartButton(tr("Let's go"), { leave() }, Modifier.padding(top = 28.dp, bottom = 16.dp).alpha(textAlpha), enabled = joined)
    }
}

/** Joining through a link: my half waits by the partner's dashed slot while it goes through. */
@Composable
fun JoiningMoment(name: String, modifier: Modifier = Modifier) {
    val colors = Heartpact.colors
    val still = rememberStill()
    val lean = if (still) 0.5f else rememberInfiniteTransition(label = "joining")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "lean").value
    Column(modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxWidth().size(230.dp)) {
                val unit = size.height / CHAR_VB_H
                val drawW = CHAR_VB_W * unit
                val ox = (size.width - drawW) / 2
                // Close, not yet touching: they lean in as it goes through.
                val gap = size.width * (0.14f - 0.05f * lean)
                translate(ox - gap, 0f) { inUnits(CHAR_VB_X, CHAR_VB_Y, CHAR_VB_W, CHAR_VB_H) { drawCharacters(Who.Left, Pose.Hug, Mood.Smile, true) } }
                translate(ox + gap, 0f) { inUnits(CHAR_VB_X, CHAR_VB_Y, CHAR_VB_W, CHAR_VB_H) { drawCharacters(Who.Right, Pose.Hug, Mood.Smile, true) } }
            }
        }
        Column(Modifier.fillMaxWidth().padding(bottom = 64.dp).semantics { liveRegion = LiveRegionMode.Polite }, horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(if (name.isNotBlank()) tr("Joining {name}…", "name" to name) else tr("Joining your partner…"), style = Heartpact.type.headline, color = colors.ink, textAlign = TextAlign.Center)
            Spinner(still)
        }
    }
}

/** The small working spinner; held as a still arc when animations are off (and in screenshots, which wait for stillness). */
@Composable
internal fun Spinner(still: Boolean, modifier: Modifier = Modifier) {
    if (still) CircularProgressIndicator(progress = { 0.3f }, modifier = modifier.size(26.dp), color = Heartpact.actor.action, strokeWidth = 2.5.dp, trackColor = androidx.compose.ui.graphics.Color.Transparent)
    else CircularProgressIndicator(modifier.size(26.dp), color = Heartpact.actor.action, strokeWidth = 2.5.dp)
}
