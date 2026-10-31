package app.heartpact.ui.games

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
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.heartpact.data.GameSession
import app.heartpact.data.GameStatus
import app.heartpact.data.SnakesMove
import app.heartpact.data.SnakesState
import app.heartpact.data.SnakesStats
import app.heartpact.i18n.tr
import app.heartpact.i18n.trn
import app.heartpact.ui.components.StickerCard
import app.heartpact.ui.graphics.Stride
import app.heartpact.ui.graphics.Who
import app.heartpact.ui.play.HeartBurst
import app.heartpact.ui.play.PlayInk
import app.heartpact.ui.play.Reaction
import app.heartpact.ui.play.Stamp
import app.heartpact.ui.play.rememberPlayHaptics
import app.heartpact.ui.play.unitHeart
import app.heartpact.ui.theme.Art
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.LocalActor
import app.heartpact.ui.theme.Shapes
import app.heartpact.ui.theme.Side
import app.heartpact.ui.theme.partner
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/*
 * Snakes and ladders (docs/games.md): a sticker board, Maya and Theo as the tokens, and a big die. Tap the die (or
 * hold it to shake, and let go) and it starts tumbling at once; the server rolls, and when its answer comes the die
 * lands on that number and the token hops square by square, climbs, slides, swaps. Both phones play the same move
 * from the state's `last`, once per move: your partner's roll plays out live in front of you. Opening a game shows it
 * as it stands, without replaying anything; with animations off, everything lands at once.
 */

private val snakesJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }

fun GamePlay.snakes(s: GameSession): SnakesState =
    runCatching { snakesJson.decodeFromString(SnakesState.serializer(), s.stateJson) }.getOrDefault(SnakesState())

/** The history line: "You won on square 100, Theo was on 87", or where both stand in a game still going. */
internal fun GamePlay.snakesOutcome(g: SnakesState, me: String, partner: String): String {
    val mine = g.positions[me] ?: 0
    val theirs = g.positions.entries.firstOrNull { it.key != me }?.value ?: 0
    return when {
        g.winner == me -> tr("You won on square 100, {partner} was on {square}", "partner" to partner, "square" to theirs)
        g.winner != null -> tr("{partner} won on square 100, you were on {square}", "partner" to partner, "square" to mine)
        else -> tr("You're on {mine}, {partner} on {theirs}", "mine" to mine, "partner" to partner, "theirs" to theirs)
    }
}

/** A speech bubble over a square for a moment: "Oh no!", "Up the ladder!". */
private data class Bubble(val text: String, val square: Int, val key: Int, val sad: Boolean = false)

/** The die's angles, spun freely while the server answers and brought to rest on its number. */
@Stable
private class DieSpin {
    var x by mutableFloatStateOf(DieMath.rest(5).first)
    var y by mutableFloatStateOf(DieMath.rest(5).second)
    var z by mutableFloatStateOf(0.12f)
    var hop by mutableFloatStateOf(0f)
    var shake by mutableFloatStateOf(0f)
    var face by mutableIntStateOf(5)

    /** Tumbles until cancelled, bouncing on the tray. */
    suspend fun spin() {
        var last = 0L
        var t = 0f
        while (coroutineContext.isActive) {
            androidx.compose.runtime.withFrameNanos { now ->
                val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceAtMost(0.05f)
                last = now
                t += dt
                x += 10.5f * dt
                y += 7.8f * dt
                z += 2.4f * dt
                hop = abs(sin(t * 9f)) * 0.55f
            }
        }
    }

    /** Lands on [number]: a turn or so more, slowing down, and a little bounce. */
    suspend fun land(number: Int, still: Boolean) {
        face = number
        val rest = DieMath.rest(number)
        if (still) { x = rest.first; y = rest.second; z = rest.third; hop = 0f; return }
        val target = DieMath.landing(Triple(x, y, z), number, turns = 1)
        val from = Triple(x, y, z)
        val startHop = hop
        Animatable(0f).animateTo(1f, tween(620, easing = FastOutSlowInEasing)) {
            val t = this.value
            x = from.first + (target.first - from.first) * t
            y = from.second + (target.second - from.second) * t
            z = from.third + (target.third - from.third) * t
            hop = startHop * (1f - t) + 0.3f * abs(sin(t * PI.toFloat() * 2f)) * (1f - t)
        }
        x = target.first; y = target.second; z = target.third
        hop = 0f
    }
}

