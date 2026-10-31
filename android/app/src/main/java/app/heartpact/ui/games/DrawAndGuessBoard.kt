package app.heartpact.ui.games

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.heartpact.data.DrawAndGuessState
import app.heartpact.data.DrawRound
import app.heartpact.data.DrawStroke
import app.heartpact.data.GameSession
import app.heartpact.data.GameStatus
import app.heartpact.i18n.tr
import app.heartpact.i18n.trn
import app.heartpact.ui.components.ButtonKind
import app.heartpact.ui.components.HeartButton
import app.heartpact.ui.components.HeartField
import app.heartpact.ui.components.NoteCard
import app.heartpact.ui.components.StickerCard
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.Shapes
import app.heartpact.ui.theme.Side
import app.heartpact.ui.theme.partner
import kotlinx.serialization.json.Json
import kotlin.math.hypot
import kotlin.math.roundToLong

/**
 * Draw and guess (`handwriting` on the wire). The drawer sees the secret word and draws; each stroke goes to the
 * server when the finger lifts. The partner watches the strokes arrive and has three guesses. Strokes are kept as
 * fractions of the canvas, so the drawing scales to any phone.
 */
@Composable
internal fun DrawAndGuessBoard(s: GameSession, me: String, partnerName: String, pending: List<PendingMove>, actions: GameActions) {
    val colors = Heartpact.colors
    val d = GamePlay.drawAndGuess(s)
    val mine = pending.filter { it.sessionId == s.id && it.round == d.round }
    val strokes = Sketching.withPending(d.strokes, mine)
    var seenReveal by rememberSaveable(s.id) { mutableIntStateOf(-1) }
    // The result card above says how it went; the last drawing stays to look at.
    if (s.status == GameStatus.Completed) {
        d.lastRound?.let { Reveal(it, d, me, partnerName) }
        return
    }

    // A team game: a heart each for every drawing guessed.
    TogetherScore(d.scores[me] ?: 0, tr("Round {round} of {rounds}", "round" to minOf(d.round + 1, d.totalRounds), "rounds" to d.totalRounds), turnOf(s, me))
    val last = d.lastRound
    val fresh = strokes.isEmpty() && d.wrongGuesses.isEmpty() && mine.isEmpty()
    if (last != null && last.round > seenReveal && fresh) {
        Reveal(last, d, me, partnerName, Modifier.padding(top = 12.dp))
        HeartButton(if (d.drawer == me) tr("Your turn to draw") else tr("Next round"), { seenReveal = last.round }, Modifier.padding(top = 14.dp))
        return
    }
    last?.let { NoteCard(lastRoundLine(it), Modifier.padding(top = 10.dp)) }

    val pen = penColor(d, d.drawer)
    if (d.drawer == me) {
        StickerCard(Modifier.fillMaxWidth().padding(top = 12.dp), shape = Shapes.Well, lift = 4.dp) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(tr("Draw this"), style = Heartpact.type.labelSmall, color = colors.ink2)
                Text(d.word.orEmpty(), style = Heartpact.type.headline, color = colors.ink)
                Text(tr("Only you can see it. No letters or numbers!"), style = Heartpact.type.small, color = colors.ink3)
            }
        }
        DrawingCanvas(strokes, pen, Modifier.padding(top = 12.dp), maxSide = canvasSide(reserve = 330.dp), onStroke = { actions.onAct(s, "stroke", it, null) })
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HeartButton(tr("Undo"), { actions.onAct(s, "undo", null, null) }, Modifier.weight(1f), kind = ButtonKind.Tonal, height = 48.dp, enabled = strokes.isNotEmpty())
            HeartButton(tr("Clear"), { actions.onAct(s, "clear", null, null) }, Modifier.weight(1f), kind = ButtonKind.Tonal, height = 48.dp, enabled = strokes.isNotEmpty())
        }
        Text(trn(d.guessesLeft, "{partner} has {count} guess left", "{partner} has {count} guesses left", "partner" to partnerName),
            style = Heartpact.type.body, color = colors.ink2, modifier = Modifier.padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite })
        WrongGuesses(d.wrongGuesses)
        // Passing ends the round with no hearts: kept apart from Undo and Clear so it isn't hit by mistake.
        HeartButton(tr("Pass on this word"), { actions.onAct(s, "pass", null, null) }, Modifier.padding(top = 6.dp), kind = ButtonKind.Text, height = 48.dp)
    } else {
        Text(tr("What is {partner} drawing?", "partner" to partnerName), style = Heartpact.type.title, color = colors.ink, modifier = Modifier.padding(top = 12.dp))
        Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
            // Smaller while the keyboard is up, so the drawing stays in view as the guess is typed.
            DrawingCanvas(strokes, pen, maxSide = canvasSide(reserve = 320.dp), description = tr("{partner}'s drawing", "partner" to partnerName))
            if (strokes.isEmpty()) Text(tr("{partner} hasn't started drawing yet", "partner" to partnerName), style = Heartpact.type.body, color = colors.ink3,
                textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center).padding(24.dp))
        }
        Text(trn(d.guessesLeft, "{count} guess left", "{count} guesses left"), style = Heartpact.type.label, color = colors.ink, modifier = Modifier.padding(top = 12.dp))
        WrongGuesses(d.wrongGuesses)
        var guess by rememberSaveable(s.id, d.round) { mutableStateOf("") }
        val sending = mine.any { it.action == "guess" }
        val send = { if (guess.isNotBlank() && !sending) { actions.onAct(s, "guess", guess.trim(), null); guess = "" } }
        HeartField(tr("Your guess"), guess, { guess = it.take(60) }, Modifier.padding(top = 12.dp),
            keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Send), actions = KeyboardActions(onSend = { send() }))
        HeartButton(tr("Guess"), send, Modifier.padding(top = 10.dp), enabled = guess.isNotBlank(), busy = sending)
    }
}

