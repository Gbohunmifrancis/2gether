package app.heartpact.ui.games

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.QuestionAnswer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.heartpact.data.GameSession
import app.heartpact.data.GameStatus
import app.heartpact.data.TruthOrDareOutcome
import app.heartpact.i18n.tr
import app.heartpact.i18n.trn
import app.heartpact.ui.components.ButtonKind
import app.heartpact.ui.components.HeartButton
import app.heartpact.ui.components.NoteCard
import app.heartpact.ui.components.StickerCard
import app.heartpact.ui.play.Reaction
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.Shapes

/** Truth or dare: the chooser picks, the prompt shows to both, the partner marks it done or skipped. */
@Composable
internal fun TruthOrDareBoard(s: GameSession, me: String, partnerName: String, busy: Boolean, actions: GameActions) {
    val colors = Heartpact.colors
    val t = GamePlay.truthOrDare(s)
    val finished = s.status == GameStatus.Completed || t.phase == "completed"
    if (!finished) {
        Scoreboard(t.scores, me, partnerName, turnOf(s, me)) { trn(it, "{count} heart", "{count} hearts") }
        Text(tr("Turn {turn} of {turns}", "turn" to minOf(t.turn + 1, t.totalTurns), "turns" to t.totalTurns), style = Heartpact.type.small, color = colors.ink2)
    }
    t.last?.let { last ->
        val done = last.result == "done"
        RoundReveal(t.turn, if (done) tr("Done!") else null, hearts = done, Modifier.padding(top = 10.dp)) { NoteCard(lastNote(last, me, partnerName)) }
    }
    when {
        finished -> Unit
        t.phase == "choosing" && t.chooser == me -> {
            Text(tr("Truth or dare?"), style = Heartpact.type.headline, color = colors.ink, modifier = Modifier.padding(top = 18.dp))
            Text(tr("Pick one. {partner} sees your prompt and says if you did it.", "partner" to partnerName), style = Heartpact.type.body, color = colors.ink2, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DealIn(t.turn to "truth", Modifier.weight(1f)) {
                    Pick(tr("Truth"), tr("Answer honestly"), Icons.Rounded.QuestionAnswer, enabled = !busy, Modifier.fillMaxWidth()) { actions.onAct(s, "choose", "truth", null) }
                }
                DealIn(t.turn to "dare", Modifier.weight(1f), delayMs = 90) {
                    Pick(tr("Dare"), tr("Do it, send proof"), Icons.Rounded.Bolt, enabled = !busy, Modifier.fillMaxWidth()) { actions.onAct(s, "choose", "dare", null) }
                }
            }
        }
        t.phase == "choosing" -> Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Couple(110.dp, Reaction.Waiting, Reaction.Thinking)
            Text(tr("{partner} is choosing truth or dare…", "partner" to partnerName), style = Heartpact.type.title, color = colors.ink, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp))
        }
        else -> {
            val mine = t.chooser == me
            val truth = t.choice == "truth"
            // The prompt is a card drawn and turned face up.
            FlipIn(t.turn to t.choice) { PromptCard(
                when {
                    mine && truth -> tr("Your truth")
                    mine -> tr("Your dare")
                    truth -> tr("{partner}'s truth", "partner" to partnerName)
                    else -> tr("{partner}'s dare", "partner" to partnerName)
                },
                t.prompt.orEmpty(), if (truth) Icons.Rounded.QuestionAnswer else Icons.Rounded.Bolt,
            ) }
            if (mine) {
                Text(if (truth) tr("Answer by message, call or voice note. {partner} marks it once you have.", "partner" to partnerName)
                    else tr("Do it and send {partner} the proof. They mark it done.", "partner" to partnerName),
                    style = Heartpact.type.bodyLarge, color = colors.ink2, modifier = Modifier.padding(top = 14.dp))
            } else {
                Text(if (truth) tr("Did {partner} answer? Mark it once they have.", "partner" to partnerName)
                    else tr("Did {partner} do it? Mark it once you've seen the proof.", "partner" to partnerName),
                    style = Heartpact.type.bodyLarge, color = colors.ink2, modifier = Modifier.padding(top = 14.dp))
                HeartButton(tr("Done"), { actions.onAct(s, "judge", "done", null) }, Modifier.padding(top = 14.dp), busy = busy)
                HeartButton(tr("Skipped"), { actions.onAct(s, "judge", "skipped", null) }, Modifier.padding(top = 6.dp), kind = ButtonKind.Outline, enabled = !busy)
            }
        }
    }
}

private fun lastNote(last: TruthOrDareOutcome, me: String, partnerName: String) = when {
    last.chooser == me && last.result == "done" -> tr("Done! A heart for you.")
    last.chooser == me -> tr("Skipped. No heart this time.")
    last.result == "done" -> tr("{partner} did it. A heart for them.", "partner" to partnerName)
    else -> tr("{partner} skipped that one.", "partner" to partnerName)
}

@Composable
private fun Pick(title: String, hint: String, icon: ImageVector, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = Heartpact.colors
    val actor = Heartpact.actor
    StickerCard(modifier) {
        Column(
            Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(vertical = 22.dp, horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(56.dp).clip(Shapes.Pill).background(actor.soft), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(28.dp), tint = actor.onSoft)
            }
            Text(title, style = Heartpact.type.headline, color = colors.ink, modifier = Modifier.padding(top = 12.dp))
            Text(hint, style = Heartpact.type.small, color = colors.ink2, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun PromptCard(label: String, prompt: String, icon: ImageVector) {
    val colors = Heartpact.colors
    val actor = Heartpact.actor
    StickerCard(Modifier.fillMaxWidth().padding(top = 16.dp), shape = Shapes.Dare) {
        Column(Modifier.padding(22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(30.dp).clip(Shapes.Pill).background(actor.soft), contentAlignment = Alignment.Center) {
                    Icon(icon, null, Modifier.size(17.dp), tint = actor.onSoft)
                }
                Text(label, style = Heartpact.type.label, color = colors.ink2)
            }
            Text(prompt, style = Heartpact.type.headline.copy(fontSize = 25.sp, lineHeight = 30.sp), color = colors.ink, modifier = Modifier.padding(top = 14.dp))
            Box(Modifier.height(4.dp))
        }
    }
}
