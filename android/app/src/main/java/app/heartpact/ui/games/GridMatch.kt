package app.heartpact.ui.games

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.heartpact.data.GameSession
import app.heartpact.data.GameStatus
import app.heartpact.data.GridMatchState
import app.heartpact.data.utc
import app.heartpact.i18n.tr
import app.heartpact.i18n.trn
import app.heartpact.ui.graphics.HalfState
import app.heartpact.ui.graphics.HeartGeometry
import app.heartpact.ui.graphics.HeartSticker
import app.heartpact.ui.graphics.LocalStillLogo
import app.heartpact.ui.graphics.inUnits
import app.heartpact.ui.play.rememberPlayHaptics
import app.heartpact.ui.theme.Art
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.LocalActor
import app.heartpact.ui.theme.Shapes
import app.heartpact.ui.theme.Side
import kotlinx.coroutines.delay
import java.time.Instant

/*
 * Connect four's frame (tic-tac-toe borrows its pips, win line and pieces): a short match on a grid, each partner playing their own half of the heart
 * (the left partner indigo, the right periwinkle). After a game the finished board stays up with its winning line for
 * the server's beat (`nextGameAtUtc`), then the next game's fresh board comes on its own, on both phones at once.
 */

/** What the board shows right now. */
internal class GridShown(
    val cells: List<String?>,
    val line: Set<Int>,
    /** The game on the board, from 0. */
    val game: Int,
    /** The person holding the phone may move. */
    val canMove: Boolean,
)

/** Which side of the heart a cell's owner plays. */
@Composable
internal fun sideOf(owner: String, me: String): Side {
    val mine = LocalActor.current
    return if (owner == me) mine else if (mine == Side.Left) Side.Right else Side.Left
}

/**
 * With animations turned off in the system settings, things change without moving and nothing loops. The same goes
 * for a still frame (screenshots hold the live logo still the same way): it shows every end state at once.
 */
@Composable
internal fun motionOff(): Boolean {
    val context = LocalContext.current
    val off = remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    return off || LocalStillLogo.current
}

/**
 * A board laid out left to right whatever the language: squares, columns and pictures are places, not reading order,
 * so square 1 is in the same corner on both phones (and where the server, the win line and the drawings put it).
 */
@Composable
internal fun LeftToRight(content: @Composable () -> Unit) = CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr, content = content)

/** [spec], or an instant change when motion is off. */
@Composable
internal fun <T> gentle(spec: AnimationSpec<T>): AnimationSpec<T> = if (motionOff()) snap() else spec

/**
 * The match around a board: who's winning, which game this is, whose turn, then the board itself. [now] is the
 * server's clock as the phone reckons it, for the beat between games.
 */
@Composable
internal fun GridMatchFrame(s: GameSession, me: String, partnerName: String, busy: Boolean, yourMove: String, now: () -> Instant, board: @Composable (GridMatchState, GridShown) -> Unit) {
    val colors = Heartpact.colors
    val g = GamePlay.grid(s)
    val myTurn = s.currentTurnUserId == me && s.status == GameStatus.InProgress
    val gameOver = g.phase == "gameOver" && s.status == GameStatus.InProgress
    // The beat after a game: the finished board stays until the server's start time for the next game, then the fresh
    // board shows here, even before the server's update arrives. A board from before the beat existed is over at once.
    val nextAt = g.nextGameAtUtc?.let { runCatching { utc(it).toEpochMilli() }.getOrNull() }
    var beatOver by remember(s.id, g.game, nextAt) { mutableStateOf(nextAt == null || now().toEpochMilli() >= nextAt) }
    LaunchedEffect(s.id, g.game, nextAt) {
        if (nextAt == null || beatOver) return@LaunchedEffect
        delay((nextAt - now().toEpochMilli()).coerceAtLeast(0))
        beatOver = true
    }
    val cleared = gameOver && beatOver
    val shown = GridShown(
        cells = if (cleared) List(g.cells.size) { null } else g.cells,
        line = if (cleared) emptySet() else g.line.orEmpty().toSet(),
        game = if (cleared) g.game + 1 else g.game,
        canMove = myTurn && !busy && (!gameOver || cleared),
    )
    val partnerId = g.players.firstOrNull { it != me }

    val games: @Composable () -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (s.status != GameStatus.Completed) Text(tr("Game {game} of {games}", "game" to shown.game + 1, "games" to g.totalGames), style = Heartpact.type.labelSmall, color = colors.ink3)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                (0 until g.totalGames).forEach { i -> GamePip(g.results.getOrNull(i), i < g.results.size, i + 1, me, partnerName) }
            }
        }
    }
    // A finished match has its result card above: here, just the games as they went, then the final board.
    if (s.status == GameStatus.Completed) {
        Box(Modifier.fillMaxWidth().padding(bottom = 14.dp), contentAlignment = Alignment.Center) { games() }
    } else {
        val turn = when {
            gameOver && !cleared -> Turn.None
            myTurn -> Turn.Mine
            s.status == GameStatus.InProgress -> Turn.Theirs
            else -> Turn.None
        }
        Scoreboard(g.scores[me] ?: 0, partnerId?.let { g.scores[it] } ?: 0, partnerName, turn, { trn(it, "{count} win", "{count} wins") }, middle = games)
    }

    val last = g.results.lastOrNull()
    val title = when {
        s.status == GameStatus.Completed -> null
        gameOver && !cleared -> when (last) {
            me -> tr("You won game {game}!", "game" to g.game + 1)
            null -> tr("Game {game} is a draw", "game" to g.game + 1)
            else -> tr("{partner} won game {game}", "partner" to partnerName, "game" to g.game + 1)
        }
        myTurn -> yourMove
        else -> tr("{partner}'s turn", "partner" to partnerName)
    }
    // Read out when it changes: the partner's move makes it your turn, a game ends.
    if (title != null) Text(title, style = Heartpact.type.title, color = colors.ink, modifier = Modifier.padding(bottom = 12.dp).semantics { liveRegion = LiveRegionMode.Polite })

    board(g, shown)

    // The result moment: who starts the next game, which comes by itself in a moment.
    if (gameOver && !cleared) {
        Text(
            if (myTurn) tr("Game {game} starts in a moment. You go first.", "game" to g.game + 2)
            else tr("Game {game} starts in a moment. {partner} goes first.", "game" to g.game + 2, "partner" to partnerName),
            style = Heartpact.type.bodyLarge, color = colors.ink2,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp), textAlign = TextAlign.Center,
        )
    }
}

