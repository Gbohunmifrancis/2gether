package app.heartpact.ui.tour

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.heartpact.i18n.tr
import app.heartpact.ui.components.ButtonKind
import app.heartpact.ui.components.HeartButton
import app.heartpact.ui.components.PagerDots
import app.heartpact.ui.components.StickerCard
import app.heartpact.ui.home.Tab
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.Shapes

/** Whether this install has shown the tour. Per install, so signing out and in doesn't replay it. */
class TourState(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("tour", Context.MODE_PRIVATE)
    fun seen() = prefs.getBoolean(KEY, false)
    fun markSeen() { prefs.edit().putBoolean(KEY, true).apply() }
    private companion object { const val KEY = "seen_v1" }
}

private data class Step(val tab: Tab?, val title: String, val body: String)

private fun steps() = listOf(
    Step(null, tr("A quick look around"), tr("Five tabs, one pact. This takes about thirty seconds, and you can skip it any time.")),
    Step(Tab.Today, tr("Today"), tr("Your dare for today, your week of hearts, and games for when you both have a minute.")),
    Step(Tab.Pacts, tr("Pacts"), tr("Every dare you've set each other, and every heart you've kept, as a sticker page.")),
    Step(Tab.Us, tr("Us"), tr("The two of you side by side. Your profile and picture, the live map, today's question and games all start here.")),
    Step(Tab.Food, tr("Food"), tr("Snap a meal for a calorie range, and see what your partner chooses to share.")),
    Step(Tab.Album, tr("Album"), tr("Photos stay on your phone until you sync them into your shared album.")),
    Step(null, tr("One more thing"), tr("Pull down on any screen to refresh it. Have fun together.")),
)

/**
 * The first-run tour: the screen dims, a spotlight glides from one tab to the next and a card says what it is for.
 * [bounds] are the tabs' rectangles in the window, measured by the navigation bar.
 */
@Composable
fun Tour(bounds: Map<Tab, Rect>, onDone: () -> Unit) {
    val all = remember { steps() }
    var index by remember { mutableIntStateOf(0) }
    val step = all[index]
    val target = step.tab?.let { bounds[it] }
    val density = LocalDensity.current

    // The spotlight glides: each edge eases to the next tab's rectangle; with no target it closes to the middle.
    val spec = tween<Float>(450, easing = FastOutSlowInEasing)
    BoxWithConstraints(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}) {
        val width = with(density) { maxWidth.toPx() }
        val height = with(density) { maxHeight.toPx() }
        val pad = with(density) { 6.dp.toPx() }
        val goal = target?.inflate(pad) ?: Rect(Offset(width / 2, height * 0.42f), Size(0f, 0f))
        val left by animateFloatAsState(goal.left, spec, label = "l")
        val top by animateFloatAsState(goal.top, spec, label = "t")
        val right by animateFloatAsState(goal.right, spec, label = "r")
        val bottom by animateFloatAsState(goal.bottom, spec, label = "b")
        val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(0f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "pulse")

        Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
            drawRect(Color(0xCC0B0D24))
            if (right > left) {
                val grow = pulse * 5.dp.toPx()
                drawRoundRect(Color.Black, Offset(left - grow, top - grow), Size(right - left + 2 * grow, bottom - top + 2 * grow), CornerRadius(28.dp.toPx()), blendMode = BlendMode.Clear)
            }
        }

        // The card sits above the spotlight (the tabs are at the bottom), or in the middle for the first and last steps.
        val cardBottomGap = if (target != null) with(density) { (height - target.top).toDp() } + 16.dp else null
        Box(Modifier.fillMaxSize().padding(horizontal = 20.dp), contentAlignment = if (cardBottomGap != null) Alignment.BottomCenter else Alignment.Center) {
            AnimatedContent(
                targetState = index,
                transitionSpec = { (fadeIn(tween(300, 120)) + slideInVertically(tween(380)) { it / 6 }) togetherWith (fadeOut(tween(160)) + slideOutVertically(tween(200)) { -it / 8 }) },
                modifier = if (cardBottomGap != null) Modifier.padding(bottom = cardBottomGap) else Modifier,
                label = "card",
            ) { i ->
                val s = all[i]
                StickerCard(Modifier.fillMaxWidth(), shape = Shapes.Dare) {
                    Column(Modifier.padding(20.dp)) {
                        Text(s.title, style = Heartpact.type.headline.copy(fontSize = 26.sp), color = Heartpact.colors.ink)
                        Text(s.body, style = Heartpact.type.body, color = Heartpact.colors.ink2, modifier = Modifier.padding(top = 8.dp))
                        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PagerDots(all.size, i, Modifier.weight(1f))
                            if (i < all.lastIndex) HeartButton(tr("Skip"), onDone, kind = ButtonKind.Text, fillWidth = false, height = 44.dp)
                            HeartButton(if (i < all.lastIndex) tr("Next") else tr("Let's go"), { if (i < all.lastIndex) index = i + 1 else onDone() }, fillWidth = false, height = 44.dp)
                        }
                    }
                }
            }
        }
    }
}
