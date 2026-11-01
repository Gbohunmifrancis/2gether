package app.heartpact.ui.components

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.heartpact.i18n.tr
import app.heartpact.push.BannerArt
import app.heartpact.push.BannerQueue
import app.heartpact.push.InAppNotice
import app.heartpact.push.bannerArt
import app.heartpact.ui.graphics.Character
import app.heartpact.ui.graphics.LocalStillLogo
import app.heartpact.ui.graphics.LogoMark
import app.heartpact.ui.graphics.Mood
import app.heartpact.ui.graphics.Pose
import app.heartpact.ui.graphics.Who
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.LocalActor
import app.heartpact.ui.theme.PlusGold
import app.heartpact.ui.theme.Side
import app.heartpact.ui.theme.partner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The in-app banner for [queue]'s current notice: it drops in from the top on a spring, pulses once with a soft tap
 * under the finger, opens its target when tapped, goes up when swiped up, and leaves by itself after five seconds
 * (held while a finger is on it). The next one waits its turn. While [blocked] (the splash) nothing shows and the queue
 * waits. With animations off it only fades.
 */
@Composable
fun InAppBannerHost(queue: BannerQueue, blocked: Boolean, onOpen: (InAppNotice) -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val notice = queue.showing ?: return
    if (blocked) return
    key(notice.id) { MovingBanner(notice, onOpen, onDone, modifier) }
}

@Composable
private fun MovingBanner(notice: InAppNotice, onOpen: (InAppNotice) -> Unit, onDone: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val still = LocalStillLogo.current || LocalInspectionMode.current
    val fadeOnly = remember { still || Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var height by remember { mutableFloatStateOf(with(density) { 140.dp.toPx() }) }
    val hidden = -(height + with(density) { 64.dp.toPx() })
    val y = remember { Animatable(if (fadeOnly) 0f else hidden) }
    val alpha = remember { Animatable(if (still) 1f else 0f) }
    val pulse = remember { Animatable(1f) }
    var held by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }

    fun leave(then: () -> Unit = {}) {
        if (leaving) return
        leaving = true
        scope.launch {
            if (fadeOnly) alpha.animateTo(0f, tween(180)) else {
                launch { alpha.animateTo(0f, tween(260, delayMillis = 60)) }
                y.animateTo(-(height + with(density) { 64.dp.toPx() }), tween(260))
            }
            then()
            onDone()
        }
    }

    LaunchedEffect(Unit) {
        if (!still) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            if (fadeOnly) alpha.animateTo(1f, tween(220))
            else {
                launch { alpha.animateTo(1f, tween(140)) }
                y.animateTo(0f, spring(dampingRatio = 0.62f, stiffness = 360f))
                // Once, like a heartbeat, as it lands.
                pulse.animateTo(1.06f, tween(130))
                pulse.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 420f))
            }
        }
        var left = BannerQueue.SHOW_MS
        while (left > 0) {
            delay(100)
            if (!held) left -= 100
        }
        leave()
    }

    val dismiss = tr("Dismiss")
    Box(modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp).padding(top = 6.dp), contentAlignment = Alignment.TopCenter) {
        InAppBannerCard(
            notice,
            Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .onSizeChanged { height = it.height.toFloat() }
                .graphicsLayer {
                    translationY = y.value
                    this.alpha = alpha.value
                    scaleX = 1f + (pulse.value - 1f) * 0.35f
                    scaleY = 1f + (pulse.value - 1f) * 0.35f
                }
                .pointerInput(notice.id) {
                    val far = 44.dp.toPx()
                    detectVerticalDragGestures(
                        onDragStart = { held = true },
                        onDragEnd = {
                            held = false
                            if (y.value < -far) leave() else scope.launch { y.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 500f)) }
                        },
                        onDragCancel = {
                            held = false
                            scope.launch { y.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 500f)) }
                        },
                    ) { change, dy ->
                        change.consume()
                        // Up follows the finger; down only gives a little.
                        val next = if (dy < 0 || y.value < 0) y.value + dy else y.value + dy * 0.2f
                        scope.launch { y.snapTo(next.coerceAtMost(24.dp.toPx())) }
                    }
                }
                .clickable(remember { MutableInteractionSource() }, indication = null) { leave { onOpen(notice) } }
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = notice.title + ". " + notice.body
                    customActions = listOf(CustomAccessibilityAction(dismiss) { leave(); true })
                },
            artPulse = pulse.value,
        )
    }
}

