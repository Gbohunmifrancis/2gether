package app.heartpact.ui.play.skills

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.heartpact.i18n.tr
import app.heartpact.i18n.trn
import app.heartpact.ui.components.ButtonKind
import app.heartpact.ui.components.HeartButton
import app.heartpact.ui.play.HeartBurst
import app.heartpact.ui.play.SkillKind
import app.heartpact.ui.play.Stamp
import app.heartpact.ui.play.unitHeart
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.Shapes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/*
 * Levelling up in practice: the card that celebrates a cleared level (a stamp, stars landing one by one with a rising
 * chime, hearts bursting), the heart-shaped wipe that carries you to the next level, and the level chip in the header.
 */

/** A cleared level: which, how well, and the stars it earned. */
internal data class Cleared(val level: Int, val score: Int, val passMark: Int, val stars: Int)

/**
 * The celebration over the page once a level is cleared. The card springs up, "Cleared!" is stamped on it, the stars
 * land one after another (a chime a step higher each), and hearts burst. [onNext] goes on to the next level;
 * [onReplay] plays this level again.
 */
@Composable
internal fun LevelClearedCard(cleared: Cleared, kind: SkillKind, sounds: LevelSounds?, onNext: () -> Unit, onReplay: () -> Unit) {
    val colors = Heartpact.colors
    val motionOff = rememberMotionOff()
    val haptics = LocalHapticFeedback.current
    val rise = remember { Animatable(if (motionOff) 1f else 0f) }
    val scrim = remember { Animatable(if (motionOff) 1f else 0f) }
    var landed by remember { mutableStateOf(if (motionOff) cleared.stars else 0) }
    LaunchedEffect(cleared) {
        sounds?.cleared()
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        if (motionOff) return@LaunchedEffect
        launch { scrim.animateTo(1f, tween(260)) }
        rise.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 300f))
        delay(380)
        for (i in 0 until cleared.stars) {
            landed = i + 1
            sounds?.star(i)
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            delay(300)
        }
    }
    val said = trn(cleared.stars, "Level {n} cleared, with {count} star. Score {score}.", "Level {n} cleared, with {count} stars. Score {score}.",
        "n" to cleared.level, "score" to cleared.score)
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.page.copy(alpha = 0.82f * scrim.value))
            // The page under the card doesn't take taps while it's up.
            .clickable(remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        HeartBurst(cleared, Modifier.fillMaxSize(), count = 30, spread = 220.dp)
        Column(
            Modifier
                .padding(horizontal = 28.dp)
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .graphicsLayer {
                    val p = rise.value
                    translationY = (1f - p) * 240.dp.toPx()
                    scaleX = 0.86f + 0.14f * p
                    scaleY = 0.86f + 0.14f * p
                    alpha = p.coerceIn(0f, 1f)
                }
                .shadow(18.dp, Shapes.Dare, ambientColor = kind.colour.ink, spotColor = kind.colour.ink)
                .clip(Shapes.Dare)
                .background(colors.sticker)
                .semantics {
                    contentDescription = said
                    liveRegion = LiveRegionMode.Polite
                }
                .padding(horizontal = 22.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(tr("Level {n}", "n" to cleared.level), style = Heartpact.type.label, color = kind.colour.ink,
                modifier = Modifier.clip(Shapes.Pill).background(kind.colour.fill).padding(horizontal = 12.dp, vertical = 4.dp))
            Box(Modifier.padding(top = 14.dp)) { Stamp(tr("Cleared!"), tilt = -6f, key = cleared, delayMs = if (motionOff) 0 else 260) }
            Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                for (i in 0 until 3) LevelStar(filled = i < landed, earned = i < cleared.stars, big = i == 1)
            }
            Text(
                tr("Score {score}. {pass} cleared it.", "score" to cleared.score, "pass" to cleared.passMark),
                style = Heartpact.type.body, color = colors.ink2, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp),
            )
            HeartButton(tr("On to level {n}", "n" to cleared.level + 1), onNext, Modifier.padding(top = 18.dp))
            HeartButton(tr("Play this level again"), onReplay, Modifier.padding(top = 8.dp), kind = ButtonKind.Outline)
        }
    }
}

