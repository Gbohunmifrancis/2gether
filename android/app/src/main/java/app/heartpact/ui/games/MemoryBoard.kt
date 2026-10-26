package app.heartpact.ui.games

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Cake
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LocalFlorist
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.heartpact.data.GameSession
import app.heartpact.data.GameStatus
import app.heartpact.i18n.tr
import app.heartpact.i18n.trn
import app.heartpact.ui.graphics.HalfState
import app.heartpact.ui.graphics.HeartSticker
import app.heartpact.ui.play.HeartBurst
import app.heartpact.ui.play.rememberPlayHaptics
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.Shapes
import kotlinx.coroutines.delay

/**
 * Memory match: twelve cards that turn over in 3D. A pair found pops and bursts hearts; a miss shakes its head
 * before turning back.
 */
@Composable
internal fun MemoryBoard(s: GameSession, me: String, partnerName: String, busy: Boolean, actions: GameActions) {
    val colors = Heartpact.colors
    val haptics = rememberPlayHaptics()
    val m = GamePlay.memory(s)
    val myTurn = s.currentTurnUserId == me && s.status == GameStatus.InProgress
    if (s.status != GameStatus.Completed) {
        Scoreboard(m.scores, me, partnerName, turnOf(s, me)) { trn(it, "{count} pair", "{count} pairs") }
        Text(when {
            myTurn && m.revealed.size == 1 -> tr("Now find its pair")
            myTurn -> if (m.lastResult == "match") tr("A pair! Go again") else tr("Your turn: flip a card")
            else -> if (m.lastResult == "different") tr("No pair. {partner}'s turn", "partner" to partnerName) else tr("{partner}'s turn", "partner" to partnerName)
        }, style = Heartpact.type.title, color = colors.ink, modifier = Modifier.padding(bottom = 12.dp))
    }
    val shown = m.lastPair.orEmpty().associate { it.index to it.value }
    // The last pair turned over: keyed by its cards, so it celebrates (or shakes) once, not on every later move.
    val pair = m.lastPair.orEmpty().map { it.index }
    val matchKey = pair.takeIf { m.lastResult == "match" && it.isNotEmpty() }?.let { "match:$it" }
    val missKey = pair.takeIf { m.lastResult == "different" && it.isNotEmpty() }?.let { "miss:$it" }
    LaunchedEffect(matchKey) { if (matchKey != null) { delay(250); haptics.thud() } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        m.cards.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { card ->
                    val value = card.value ?: shown[card.index]
                    val faded = card.value == null && value != null
                    MemoryCard(
                        card.index, value, card.matched, faded, m.theme,
                        enabled = myTurn && !busy && (value == null || faded),
                        celebrate = matchKey.takeIf { card.index in pair },
                        miss = missKey.takeIf { card.index in pair },
                        modifier = Modifier.weight(1f),
                    ) { actions.onAct(s, "flip", null, card.index) }
                }
            }
        }
    }
}

@Composable
private fun MemoryCard(index: Int, value: Int?, matched: Boolean, faded: Boolean, theme: String?, enabled: Boolean, celebrate: Any?, miss: Any?, modifier: Modifier, onFlip: () -> Unit) {
    val colors = Heartpact.colors
    val actor = Heartpact.actor
    val still = motionOff()
    val faceUp = value != null
    // The card turns about its upright: past 90° its face shows instead of its back.
    val turn = remember { Animatable(if (faceUp) 180f else 0f) }
    LaunchedEffect(faceUp) {
        val target = if (faceUp) 180f else 0f
        if (still) turn.snapTo(target) else turn.animateTo(target, spring(dampingRatio = 0.62f, stiffness = 240f))
    }
    // While it turns back over, it keeps the face it had.
    var face by remember { mutableStateOf(value) }
    SideEffect { if (value != null) face = value }
    val pop = remember { Animatable(1f) }
    var burst by remember { mutableStateOf<Any?>(null) }
    LaunchedEffect(celebrate) {
        if (celebrate == null || still) return@LaunchedEffect
        delay(280)
        burst = celebrate
        pop.animateTo(1f, spring(dampingRatio = 0.28f, stiffness = 420f), initialVelocity = 3.5f)
    }
    val shake = rememberShake(miss)
    val alpha by animateFloatAsState(if (matched) 0.6f else if (faded) 0.82f else 1f, gentle(tween(600, delayMillis = 500)), label = "card")
    val showFace = turn.value > 90f
    val label = (value ?: face)?.takeIf { faceUp }?.let { GamePlay.cardLabel(it, theme) } ?: tr("Face-down card {number}", "number" to index + 1)
    Box(modifier.aspectRatio(0.8f)) {
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer {
                    rotationY = turn.value
                    cameraDistance = 16f * density
                    translationX = shake.value * density
                    scaleX = pop.value; scaleY = pop.value
                    this.alpha = alpha
                }
                .clip(Shapes.Tile)
                .background(if (showFace) colors.sticker else actor.action)
                .border(2.dp, colors.rule, Shapes.Tile)
                .clickable(enabled = enabled, role = Role.Button, onClick = onFlip)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            val current = value ?: face
            // The face is drawn mirrored back, so it reads the right way round once the card has turned.
            if (showFace && current != null) Icon(cardIcon(current, theme), null, Modifier.size(34.dp).graphicsLayer { rotationY = 180f }, tint = actor.action)
            else HeartSticker(HalfState.Stuck, HalfState.Stuck, 26.dp, Modifier.graphicsLayer { this.alpha = 0.35f })
        }
        HeartBurst(burst, Modifier.matchParentSize(), count = 12, spread = 80.dp)
    }
}

private fun cardIcon(value: Int, theme: String?) = if (theme == "naija") NaijaStickers.card(value) else when (value) {
    1 -> Icons.Rounded.Favorite
    2 -> Icons.Rounded.Star
    3 -> Icons.Rounded.LocalFlorist
    4 -> Icons.Rounded.WbSunny
    5 -> Icons.Rounded.Bedtime
    else -> Icons.Rounded.Cake
}
