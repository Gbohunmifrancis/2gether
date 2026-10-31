package app.heartpact.ui.games

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.heartpact.data.GameSession
import app.heartpact.data.GameStatus
import app.heartpact.i18n.tr
import app.heartpact.i18n.trn
import app.heartpact.ui.components.StickerCard
import app.heartpact.ui.graphics.Character
import app.heartpact.ui.graphics.Mood
import app.heartpact.ui.graphics.Pose
import app.heartpact.ui.graphics.Who
import app.heartpact.ui.play.BlobColour
import app.heartpact.ui.play.HeartBurst
import app.heartpact.ui.play.PlayInk
import app.heartpact.ui.play.Reaction
import app.heartpact.ui.play.ReactingCharacter
import app.heartpact.ui.play.Stamp
import app.heartpact.ui.play.blobPath
import app.heartpact.ui.play.unitHeart
import app.heartpact.ui.play.penCircle
import app.heartpact.ui.play.popSpring
import app.heartpact.ui.play.rememberPlayHaptics
import app.heartpact.ui.theme.Art
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.LocalActor
import app.heartpact.ui.theme.Shapes
import app.heartpact.ui.theme.Side
import app.heartpact.ui.theme.partner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * What makes the couple games move, shared by every board: the scoreboard with the two of you on it, the countdown,
 * the finished game's result, cards dealt in and flipped over, a shake for a miss, a timer that hurries you.
 * Built on the play kit (ui/play) so the couple games and the skill games move the same way.
 */

/** Whose go it is, as a scoreboard shows it. */
internal enum class Turn { Mine, Theirs, Both, None }

internal fun turnOf(s: GameSession, me: String): Turn = when {
    s.status != GameStatus.InProgress || s.currentTurnUserId == null -> Turn.None
    s.currentTurnUserId == me -> Turn.Mine
    else -> Turn.Theirs
}

/**
 * Maya or Theo on a game screen: [ReactingCharacter] when things may move, and the same pose standing still when
 * motion is off (ReactingCharacter breathes forever).
 */
@Composable
internal fun PlayCharacter(who: Who, reaction: Reaction, size: Dp, modifier: Modifier = Modifier, key: Any? = reaction) {
    if (!motionOff()) {
        ReactingCharacter(who, reaction, size, modifier, key)
        return
    }
    val pose = when (reaction) {
        Reaction.Cheer -> Pose.Cheer
        Reaction.Sad -> Pose.Rest
        Reaction.Idle -> if (who != Who.Both) Pose.Wave else Pose.Stand
        else -> Pose.Stand
    }
    val mood = when (reaction) {
        Reaction.Cheer -> Mood.Cheer
        Reaction.Sad -> Mood.Worried
        else -> Mood.Smile
    }
    Character(who, pose, size, modifier, mood = mood)
}

/**
 * Maya and Theo side by side, the left half on the left, standing close enough that the heart shows between them.
 * [mine] is how the phone holder's character reacts, [theirs] their partner's.
 */
@Composable
internal fun Couple(size: Dp, mine: Reaction, theirs: Reaction, modifier: Modifier = Modifier, key: Any? = null) {
    val leftIsMe = LocalActor.current == Side.Left
    val left = if (leftIsMe) mine else theirs
    val right = if (leftIsMe) theirs else mine
    // Each half fills one side of its own canvas, so the canvases overlap most of the way to bring the halves together.
    // The heart is a picture, not text: it keeps its left half on the left in a right-to-left language too.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(modifier, verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(-size * 0.8f)) {
            PlayCharacter(Who.Left, left, size, key = key ?: left)
            PlayCharacter(Who.Right, right, size, key = key ?: right)
        }
    }
}

/** The character for the person holding the phone, and for their partner. */
@Composable internal fun myWho(): Who = if (LocalActor.current == Side.Left) Who.Left else Who.Right
@Composable internal fun theirWho(): Who = if (LocalActor.current == Side.Left) Who.Right else Who.Left
@Composable internal fun theirSide(): Side = if (LocalActor.current == Side.Left) Side.Right else Side.Left

/** The scoreboard over a game: each of you as your half, your score, and who's playing now. */
@Composable
internal fun Scoreboard(scores: Map<String, Int>, me: String, partnerName: String, turn: Turn, count: (Int) -> String) =
    Scoreboard(scores[me] ?: 0, scores.entries.firstOrNull { it.key != me }?.value ?: 0, partnerName, turn, count)