/** The banner itself, still: the art for the kind of notice, its title and line, and a grab bar that says "swipe up". */
@Composable
fun InAppBannerCard(notice: InAppNotice, modifier: Modifier = Modifier, artPulse: Float = 1f) {
    val colors = Heartpact.colors
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier
            .shadow(14.dp, shape, ambientColor = colors.left.art.copy(alpha = 0.45f), spotColor = colors.left.art.copy(alpha = 0.5f))
            .clip(shape)
            .background(colors.sticker)
            .border(1.5.dp, colors.rule2, shape)
            .padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BannerArtwork(bannerArt(notice.type), 54.dp, Modifier.graphicsLayer { scaleX = artPulse; scaleY = artPulse })
            Column(Modifier.weight(1f)) {
                Text(notice.title, style = Heartpact.type.label, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (notice.body.isNotBlank()) {
                    Text(notice.body, style = Heartpact.type.body, color = colors.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
        Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp).width(32.dp).height(4.dp).clip(CircleShape).background(colors.rule2))
    }
}

/** The partner (the other half of the heart from the person holding the phone), both of you, or the heart, by kind. */
@Composable
fun BannerArtwork(art: BannerArt, size: Dp, modifier: Modifier = Modifier) {
    val colors = Heartpact.colors
    val partnerSide = if (LocalActor.current == Side.Left) Side.Right else Side.Left
    val partner = if (partnerSide == Side.Left) Who.Left else Who.Right
    val tint = when (art) {
        BannerArt.Plus -> PlusGold.Pale
        else -> colors.partner(partnerSide).soft
    }
    Box(modifier.size(size).clip(CircleShape).background(tint), contentAlignment = Alignment.Center) {
        val figure = size * 0.8f
        // One half sits off the middle of the characters' box (the left half at 0..50 of its units, the right at
        // 50..100): nudge it back to the middle of the circle.
        val nudge = figure * (25f / 144f) * (if (partner == Who.Left) 1f else -1f)
        @Composable fun Character(who: Who, pose: Pose, size: Dp, mood: Mood = if (pose == Pose.Cheer) Mood.Cheer else Mood.Smile) =
            app.heartpact.ui.graphics.Character(who, pose, size, if (who == Who.Both) Modifier else Modifier.absoluteOffset(x = nudge), mood = mood)
        when (art) {
            BannerArt.Message -> Character(partner, Pose.Wave, figure)
            BannerArt.DareDone -> Character(partner, Pose.Cheer, figure)
            BannerArt.DareSet -> Character(partner, Pose.Stand, figure)
            BannerArt.CallOff -> Character(partner, Pose.Stand, figure, mood = Mood.Worried)
            BannerArt.Live -> Character(partner, Pose.Walk, figure)
            BannerArt.Missed -> Character(partner, Pose.Rest, figure, mood = Mood.Worried)
            BannerArt.Game -> Character(Who.Both, Pose.Cheer, figure)
            BannerArt.Cycle -> Character(partner, Pose.Rest, figure)
            BannerArt.Album, BannerArt.Couple -> Character(Who.Both, Pose.Hug, figure)
            BannerArt.Plus -> Character(Who.Both, Pose.Cheer, figure)
            BannerArt.Heart -> LogoMark(size * 0.56f, night = colors.isNight)
        }
    }
}
