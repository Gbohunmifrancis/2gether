package app.heartpact.ui.plus

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.heartpact.billing.PlusOffers
import app.heartpact.data.PlanLimits
import app.heartpact.i18n.tr
import app.heartpact.i18n.trn
import app.heartpact.ui.components.HeartButton
import app.heartpact.ui.components.GoldSparkle
import app.heartpact.ui.components.PlusBadge
import app.heartpact.ui.components.drawSparkle
import app.heartpact.ui.components.StickerCard
import app.heartpact.ui.components.rememberStill
import app.heartpact.ui.theme.Art
import app.heartpact.ui.graphics.HalfState
import app.heartpact.ui.graphics.HeartSticker
import app.heartpact.ui.graphics.Who
import app.heartpact.ui.play.PlayInk
import app.heartpact.ui.play.Reaction
import app.heartpact.ui.play.ReactingCharacter
import app.heartpact.ui.play.skills.rememberLevelSounds
import app.heartpact.ui.play.unitHeart
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.PlusGold
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The celebration's words, kept apart from layout so they can be tested as text. */
object CelebrationText {
    fun title(celebration: Celebration, partnerName: String): String = when (celebration.kind) {
        CelebrationKind.BoughtByMe -> tr("Welcome to Heartpact Plus")
        CelebrationKind.BoughtByPartner -> tr("{partner} got you Heartpact Plus", "partner" to partnerName)
        CelebrationKind.Gift -> tr("A gift from Heartpact")
    }

    fun subtitle(celebration: Celebration, partnerName: String): String = when (celebration.kind) {
        CelebrationKind.BoughtByMe -> tr("It covers {partner} too. Here's what you both get now.", "partner" to partnerName)
        CelebrationKind.BoughtByPartner -> tr("It covers you both. Here's what you get now.")
        CelebrationKind.Gift -> celebration.untilUtc?.let { tr("Plus is on us until {date}. Here's what you both get.", "date" to PlusText.date(it)) }
            ?: tr("Plus is on us. Here's what you both get.")
    }

    /** What Plus unlocks, one short line each, from the server's Plus limits. */
    fun benefits(limits: PlanLimits?): List<String> {
        if (limits == null) return emptyList()
        return listOfNotNull(
            limits.activeDaresPerPerson?.let { trn(it, "Up to {count} daily dare each", "Up to {count} daily dares each") } ?: tr("Unlimited daily dares"),
            limits.foodScansPerDay.takeIf { it > 0 }?.let { trn(it, "{count} food photo estimate a day", "{count} food photo estimates a day") },
            limits.memoriesBytes.takeIf { it > 0 }?.let { tr("{size} of shared album space", "size" to PlusOffers.storage(it)) },
            limits.questionHistoryDays.takeIf { it > 0 }?.let { trn(it, "{count} day of question history", "{count} days of question history") },
        )
    }
}

/** The celebration over everything, full screen; Back or "Let's go" closes it. */
@Composable
fun PlusCelebrationDialog(celebration: Celebration, partnerName: String, onDone: () -> Unit) {
    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        PlusCelebrationScreen(celebration, partnerName, onDone)
    }
}

/**
 * Plus has started: the two halves cheer and hop under a burst of hearts and gold sparkles, with what Plus brings as
 * short lines and one button. A soft chime plays if the ringer is on. With animations off it is a still picture: the
 * characters, the gold glow and a few sparkles, no burst.
 */