/**
 * The scoreboard: you on the left, your partner on the right, each with their character. A score that goes up counts
 * up, and its side hops and bursts hearts; the side whose go it is lights up in its colour and gently pulses.
 * [middle] sits between the two (a match's games, for instance).
 */
@Composable
internal fun Scoreboard(mine: Int, theirs: Int, partnerName: String, turn: Turn, count: (Int) -> String, modifier: Modifier = Modifier, middle: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        ScoreSide(tr("You"), mine, LocalActor.current, myWho(), turn == Turn.Mine || turn == Turn.Both, mine = true, count, Modifier.weight(1f))
        if (middle != null) Box(Modifier.padding(horizontal = 6.dp)) { middle() } else Spacer(Modifier.width(10.dp))
        ScoreSide(partnerName, theirs, theirSide(), theirWho(), turn == Turn.Theirs || turn == Turn.Both, mine = false, count, Modifier.weight(1f))
    }
}

@Composable
private fun ScoreSide(name: String, score: Int, side: Side, who: Who, active: Boolean, mine: Boolean, count: (Int) -> String, modifier: Modifier) {
    val colors = Heartpact.colors
    val partner = colors.partner(side)
    val still = motionOff()
    val haptics = rememberPlayHaptics()
    // The score as last shown: a rise counts up from it, and that side hops and bursts hearts for a moment.
    var before by remember { mutableIntStateOf(score) }
    var cheer by remember { mutableStateOf<Int?>(null) }
    val shown = remember { Animatable(score.toFloat()) }
    val bump = remember { Animatable(1f) }
    LaunchedEffect(score) {
        if (score > before) {
            cheer = score
            if (mine) haptics.thud() else haptics.tick()
            if (!still) launch { bump.snapTo(1.4f); bump.animateTo(1f, popSpring()) }
        }
        val steps = abs(score - before)
        before = score
        if (still) shown.snapTo(score.toFloat()) else shown.animateTo(score.toFloat(), tween(380 + 70 * steps.coerceAtMost(8), easing = FastOutSlowInEasing))
    }
    LaunchedEffect(cheer) { if (cheer != null) { delay(1600); cheer = null } }
    val pulse = if (active && !motionOff()) {
        val loop = rememberInfiniteTransition(label = "turn")
        loop.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    } else null
    val fill by animateColorAsState(if (active) partner.soft else colors.sticker, gentle(tween(350)), label = "fill")
    val reaction = when {
        cheer != null -> Reaction.Cheer
        active -> Reaction.Thinking
        else -> Reaction.Waiting
    }
    val turnLabel = tr("Playing now")
    Box(modifier, contentAlignment = if (mine) Alignment.CenterStart else Alignment.CenterEnd) {
        Row(
            Modifier
                .graphicsLayer {
                    val p = pulse?.value ?: 0f
                    scaleX = 1f + 0.035f * p; scaleY = 1f + 0.035f * p
                    transformOrigin = TransformOrigin(if (mine) 0.2f else 0.8f, 0.5f)
                }
                .shadow(if (active) 6.dp else 3.dp, Shapes.Pill, ambientColor = colors.left.art.copy(alpha = 0.5f), spotColor = colors.left.art.copy(alpha = 0.5f))
                .clip(Shapes.Pill)
                .background(fill)
                .drawBehind {
                    if (active) {
                        val p = pulse?.value ?: 1f
                        val w = 2.dp.toPx()
                        drawRoundRect(partner.action.copy(alpha = 0.35f + 0.45f * p), topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2), style = Stroke(w))
                    }
                }
                .semantics(mergeDescendants = true) { if (active) stateDescription = turnLabel }
                .padding(start = if (mine) 2.dp else 16.dp, end = if (mine) 16.dp else 2.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (mine) PlayCharacter(who, reaction, 46.dp, key = cheer ?: reaction)
            Column(horizontalAlignment = if (mine) Alignment.Start else Alignment.End, modifier = Modifier.weight(1f, fill = false)) {
                Text(name, style = Heartpact.type.label, color = if (active) partner.onSoft else colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // The count always reads whole ("2 wins", never a bare "2" cut at the space): on a narrow pill it shrinks to fit.
                val small = Heartpact.type.small
                BasicText(count(shown.value.roundToInt()), style = small.copy(color = if (active) partner.onSoft else colors.ink2), maxLines = 1, softWrap = false,
                    autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = small.fontSize, stepSize = 0.5.sp),
                    modifier = Modifier.graphicsLayer { scaleX = bump.value; scaleY = bump.value; transformOrigin = TransformOrigin(if (mine) 0f else 1f, 0.5f) })
            }
            if (!mine) PlayCharacter(who, reaction, 46.dp, key = cheer ?: reaction)
        }
        HeartBurst(cheer, Modifier.matchParentSize(), count = 14, spread = 90.dp)
    }
}

