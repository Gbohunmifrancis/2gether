package app.heartpact.ui.games

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.heartpact.data.GameSession
import app.heartpact.data.GameStatus
import app.heartpact.data.GameType
import app.heartpact.i18n.tr
import app.heartpact.ui.components.StickerCard
import app.heartpact.ui.play.PlayInk
import app.heartpact.ui.play.unitHeart
import app.heartpact.ui.theme.Heartpact

/*
 * What Same answer, Guess my answer, Would you rather and Trivia share: a move you both make at once that crossed
 * your partner's, and the round-by-round recap under a finished game.
 */

/**
 * After a `409` for a move both partners make at once: the move still stands on [after] (the same round, and I
 * haven't moved in it yet), so it can go again. The partner's move landing first is no reason to lose mine.
 */
internal fun GamePlay.resendable(before: GameSession, after: GameSession): Boolean {
    if (after.id != before.id || after.status != GameStatus.InProgress) return false
    return when (before.gameType) {
        GameType.WouldYouRather -> wouldYouRather(after).let { it.round == wouldYouRather(before).round && it.myPick == null }
        GameType.Trivia -> trivia(after).let { !it.completed && it.round == trivia(before).round && it.myAnswer == null }
        GameType.CoupleQuiz -> quiz(after).let { it.round == quiz(before).round && it.mine == null }
        else -> false
    }
}

/** One round in a finished game's recap: what was asked, what each of you said, and whether it was a hit. */
internal data class RecapRow(val question: String, val lines: List<String>, val hit: Boolean)

/**
 * The finished game, round by round: each question with both of your answers under it, a heart beside the ones you
 * matched (or got right). [title] heads the card. Empty for games finished before the server kept the rounds.
 */
@Composable
internal fun RecapCard(rows: List<RecapRow>, modifier: Modifier = Modifier, title: String = tr("Round by round")) {
    if (rows.isEmpty()) return
    val colors = Heartpact.colors
    StickerCard(modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(title, style = Heartpact.type.titleSmall, color = colors.ink, modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 6.dp).semantics { heading() })
        rows.forEachIndexed { i, row ->
            val hitLabel = if (row.hit) tr("A hit") else tr("A miss")
            var m = Modifier.fillMaxWidth()
            if (i > 0) m = m.drawBehind {
                drawLine(colors.rule, Offset(0f, 0f), Offset(size.width, 0f), 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())))
            }
            Row(
                m.semantics(mergeDescendants = true) { contentDescription = listOf("${i + 1}. ${row.question}", *row.lines.toTypedArray(), hitLabel).joinToString(". ") }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RecapMark(i + 1, row.hit)
                Column(Modifier.weight(1f)) {
                    Text(row.question, style = Heartpact.type.label, color = colors.ink)
                    row.lines.forEach { Text(it, style = Heartpact.type.small, color = colors.ink2, modifier = Modifier.padding(top = 2.dp)) }
                }
            }
        }
        Box(Modifier.padding(bottom = 8.dp))
    }
}

/** Ink on the pen-pink heart, by day and by night (white on pink is too faint to read). */
private val OnPen = androidx.compose.ui.graphics.Color(0xFF161A3D)

/** The round's number in a heart: filled in pen for a hit, an outline for a miss, so it reads without colour. */
@Composable
private fun RecapMark(number: Int, hit: Boolean) {
    val colors = Heartpact.colors
    val outline = colors.rule2
    Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(30.dp)) {
            translate(center.x, center.y + 1.dp.toPx()) {
                val heart = unitHeart(size.minDimension * 0.98f)
                if (hit) drawPath(heart, PlayInk.Pen) else drawPath(heart, outline, style = Stroke(2.dp.toPx()))
            }
        }
        Text("$number", style = Heartpact.type.labelSmall, color = if (hit) OnPen else colors.ink2, modifier = Modifier.padding(bottom = 3.dp))
    }
}
