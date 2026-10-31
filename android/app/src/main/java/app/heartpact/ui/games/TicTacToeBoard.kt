package app.heartpact.ui.games

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.heartpact.data.GameSession
import app.heartpact.data.GameStatus
import app.heartpact.data.utc
import app.heartpact.i18n.tr
import app.heartpact.i18n.trn
import app.heartpact.ui.components.StickerCard
import app.heartpact.ui.play.PlayInk
import app.heartpact.ui.play.dottedPage
import app.heartpact.ui.play.popSpring
import app.heartpact.ui.play.rememberPlayHaptics
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.Shapes
import app.heartpact.ui.theme.partner
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.math.max

/**
 * Tic-tac-toe, both at once: draw your X or O on a square with a finger (or tap it for a quick pen mark) and it's
 * yours if you got there first. Your partner's stroke shows live as they draw it, in their colour. After each mark
 * your pen refills for a moment; after each game the finished board stays up for a beat, then a fresh one comes.
 */
@Composable
internal fun TicTacToeBoard(s: GameSession, me: String, partnerName: String, live: TicTacToeUi, ink: LiveInk, actions: GameActions, now: () -> Instant) {
    val colors = Heartpact.colors
    val t = GamePlay.ticTacToe(s)
    val still = motionOff()
    val haptics = rememberPlayHaptics()
    val inPlay = s.status == GameStatus.InProgress
    val partnerId = t.players.firstOrNull { it != me }

    // The beat after a game: the finished board stays until the server's start time for the next game, then a fresh
    // board shows here, even before the server's update arrives. A board from before the beat existed is over at once.
    val nextAt = t.nextGameAtUtc?.let { runCatching { utc(it).toEpochMilli() }.getOrNull() }
    var beatOver by remember(s.id, t.game, nextAt) { mutableStateOf(nextAt == null || now().toEpochMilli() >= nextAt) }
    LaunchedEffect(s.id, t.game, nextAt) {
        if (nextAt == null || beatOver) return@LaunchedEffect
        delay((nextAt - now().toEpochMilli()).coerceAtLeast(0))
        beatOver = true
    }
    val fresh = inPlay && t.phase == "gameOver" && beatOver
    val game = if (fresh) t.game + 1 else t.game
    val cells = List(9) { if (fresh) null else t.cells.getOrNull(it) }
    val line = if (fresh) emptySet() else t.line.orEmpty().toSet()
    val open = inPlay && (t.phase == "playing" || fresh)
    val pending = live.pending.filter { it.sessionId == s.id && it.game == game }
    val myX = TicTacToeInk.isX(t.players, me)

    // What each square shows: the server's mark (hand drawn, or a pen mark for one placed without strokes) with my
    // strokes still on their way laid on top, or my mark on its way.
    val shown = List(9) { i ->
        val owner = cells[i] ?: pending.firstOrNull { it.cell == i && !it.extra }?.let { me }
        val drawn = if (fresh) null else t.marks.getOrNull(i)?.strokes?.takeIf { it.isNotEmpty() }
        val claim = pending.firstOrNull { it.cell == i && !it.extra }?.strokes
        val base = when {
            cells[i] != null -> drawn ?: TicTacToeInk.tapMark(TicTacToeInk.isX(t.players, cells[i]!!), i, game)
            claim != null -> claim.ifEmpty { TicTacToeInk.tapMark(myX, i, game) }
            else -> emptyList()
        }
        val extra = if (owner == me) pending.filter { it.cell == i && it.extra }.flatMap { it.strokes } else emptyList()
        owner to (base + extra).take(TicTacToeInk.MAX_STROKES).map(TicTacToeInk::points)
    }

    // The partner's ink fades once they stop adding to it; a square that's taken shows its mark instead.
    val inking = ink.trails.isNotEmpty()
    LaunchedEffect(inking, s.id) {
        while (inking) {
            ink.prune(now().toEpochMilli(), s.id)
            delay(250)
        }
    }

    // "Too slow!": the server's (a mark that didn't land) or this phone's own (the square went while the finger was down).
    var localSlow by remember(s.id) { mutableStateOf<TooSlow?>(null) }
    var slow by remember(s.id) { mutableStateOf<TooSlow?>(null) }
    LaunchedEffect(live.tooSlow?.id) { live.tooSlow?.takeIf { it.sessionId == s.id }?.let { slow = it } }
    LaunchedEffect(localSlow?.id) { localSlow?.let { slow = it } }
    LaunchedEffect(slow?.id) {
        val current = slow ?: return@LaunchedEffect
        haptics.tick()
        delay(1300)
        if (slow?.id == current.id) slow = null
    }
    val slowHere = slow?.takeIf { it.game == game }

    // The finger's state, read by gesture handlers that outlive recompositions.
    var penShake by remember { mutableStateOf(0) }
    var drewHere by remember(s.id) { mutableStateOf<Pair<Int, Int>?>(null) }
    val rules by rememberUpdatedState(CellRules(open, game, shown.map { it.first }, me, live, now))
    val strokeNumber = remember { intArrayOf(0) }
    val localIds = remember { longArrayOf(-1) }
    val input = remember(s.id) {
        object : CellInput {
            override fun start(cell: Int): Touch = rules.start(cell)
            override fun ink(cell: Int, stroke: Int, points: FloatArray) = actions.onInk(s, cell, stroke, points)
            override fun nextStroke() = ++strokeNumber[0]
            override fun refused(cell: Int, touch: Touch) {
                if (touch.start == Start.Drying) penShake++
                haptics.tick()
            }
            override fun finish(cell: Int, touch: Touch, points: List<Offset>, tap: Boolean) {
                val r = rules
                when {
                    touch.start == Start.Extra -> if (!tap && points.size > 1) actions.onAct(s, "ink", TicTacToeInk.encode(r.game, listOf(TicTacToeInk.stroke(points))), cell)
                    r.owners.getOrNull(cell) != null || !r.open || r.game != touch.game -> {
                        // Taken while the finger was down: un-draw it here, no need to ask.
                        localSlow = TooSlow(s.id, touch.game, cell, localIds[0]--, taken = true)
                        actions.onInk(s, -1, 0, FloatArray(0))
                    }
                    tap -> actions.onAct(s, "place", TicTacToeInk.encode(r.game, TicTacToeInk.tapMark(myX, cell, r.game)), cell)
                    else -> {
                        drewHere = r.game to cell
                        actions.onAct(s, "place", TicTacToeInk.encode(r.game, listOf(TicTacToeInk.stroke(points))), cell)
                    }
                }
            }
        }
    }

    // The match: who's winning, which game this is; then what's happening now.
    val pips: @Composable () -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (s.status != GameStatus.Completed) Text(tr("Game {game} of {games}", "game" to game + 1, "games" to t.totalGames), style = Heartpact.type.labelSmall, color = colors.ink3)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                (0 until t.totalGames).forEach { i -> GamePip(t.results.getOrNull(i), i < t.results.size, i + 1, me, partnerName) }
            }
        }
    }
    if (s.status == GameStatus.Completed) {
        Box(Modifier.fillMaxWidth().padding(bottom = 14.dp), contentAlignment = Alignment.Center) { pips() }
    } else {
        Scoreboard(t.scores[me] ?: 0, partnerId?.let { t.scores[it] } ?: 0, partnerName, if (open) Turn.Both else Turn.None,
            { trn(it, "{count} win", "{count} wins") }, middle = pips)
        val last = t.results.lastOrNull()
        val title = when {
            !open && t.phase == "gameOver" -> when (last) {
                me -> tr("You won game {game}!", "game" to t.game + 1)
                null -> tr("Game {game} is a draw", "game" to t.game + 1)
                else -> tr("{partner} won game {game}", "partner" to partnerName, "game" to t.game + 1)
            }
            myX -> tr("You're X. Draw fast!")
            else -> tr("You're O. Draw fast!")
        }
        Text(title, style = Heartpact.type.title, color = colors.ink, modifier = Modifier.padding(bottom = 12.dp))
    }

    StickerCard(Modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.dottedPage(colors.rule)) {
            val gap = 8.dp
            val pad = 12.dp
            val cell = (maxWidth - pad * 2 - gap * 2) / 3
            Column(Modifier.padding(pad), verticalArrangement = Arrangement.spacedBy(gap)) {
                (0 until 3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        (0 until 3).forEach { column ->
                            val i = row * 3 + column
                            val (owner, strokes) = shown[i]
                            val trail = ink.trails[i]?.takeIf { owner == null && it.sessionId == s.id && open }?.strokes
                            Square(
                                index = i, game = game, owner = owner, strokes = strokes, me = me, partnerName = partnerName,
                                trail = trail, winning = i in line, decided = line.isNotEmpty(), open = open,
                                drawnHere = drewHere == game to i, shake = slowHere?.takeIf { it.cell == i }?.id,
                                input = input, modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
            WinLine(line, 3, 3, gap, Modifier.matchParentSize().padding(pad))
            slowHere?.let { bounce ->
                val x = pad + (cell + gap) * (bounce.cell % 3)
                val y = pad + (cell + gap) * (bounce.cell / 3)
                Box(Modifier.offset(x, y).size(cell), contentAlignment = Alignment.Center) {
                    TooSlowBubble(bounce.id, bounce.taken, Modifier.wrapContentSize(unbounded = true))
                }
            }
        }
    }

    if (open) {
        PenMeter(live.penFromMs, live.penReadyAtMs, now, penShake, Modifier.padding(top = 14.dp))
        Text(tr("Draw your mark on a square, or tap one. First one there gets it!"), style = Heartpact.type.body, color = colors.ink2,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
    } else if (inPlay && t.phase == "gameOver") {
        Text(tr("Game {game} starts in a moment…", "game" to t.game + 2), style = Heartpact.type.bodyLarge, color = colors.ink2,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
    }
}

/** How a touch on a square begins: a new mark, another stroke on my newest mark, or not at all (and why). */
internal enum class Start { Claim, Extra, Taken, Drying, Closed }

/** A touch as it began: how, and in which game. */
internal data class Touch(val start: Start, val game: Int)

/** The rules for a touch, as of the latest composition. */
internal class CellRules(val open: Boolean, val game: Int, val owners: List<String?>, val me: String, val live: TicTacToeUi, val now: () -> Instant) {
    fun start(cell: Int): Touch {
        val nowMs = now().toEpochMilli()
        val wet = live.wet
        val start = when {
            !open -> Start.Closed
            owners.getOrNull(cell) == me && wet != null && wet.cell == cell && wet.game == game && nowMs <= wet.untilMs && wet.strokes < TicTacToeInk.MAX_STROKES -> Start.Extra
            owners.getOrNull(cell) != null -> Start.Taken
            nowMs < live.penReadyAtMs -> Start.Drying
            else -> Start.Claim
        }
        return Touch(start, game)
    }
}

internal interface CellInput {
    fun start(cell: Int): Touch
    fun ink(cell: Int, stroke: Int, points: FloatArray)
    fun nextStroke(): Int
    fun refused(cell: Int, touch: Touch)
    fun finish(cell: Int, touch: Touch, points: List<Offset>, tap: Boolean)
}

@Composable
private fun Square(
    index: Int, game: Int, owner: String?, strokes: List<List<Offset>>, me: String, partnerName: String, trail: List<List<Offset>>?,
    winning: Boolean, decided: Boolean, open: Boolean, drawnHere: Boolean, shake: Long?, input: CellInput, modifier: Modifier,
) {
    val colors = Heartpact.colors
    val still = motionOff()
    val side = owner?.let { sideOf(it, me) }
    val pen = side?.let { colors.partner(it).action } ?: Color.Transparent
    val partnerPen = colors.partner(theirSide()).action
    val myPen = Heartpact.actor.action
    val pulse = winPulse(winning)
    val lifted by animateFloatAsState(if (winning) 1f else 0f, gentle(tween(700)), label = "line")
    val dimmed by animateFloatAsState(if (decided && !winning) 1f else 0f, gentle(tween(500)), label = "dim")
    val tile = if (side != null) lerp(colors.sticker2, colors.partner(side).soft, lifted) else colors.sticker2
    val shaken = rememberShake(shake)

    // A mark that lands pops; one that wasn't drawn by this finger is drawn on quickly, like a pen.
    val present = strokes.isNotEmpty()
    val drawOn = remember(game, index) { Animatable(if (present) 1f else 0f) }
    val pop = remember(game, index) { Animatable(1f) }
    LaunchedEffect(present) {
        if (!present) { drawOn.snapTo(0f); return@LaunchedEffect }
        if (drawOn.value >= 1f) return@LaunchedEffect
        if (still) { drawOn.snapTo(1f); return@LaunchedEffect }
        launch { pop.snapTo(1.18f); pop.animateTo(1f, popSpring()) }
        if (drawnHere) drawOn.snapTo(1f) else drawOn.animateTo(1f, tween(170, easing = LinearEasing))
    }

    // The stroke under the finger, and its live ink to the partner (a message every 50 ms at most).
    val finger = remember { mutableStateListOf<Offset>() }
    var fingerPen by remember { mutableStateOf(myPen) }
    val scope = rememberCoroutineScope()

    val label = when {
        owner == null && trail != null -> tr("Square {number}, {partner} is drawing", "number" to index + 1, "partner" to partnerName)
        owner == null -> tr("Square {number}, empty", "number" to index + 1)
        owner == me -> tr("Square {number}, yours", "number" to index + 1)
        else -> tr("Square {number}, {partner}'s", "number" to index + 1, "partner" to partnerName)
    }
    val place = tr("Place your mark")
    Box(
        modifier.aspectRatio(1f)
            .graphicsLayer { translationX = shaken.value * density }
            .clip(Shapes.Tile).background(tile)
            .semantics {
                contentDescription = label
                if (open && owner == null) {
                    role = Role.Button
                    onClick(place) {
                        val touch = input.start(index)
                        if (touch.start == Start.Claim) input.finish(index, touch, emptyList(), tap = true) else input.refused(index, touch)
                        true
                    }
                }
            }
            .pointerInput(index) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val touch = input.start(index)
                    if (touch.start != Start.Claim && touch.start != Start.Extra) {
                        if (touch.start != Start.Closed) input.refused(index, touch)
                        return@awaitEachGesture
                    }
                    down.consume()
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    val h = size.height.toFloat().coerceAtLeast(1f)
                    fun norm(p: Offset) = Offset((p.x / w).coerceIn(0f, 1f), (p.y / h).coerceIn(0f, 1f))
                    finger.clear()
                    finger.add(norm(down.position))
                    fingerPen = myPen
                    val stroke = input.nextStroke()
                    var travel = 0f
                    // Live ink: whatever's new since the last message, every 50 ms, while the finger is down.
                    var sent = 0
                    fun flush() {
                        if (finger.size <= sent) return
                        val fresh = finger.subList(maxOf(0, sent - 1), finger.size).toList().takeLast(TicTacToeInk.INK_POINTS)
                        sent = finger.size
                        input.ink(index, stroke, TicTacToeInk.inkNumbers(fresh))
                    }
                    val sender: Job = scope.launch {
                        while (isActive) {
                            delay(TicTacToeInk.INK_EVERY_MS)
                            flush()
                        }
                    }
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            change.historical.forEach { finger.add(norm(it.position)) }
                            finger.add(norm(change.position))
                            travel = max(travel, (change.position - down.position).getDistance())
                            change.consume()
                            if (!change.pressed) break
                        }
                    } finally {
                        sender.cancel()
                    }
                    val tap = travel < viewConfiguration.touchSlop
                    if (!tap) flush()
                    val points = finger.toList()
                    finger.clear()
                    input.finish(index, touch, points, tap)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize().padding(6.dp)) {
            val width = size.minDimension * 0.085f
            trail?.let { inkStrokes(it, partnerPen.copy(alpha = 0.6f), width * 0.85f, 1f) }
            if (present) {
                val p = pop.value * (1f + 0.1f * sinHalf(lifted)) * (1f + 0.07f * pulse)
                val alpha = 1f - 0.55f * dimmed
                scale(p, pivot = center) { inkStrokes(strokes, pen.copy(alpha = alpha), width, drawOn.value) }
            }
            if (finger.isNotEmpty()) inkStrokes(listOf(finger.toList()), fingerPen, width, 1f)
        }
    }
}

private fun sinHalf(p: Float) = kotlin.math.sin(Math.PI.toFloat() * p)


/**
 * Hand-drawn strokes (points as fractions of the square), smoothed through the midpoints, round ends; a tap of a
 * point is a dot. With [progress] under 1 only that share of the whole mark is drawn, stroke after stroke.
 */
private fun DrawScope.inkStrokes(strokes: List<List<Offset>>, colour: Color, width: Float, progress: Float) {
    if (progress <= 0f) return
    val paths = strokes.filter { it.isNotEmpty() }.map { points ->
        val scaled = points.map { Offset(it.x * size.width, it.y * size.height) }
        Path().apply {
            moveTo(scaled[0].x, scaled[0].y)
            if (scaled.size == 1) lineTo(scaled[0].x + 0.01f, scaled[0].y)
            for (i in 1 until scaled.size - 1) {
                val mid = (scaled[i] + scaled[i + 1]) / 2f
                quadraticTo(scaled[i].x, scaled[i].y, mid.x, mid.y)
            }
            if (scaled.size > 1) lineTo(scaled.last().x, scaled.last().y)
        }
    }
    val style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round)
    if (progress >= 1f) {
        paths.forEach { drawPath(it, colour, style = style) }
        return
    }
    val measures = paths.map { PathMeasure().apply { setPath(it, false) } }
    var left = measures.sumOf { it.length.toDouble() }.toFloat() * progress
    measures.forEach { measure ->
        if (left <= 0f) return
        val part = Path()
        measure.getSegment(0f, minOf(left, measure.length), part, true)
        drawPath(part, colour, style = style)
        left -= measure.length
    }
}