/** Before a game: the number drops in big and bouncy each second, the pen circles it, and you both hop, ready. */
@Composable
internal fun Countdown(seconds: Long) {
    val colors = Heartpact.colors
    val still = motionOff()
    val haptics = rememberPlayHaptics()
    val drop = remember(seconds) { Animatable(if (still) 1f else 0f) }
    val pen = remember(seconds) { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(seconds) {
        haptics.tick()
        if (!still) {
            launch { drop.animateTo(1f, spring(dampingRatio = 0.34f, stiffness = 300f)) }
            pen.animateTo(1f, tween(820, easing = LinearEasing))
        }
    }
    Column(Modifier.fillMaxWidth().padding(top = 72.dp).semantics(mergeDescendants = true) { contentDescription = tr("Starting in {seconds}", "seconds" to seconds) },
        horizontalAlignment = Alignment.CenterHorizontally) {
        // The two lean in either side of the number; each half fills only one side of its canvas, hence the overlap.
        // Left half on the left in every language, as in the heart.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy((-34).dp)) {
            PlayCharacter(Who.Left, Reaction.Cheer, 112.dp, key = seconds)
            Box(Modifier.size(width = 150.dp, height = 170.dp), contentAlignment = Alignment.Center) {
                Text("$seconds", style = Heartpact.type.display.copy(fontSize = 120.sp, lineHeight = 120.sp), color = colors.ink, textAlign = TextAlign.Center,
                    modifier = Modifier
                        .graphicsLayer {
                            val p = drop.value
                            // Falls in from above, lands a touch squashed, and springs back up.
                            translationY = -(1f - p.coerceAtMost(1f)) * 120.dp.toPx()
                            val squash = (p - 1f).coerceAtLeast(-0.3f)
                            scaleX = 1f + squash * 0.6f; scaleY = 1f - squash * 0.6f
                            transformOrigin = TransformOrigin(0.5f, 1f)
                            alpha = (p * 3f).coerceIn(0f, 1f)
                        }
                        .drawBehind {
                            val grow = 26.dp.toPx()
                            penCircle(pen.value, width = 4.dp.toPx(), seed = seconds.toInt(), around = Rect(-grow, 0f, size.width + grow, size.height + 6.dp.toPx()))
                        })
            }
            PlayCharacter(Who.Right, Reaction.Cheer, 112.dp, key = seconds)
        }
        }
        Text(tr("Get ready"), style = Heartpact.type.title, color = colors.ink2, modifier = Modifier.padding(top = 20.dp))
    }
}

/**
 * A finished game: both totals count up, the winner's is circled in pen and bursts hearts, the stamp comes down,
 * and Maya and Theo react. Games played as a team get one shared total. TalkBack reads the outcome.
 */
@Composable
internal fun GameResultCard(s: GameSession, me: String, partnerName: String, modifier: Modifier = Modifier) {
    val score = GamePlay.score(s, me) ?: return
    val colors = Heartpact.colors
    val still = motionOff()
    val outcome = GamePlay.outcome(s, me, partnerName)
    val title = GamePlay.title(s)
    StickerCard(modifier.fillMaxWidth().padding(bottom = 16.dp).semantics(mergeDescendants = true) { contentDescription = "$title. $outcome" }) {
        Column(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 22.dp, start = 16.dp, end = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(tr("Final score"), style = Heartpact.type.label, color = colors.ink3)
            if (score.together) {
                Box(contentAlignment = Alignment.Center) {
                    Couple(84.dp, Reaction.Cheer, Reaction.Cheer, Modifier.padding(top = 12.dp), key = s.id)
                    CelebrateWhenCounted(score.mine, Modifier.matchParentSize())
                }
                CountedTotal(score.mine, circled = true, Modifier.padding(top = 14.dp))
                Text(outcome, style = Heartpact.type.bodyLarge, color = colors.ink2, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp))
            } else {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
                    ResultSide(tr("You"), score.mine, myWho(), winner = score.won, draw = score.draw)
                    ResultSide(partnerName, score.theirs, theirWho(), winner = score.lost, draw = score.draw)
                }
            }
            Stamp(GamePlay.stamp(score, partnerName), Modifier.padding(top = 20.dp), key = s.id, delayMs = if (still) 0 else 1100)
        }
    }
}

