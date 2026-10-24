package app.heartpact.ui.games

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.heartpact.data.GameSession
import app.heartpact.i18n.tr
import app.heartpact.ui.components.StickerCard
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.Shapes
import app.heartpact.ui.theme.partner
import kotlin.math.PI
import kotlin.math.sin

/** Tic-tac-toe: each partner places their half of the heart; three in a row wins the game. */
@Composable
fun TicTacToeBoard(s: GameSession, me: String, partnerName: String, busy: Boolean, actions: GameActions) {
    GridMatchFrame(s, me, partnerName, busy, tr("Your turn: pick a square")) { _, shown ->
        StickerCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (0 until 3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (0 until 3).forEach { column ->
                            val index = row * 3 + column
                            Square(index, shown.cells.getOrNull(index), index in shown.line, shown.line.isNotEmpty(), shown.canMove, me, partnerName,
                                Modifier.weight(1f)) { actions.onAct(s, "place", null, index) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Square(index: Int, owner: String?, winning: Boolean, decided: Boolean, canMove: Boolean, me: String, partnerName: String, modifier: Modifier, onPlace: () -> Unit) {
    val colors = Heartpact.colors
    // A new piece pops in; a cleared board empties at once. Pieces already down when the board opens don't move.
    val placed by animateFloatAsState(if (owner != null) 1f else 0f, if (owner != null) gentle(spring(0.5f, Spring.StiffnessMediumLow)) else snap(), label = "place")
    // The winning line lifts once and its squares take the winner's colour; the rest step back.
    val lifted by animateFloatAsState(if (winning) 1f else 0f, gentle(tween(700)), label = "line")
    val dimmed by animateFloatAsState(if (decided && !winning) 1f else 0f, gentle(tween(500)), label = "dim")
    val side = owner?.let { sideOf(it, me) }
    val tile = if (side != null) lerp(colors.sticker2, colors.partner(side).soft, lifted) else colors.sticker2
    val label = when (owner) {
        null -> tr("Square {number}, empty", "number" to index + 1)
        me -> tr("Square {number}, yours", "number" to index + 1)
        else -> tr("Square {number}, {partner}'s", "number" to index + 1, "partner" to partnerName)
    }
    Box(
        modifier.aspectRatio(1f).clip(Shapes.Tile).background(tile)
            .clickable(enabled = canMove && owner == null, onClick = onPlace)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (side != null) {
            val pop = 1f + 0.12f * sin(PI.toFloat() * lifted)
            Box(Modifier.size(72.dp).graphicsLayer {
                scaleX = placed * pop
                scaleY = placed * pop
                alpha = 1f - 0.55f * dimmed
            }, contentAlignment = Alignment.Center) { HalfHeartPiece(side, 72.dp) }
        }
    }
}