@Composable
internal fun SnakesAndLaddersBoard(s: GameSession, me: String, partnerName: String, busy: Boolean, actions: GameActions) {
    val colors = Heartpact.colors
    val g = GamePlay.snakes(s)
    val partner = g.players.firstOrNull { it != me }
    val still = motionOff()
    val sounds = rememberSnakesSounds()
    val haptics = rememberPlayHaptics()
    val scope = rememberCoroutineScope()
    val meSide = LocalActor.current
    val theirSide = if (meSide == Side.Left) Side.Right else Side.Left
    fun whoOf(player: String) = if ((player == me) == (meSide == Side.Left)) Who.Left else Who.Right

    // What's on screen: replays play moves newer than the one the board opened on, once each, in order.
    var seen by remember(s.id) { mutableIntStateOf(g.last?.move ?: 0) }
    val queue = remember(s.id) { mutableStateListOf<SnakesMove>() }
    val shown = remember(s.id) { mutableStateMapOf<String, Int>().apply { putAll(g.positions) } }
    val shields = remember(s.id) { mutableStateMapOf<String, Boolean>().apply { putAll(g.shields) } }
    var beat by remember(s.id) { mutableStateOf<SnakesBeat?>(null) }
    val progress = remember(s.id) { Animatable(0f) }
    var replaying by remember(s.id) { mutableStateOf(false) }
    var playingMove by remember(s.id) { mutableStateOf<SnakesMove?>(null) }
    var bubble by remember(s.id) { mutableStateOf<Bubble?>(null) }
    val reactions = remember(s.id) { mutableStateMapOf<String, Reaction>() }
    var cheerKey by remember(s.id) { mutableIntStateOf(0) }
    var burst by remember(s.id) { mutableStateOf<Int?>(null) }
    val die = remember(s.id) { DieSpin().apply { g.last?.roll?.let { r -> DieMath.rest(r).let { (a, b, c) -> x = a; y = b; z = c; face = r } } } }
    var spinning by remember(s.id) { mutableStateOf<Job?>(null) }
    var rolling by remember(s.id) { mutableStateOf(false) }

    val incoming = g.last?.takeIf { !still && SnakesReplay.shouldReplay(seen, it) }
    LaunchedEffect(g.last?.move) {
        val move = g.last ?: return@LaunchedEffect
        if (!SnakesReplay.shouldReplay(seen, move)) return@LaunchedEffect
        seen = move.move
        if (still) {
            die.land(move.roll, still = true)
            spinning?.cancel(); rolling = false
        } else queue += move
    }
    val busyNow = replaying || queue.isNotEmpty() || incoming != null
    // When nothing is playing, the board is the state.
    LaunchedEffect(busyNow, g.positions, g.shields) {
        if (!busyNow) { shown.clear(); shown.putAll(g.positions); shields.clear(); shields.putAll(g.shields) }
    }
    // A roll the server never answered (offline, refused): the die settles where it was.
    LaunchedEffect(rolling, busy) {
        if (!rolling || busy) return@LaunchedEffect
        delay(700)
        if (rolling && queue.isEmpty() && SnakesReplay.shouldReplay(seen, g.last).not()) {
            spinning?.cancel(); spinning = null; rolling = false
            die.land(die.face, still)
        }
    }

    val currentState by rememberUpdatedState(g)
    LaunchedEffect(s.id) {
        snapshotFlow { queue.firstOrNull() }.filterNotNull().collect { move ->
            replaying = true
            playingMove = move
            val now = currentState
            val other = now.players.firstOrNull { it != move.player }
            SnakesReplay.before(move, other, shown[other] ?: 0).forEach { (k, v) -> shown[k] = v }
            // The mover's shield as it was: a hug gives one, a cancelled snake used one.
            if (move.events.any { it.kind == "hug" }) shields[move.player] = false
            if (move.events.any { it.kind == "shield" }) shields[move.player] = true
            // Too far behind (a burst of moves at once): catch up without the show.
            val hurry = queue.size > 2
            val mine = move.player == me
            val spin = spinning
            if (mine && spin != null && spin.isActive) {
                spin.cancel()
                spinning = null
            } else if (!hurry) {
                sounds?.dice()
                val job = launch { die.spin() }
                delay(520)
                job.cancel()
            }
            die.land(move.roll, still = hurry)
            rolling = false
            haptics.thud()
            reactions[move.player] = Reaction.Thinking
            if (!hurry) delay(160)
            var hops = 0
            for (b in SnakesReplay.beats(move, other)) {
                if (!hurry) {
                    when (b) {
                        is SnakesBeat.Hop -> { sounds?.step(hops++); haptics.tick(); if (b.bouncing && b.from == 100) bubble = Bubble(tr("Bounce!"), 100, move.move * 10) }
                        is SnakesBeat.Climb -> { sounds?.ladder(); haptics.thud(); reactions[b.player] = Reaction.Cheer; cheerKey++; bubble = Bubble(tr("Up the ladder!"), b.from, move.move * 10 + 1) }
                        is SnakesBeat.Slide -> { sounds?.slide(); haptics.thud(); reactions[b.player] = Reaction.Sad; cheerKey++; bubble = Bubble(tr("Oh no!"), b.from, move.move * 10 + 2, sad = true) }
                        is SnakesBeat.Shielded -> { sounds?.boost(); haptics.thud(); reactions[b.player] = Reaction.Cheer; cheerKey++; bubble = Bubble(tr("Saved by a hug!"), b.at, move.move * 10 + 3) }
                        is SnakesBeat.Hug -> { sounds?.boost(); haptics.tick(); reactions[b.player] = Reaction.Cheer; cheerKey++; bubble = Bubble(tr("Hug shield!"), b.at, move.move * 10 + 4) }
                        is SnakesBeat.Boost -> { sounds?.boost(); haptics.thud(); reactions[b.player] = Reaction.Cheer; cheerKey++; bubble = Bubble(tr("Love boost!"), b.from, move.move * 10 + 5) }
                        is SnakesBeat.Heartbreak -> { sounds?.slide(low = true); haptics.thud(); reactions[b.player] = Reaction.Sad; cheerKey++; bubble = Bubble(tr("Heartbreak!"), b.from, move.move * 10 + 6, sad = true) }
                        is SnakesBeat.Swap -> { sounds?.boost(); haptics.thud(); reactions[b.player] = Reaction.Cheer; reactions[b.partner] = Reaction.Sad; cheerKey++; bubble = Bubble(tr("Kiss & swap!"), b.from, move.move * 10 + 7) }
                        is SnakesBeat.Kiss -> { haptics.tick(); bubble = Bubble(tr("Mwah!"), b.at, move.move * 10 + 8) }
                        is SnakesBeat.ThreeSixes -> { sounds?.slide(low = true); haptics.thud(); reactions[b.player] = Reaction.Sad; cheerKey++; bubble = Bubble(tr("Three sixes! Back you go"), b.from, move.move * 10 + 9, sad = true) }
                        is SnakesBeat.Win -> { sounds?.win(); haptics.thud(); reactions[b.player] = Reaction.Cheer; cheerKey++; burst = move.move; bubble = Bubble(tr("Made it!"), 100, move.move * 10 + 1) }
                    }
                    beat = b
                    progress.snapTo(0f)
                    progress.animateTo(1f, tween(b.ms, easing = LinearEasing))
                }
                SnakesReplay.after(b, shown.toMap()).forEach { (k, v) -> shown[k] = v }
                when (b) {
                    is SnakesBeat.Hug -> shields[b.player] = true
                    is SnakesBeat.Shielded -> shields[b.player] = false
                    else -> Unit
                }
                beat = null
            }
            // The move ends where the server says, whatever this phone made of it.
            shown[move.player] = move.to
            if (!hurry) delay(260)
            if (reactions[move.player] == Reaction.Thinking) reactions.remove(move.player)
            playingMove = null
            queue.removeAt(0)
            if (queue.isEmpty()) replaying = false
        }
    }
    LaunchedEffect(bubble?.key) { if (bubble != null) { delay(1500); bubble = null } }
    LaunchedEffect(cheerKey) { if (cheerKey > 0) { delay(1700); reactions.keys.toList().forEach { if (reactions[it] != Reaction.Thinking) reactions.remove(it) } } }

    val positions: Map<String, Int> = if (busyNow) shown else g.positions
    val shieldsShown: Map<String, Boolean> = if (busyNow) shields else g.shields
    val over = s.status != GameStatus.InProgress || g.finished
    val myTurn = s.status == GameStatus.InProgress && !g.finished && (g.turn ?: s.currentTurnUserId) == me
    val canRoll = myTurn && !busyNow && !rolling && !busy

    // The two of you, where you are, and whose roll it is.
    SnakesScoreboard(
        mine = positions[me] ?: 0, theirs = partner?.let { positions[it] } ?: 0, partnerName = partnerName,
        myTurn = !over && !busyNow && myTurn, theirTurn = !over && !busyNow && !myTurn,
        myReaction = reactions[me] ?: if (over && !busyNow) (if (g.winner == me) Reaction.Cheer else Reaction.Waiting) else if (myTurn && !busyNow) Reaction.Thinking else Reaction.Waiting,
        theirReaction = partner?.let { reactions[it] } ?: if (over && !busyNow) (if (g.winner != null && g.winner != me) Reaction.Cheer else Reaction.Waiting) else if (!myTurn && !busyNow) Reaction.Thinking else Reaction.Waiting,
        myShield = shieldsShown[me] == true, theirShield = partner?.let { shieldsShown[it] } == true, key = cheerKey,
    )

    // What's happening, in one line.
    val playing = playingMove
    val headline = when {
        playing != null && playing.player == me -> tr("You rolled {number}", "number" to playing.roll)
        playing != null -> tr("{partner} rolled {number}", "partner" to partnerName, "number" to playing.roll)
        rolling -> tr("Rolling…")
        over -> GamePlay.snakesOutcome(g, me, partnerName)
        myTurn && g.last?.player == me && g.last.extraTurn -> tr("Six! Roll again")
        myTurn -> tr("Your roll!")
        g.last?.player != me && g.last?.extraTurn == true -> tr("{partner} rolled a six and goes again", "partner" to partnerName)
        else -> tr("{partner} is rolling…", "partner" to partnerName)
    }
    Text(headline, style = Heartpact.type.title, color = colors.ink, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp).semantics { liveRegion = LiveRegionMode.Polite })

    // The board.
    val sway = if (still) 0f else {
        val loop = rememberInfiniteTransition(label = "sway")
        val phase by loop.animateFloat(0f, 2f * PI.toFloat(), infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart), label = "phase")
        phase
    }
    val description = tr("The board: you're on square {mine}, {partner} on square {theirs}", "mine" to (positions[me] ?: 0), "partner" to partnerName, "theirs" to (partner?.let { positions[it] } ?: 0))
    BoardCard(
        g = g, positions = positions, shields = shieldsShown, beat = beat, progress = { progress.value }, sway = { sway }, still = still,
        whoOf = ::whoOf, turnPlayer = if (over) null else (g.turn ?: s.currentTurnUserId), bubble = bubble, burst = burst,
        winner = if (over && !busyNow) g.winner else null, description = description,
    )

    // How far along each of you is.
    ProgressStrip(positions[me] ?: 0, partner?.let { positions[it] } ?: 0, meSide, theirSide, partnerName, Modifier.padding(top = 14.dp))

    if (over && !busyNow) {
        if (s.status == GameStatus.Completed) SnakesFinish(g, me, partnerName, Modifier.padding(top = 18.dp))
        return
    }

    // The die, on its tray.
    DieTray(
        die = die, enabled = canRoll, still = still, active = myTurn && !busyNow,
        hint = if (canRoll) tr("Tap the die, or hold it to shake") else null,
        onPress = { sounds?.dice() },
        onRoll = {
            if (canRoll) {
                rolling = true
                haptics.tick()
                if (!still) spinning = scope.launch { die.spin() }
                actions.onAct(s, "roll", null, null)
            }
        },
        modifier = Modifier.padding(top = 16.dp),
    )
    Legend(Modifier.padding(top = 14.dp))
}