@Composable
private fun ResultSide(name: String, score: Int, who: Who, winner: Boolean, draw: Boolean) {
    val colors = Heartpact.colors
    Box(contentAlignment = Alignment.TopCenter) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(132.dp)) {
            PlayCharacter(who, if (winner || draw) Reaction.Cheer else Reaction.Idle, 76.dp, key = "result")
            Text(name, style = Heartpact.type.label, color = colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
            CountedTotal(score, circled = winner, Modifier.padding(top = 14.dp))
        }
        if (winner) CelebrateWhenCounted(score, Modifier.matchParentSize())
    }
}

/** Hearts for the winner, once their total has counted up. */
@Composable
private fun CelebrateWhenCounted(score: Int, modifier: Modifier) {
    var burst by remember { mutableStateOf<Int?>(null) }
    val still = motionOff()
    LaunchedEffect(Unit) { if (!still) { delay(countMs(score) + 300L); burst = score } }
    HeartBurst(burst, modifier, count = 22, spread = 130.dp)
}

private fun countMs(score: Int) = 250 + (500 + score * 60).coerceAtMost(1300)

/** A total that counts up from nothing, circled in pen once it lands when [circled]. */
@Composable
internal fun CountedTotal(value: Int, circled: Boolean, modifier: Modifier = Modifier, textSize: TextUnit = 60.sp) {
    val still = motionOff()
    val haptics = rememberPlayHaptics()
    val count = remember { Animatable(if (still) value.toFloat() else 0f) }
    val pen = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(value) {
        if (still) return@LaunchedEffect
        delay(250)
        var last = 0
        count.animateTo(value.toFloat(), tween((500 + value * 60).coerceAtMost(1300), easing = FastOutSlowInEasing)) {
            val v = this.value.roundToInt()
            if (v != last) { last = v; haptics.tick() }
        }
        if (circled) pen.animateTo(1f, tween(480, easing = LinearEasing))
    }
    Box(modifier.drawBehind {
        if (circled) {
            // Wider than tall, as a pen rings a number: room either side, a snug fit above and below.
            val grow = 22.dp.toPx()
            penCircle(pen.value, width = 3.5.dp.toPx(), seed = value % 5, around = Rect(-grow, -4.dp.toPx(), size.width + grow, size.height + 6.dp.toPx()))
        }
    }, contentAlignment = Alignment.Center) {
        Text("${count.value.roundToInt()}", style = Heartpact.type.display.copy(fontSize = textSize, lineHeight = textSize), color = Heartpact.colors.ink, textAlign = TextAlign.Center)
    }
}

/** A card dealt in afresh for each new [key]: it slides in from the side, turning a little, and settles. */
@Composable
internal fun DealIn(key: Any?, modifier: Modifier = Modifier, delayMs: Long = 0, content: @Composable () -> Unit) {
    val still = motionOff()
    val p = remember(key) { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(key) { delay(delayMs); p.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 240f)) }
    Box(modifier.graphicsLayer {
        val q = 1f - p.value
        translationX = q * 80.dp.toPx()
        rotationZ = q * 6f
        alpha = p.value.coerceIn(0f, 1f)
    }) { content() }
}

/** A reveal turning face up for each new [key], top edge first, after [delayMs]. */
@Composable
internal fun FlipIn(key: Any?, modifier: Modifier = Modifier, delayMs: Long = 0, content: @Composable () -> Unit) {
    val still = motionOff()
    val p = remember(key) { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(key) { delay(delayMs); p.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 200f)) }
    Box(modifier.graphicsLayer {
        rotationX = (1f - p.value) * -90f
        cameraDistance = 14f * density
        transformOrigin = TransformOrigin(0.5f, 0f)
        alpha = if (p.value < 0.04f) 0f else 1f
    }) { content() }
}

