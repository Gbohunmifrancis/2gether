package app.heartpact.ui.onboarding

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.heartpact.i18n.tr
import app.heartpact.ui.components.ButtonKind
import app.heartpact.ui.components.HeartButton
import app.heartpact.ui.graphics.Character
import app.heartpact.ui.graphics.Mood
import app.heartpact.ui.graphics.Pose
import app.heartpact.ui.graphics.Who
import app.heartpact.ui.theme.Art
import app.heartpact.ui.theme.Heartpact
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Three pages before the first sign-up, told as one little story on a shared stage: the two halves start apart while a
 * dare rings, walk toward each other as you swipe and click together into the heart, then the heart keeps what's
 * private safe. The background colour and the halves move with your finger, so the pages feel like one scene.
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit, initialPage: Int = 0) {
    val pages = 3
    val pager = rememberPagerState(initialPage = initialPage) { pages }
    val scope = rememberCoroutineScope()
    val colors = Heartpact.colors
    // 0 on the first page, 2 on the last, fractional while swiping.
    val progress = { (pager.currentPage + pager.currentPageOffsetFraction).coerceIn(0f, (pages - 1).toFloat()) }
    val grounds = listOf(Art.SplashIndigo, colors.right.soft, colors.left.tint)
    val p = progress()
    val ground = sample(grounds, p)
    val inkOnGround = lerp(Color.White, colors.ink, (p).coerceIn(0f, 1f))
    val subOnGround = lerp(Color.White.copy(alpha = 0.78f), colors.ink2, (p).coerceIn(0f, 1f))

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(ground, lerp(ground, colors.page, 0.55f))))) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f))
                if (pager.currentPage < pages - 1) {
                    Text(tr("Skip"), color = subOnGround, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                        modifier = Modifier.clip(CircleShape).padding(horizontal = 12.dp, vertical = 8.dp).then(Modifier.clickableNoRipple(onDone)))
                }
            }
            Stage(progress, Modifier.fillMaxWidth().weight(1f))
            HorizontalPager(pager, Modifier.fillMaxWidth().height(190.dp)) { page ->
                val text = when (page) {
                    0 -> tr("Dare each other to move.") to tr("Pick the time. Their phone rings, and the camera counts every rep until it's done.")
                    1 -> tr("Two halves, one heart.") to tr("They stick on their half when they set the dare. Do the reps and the camera sticks on yours.")
                    else -> tr("Yours until you share it.") to tr("Photos, location and your cycle start private. You choose what your partner sees, and can change it any time.")
                }
                Words(text.first, text.second, active = pager.currentPage == page, ink = inkOnGround, sub = subOnGround)
            }
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 20.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                HeartsProgress(pages, p, inkOnGround, Modifier.weight(1f))
                if (pager.currentPage < pages - 1) {
                    HeartButton(tr("Next"), { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }, fillWidth = false, trailingIcon = Icons.AutoMirrored.Rounded.ArrowForward)
                } else {
                    HeartButton(tr("Let's begin"), onDone, fillWidth = false, trailingIcon = Icons.Rounded.Favorite)
                }
            }
        }
    }
}

/** Linear blend through the pages' colours. */
private fun sample(stops: List<Color>, p: Float): Color {
    val i = p.toInt().coerceIn(0, stops.lastIndex - 1)
    return lerp(stops[i], stops[i + 1], (p - i).coerceIn(0f, 1f))
}

/** How strongly page [page] shows at [p]: 1 on it, 0 a page away. */
private fun near(p: Float, page: Int) = (1f - abs(p - page)).coerceIn(0f, 1f)