/** The scoreboard: each of you as your half, on your square, with a hug shield badge while you carry one. */
@Composable
private fun SnakesScoreboard(mine: Int, theirs: Int, partnerName: String, myTurn: Boolean, theirTurn: Boolean, myReaction: Reaction, theirReaction: Reaction,
                             myShield: Boolean, theirShield: Boolean, key: Int) {
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        RacerPill(tr("You"), mine, LocalActor.current, myWho(), myTurn, myReaction, myShield, mine = true, key, Modifier.weight(1f))
        RacerPill(partnerName, theirs, theirSide(), theirWho(), theirTurn, theirReaction, theirShield, mine = false, key, Modifier.weight(1f))
    }
}

@Composable
private fun RacerPill(name: String, square: Int, side: Side, who: Who, active: Boolean, reaction: Reaction, shield: Boolean, mine: Boolean, key: Int, modifier: Modifier) {
    val colors = Heartpact.colors
    val partner = colors.partner(side)
    val pulse = if (active && !motionOff()) {
        val loop = rememberInfiniteTransition(label = "turn")
        loop.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse").value
    } else 0f
    Row(
        modifier
            .graphicsLayer { scaleX = 1f + 0.03f * pulse; scaleY = 1f + 0.03f * pulse }
            .shadow(if (active) 6.dp else 3.dp, Shapes.Pill, ambientColor = colors.left.art.copy(alpha = 0.5f), spotColor = colors.left.art.copy(alpha = 0.5f))
            .clip(Shapes.Pill)
            .background(if (active) partner.soft else colors.sticker)
            .semantics(mergeDescendants = true) {}
            .padding(start = if (mine) 2.dp else 14.dp, end = if (mine) 14.dp else 2.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (mine) PlayCharacter(who, reaction, 44.dp, key = "$reaction$key")
        Column(Modifier.weight(1f), horizontalAlignment = if (mine) Alignment.Start else Alignment.End) {
            Text(name, style = Heartpact.type.label, color = if (active) partner.onSoft else colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (shield && !mine) ShieldBadge()
                Text(if (square == 0) tr("Start") else tr("Square {number}", "number" to square), style = Heartpact.type.small, color = if (active) partner.onSoft else colors.ink2, maxLines = 1)
                if (shield && mine) ShieldBadge()
            }
        }
        if (!mine) PlayCharacter(who, reaction, 44.dp, key = "$reaction$key")
    }
}

@Composable
private fun ShieldBadge() {
    val label = tr("Hug shield on")
    Canvas(Modifier.size(14.dp).semantics { contentDescription = label }) {
        drawCircle(heartColour("shield").copy(alpha = 0.25f), size.minDimension / 2f)
        drawCircle(heartColour("shield"), size.minDimension / 2f, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
        translate(center.x, center.y + 0.5.dp.toPx()) { drawPath(unitHeart(size.minDimension * 0.62f), heartColour("shield")) }
    }
}

/** The board as a die-cut sticker, with the tokens, a bubble for what just happened, and hearts for a win. */
@Composable
private fun BoardCard(
    g: SnakesState, positions: Map<String, Int>, shields: Map<String, Boolean>, beat: SnakesBeat?, progress: () -> Float, sway: () -> Float, still: Boolean,
    whoOf: (String) -> Who, turnPlayer: String?, bubble: Bubble?, burst: Int?, winner: String?, description: String,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val numberStyle = TextStyle(fontSize = 8.5.sp, fontWeight = FontWeight.Bold, lineHeight = 9.sp)
    val numbers = remember(measurer, density) { (1..100).map { measurer.measure("$it", numberStyle) } }
    val startText = tr("Start")
    val start = remember(measurer, startText) { measurer.measure(startText, TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold)) }
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier.fillMaxWidth()
            .shadow(6.dp, shape, ambientColor = Art.Shadow.copy(alpha = 0.5f), spotColor = Art.Shadow.copy(alpha = 0.5f))
            .background(Color.White, shape)
            .padding(5.dp)
            .clip(RoundedCornerShape(15.dp))
            .semantics { contentDescription = description },
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(SnakesGeometry.SIDE / SnakesGeometry.HEIGHT)) {
            val unitDp = maxWidth / SnakesGeometry.SIDE
            Canvas(Modifier.fillMaxSize()) {
                val u = size.width / SnakesGeometry.SIDE
                val phase = sway()
                val p = progress()
                val b = beat
                val highlight = turnPlayer?.let { positions[it] }?.takeIf { it > 0 }?.let { SnakesGeometry.row(it) }
                drawSquares(u, numbers, start, if (b == null) highlight else null)
                g.board.hearts.forEach { drawHeartSquare(it, u) }
                g.board.ladders.forEach { drawLadder(it, u) }
                g.board.snakes.forEachIndexed { i, link ->
                    val gulp = when {
                        b is SnakesBeat.Slide && b.from == link.from -> if (p < 0.18f) p / 0.18f else (0.55f + 0.45f * sin(p * 38f)) * (1f - ((p - 0.85f) / 0.15f).coerceIn(0f, 1f))
                        b is SnakesBeat.Shielded && b.at == link.from -> if (p < 0.3f) p / 0.3f else (1f - (p - 0.3f) / 0.2f).coerceIn(0f, 1f)
                        else -> 0f
                    }
                    val tongue = if (still) 0f else sin(phase * 1f + i * 1.9f).coerceAtLeast(0f).let { it * it * it * it * it * it }
                    // A sliding snake holds its wiggle so the token rides the curve it's drawn on.
                    drawSnake(link, i, u, if (b is SnakesBeat.Slide && b.from == link.from) 0f else phase + i * 0.9f, gulp, tongue)
                }
                // The tokens: the one moving on top.
                val players = g.players.sortedBy { if (b != null && (it == b.player || (b is SnakesBeat.Swap && it == b.partner))) 1 else 0 }
                val restSquares = players.map { positions[it] ?: 0 }
                val together = b == null && players.size == 2 && restSquares[0] == restSquares[1]
                players.forEach { player ->
                    val pose = if (b != null) SnakesReplay.tokenAt(b, p, player, positions, g.board.snakes, 0f) else TokenPose(SnakesGeometry.center(positions[player] ?: 0), 0f)
                    val won = winner == player
                    val lost = winner != null && winner != player
                    val (bodyPose, mood) = tokenPose(pose.cheer || won, pose.worried || lost)
                    if (pose.trail && b is SnakesBeat.Boost) drawTrail(pose.at, SnakesGeometry.center(b.from), u, p)
                    val at = pose.at - Offset(0f, pose.lift) + Offset(0f, -0.1f)
                    // A soft shadow on the square, shrinking as the token jumps.
                    val air = (abs(pose.lift) / 0.6f).coerceIn(0f, 1f)
                    val w = u * (if (together) 0.62f else 0.44f) * (1f - 0.4f * air)
                    drawOval(Art.Shadow.copy(alpha = 0.2f * (1f - 0.6f * air)), Offset(pose.at.x * u - w / 2f, (pose.at.y + 0.33f) * u - u * 0.05f), androidx.compose.ui.geometry.Size(w, u * 0.1f))
                    drawToken(whoOf(player), at, u, 0.92f, bodyPose, mood, together, shields[player] == true, pose.squash, pose.tilt, pose.spin,
                        stride = if (b is SnakesBeat.Hop && b.player == player) Stride(p * 2f * PI.toFloat()) else null)
                }
                if (b is SnakesBeat.Heartbreak) drawCrackedHeart(SnakesGeometry.center(b.from), u, p)
                if (b is SnakesBeat.Kiss || b is SnakesBeat.Swap || b is SnakesBeat.Hug) {
                    val at = when (b) { is SnakesBeat.Kiss -> b.at; is SnakesBeat.Swap -> b.from; is SnakesBeat.Hug -> b.at; else -> 0 }
                    drawKisses(SnakesGeometry.center(at), u, p)
                }
            }
            // A bubble over the square where it happened.
            if (bubble != null) {
                val c = SnakesGeometry.center(bubble.square)
                SpeechBubble(bubble, Modifier.offset(x = (unitDp * c.x - 60.dp).coerceIn(0.dp, maxWidth - 120.dp), y = (unitDp * c.y - unitDp * 1.55f).coerceAtLeast(0.dp)))
            }
            if (burst != null) {
                val c = SnakesGeometry.center(100)
                HeartBurst(burst, Modifier.offset(x = unitDp * c.x - 60.dp, y = unitDp * c.y - 60.dp).size(120.dp), count = 24, spread = 140.dp)
            }
        }
    }
}