/** A quick shake, "no", whenever [trigger] changes to something other than null. Read it as a dp offset. */
@Composable
internal fun rememberShake(trigger: Any?): Animatable<Float, *> {
    val still = motionOff()
    val shake = remember { Animatable(0f) }
    LaunchedEffect(trigger) {
        if (trigger == null || still) return@LaunchedEffect
        shake.snapTo(0f)
        shake.animateTo(0f, keyframes {
            durationMillis = 460
            -11f at 60; 10f at 130; -7f at 200; 5f at 270; -2.5f at 340; 0f at 460
        })
    }
    return shake
}

/** Seconds left, as a ring that runs down; in the last five it turns red and beats with each second. */
@Composable
internal fun TimerBadge(left: Long, total: Int, modifier: Modifier = Modifier) {
    val colors = Heartpact.colors
    val still = motionOff()
    val haptics = rememberPlayHaptics()
    val urgent = left in 1..5
    val tint = if (urgent) colors.error else Heartpact.actor.action
    val share by animateFloatAsState((left / total.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f), gentle(tween(950, easing = LinearEasing)), label = "left")
    val beat = remember { Animatable(1f) }
    LaunchedEffect(left) {
        if (!urgent) return@LaunchedEffect
        haptics.tick()
        if (!still) { beat.snapTo(1.25f); beat.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 500f)) }
    }
    val label = tr("{seconds} seconds left", "seconds" to left)
    Box(modifier.size(66.dp).graphicsLayer { scaleX = beat.value; scaleY = beat.value }.semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        val track = colors.rule2
        Canvas(Modifier.fillMaxSize()) {
            val w = 5.dp.toPx()
            val inset = w / 2
            drawCircle(track, size.minDimension / 2 - inset, style = Stroke(w))
            drawArc(tint, -90f, 360f * share, false, topLeft = Offset(inset, inset), size = Size(size.width - w, size.height - w), style = Stroke(w, cap = StrokeCap.Round))
        }
        Text(tr("{seconds}s", "seconds" to left), style = Heartpact.type.title, color = if (urgent) colors.error else colors.ink)
    }
}

/**
 * A line drawn with the pen through the middle of a winning row, from [from] to [to], drawn on as [progress] goes
 * 0 to 1. It wavers a touch like a hand and runs a little past both ends.
 */
internal fun DrawScope.penLine(from: Offset, to: Offset, progress: Float, colour: Color = PlayInk.Pen, width: Float = 6.dp.toPx()) {
    if (progress <= 0f) return
    val d = to - from
    val length = d.getDistance().coerceAtLeast(1f)
    val dir = d / length
    val normal = Offset(-dir.y, dir.x)
    val start = from - dir * (length * 0.12f)
    val end = to + dir * (length * 0.12f)
    val path = Path()
    val steps = 40
    for (i in 0..steps) {
        val t = i / steps.toFloat()
        val wave = sin(t * PI.toFloat() * 2.2f + 0.6f) * 3.dp.toPx() * (1f - t * 0.4f)
        val p = start + (end - start) * t + normal * wave
        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }
    val measure = PathMeasure().apply { setPath(path, false) }
    val part = Path()
    measure.getSegment(0f, measure.length * progress.coerceIn(0f, 1f), part, true)
    drawPath(part, colour, style = Stroke(width, cap = StrokeCap.Round))
}

/**
 * The scoreboard for games played as a team: the two of you side by side and the hearts you've earned together,
 * counting up with a hop and a burst when one lands. [label] says how far in you are.
 */