/** "Too slow!" popping up over a square the partner got to first; a plain shake (no words) when it was something else. */
@Composable
private fun TooSlowBubble(key: Long, taken: Boolean, modifier: Modifier = Modifier) {
    if (!taken) return
    val still = motionOff()
    val rise = remember(key) { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(key) { if (!still) rise.animateTo(1f, popSpring()) }
    Text(
        tr("Too slow!"), style = Heartpact.type.label, color = Color.White,
        modifier = modifier
            .graphicsLayer {
                val p = rise.value
                scaleX = 0.4f + 0.6f * p; scaleY = 0.4f + 0.6f * p
                translationY = -14.dp.toPx() * p
                rotationZ = -7f
                alpha = p.coerceIn(0f, 1f)
            }
            .clip(Shapes.Pill).background(PlayInk.Pen).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** My pen: an ink bar in my colour that refills after each mark; a refused touch while it's drying gives it a shake. */
@Composable
private fun PenMeter(fromMs: Long, readyAtMs: Long, now: () -> Instant, shakeKey: Int, modifier: Modifier = Modifier) {
    val colors = Heartpact.colors
    val still = motionOff()
    val mine = Heartpact.actor
    fun fill(): Float = if (readyAtMs <= fromMs) 1f else ((now().toEpochMilli() - fromMs).toFloat() / (readyAtMs - fromMs)).coerceIn(0f, 1f)
    var level by remember { mutableFloatStateOf(fill()) }
    LaunchedEffect(fromMs, readyAtMs) {
        level = fill()
        if (still) {
            // Without motion it fills in one step, when the pen is ready.
            if (level < 1f) { delay((readyAtMs - now().toEpochMilli()).coerceAtLeast(0)); level = 1f }
            return@LaunchedEffect
        }
        while (level < 1f) {
            withFrameMillis { }
            level = fill()
        }
    }
    val shake = rememberShake(shakeKey.takeIf { it > 0 })
    val ready = level >= 1f
    val state = if (ready) tr("Pen ready") else tr("Ink drying…")
    Row(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) { stateDescription = state }
            .graphicsLayer { translationX = shake.value * density },
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(18.dp)) {
            // A pen nib, tilted, in my colour.
            val w = size.width
            val nib = Path().apply {
                moveTo(w * 0.15f, w * 0.85f); lineTo(w * 0.3f, w * 0.45f); lineTo(w * 0.75f, w * 0.05f)
                lineTo(w * 0.95f, w * 0.25f); lineTo(w * 0.55f, w * 0.7f); close()
            }
            drawPath(nib, mine.action)
            drawCircle(colors.sticker, w * 0.06f, Offset(w * 0.52f, w * 0.48f))
        }
        Box(Modifier.padding(start = 8.dp).width(120.dp).height(10.dp).clip(Shapes.Pill).background(colors.rule2)) {
            Box(Modifier.fillMaxWidth(level).height(10.dp).clip(Shapes.Pill).background(if (ready) mine.action else mine.action.copy(alpha = 0.55f)))
        }
        Text(state, style = Heartpact.type.small, color = if (ready) colors.ink2 else colors.ink3, modifier = Modifier.padding(start = 8.dp).width(96.dp))
    }
}
