package app.heartpact.ui.games

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.heartpact.data.GameSession
import app.heartpact.data.GameStatus
import app.heartpact.i18n.tr
import app.heartpact.ui.components.HeartButton
import app.heartpact.ui.components.HeartField
import app.heartpact.ui.components.NoteCard
import app.heartpact.ui.theme.Heartpact

/** Same answer (`coupleQuiz` on the wire): one question, both answer; a heart each when the answers match. */
@Composable
internal fun QuizBoard(s: GameSession, me: String, partnerName: String, busy: Boolean, actions: GameActions) {
    val colors = Heartpact.colors
    val q = GamePlay.quiz(s)
    // The result card above says how it went.
    if (s.status == GameStatus.Completed) return
    var answer by remember(q.round) { mutableStateOf("") }
    val myTurn = s.currentTurnUserId == me && s.status == GameStatus.InProgress
    TogetherScore(q.scores[me] ?: 0, tr("Question {round} of {rounds}", "round" to minOf(q.round + 1, q.totalRounds), "rounds" to q.totalRounds), turnOf(s, me))
    when (q.lastResult) {
        "match" -> RoundReveal(q.round, tr("Same answer!"), hearts = true, Modifier.padding(top = 10.dp)) {
            NoteCard(tr("You both said the same thing. A heart for each of you."))
        }
        "different" -> RoundReveal(q.round, null, hearts = false, Modifier.padding(top = 10.dp)) { NoteCard(tr("Different answers last time. Next one!")) }
    }
    DealIn(q.round) {
        Column(Modifier.fillMaxWidth()) {
            Text(q.prompt, style = Heartpact.type.headline.copy(fontSize = 26.sp, lineHeight = 30.sp), color = colors.ink, modifier = Modifier.padding(top = 16.dp))
            if (myTurn) {
                Text(if (q.answerCount == 1) tr("{partner} has answered. Your turn: see if you match.", "partner" to partnerName) else tr("You go first. {partner} answers after you.", "partner" to partnerName), style = Heartpact.type.body, color = colors.ink2, modifier = Modifier.padding(top = 10.dp))
                HeartField(tr("Your answer"), answer, { answer = it.take(200) }, Modifier.padding(top = 10.dp), keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
                HeartButton(tr("Send"), { actions.onAct(s, "answer", answer.trim(), null) }, Modifier.padding(top = 12.dp), enabled = answer.isNotBlank(), busy = busy)
            } else {
                Text(tr("Waiting for {partner}'s answer…", "partner" to partnerName), style = Heartpact.type.bodyLarge, color = colors.ink2, modifier = Modifier.padding(top = 18.dp))
            }
        }
    }
}