/** A comic bubble that pops up and floats: cheerful pink for good news, ink for an "Oh no!". */
@Composable
private fun SpeechBubble(bubble: Bubble, modifier: Modifier) {
    val still = motionOff()
    val pop = remember(bubble.key) { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(bubble.key) { pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 420f)) }
    Box(modifier.width(120.dp), contentAlignment = Alignment.BottomCenter) {
        Text(
            bubble.text, style = Heartpact.type.label.copy(fontSize = 13.sp), color = if (bubble.sad) Color.White else Color(0xFF7A1F43), textAlign = TextAlign.Center, maxLines = 2,
            modifier = Modifier
                .graphicsLayer { scaleX = pop.value; scaleY = pop.value; transformOrigin = TransformOrigin(0.5f, 1f); rotationZ = if (bubble.sad) -4f else 4f }
                .shadow(4.dp, RoundedCornerShape(14.dp))
                .background(Color.White, RoundedCornerShape(14.dp))
                .padding(2.dp)
                .background(if (bubble.sad) Color(0xFF3F49A8) else Color(0xFFFFD6E3), RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp)
                .widthIn(max = 110.dp),
        )
    }
}

/** A strip from 1 to 100 with both of you on it: who's ahead, at a glance. */
@Composable
private fun ProgressStrip(mine: Int, theirs: Int, meSide: Side, theirSide: Side, partnerName: String, modifier: Modifier = Modifier) {
    val colors = Heartpact.colors
    val label = tr("You're on {mine}, {partner} on {theirs}", "mine" to mine, "partner" to partnerName, "theirs" to theirs)
    BoxWithConstraints(modifier.fillMaxWidth().height(52.dp).semantics { contentDescription = label }) {
        val track = maxWidth - 20.dp
        Canvas(Modifier.fillMaxSize()) {
            val y = size.height / 2f
            val left = 10.dp.toPx()
            val right = size.width - 10.dp.toPx()
            drawLine(colors.rule2, Offset(left, y), Offset(right, y), 7.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
            listOf(25, 50, 75).forEach { n -> val x = left + (right - left) * n / 100f; drawCircle(colors.sticker, 2.dp.toPx(), Offset(x, y)) }
            translate(right, y) { drawPath(unitHeart(16.dp.toPx()), Color.White); drawPath(unitHeart(12.dp.toPx()), PlayInk.Pen) }
        }
        // You ride above the line, your partner below it, so you never hide each other.
        listOf(Triple(mine, meSide, true), Triple(theirs, theirSide, false)).forEach { (square, side, isMe) ->
            val x = track * (square / 100f)
            Column(Modifier.offset(x = x - 3.dp, y = if (isMe) 0.dp else 19.dp).width(26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (isMe) StripNumber(square)
                Canvas(Modifier.size(16.dp)) {
                    translate(center.x, center.y) {
                        drawPath(unitHeart(size.minDimension * 1.25f), Color.White)
                        drawPath(unitHeart(size.minDimension), if (side == Side.Left) Art.Left else Art.Right)
                    }
                }
                if (!isMe) StripNumber(square)
            }
        }
    }
}

@Composable
private fun StripNumber(square: Int) = Text("$square", style = Heartpact.type.labelSmall.copy(fontSize = 10.sp, lineHeight = 11.sp), color = Heartpact.colors.ink2)

/** The die on its tray: tap to roll, or hold to shake it and let go. It lights up when it's your roll. */
@Composable
private fun DieTray(die: DieSpin, enabled: Boolean, still: Boolean, active: Boolean, hint: String?, onPress: () -> Unit, onRoll: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Heartpact.colors
    val haptics = rememberPlayHaptics()
    var pressed by remember { mutableStateOf(false) }
    val enabledNow by rememberUpdatedState(enabled)
    val roll by rememberUpdatedState(onRoll)
    val press by rememberUpdatedState(onPress)
    // Held: the die rattles in the hand.
    LaunchedEffect(pressed) {
        if (!pressed || still) { die.shake = 0f; return@LaunchedEffect }
        var t = 0f
        while (pressed) {
            androidx.compose.runtime.withFrameNanos { }
            t += 0.016f
            die.shake = sin(t * 46f) * 0.12f
            if ((t * 1000).toInt() % 180 < 17) haptics.tick()
        }
    }
    val breathe = if (active && !still) {
        val loop = rememberInfiniteTransition(label = "die")
        loop.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe").value
    } else 0f
    val label = tr("Roll the die")
    // The tray glows in your own soft colour when it's your roll.
    val actor = Heartpact.actor
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(132.dp)
                .semantics {
                    role = Role.Button
                    contentDescription = label
                    if (enabled) onClick(label) { roll(); true }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        if (!enabledNow) return@detectTapGestures
                        pressed = true
                        press()
                        val released = tryAwaitRelease()
                        pressed = false
                        if (released) roll()
                    })
                },
            contentAlignment = Alignment.Center,
        ) {
            // The tray: a soft dish that glows in your colour when it's your roll.
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2f
                drawCircle(if (active) lerp(actor.soft, colors.sticker, 0.35f * breathe) else colors.sticker2, r * 0.92f, center + Offset(0f, r * 0.06f))
                if (active) drawCircle(actor.action.copy(alpha = 0.25f + 0.3f * breathe), r * 0.92f, center + Offset(0f, r * 0.06f), style = androidx.compose.ui.graphics.drawscope.Stroke(2.5.dp.toPx(),
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 6.dp.toPx()))))
                drawOval(Art.Shadow.copy(alpha = 0.16f * (1f - die.hop * 0.8f)), Offset(center.x - r * 0.42f * (1f - die.hop * 0.3f), center.y + r * 0.44f), androidx.compose.ui.geometry.Size(r * 0.84f * (1f - die.hop * 0.3f), r * 0.16f))
            }
            Canvas(Modifier.size(112.dp).graphicsLayer {
                translationY = -die.hop * 34.dp.toPx() - 6.dp.toPx()
                rotationZ = die.shake * 30f
                val s = if (pressed) 0.94f else 1f + 0.03f * breathe
                scaleX = s; scaleY = s
            }) {
                drawDie(die.x + die.shake, die.y - die.shake * 0.6f, die.z)
            }
        }
        if (hint != null) Text(hint, style = Heartpact.type.small, color = colors.ink2, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 2.dp))
    }
}


