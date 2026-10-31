package app.heartpact.ui.games

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.heartpact.data.GameSession
import app.heartpact.data.ServerClock
import app.heartpact.i18n.tr
import app.heartpact.ui.components.StickerCard
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.Shapes
import app.heartpact.ui.theme.partner
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import java.time.Instant

/**
 * Connect four: tap a column and your disc drops to the lowest free hole; four in a row wins the game. After each game
 * the result stays up for the server's beat, then the next board comes by itself ([now]: the server's clock).
 */
@Composable
fun ConnectFourBoard(s: GameSession, me: String, partnerName: String, busy: Boolean, actions: GameActions, now: () -> Instant = { ServerClock.now() }) {
    GridMatchFrame(s, me, partnerName, busy, tr("Your turn: tap a column"), now) { g, shown ->
        val columns = g.columns
        val rows = g.rows
        // The newest disc gets a ring, so a partner's move that landed while you looked away is easy to find.
        val latest = g.lastMove?.takeIf { shown.line.isEmpty() && shown.game == g.game && shown.cells.getOrNull(it) != null }
        // Column 1 is on the left in every language, as on the partner's phone and under the win line's pen.
        StickerCard(Modifier.fillMaxWidth(), shape = Shapes.Well) { LeftToRight {
            Box {
                Row(Modifier.padding(8.dp)) {
                    (0 until columns).forEach { column ->
                        val cells = (0 until rows).map { row -> row * columns + column }
                        val landing = cells.lastOrNull { shown.cells.getOrNull(it) == null }
                        val label = if (landing == null) tr("Column {number}, full", "number" to column + 1) else tr("Column {number}", "number" to column + 1)
                        Column(
                            Modifier.weight(1f)
                                .clickable(enabled = shown.canMove && landing != null, role = Role.Button) { actions.onAct(s, "drop", null, column) }
                                .semantics { contentDescription = label },
                        ) {
                            cells.forEachIndexed { row, index ->
                                Hole(row, shown.cells.getOrNull(index), index in shown.line, shown.line.isNotEmpty(), shown.canMove && index == landing, me, partnerName, latest = index == latest)
                            }
                        }
                    }
                }
                WinLine(shown.line, columns, rows, 0.dp, Modifier.matchParentSize().padding(8.dp))
            }
        } }
    }
}

@Composable
private fun Hole(row: Int, owner: String?, winning: Boolean, decided: Boolean, landing: Boolean, me: String, partnerName: String, latest: Boolean) {
    val colors = Heartpact.colors
    // A new disc falls in from above the board and settles with a small bounce; a cleared board empties at once.
    // It falls under gravity (slow, then fast), lands with a squash and wobbles flat: the deeper the hole, the longer the fall.
    val still = motionOff()
    val dropped = remember { Animatable(if (owner != null) 1f else 0f) }
    val land = remember { Animatable(0f) }
    LaunchedEffect(owner) {
        when {
            owner == null -> dropped.snapTo(0f)
            still -> dropped.snapTo(1f)
            dropped.value < 1f -> {
                dropped.animateTo(1f, tween((150 * sqrt(row + 1f)).toInt() + 80, easing = { t -> t * t }))
                land.animateTo(0f, spring(dampingRatio = 0.3f, stiffness = 650f), initialVelocity = 9f)
            }
        }
    }
    val pulse = winPulse(winning)
    val lifted by animateFloatAsState(if (winning) 1f else 0f, gentle(tween(700)), label = "line")
    val dimmed by animateFloatAsState(if (decided && !winning) 1f else 0f, gentle(tween(500)), label = "dim")
    val side = owner?.let { sideOf(it, me) }
    val hole = if (side != null) lerp(colors.page2, colors.partner(side).soft, lifted) else colors.page2
    val slot = Heartpact.actor.action
    val ring = side?.let { colors.partner(it).action }
    // Read out with the column (it merges its holes): the discs from the top down.
    val label = when {
        owner == null -> null
        owner == me -> tr("Your disc")
        else -> tr("{partner}'s disc", "partner" to partnerName)
    }
    Box(Modifier.fillMaxWidth().aspectRatio(1f).padding(3.dp).then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(hole)
            if (latest && ring != null && dropped.value >= 1f) drawCircle(ring, size.minDimension / 2 - 0.5.dp.toPx(), style = Stroke(2.5.dp.toPx()))
            if (landing) drawCircle(slot.copy(alpha = 0.55f), size.minDimension / 2 - 3.dp.toPx(), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 6f))))
        }
        if (side != null) {
            val pop = (1f + 0.1f * sin(PI.toFloat() * lifted)) * (1f + 0.08f * pulse)
            HeartDisc(side, Modifier.fillMaxSize().padding(2.dp).graphicsLayer {
                translationY = -(row + 1) * size.height * 1.1f * (1f - dropped.value)
                val squash = 0.07f * land.value
                scaleX = pop * (1f + squash)
                scaleY = pop * (1f - squash)
                transformOrigin = TransformOrigin(0.5f, 0.9f)
                alpha = 1f - 0.55f * dimmed
            })
        }
    }
}
