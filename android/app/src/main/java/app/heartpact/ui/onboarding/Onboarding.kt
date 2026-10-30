package app.heartpact.ui.onboarding

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.absoluteValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.heartpact.i18n.tr
import app.heartpact.ui.components.ButtonKind
import app.heartpact.ui.components.CardRow
import app.heartpact.ui.components.HeartButton
import app.heartpact.ui.components.PagerDots
import app.heartpact.ui.components.StickerCard
import app.heartpact.ui.graphics.Character
import app.heartpact.ui.graphics.HalfState
import app.heartpact.ui.graphics.HeartSticker
import app.heartpact.ui.graphics.Mood
import app.heartpact.ui.graphics.PeeledHeart
import app.heartpact.ui.graphics.Pose
import app.heartpact.ui.graphics.Who
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.Shapes
import kotlinx.coroutines.launch

/** Three pages before the first sign-up: dares, hearts, privacy. Skip and Get started both lead on. */
@Composable
fun OnboardingScreen(onDone: () -> Unit, initialPage: Int = 0) {
    val pages = 3
    val pager = rememberPagerState(initialPage = initialPage) { pages }
    val scope = rememberCoroutineScope()
    val colors = Heartpact.colors
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (pager.currentPage > 0) {
                IconButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, tr("Back"), tint = colors.ink2)
                }
            }
            Box(Modifier.weight(1f))
            if (pager.currentPage < pages - 1) HeartButton(tr("Skip"), onDone, kind = ButtonKind.Text, fillWidth = false)
        }
        HorizontalPager(pager, Modifier.weight(1f)) { page ->
            // How far this page is from centre: 0 settled, 1 gone. Pages shrink and fade as they leave.
            val away = { ((pager.currentPage - page) + pager.currentPageOffsetFraction).absoluteValue.coerceIn(0f, 1f) }
            val active = pager.currentPage == page
            Box(Modifier.graphicsLayer {
                val a = away()
                alpha = 1f - a * 0.7f
                scaleX = 1f - a * 0.1f
                scaleY = 1f - a * 0.1f
                translationX = size.width * a * 0.12f * (if (pager.currentPage > page) -1 else 1)
            }) {
                when (page) {
                    0 -> Page(tr("Dare each other to move."), tr("Set a dare for your partner. At the time you pick, their phone rings and the camera counts the reps."), active) { DareArt() }
                    1 -> Page(tr("Every promise you keep becomes a heart."), tr("Your partner sticks on their half when they set the dare. Do the reps and the camera sticks on yours."), active) { HeartsArt() }
                    else -> Page(tr("Nothing is shared until you choose."), tr("Everything starts private. You can always see what your partner can see, and change it in one tap."), active) { PrivateArt() }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            PagerDots(pages, pager.currentPage, Modifier.weight(1f))
            if (pager.currentPage < pages - 1) {
                HeartButton(tr("Next"), { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }, fillWidth = false, trailingIcon = Icons.AutoMirrored.Rounded.ArrowForward)
            } else {
                HeartButton(tr("Get started"), onDone, fillWidth = false)
            }
        }
    }
}

@Composable
private fun Page(title: String, body: String, active: Boolean, art: @Composable BoxScope.() -> Unit) {
    // Each time a page arrives its art springs in, then keeps bobbing; the words rise in after it, one after the other.
    val artIn by animateFloatAsState(if (active) 1f else 0f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessLow), label = "art")
    val titleIn by animateFloatAsState(if (active) 1f else 0f, tween(if (active) 520 else 150, delayMillis = if (active) 180 else 0, easing = FastOutSlowInEasing), label = "title")
    val bodyIn by animateFloatAsState(if (active) 1f else 0f, tween(if (active) 520 else 150, delayMillis = if (active) 340 else 0, easing = FastOutSlowInEasing), label = "body")
    val bob by rememberInfiniteTransition(label = "bob").animateFloat(
        -1f, 1f, infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob",
    )
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Box(
            Modifier.fillMaxWidth().weight(1f).graphicsLayer {
                val scale = 0.6f + 0.4f * artIn
                scaleX = scale; scaleY = scale
                alpha = artIn.coerceIn(0f, 1f)
                translationY = bob * 7.dp.toPx() + (1f - artIn) * 40.dp.toPx()
                rotationZ = (1f - artIn) * -6f
            },
            content = art,
        )
        Text(title, style = Heartpact.type.headline.copy(fontSize = 31.sp, lineHeight = 33.sp), color = Heartpact.colors.ink,
            modifier = Modifier.graphicsLayer { alpha = titleIn; translationY = (1f - titleIn) * 28.dp.toPx() })
        Text(body, style = Heartpact.type.bodyLarge, color = Heartpact.colors.ink2,
            modifier = Modifier.padding(top = 12.dp, bottom = 28.dp).graphicsLayer { alpha = bodyIn; translationY = (1f - bodyIn) * 28.dp.toPx() })
    }
}

@Composable
private fun BoxScope.DareArt() {
    val colors = Heartpact.colors
    StickerCard(Modifier.align(Alignment.Center).padding(end = 46.dp).rotate(-3f), shape = Shapes.Dare, lift = 14.dp) {
        Column(Modifier.padding(18.dp)) {
            Text(tr("20 sit-ups by 8:00"), style = Heartpact.type.title, color = colors.ink)
            Text(tr("Theo's dare for Maya"), style = Heartpact.type.small, color = colors.ink2, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.Alarm, null, tint = colors.right.action)
                Text(tr("Rings at 8:00 until it's done"), style = Heartpact.type.label, color = colors.ink)
            }
            HeartButton(tr("Start on camera"), {}, Modifier.padding(top = 14.dp), icon = Icons.Rounded.PhotoCamera, height = 40.dp, fillWidth = false)
        }
    }
    Character(Who.Right, Pose.Wave, 118.dp, Modifier.align(Alignment.TopEnd).padding(top = 24.dp))
}

@Composable
private fun BoxScope.HeartsArt() {
    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            HeartSticker(HalfState.Slot, HalfState.Stuck, 86.dp)
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = Heartpact.colors.ink3)
            HeartSticker(HalfState.Stuck, HalfState.Stuck, 86.dp)
        }
        StickerCard {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(3) { HeartSticker(HalfState.Stuck, HalfState.Stuck, 26.dp) }
                PeeledHeart(26.dp)
                repeat(2) { HeartSticker(HalfState.Stuck, HalfState.Stuck, 26.dp) }
                HeartSticker(HalfState.Slot, HalfState.Stuck, 26.dp)
            }
        }
    }
}

@Composable
private fun BoxScope.PrivateArt() {
    Character(Who.Left, Pose.Rest, 96.dp, Modifier.align(Alignment.TopEnd).padding(top = 30.dp), mood = Mood.Smile)
    StickerCard(Modifier.align(Alignment.Center).padding(top = 90.dp).fillMaxWidth()) {
        CardRow(tr("Photos stay on your phone"), subtitle = tr("until you tap Sync"), icon = Icons.Rounded.PhotoLibrary)
        CardRow(tr("Location only while you both share"), subtitle = tr("either of you can pause it"), icon = Icons.Rounded.LocationOn, divider = true)
        CardRow(tr("You choose what your partner sees"), subtitle = tr("of your cycle, food and goals"), icon = Icons.Rounded.WaterDrop, divider = true)
    }
}