@Composable
internal fun TogetherScore(hearts: Int, label: String, turn: Turn, modifier: Modifier = Modifier) {
    val colors = Heartpact.colors
    val still = motionOff()
    val haptics = rememberPlayHaptics()
    var before by remember { mutableIntStateOf(hearts) }
    var cheer by remember { mutableStateOf<Int?>(null) }
    val shown = remember { Animatable(hearts.toFloat()) }
    LaunchedEffect(hearts) {
        if (hearts > before) { cheer = hearts; haptics.thud() }
        before = hearts
        if (still) shown.snapTo(hearts.toFloat()) else shown.animateTo(hearts.toFloat(), tween(500, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(cheer) { if (cheer != null) { delay(1600); cheer = null } }
    fun reaction(active: Boolean) = when {
        cheer != null -> Reaction.Cheer
        active -> Reaction.Thinking
        else -> Reaction.Waiting
    }
    Row(modifier.fillMaxWidth().padding(bottom = 14.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Box {
            Couple(48.dp, reaction(turn == Turn.Mine || turn == Turn.Both), reaction(turn == Turn.Theirs || turn == Turn.Both), key = cheer)
            HeartBurst(cheer, Modifier.matchParentSize(), count = 14, spread = 90.dp)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(label, style = Heartpact.type.small, color = colors.ink2)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Canvas(Modifier.size(16.dp)) { translate(center.x, center.y) { drawPath(unitHeart(size.minDimension * 1.15f), PlayInk.Pen) } }
                Text(trn(shown.value.roundToInt(), "{count} heart", "{count} hearts"), style = Heartpact.type.titleSmall, color = colors.ink)
            }
        }
    }
}

/**
 * A round's result turning face up for each new [key]: stamped with [stamp] when there's something to celebrate,
 * hearts bursting over it when [hearts].
 */
@Composable
internal fun RoundReveal(key: Any?, stamp: String?, hearts: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val still = motionOff()
    FlipIn(key, modifier) {
        Box {
            content()
            if (stamp != null) Stamp(stamp, Modifier.align(Alignment.TopEnd).padding(end = 6.dp).graphicsLayer { translationY = -18.dp.toPx() },
                tilt = 7f, key = key, delayMs = if (still) 0 else 380)
            HeartBurst(key.takeIf { hearts }, Modifier.matchParentSize(), count = 16, spread = 120.dp)
        }
    }
}

/** A small pill tag ("best 88"), in a blob's colours. */
@Composable
internal fun Tag(text: String, fill: Color, ink: Color, modifier: Modifier = Modifier) {
    Text(text, style = Heartpact.type.labelSmall, color = ink, maxLines = 1,
        modifier = modifier.clip(Shapes.Pill).background(fill).padding(horizontal = 10.dp, vertical = 3.dp))
}

/** How a choice shows it was picked: a small jump ([bump], a scale) and its badge turning over ([turn], degrees). */
internal class PickMotion(val bump: Float, val turn: Float)

@Composable
internal fun pickedFlip(picked: Boolean): PickMotion {
    val v by animateFloatAsState(if (picked) 1f else 0f, gentle(spring(dampingRatio = 0.5f, stiffness = 300f)), label = "pick")
    return PickMotion(1f + 0.04f * sin(PI.toFloat() * v.coerceIn(0f, 1f)), if (picked) 180f * (1f - v) else 0f)
}

/** For games you both play at once: whoever of [players] hasn't moved yet ([done] have). */
internal fun waitingOn(players: List<String>, me: String, done: Collection<String>): Turn {
    val mine = me !in done
    val theirs = players.any { it != me && it !in done }
    return when {
        mine && theirs -> Turn.Both
        mine -> Turn.Mine
        theirs -> Turn.Theirs
        else -> Turn.None
    }
}

/** A big letter on a pastel blob that pops in, spinning a little, each time [key] changes (a new round's letter). */
@Composable
internal fun LetterBlob(letter: String, colour: BlobColour, key: Any?, modifier: Modifier = Modifier, size: Dp = 92.dp) {
    val still = motionOff()
    val pop = remember(key) { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(key) { pop.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 320f)) }
    Box(
        modifier.size(size)
            .graphicsLayer {
                scaleX = pop.value; scaleY = pop.value
                rotationZ = (1f - pop.value) * -40f
            }
            .drawBehind {
                val path = blobPath(letter.hashCode(), this.size)
                drawPath(path, Art.Keyline, style = Stroke(6.dp.toPx()))
                drawPath(path, colour.fill)
            },
        contentAlignment = Alignment.Center,
    ) {
        val type = (size.value * 0.56f).sp
        Text(letter, style = Heartpact.type.display.copy(fontSize = type, lineHeight = type), color = colour.ink)
    }
}
