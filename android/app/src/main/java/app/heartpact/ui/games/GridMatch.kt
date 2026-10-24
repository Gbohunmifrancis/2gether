package app.heartpact.ui.games

import android.provider.Settings
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.heartpact.data.GameSession
import app.heartpact.data.GameStatus
import app.heartpact.data.GridMatchState
import app.heartpact.i18n.tr
import app.heartpact.i18n.trn
import app.heartpact.ui.components.HeartButton
import app.heartpact.ui.graphics.HalfState
import app.heartpact.ui.graphics.HeartGeometry
import app.heartpact.ui.graphics.HeartSticker
import app.heartpact.ui.graphics.inUnits
import app.heartpact.ui.theme.Art
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.LocalActor
import app.heartpact.ui.theme.Shapes
import app.heartpact.ui.theme.Side
import app.heartpact.ui.theme.partner

/*
 * Shared by tic-tac-toe and connect four: a short match on a grid, each partner playing their own half of the heart
 * (the left partner indigo, the right periwinkle). After a game the finished board stays up with its winning line;
 * the next game's starter taps "Start game" to clear it on their phone, and their first move starts it for both.
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

/** With animations turned off in the system settings, boards change without moving. */
@Composable
internal fun motionOff(): Boolean {
    val context = LocalContext.current
    return remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

/** [spec], or an instant change when motion is off. */
@Composable
internal fun <T> gentle(spec: AnimationSpec<T>): AnimationSpec<T> = if (motionOff()) snap() else spec

/** The match around a board: who's winning, which game this is, whose turn, then the board itself. */
@Composable
internal fun GridMatchFrame(s: GameSession, me: String, partnerName: String, busy: Boolean, yourMove: String, board: @Composable (GridMatchState, GridShown) -> Unit) {
    val colors = Heartpact.colors
    val g = GamePlay.grid(s)
    val myTurn = s.currentTurnUserId == me && s.status == GameStatus.InProgress
    val gameOver = g.phase == "gameOver" && s.status == GameStatus.InProgress
    var clearedGame by remember(s.id) { mutableIntStateOf(-1) }
    val cleared = gameOver && myTurn && clearedGame == g.game
    val shown = GridShown(
        cells = if (cleared) List(g.cells.size) { null } else g.cells,
        line = if (cleared) emptySet() else g.line.orEmpty().toSet(),
        game = if (cleared) g.game + 1 else g.game,
        canMove = myTurn && !busy && (!gameOver || cleared),
    )
    val partnerId = g.players.firstOrNull { it != me }

    Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        PlayerScore(tr("You"), g.scores[me] ?: 0, LocalActor.current, myTurn && !gameOver || cleared, Modifier.weight(1f), end = false)
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(tr("Game {game} of {games}", "game" to shown.game + 1, "games" to g.totalGames), style = Heartpact.type.labelSmall, color = colors.ink3)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                (0 until g.totalGames).forEach { i -> GamePip(g.results.getOrNull(i), i < g.results.size, i + 1, me, partnerName) }
            }
        }
        PlayerScore(partnerName, partnerId?.let { g.scores[it] } ?: 0, if (LocalActor.current == Side.Left) Side.Right else Side.Left,
            s.status == GameStatus.InProgress && !myTurn && !gameOver, Modifier.weight(1f), end = true)
    }

    val last = g.results.lastOrNull()
    val title = when {
        s.status == GameStatus.Completed -> GamePlay.outcome(s, me, partnerName)
        gameOver && !cleared -> when (last) {
            me -> tr("You won game {game}!", "game" to g.game + 1)
            null -> tr("Game {game} is a draw", "game" to g.game + 1)
            else -> tr("{partner} won game {game}", "partner" to partnerName, "game" to g.game + 1)
        }
        myTurn -> yourMove
        else -> tr("{partner}'s turn", "partner" to partnerName)
    }
    Text(title, style = Heartpact.type.title, color = colors.ink, modifier = Modifier.padding(bottom = 12.dp))

    board(g, shown)

    if (gameOver && !cleared) {
        if (myTurn) HeartButton(tr("Start game {game}", "game" to g.game + 2), { clearedGame = g.game }, Modifier.padding(top = 16.dp))
        else Text(tr("{partner} starts game {game}.", "partner" to partnerName, "game" to g.game + 2), style = Heartpact.type.bodyLarge, color = colors.ink2,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp), textAlign = TextAlign.Center)
    }
}

@Composable
private fun PlayerScore(name: String, wins: Int, side: Side, turn: Boolean, modifier: Modifier, end: Boolean) {
    val colors = Heartpact.colors
    val partner = colors.partner(side)
    Box(modifier, contentAlignment = if (end) Alignment.CenterEnd else Alignment.CenterStart) {
        Row(
            Modifier.clip(Shapes.Pill).background(if (turn) partner.soft else colors.page).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!end) HalfHeartPiece(side, 26.dp)
            Column(horizontalAlignment = if (end) Alignment.End else Alignment.Start) {
                Text(name, style = Heartpact.type.label, color = if (turn) partner.onSoft else colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(trn(wins, "{count} win", "{count} wins"), style = Heartpact.type.small, color = colors.ink2, maxLines = 1)
            }
            if (end) HalfHeartPiece(side, 26.dp)
        }
    }
}

/** One game of the match: the winner's half, a dashed heart for a draw, a dot for a game still to come. */
@Composable
private fun GamePip(winner: String?, played: Boolean, number: Int, me: String, partnerName: String) {
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
    if (side == Side.Left) HeartSticker(HalfState.Stuck, HalfState.Empty, size, modifier.offset(x = shift))
    else HeartSticker(HalfState.Empty, HalfState.Stuck, size, modifier.offset(x = -shift))
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