/** The end of a round: the word for both, how it went, and the drawing. */
@Composable
private fun Reveal(last: DrawRound, d: DrawAndGuessState, me: String, partnerName: String, modifier: Modifier = Modifier) {
    val colors = Heartpact.colors
    val drewIt = last.drawer == me
    val guessed = last.outcome == "guessed"
    RoundReveal(last.round, if (guessed) tr("Guessed!") else null, hearts = guessed, modifier) { StickerCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(tr("The word was"), style = Heartpact.type.labelSmall, color = colors.ink2)
            Text(last.word, style = Heartpact.type.display.copy(fontSize = 40.sp, lineHeight = 42.sp), color = colors.ink)
            Text(when (last.outcome) {
                "guessed" -> if (drewIt) tr("{partner} guessed it. A heart each!", "partner" to partnerName) else tr("You guessed it. A heart each!")
                "passed" -> if (drewIt) tr("You passed on this one.") else tr("{partner} passed on this one.", "partner" to partnerName)
                else -> tr("Three misses. No hearts this round.")
            }, style = Heartpact.type.bodyLarge, color = colors.ink2, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
            DrawingCanvas(last.strokes, penColor(d, last.drawer), description = tr("The drawing"))
            WrongGuesses(last.wrongGuesses)
        }
    } }
}