/** What the heart squares do, under the board. */
@Composable
private fun Legend(modifier: Modifier = Modifier) {
    val colors = Heartpact.colors
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("swap" to tr("Kiss & swap"), "boost" to tr("Love boost +3"), "shield" to tr("Hug shield"), "heartbreak" to tr("Heartbreak −5")).forEach { (kind, text) ->
            Row(
                Modifier.clip(Shapes.Pill).background(colors.sticker).padding(start = 6.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Canvas(Modifier.size(22.dp)) {
                    val u = size.width / 0.75f
                    // Drawn as on the board, centred in the chip.
                    translate(-SnakesGeometry.center(1).x * u + size.width / 2f - u * 0.06f, -SnakesGeometry.center(1).y * u + size.height / 2f - u * 0.08f) {
                        drawHeartSquare(app.heartpact.data.SnakesHeart(1, kind), u)
                    }
                }
                Text(text, style = Heartpact.type.labelSmall, color = colors.ink)
            }
        }
    }
    Text(tr("Kiss & swap trades places with whoever is ahead. A hug shield stops your next snake."), style = Heartpact.type.small, color = colors.ink2,
        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
}

/** The end: hearts burst, the winner is stamped, and both of you get your numbers. */
@Composable
private fun SnakesFinish(g: SnakesState, me: String, partnerName: String, modifier: Modifier = Modifier) {
    val colors = Heartpact.colors
    val still = motionOff()
    val partner = g.players.firstOrNull { it != me }
    val iWon = g.winner == me
    var burst by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) { if (!still) { delay(300); burst = 1 } }
    StickerCard(modifier.fillMaxWidth()) {
        Box {
            Column(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 20.dp, start = 14.dp, end = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Stamp(if (iWon) tr("You won!") else tr("{partner} won", "partner" to partnerName), key = g.winner, delayMs = if (still) 0 else 500)
                Text(GamePlay.snakesOutcome(g, me, partnerName), style = Heartpact.type.body, color = colors.ink2, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp))
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Top) {
                    FinishSide(tr("You"), myWho(), g.positions[me] ?: 0, g.stats[me] ?: SnakesStats(), won = iWon)
                    FinishSide(partnerName, theirWho(), partner?.let { g.positions[it] } ?: 0, partner?.let { g.stats[it] } ?: SnakesStats(), won = !iWon && g.winner != null)
                }
            }
            HeartBurst(burst, Modifier.matchParentSize(), count = 26, spread = 150.dp)
        }
    }
}

@Composable
private fun FinishSide(name: String, who: Who, square: Int, stats: SnakesStats, won: Boolean) {
    val colors = Heartpact.colors
    Column(Modifier.width(150.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        PlayCharacter(who, if (won) Reaction.Cheer else Reaction.Idle, 64.dp, key = "finish")
        Text(name, style = Heartpact.type.label, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Text(tr("Square {number}", "number" to square), style = Heartpact.type.titleSmall, color = colors.ink)
        Spacer(Modifier.height(6.dp))
        listOf(
            trn(stats.rolls, "{count} roll", "{count} rolls"),
            trn(stats.ladders, "{count} ladder climbed", "{count} ladders climbed"),
            trn(stats.snakes, "{count} snake slide", "{count} snake slides"),
            trn(stats.sixes, "{count} six", "{count} sixes"),
        ).plus(listOfNotNull(
            stats.swaps.takeIf { it > 0 }?.let { trn(it, "{count} swap", "{count} swaps") },
            stats.saves.takeIf { it > 0 }?.let { trn(it, "{count} hug save", "{count} hug saves") },
        )).forEach { Text(it, style = Heartpact.type.small, color = colors.ink2, textAlign = TextAlign.Center) }
    }
}