/** The shared stage: the halves, the ringing dare, the click into one heart, and the privacy keepsakes. */
@Composable
private fun Stage(progress: () -> Float, modifier: Modifier) {
    val clock by rememberInfiniteTransition(label = "stage").animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "t")
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val p = progress()
        val heart = minOf(maxWidth * 0.62f, maxHeight * 0.62f)
        // Apart on the first page, together from the second: the halves close the gap as you swipe.
        val apart = (1f - p).coerceIn(0f, 1f)
        val gap = maxWidth * 0.19f * FastOutSlowInEasing.transform(apart)
        val bob = sin(clock * 2 * PI.toFloat() * 2) * 6f * (0.3f + 0.7f * apart)
        val joined = near(p, 1).let { if (p >= 1f) 1f else it }
        // A little pop when they click together.
        val pop by animateFloatAsState(if (p > 0.97f) 1f else 0f, tween(420, easing = FastOutSlowInEasing), label = "pop")
        val popScale = 1f + 0.08f * sin(pop * PI.toFloat())

        // Page 1 prop: the dare's alarm, ringing between them.
        val ring = near(p, 0)
        if (ring > 0.01f) {
            Bubble(Icons.Rounded.Alarm, 76.dp, Modifier.offset { IntOffset(0, (-heart.toPx() * 0.55f).roundToInt()) }.graphicsLayer {
                alpha = ring; scaleX = 0.6f + 0.4f * ring; scaleY = scaleX
                rotationZ = sin(clock * 2 * PI.toFloat() * 6) * 14f * ring
            }, tint = Heartpact.colors.right.action)
            Text("8:00", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp,
                modifier = Modifier.offset { IntOffset(0, (-heart.toPx() * 0.55f + 62.dp.toPx()).roundToInt()) }.graphicsLayer { alpha = ring })
        }

        // The two halves: drawn on the same canvas spot they make the whole heart; the gap pulls them apart.
        Box(Modifier.size(heart).graphicsLayer { scaleX = popScale; scaleY = popScale }, contentAlignment = Alignment.Center) {
            if (apart < 0.03f) {
                // Together they are one sticker: one die-cut edge round the whole heart, no seam between the halves.
                Character(Who.Both, Pose.Hug, heart, Modifier.offset { IntOffset(0, (bob * 0.4f).dp.roundToPx()) }, mood = Mood.Cheer)
            } else {
                Character(Who.Left, if (apart > 0.6f) Pose.Wave else Pose.Stand, heart,
                    Modifier.offset { IntOffset(-gap.roundToPx(), bob.dp.roundToPx()) }, mood = if (joined > 0.9f) Mood.Cheer else Mood.Smile)
                Character(Who.Right, if (apart > 0.6f) Pose.Wave else Pose.Stand, heart,
                    Modifier.offset { IntOffset(gap.roundToPx(), (-bob).dp.roundToPx()) }, mood = if (joined > 0.9f) Mood.Cheer else Mood.Smile)
            }
        }

        // Page 2: hearts burst out as they click.
        val burst = near(p, 1)
        if (burst > 0.05f) Canvas(Modifier.size(heart * 1.6f)) {
            val c = Offset(size.width / 2, size.height / 2)
            repeat(10) { i ->
                val a = i / 10f * 2 * PI.toFloat() + clock * 0.6f
                val r = size.minDimension * (0.34f + 0.12f * ((clock + i * 0.1f) % 1f)) * burst
                drawCircle(if (i % 2 == 0) Art.Left else Art.Right, 5.dp.toPx() * burst, c + Offset(cos(a) * r, sin(a) * r), alpha = burst * 0.8f)
            }
        }

        // Page 3: what's yours circles the heart, kept by a lock.
        val keep = near(p, 2)
        if (keep > 0.01f) {
            val orbit = heart.value * 0.62f
            listOf(Icons.Rounded.PhotoLibrary, Icons.Rounded.LocationOn, Icons.Rounded.WaterDrop).forEachIndexed { i, icon ->
                val a = (i / 3f + clock * 0.25f) * 2 * PI.toFloat()
                Bubble(icon, 54.dp, Modifier.offset { IntOffset((cos(a) * orbit * keep).dp.roundToPx(), (sin(a) * orbit * 0.55f * keep).dp.roundToPx()) }.graphicsLayer { alpha = keep })
            }
            Bubble(Icons.Rounded.Lock, 48.dp, Modifier.offset { IntOffset(0, (heart.toPx() * 0.42f).roundToInt()) }.graphicsLayer { alpha = keep; scaleX = keep; scaleY = keep },
                tint = Heartpact.colors.left.action)
        }
    }
}

/** A white sticker circle holding an icon. */
@Composable
private fun Bubble(icon: ImageVector, size: Dp, modifier: Modifier, tint: Color = Heartpact.colors.ink) {
    Box(modifier.size(size).shadow(8.dp, CircleShape).clip(CircleShape).background(Heartpact.colors.sticker), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

/** The page's words: the title rises in word by word, then the line under it. */
@Composable
private fun Words(title: String, body: String, active: Boolean, ink: Color, sub: Color) {
    val shown by animateFloatAsState(if (active) 1f else 0f, tween(if (active) 700 else 150, easing = FastOutSlowInEasing), label = "words")
    val words = title.split(" ")
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            words.forEachIndexed { i, word ->
                val start = i / (words.size + 2f)
                val t = ((shown - start) / (1f - start)).coerceIn(0f, 1f)
                Text(word, style = Heartpact.type.headline.copy(fontSize = 34.sp, lineHeight = 38.sp), color = ink,
                    modifier = Modifier.graphicsLayer { alpha = t; translationY = (1f - t) * 22.dp.toPx() })
            }
        }
        val b = ((shown - 0.45f) / 0.55f).coerceIn(0f, 1f)
        Text(body, style = Heartpact.type.bodyLarge, color = sub, modifier = Modifier.padding(top = 12.dp).graphicsLayer { alpha = b; translationY = (1f - b) * 16.dp.toPx() })
    }
}

/** Progress as hearts: the current one swells and fills as you swipe toward it. */
@Composable
private fun HeartsProgress(count: Int, p: Float, ink: Color, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            val on = near(p, i)
            Icon(Icons.Rounded.Favorite, null, tint = ink.copy(alpha = 0.25f + 0.75f * on), modifier = Modifier.size((14 + 8 * on).dp))
        }
        Box(Modifier.width(4.dp))
    }
}

@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