private fun lastRoundLine(last: DrawRound) = when (last.outcome) {
    "guessed" -> tr("Last round: {word}. Guessed!", "word" to last.word)
    "passed" -> tr("Last round: {word}. Passed.", "word" to last.word)
    else -> tr("Last round: {word}. Not guessed.", "word" to last.word)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WrongGuesses(guesses: List<String>) {
    if (guesses.isEmpty()) return
    val colors = Heartpact.colors
    Text(tr("Not it"), style = Heartpact.type.labelSmall, color = colors.ink2, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
    // The newest miss shakes its head as it lands.
    val shake = rememberShake(guesses.size)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        guesses.forEachIndexed { i, guess ->
            val newest = i == guesses.lastIndex
            Text(guess, style = Heartpact.type.label.copy(textDecoration = TextDecoration.LineThrough), color = colors.ink3,
                modifier = Modifier.graphicsLayer { if (newest) translationX = shake.value * density }
                    .clip(Shapes.Pill).background(colors.sticker2).border(1.dp, colors.rule2, Shapes.Pill).padding(horizontal = 12.dp, vertical = 7.dp))
        }
    }
}

/** The drawer's pen: their half of the heart (the couple's first member is the left half). */
@Composable
private fun penColor(d: DrawAndGuessState, drawer: String?): Color =
    Heartpact.colors.partner(if (d.players.indexOf(drawer) == 1) Side.Right else Side.Left).action

/**
 * A square canvas. With [onStroke] it takes drawing: the stroke under the finger shows as it's drawn and is handed
 * over, simplified and encoded, when the finger lifts.
 */
@Composable
private fun DrawingCanvas(strokes: List<DrawStroke>, pen: Color, modifier: Modifier = Modifier, maxSide: Dp = Dp.Unspecified, description: String = tr("Drawing canvas"), onStroke: ((String) -> Unit)? = null) {
    val colors = Heartpact.colors
    val live = remember { mutableStateListOf<Offset>() }
    // Watching: a stroke that arrives is drawn on, pen-speed, rather than popping in whole. [shown] strokes are done.
    val still = motionOff()
    var shown by remember { mutableIntStateOf(strokes.size) }
    val drawing = remember { Animatable(0f) }
    val arriving = onStroke == null && !still && strokes.size == shown + 1
    LaunchedEffect(strokes.size) {
        if (onStroke == null && !still && strokes.size == shown + 1) {
            drawing.snapTo(0f)
            drawing.animateTo(1f, tween(Sketching.drawOnMs(strokes.last()), easing = LinearEasing))
        }
        shown = strokes.size
        drawing.snapTo(0f)
    }
    // The gesture handler outlives recompositions: always hand the stroke to the latest callback.
    val send by rememberUpdatedState(onStroke)
    var input = Modifier.semantics { contentDescription = description }
    if (onStroke != null) input = input.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            down.consume()
            live.clear()
            live.add(Sketching.normalize(down.position, size.width.toFloat(), size.height.toFloat()))
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                change.historical.forEach { live.add(Sketching.normalize(it.position, size.width.toFloat(), size.height.toFloat())) }
                live.add(Sketching.normalize(change.position, size.width.toFloat(), size.height.toFloat()))
                change.consume()
                if (!change.pressed) break
            }
            val points = live.toList()
            live.clear()
            send?.invoke(Sketching.encode(points, Sketching.PEN_WIDTH))
        }
    }
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) { Box(
        Modifier.widthIn(max = maxSide).fillMaxWidth().aspectRatio(1f).clip(Shapes.Well).background(colors.sticker).border(2.dp, colors.rule2, Shapes.Well).then(input),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            strokes.forEachIndexed { i, stroke ->
                val progress = if (arriving && i == strokes.lastIndex) drawing.value else 1f
                drawStroke(stroke.points.map { p -> Offset(p.getOrElse(0) { 0.0 }.toFloat(), p.getOrElse(1) { 0.0 }.toFloat()) }, stroke.width.toFloat(), pen, progress)
            }
            if (live.isNotEmpty()) drawStroke(live.toList(), Sketching.PEN_WIDTH.toFloat(), pen)
        }
    } }
}

/**
 * The longest side the canvas may have: the window's height less [reserve] for what must stay in view with it (the
 * word, the buttons, the guess field) and less the keyboard while it's up, so on a phone held sideways or with the
 * keyboard open the whole drawing still fits on screen. Never smaller than a usable 180 dp.
 */
@Composable
private fun canvasSide(reserve: Dp): Dp {
    val density = LocalDensity.current
    val window = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    val keyboard = with(density) { WindowInsets.ime.getBottom(density).toDp() }
    if (window <= 0.dp) return Dp.Unspecified
    return (window - keyboard - reserve).coerceAtLeast(180.dp)
}

/**
 * One stroke, smoothed: a curve through every point (Catmull-Rom tangents, kept short so long edges don't bulge, and
 * none at sharp turns so corners stay corners), round ends; a tap is a dot.
 */