/** One game of the match: the winner's half, a dashed heart for a draw, a dot for a game still to come. */
@Composable
internal fun GamePip(winner: String?, played: Boolean, number: Int, me: String, partnerName: String) {
    val colors = Heartpact.colors
    val label = when {
        !played -> null
        winner == null -> tr("Game {game}: a draw", "game" to number)
        winner == me -> tr("Game {game}: you won", "game" to number)
        else -> tr("Game {game}: {partner} won", "game" to number, "partner" to partnerName)
    }
    val modifier = label?.let { Modifier.semantics { contentDescription = it } } ?: Modifier
    Box(modifier.size(16.dp), contentAlignment = Alignment.Center) {
        when {
            !played -> Box(Modifier.size(6.dp).clip(Shapes.Pill).background(colors.ink4))
            winner == null -> HeartSticker(HalfState.Slot, HalfState.Slot, 15.dp)
            else -> HalfHeartPiece(sideOf(winner, me), 18.dp)
        }
    }
}

/** A partner's piece: their half of the heart as a sticker, centred on its own. */
@Composable
internal fun HalfHeartPiece(side: Side, size: Dp, modifier: Modifier = Modifier) {
    // The half sits off the sticker's centre line; this shift puts its middle in the middle.
    val shift = size * 0.22f
    if (side == Side.Left) HeartSticker(HalfState.Stuck, HalfState.Empty, size, modifier.absoluteOffset(x = shift))
    else HeartSticker(HalfState.Empty, HalfState.Stuck, size, modifier.absoluteOffset(x = -shift))
}

/** A connect four disc in a partner's colour, with a white keyline and their half of the heart pressed into it. */
@Composable
internal fun HeartDisc(side: Side, modifier: Modifier = Modifier) {
    val fill = if (side == Side.Left) Art.Left else Art.Right
    val half = remember(side) { if (side == Side.Left) HeartGeometry.left() else HeartGeometry.right() }
    Canvas(modifier) {
        val r = size.minDimension / 2
        drawCircle(Art.Shadow, r * 0.97f, center + Offset(0f, r * 0.09f), alpha = 0.16f)
        drawCircle(Art.Keyline, r)
        drawCircle(fill, r * 0.86f)
        // Fit the half (about 51 by 94 units) into the middle half of the disc.
        val midX = if (side == Side.Left) 24.5f else 75.5f
        inUnits(midX - 95f, 53f - 95f, 190f, 190f) { drawPath(half, Art.Keyline, alpha = 0.9f) }
    }
}

/**
 * The straight runs in a winning [line] (cells row by row on a grid [columns] wide), each as its two end cells. One
 * mark can make two lines at once (a row and a column through it): the server sends their cells together, and each
 * is struck through on its own, never one stroke from the smallest cell to the largest.
 */
internal fun winRuns(line: Set<Int>, columns: Int, rows: Int, shortest: Int = 3): List<Pair<Int, Int>> {
    val directions = listOf(1 to 0, 0 to 1, 1 to 1, -1 to 1)
    fun cell(column: Int, row: Int) = if (column in 0 until columns && row in 0 until rows) row * columns + column else -1
    return directions.flatMap { (dc, dr) ->
        line.sorted().mapNotNull { start ->
            val (c, r) = start % columns to start / columns
            if (cell(c - dc, r - dr) in line) return@mapNotNull null
            var length = 1
            while (cell(c + dc * length, r + dr * length) in line) length++
            if (length >= shortest) start to cell(c + dc * (length - 1), r + dr * (length - 1)) else null
        }
    }
}

/**
 * The pen struck through a winning line (two, when one mark made two), over a grid of [columns] by [rows] cells [gap]
 * apart that fills this canvas. It's drawn on once, after the winning pieces have lifted.
 */
@Composable
internal fun WinLine(line: Set<Int>, columns: Int, rows: Int, gap: Dp, modifier: Modifier = Modifier) {
    val runs = remember(line, columns, rows) { winRuns(line, columns, rows) }
    if (runs.isEmpty()) return
    val still = motionOff()
    val haptics = rememberPlayHaptics()
    val pen = remember(line) { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(line) {
        if (still) return@LaunchedEffect
        delay(350)
        pen.animateTo(1f, tween(520, easing = FastOutSlowInEasing))
        haptics.thud()
    }
    Canvas(modifier) {
        val g = gap.toPx()
        val cellW = (size.width - g * (columns - 1)) / columns
        val cellH = (size.height - g * (rows - 1)) / rows
        fun centre(index: Int) = Offset((index % columns) * (cellW + g) + cellW / 2, (index / columns) * (cellH + g) + cellH / 2)
        runs.forEach { (from, to) -> penLine(centre(from), centre(to), pen.value) }
    }
}

/** A winning piece's gentle, endless pulse (0 to 1); nothing when it isn't winning or motion is off. */
@Composable
internal fun winPulse(winning: Boolean): Float {
    if (!winning || motionOff()) return 0f
    val loop = rememberInfiniteTransition(label = "win")
    val p by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    return p
}