@Composable
fun PlusCelebrationScreen(celebration: Celebration, partnerName: String, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Heartpact.colors
    val still = rememberStill()
    val sounds = rememberLevelSounds()
    val haptics = LocalHapticFeedback.current
    val rise = remember { Animatable(if (still) 1f else 0f) }
    val burst = remember { Animatable(if (still) 1f else 0f) }
    var hops by remember { mutableIntStateOf(0) }
    var origin by remember { mutableStateOf<Offset?>(null) }
    var rootTopLeft by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(Unit) {
        if (still) {
            sounds?.cleared()
            return@LaunchedEffect
        }
        launch { rise.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 240f)) }
        delay(280)
        sounds?.cleared()
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        hops++
        launch { burst.animateTo(1f, tween(2400, easing = LinearEasing)) }
        // A few more happy hops, then they settle and breathe.
        repeat(2) {
            delay(1100)
            hops++
        }
    }
    BackHandler(onBack = onDone)
    val title = CelebrationText.title(celebration, partnerName)
    val benefits = CelebrationText.benefits(celebration.limits)
    Box(
        modifier
            .fillMaxSize()
            .background(colors.page)
            .onGloballyPositioned { rootTopLeft = it.boundsInRoot().topLeft }
            .semantics { paneTitle = title },
    ) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars), horizontalAlignment = Alignment.CenterHorizontally) {
            // Centred in the space above the button on a tall phone; scrolls on a short one or with large text.
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth()
                        .height(250.dp)
                        .onGloballyPositioned { origin = it.boundsInRoot().center }
                        .clearAndSetSemantics {},
                    contentAlignment = Alignment.Center,
                ) {
                    Glow(Modifier.fillMaxSize(), twinkle = !still, settled = burst.value)
                    Column(
                        Modifier.graphicsLayer {
                            val p = rise.value
                            translationY = (1f - p) * 120.dp.toPx()
                            scaleX = 0.7f + 0.3f * p
                            scaleY = 0.7f + 0.3f * p
                            alpha = p.coerceIn(0f, 1f)
                        },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // The pact made whole, held up over their heads.
                        HeartSticker(HalfState.Stuck, HalfState.Stuck, 46.dp, Modifier.graphicsLayer { rotationZ = -8f })
                        ReactingCharacter(Who.Both, Reaction.Cheer, 168.dp, Modifier.padding(top = 2.dp), key = hops)
                    }
                }
                PlusBadge(Modifier.padding(top = 6.dp), height = 26.dp)
                Text(
                    title,
                    style = Heartpact.type.headline,
                    color = colors.ink,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 14.dp).semantics {
                        heading()
                        liveRegion = LiveRegionMode.Polite
                    },
                )
                Text(
                    CelebrationText.subtitle(celebration, partnerName),
                    style = Heartpact.type.bodyLarge,
                    color = colors.ink2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp).widthIn(max = 420.dp),
                )
                if (benefits.isNotEmpty()) {
                    StickerCard(Modifier.padding(top = 20.dp).widthIn(max = 420.dp).fillMaxWidth()) {
                        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            benefits.forEach { line ->
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    GoldSparkle(18.dp)
                                    Text(line, style = Heartpact.type.label, color = colors.ink)
                                }
                            }
                        }
                    }
                }
            } }
            HeartButton(tr("Let's go"), onDone, Modifier.padding(horizontal = 24.dp).padding(top = 12.dp, bottom = 20.dp).widthIn(max = 420.dp))
        }
        if (!still && burst.value < 1f) {
            val at = origin?.let { it - rootTopLeft }
            if (at != null) Burst(burst.value, at, Modifier.fillMaxSize().clearAndSetSemantics {})
        }
    }
}