private fun DrawScope.drawStroke(points: List<Offset>, width: Float, color: Color, progress: Float = 1f) {
    if (points.isEmpty() || progress <= 0f) return
    val px = (width * size.width).coerceAtLeast(1f)
    val scaled = points.map { Offset(it.x * size.width, it.y * size.height) }
    if (scaled.size == 1 || scaled.all { (it - scaled[0]).getDistance() < 0.5f }) {
        drawCircle(color, px / 2, scaled[0])
        return
    }
    val path = Path().apply {
        moveTo(scaled[0].x, scaled[0].y)
        val tangents = scaled.indices.map { tangent(scaled, it) }
        for (i in 0 until scaled.size - 1) {
            val p1 = scaled[i]
            val p2 = scaled[i + 1]
            val reach = (p2 - p1).getDistance() / 3f
            val c1 = p1 + clampLength(tangents[i], reach)
            val c2 = p2 - clampLength(tangents[i + 1], reach)
            cubicTo(c1.x, c1.y, c2.x, c2.y, p2.x, p2.y)
        }
    }
    val shown = if (progress >= 1f) path else Path().also { part ->
        val measure = PathMeasure().apply { setPath(path, false) }
        measure.getSegment(0f, measure.length * progress, part, true)
    }
    drawPath(shown, color, style = Stroke(px, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** Catmull-Rom's tangent at point [i], or none at a sharp turn so a corner stays a corner. */
private fun tangent(points: List<Offset>, i: Int): Offset {
    if (i == 0 || i == points.size - 1) return Offset.Zero
    val before = points[i] - points[i - 1]
    val after = points[i + 1] - points[i]
    val lengths = before.getDistance() * after.getDistance()
    if (lengths == 0f || (before.x * after.x + before.y * after.y) / lengths < 0.5f) return Offset.Zero
    return (points[i + 1] - points[i - 1]) / 2f
}

private fun clampLength(v: Offset, max: Float): Offset {
    val length = v.getDistance()
    return if (length <= max || length == 0f) v else v * (max / length)
}

/** Pure stroke handling for Draw and guess: the wire format (docs/games.md) and what the drawer sees before the server answers. */
object Sketching {
    /** Pen width as a fraction of the canvas width. */
    const val PEN_WIDTH = 0.012
    const val MAX_POINTS = 200
    // The server wants both fields, even when the width is the default.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun normalize(position: Offset, width: Float, height: Float) =
        Offset((position.x / width.coerceAtLeast(1f)).coerceIn(0f, 1f), (position.y / height.coerceAtLeast(1f)).coerceIn(0f, 1f))

    /**
     * Fewer points, same shape (Ramer–Douglas–Peucker): the tolerance grows until the stroke fits in [max] points,
     * so a long scribble still goes in one move under the server's size limit.
     */
    fun simplify(points: List<Offset>, max: Int = MAX_POINTS): List<Offset> {
        if (points.size <= 2) return points
        var tolerance = 0.0015f
        var result = rdp(points, tolerance)
        while (result.size > max) {
            tolerance *= 1.6f
            result = rdp(points, tolerance)
        }
        return result
    }

    /** `{"points":[[x,y],...],"width":w}`, coordinates rounded to three places. */
    fun encode(points: List<Offset>, width: Double): String {
        val simple = simplify(points)
        val stroke = DrawStroke(simple.map { listOf(round3(it.x.toDouble()), round3(it.y.toDouble())) }, width)
        return json.encodeToString(DrawStroke.serializer(), stroke)
    }

    /** How long a stroke takes to draw on for the partner watching: about a second across the canvas, quick for a dot. */
    fun drawOnMs(stroke: DrawStroke): Int {
        val length = stroke.points.zipWithNext { a, b -> hypot(b.getOrElse(0) { 0.0 } - a.getOrElse(0) { 0.0 }, b.getOrElse(1) { 0.0 } - a.getOrElse(1) { 0.0 }) }.sum()
        return (length * 900).toInt().coerceIn(140, 750)
    }

    fun decode(value: String?): DrawStroke? = value?.let { runCatching { json.decodeFromString(DrawStroke.serializer(), it) }.getOrNull() }

    /** The server's strokes with this phone's moves still on their way laid on top, in order. */
    fun withPending(strokes: List<DrawStroke>, pending: List<PendingMove>): List<DrawStroke> =
        pending.fold(strokes) { acc, move ->
            when (move.action) {
                "stroke" -> decode(move.value)?.let { acc + it } ?: acc
                "undo" -> acc.dropLast(1)
                "clear" -> emptyList()
                else -> acc
            }
        }

    private fun round3(value: Double) = (value * 1000).roundToLong() / 1000.0

    private fun rdp(points: List<Offset>, tolerance: Float): List<Offset> {
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to points.size - 1)
        while (stack.isNotEmpty()) {
            val (start, end) = stack.removeLast()
            var farthest = -1
            var distance = tolerance
            for (i in start + 1 until end) {
                val d = distanceToSegment(points[i], points[start], points[end])
                if (d > distance) { distance = d; farthest = i }
            }
            if (farthest >= 0) {
                keep[farthest] = true
                stack.addLast(start to farthest)
                stack.addLast(farthest to end)
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    private fun distanceToSegment(p: Offset, a: Offset, b: Offset): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val length = dx * dx + dy * dy
        if (length == 0f) return hypot(p.x - a.x, p.y - a.y)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / length).coerceIn(0f, 1f)
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }
}