/** One star on the card: an outline until it lands, then filled with a pop. Unearned stars stay outlines. */
@Composable
private fun LevelStar(filled: Boolean, earned: Boolean, big: Boolean) {
    val pop = remember { Animatable(1f) }
    val motionOff = rememberMotionOff()
    LaunchedEffect(filled) {
        if (!filled || motionOff) return@LaunchedEffect
        pop.snapTo(1.6f)
        pop.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 420f))
    }
    val size: Dp = if (big) 56.dp else 44.dp
    val empty = Heartpact.colors.rule2
    Canvas(Modifier.size(size).graphicsLayer { scaleX = pop.value; scaleY = pop.value; rotationZ = if (filled) 0f else -8f }) {
        val star = starPath(Offset(this.size.width / 2, this.size.height / 2), this.size.minDimension / 2, this.size.minDimension / 4.6f)
        if (filled) {
            drawPath(star, StarGold)
            drawPath(star, StarEdge, style = Stroke(2.dp.toPx()))
            // A small shine on the upper left point.
            drawCircle(Color.White.copy(alpha = 0.55f), this.size.minDimension * 0.07f, Offset(this.size.width * 0.38f, this.size.height * 0.34f))
        } else {
            drawPath(star, if (earned) StarGold.copy(alpha = 0.2f) else empty.copy(alpha = 0.5f))
            drawPath(star, empty, style = Stroke(2.dp.toPx()))
        }
    }
}

/** The stars' gold, warm and bright on the sticker card by day and night, with a deeper edge. */
private val StarGold = app.heartpact.ui.theme.PlusGold.Bright
private val StarEdge = app.heartpact.ui.theme.PlusGold.Deep

/** A five-pointed star centred on [c]. */
private fun starPath(c: Offset, outer: Float, inner: Float): Path = Path().apply {
    for (i in 0 until 10) {
        val r = if (i % 2 == 0) outer else inner
        val a = -PI / 2 + PI * i / 5
        val p = c + Offset((cos(a) * r).toFloat(), (sin(a) * r).toFloat())
        if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
    }
    close()
}

/**
 * The wipe between levels: a heart-shaped window over the page closes to nothing (the page goes the game's colour),
 * the next level's number pops in the middle, then the heart opens again on the new round. [onClosed] swaps the round
 * while the window is shut; [onDone] when it has opened again. With motion off it just swaps.
 */
@Composable
internal fun HeartWipe(level: Int, kind: SkillKind, sounds: LevelSounds?, onClosed: () -> Unit, onDone: () -> Unit) {
    val motionOff = rememberMotionOff()
    val closed by rememberUpdatedState(onClosed)
    val done by rememberUpdatedState(onDone)
    // 0: the heart window is wide open (nothing covered); 1: shut.
    val shut = remember { Animatable(0f) }
    val number = remember { Animatable(0f) }
    LaunchedEffect(level) {
        if (motionOff) { closed(); done(); return@LaunchedEffect }
        sounds?.whoosh()
        shut.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
        closed()
        number.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 380f))
        delay(420)
        launch { number.animateTo(0f, tween(200)) }
        shut.animateTo(0f, tween(520, easing = LinearOutSlowInEasing))
        done()
    }
    val fill = kind.colour.fill
    val ink = kind.colour.ink
    Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null) {}, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            // The heart has to cover the screen's corners when open: size it by the diagonal.
            val full = hypot(this.size.width, this.size.height) * 2.1f
            val heartSize = full * (1f - shut.value)
            val cover = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(Offset.Zero, this@Canvas.size))
                if (heartSize > 1f) addPath(unitHeart(heartSize), Offset(this@Canvas.size.width / 2, this@Canvas.size.height * 0.46f))
            }
            drawPath(cover, fill)
            // A soft ring of the ink just inside the heart's edge, so the closing shape reads as a heart.
            if (heartSize > 1f && shut.value > 0.05f) translate(this.size.width / 2, this.size.height * 0.46f) {
                drawPath(unitHeart(heartSize), ink.copy(alpha = 0.25f * shut.value), style = Stroke(6.dp.toPx()))
            }
        }
        Column(
            Modifier.graphicsLayer { val p = number.value; scaleX = 0.6f + 0.4f * p; scaleY = 0.6f + 0.4f * p; alpha = p.coerceIn(0f, 1f) },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(tr("Level"), style = Heartpact.type.label, color = ink)
            Text("$level", style = Heartpact.type.display.copy(fontSize = 84.sp, lineHeight = 84.sp), color = ink)
        }
    }
}

/** The level in the header: "Level 4"; tapping it opens the levels reached so far, to replay an easier one. */
@Composable
internal fun LevelChip(kind: SkillKind, level: Int, reached: Int, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Text(
            tr("Level {n}", "n" to level) + if (reached > 1) "  ▾" else "",
            style = Heartpact.type.labelSmall,
            color = kind.colour.ink,
            modifier = Modifier
                .clip(Shapes.Pill)
                .background(kind.colour.fill)
                .clickable(enabled = reached > 1) { open = true }
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
        DropdownMenu(open, onDismissRequest = { open = false }) {
            for (n in reached downTo 1) {
                DropdownMenuItem(
                    text = { Text(tr("Level {n}", "n" to n), style = if (n == level) Heartpact.type.label else Heartpact.type.body) },
                    onClick = { open = false; if (n != level) onPick(n) },
                )
            }
        }
    }
}