/** A soft gold glow behind the characters, with sparkles and small hearts around them that twinkle once settled. */
@Composable
private fun Glow(modifier: Modifier, twinkle: Boolean, settled: Float) {
    val night = Heartpact.colors.isNight
    val loop = if (twinkle) {
        val t = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            while (true) {
                t.snapTo(0f)
                t.animateTo(1f, tween(2600, easing = LinearEasing))
            }
        }
        t.value
    } else 0f
    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height * 0.55f)
        val r = size.minDimension * 0.62f
        drawCircle(Brush.radialGradient(listOf(PlusGold.Bright.copy(alpha = if (night) 0.30f else 0.42f), PlusGold.Bright.copy(alpha = 0f)), c, r), r, c)
        // Fixed spots around the pair, as fractions of the box; with motion, they fade in as the burst settles.
        val appear = if (twinkle) ((settled - 0.35f) / 0.4f).coerceIn(0f, 1f) else 1f
        SPARKLES.forEachIndexed { i, (fx, fy, s) ->
            val beat = if (twinkle) 0.6f + 0.4f * sin((loop + i * 0.37f) * 2f * PI.toFloat()) else 1f
            val p = Offset((size.width / 2 + fx * size.height).coerceIn(16.dp.toPx(), size.width - 16.dp.toPx()), size.height * fy)
            if (i % 3 == 2) {
                withTransform({ translate(p.x, p.y); rotate(if (i % 2 == 0) -14f else 12f, Offset.Zero) }) {
                    drawPath(unitHeart(s * 2.2f * density), if (i % 2 == 0) PlayInk.PenSoft else Art.Right, alpha = appear * beat)
                }
            } else {
                drawSparkle(p, s * density * beat, PlusGold.Bright.copy(alpha = appear), PlusGold.Deep.copy(alpha = appear))
            }
        }
    }
}

/** Where the still sparkles sit: x from the centre in box heights, y as a fraction of the box, and size in dp. */
private val SPARKLES = listOf(
    Triple(-0.62f, 0.18f, 9f), Triple(0.58f, 0.12f, 11f), Triple(-0.74f, 0.62f, 6f),
    Triple(0.70f, 0.52f, 8f), Triple(-0.46f, 0.86f, 7f), Triple(0.50f, 0.88f, 6f),
    Triple(-0.30f, 0.06f, 6f), Triple(0.34f, 0.30f, 5f), Triple(0.80f, 0.80f, 6f),
)

/** Hearts, gold sparkles and confetti flying out of [at] and falling, for [p] from 0 to 1. */
@Composable
private fun Burst(p: Float, at: Offset, modifier: Modifier) {
    val hearts = listOf(PlayInk.Pen, PlayInk.PenSoft, Art.Right, Art.Left, PlayInk.Blush)
    val golds = listOf(PlusGold.Bright, PlusGold.Deep, PlusGold.Pale)
    Canvas(modifier) {
        val fade = if (p > 0.7f) (1f - (p - 0.7f) / 0.3f) else 1f
        val grow = if (p < 0.08f) p / 0.08f else 1f
        val out = 1f - (1f - p) * (1f - p) * (1f - p)
        val fall = 320.dp.toPx() * p * p
        repeat(PIECES) { i ->
            val angle = (i / PIECES.toFloat()) * 2f * PI.toFloat() + sin(i * 12.9898f) * 0.5f
            val speed = size.minDimension * (0.35f + 0.4f * ((sin(i * 78.233f) + 1f) / 2f))
            val pos = Offset(at.x + cos(angle) * speed * out, at.y + sin(angle) * speed * out * 0.9f + fall * (0.6f + (i % 4) * 0.15f))
            val spin = angle * 50f + p * 360f * (if (i % 2 == 0) 1f else -1f)
            when (i % 3) {
                0 -> withTransform({ translate(pos.x, pos.y); rotate(spin * 0.3f, Offset.Zero) }) {
                    drawPath(unitHeart((9.dp.toPx() + (i % 4) * 3.dp.toPx()) * grow), hearts[i % hearts.size], alpha = fade)
                }
                1 -> drawSparkle(pos, (5.dp.toPx() + (i % 3) * 2.dp.toPx()) * grow * (0.8f + 0.2f * sin(p * 30f + i)), golds[i % golds.size].copy(alpha = fade), PlusGold.Deep.copy(alpha = fade))
                else -> rotate(spin, pos) {
                    // Confetti: a little gold ribbon turning over (its height shrinks and grows as it spins).
                    val w = 5.dp.toPx() * grow
                    val h = 10.dp.toPx() * grow * kotlin.math.abs(cos(p * 14f + i))
                    drawRect(golds[(i / 3) % golds.size], Offset(pos.x - w / 2, pos.y - h / 2), Size(w, h.coerceAtLeast(1f)), alpha = fade)
                }
            }
        }
    }
}

private const val PIECES = 72
