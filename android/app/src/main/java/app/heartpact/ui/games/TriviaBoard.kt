package app.heartpact.ui.games

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.heartpact.data.GameSession
import app.heartpact.data.GameStatus
import app.heartpact.data.TriviaReveal
import app.heartpact.i18n.tr
import app.heartpact.i18n.trn
import app.heartpact.ui.components.NoteCard
import app.heartpact.ui.components.StickerCard
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.Shapes

/**
 * Trivia: both answer the same question at once. The partner's pick and the right answer stay hidden until both are
 * in; then the last question's result shows above the next one.
 */
@Composable
internal fun TriviaBoard(s: GameSession, me: String, partnerName: String, busy: Boolean, actions: GameActions) {
    val colors = Heartpact.colors
    val t = GamePlay.trivia(s)
    val finished = s.status == GameStatus.Completed || t.completed
    if (!finished) {
        Scoreboard(t.scores, me, partnerName, waitingOn(t.players, me, t.answered)) { trn(it, "{count} point", "{count} points") }
        Text(tr("Question {round} of {rounds}", "round" to minOf(t.round + 1, t.totalRounds), "rounds" to t.totalRounds), style = Heartpact.type.small, color = colors.ink2)
    }
    t.reveal?.let { RevealCard(it, me, partnerName) }

    val question = t.question
    if (finished) return
    if (question == null) {
        Text(GamePlay.outcome(s, me, partnerName), style = Heartpact.type.headline, color = colors.ink, modifier = Modifier.padding(top = 20.dp))
        return
    }

    // Each question is dealt in fresh.
    DealIn(t.round) {
        Column(Modifier.fillMaxWidth()) {
            Text(question.text, style = Heartpact.type.headline.copy(fontSize = 24.sp, lineHeight = 29.sp), color = colors.ink, modifier = Modifier.padding(top = 16.dp))
            val mine = t.myAnswer
            val partnerIn = t.answered.any { it != me }
            when {
                mine != null -> Text(tr("Locked in. Waiting for {partner}…", "partner" to partnerName), style = Heartpact.type.body, color = colors.ink2, modifier = Modifier.padding(top = 10.dp))
                partnerIn -> NoteCard(tr("{partner} has answered. Your pick?", "partner" to partnerName), Modifier.padding(top = 12.dp))
                else -> Text(tr("Pick one. {partner} is answering too.", "partner" to partnerName), style = Heartpact.type.body, color = colors.ink2, modifier = Modifier.padding(top = 10.dp))
            }
            val canPick = mine == null && s.status == GameStatus.InProgress && !busy
            Column(Modifier.padding(top = 4.dp)) {
                question.options.forEachIndexed { index, option ->
                    Option(index, option, picked = mine == index, faded = mine != null && mine != index, enabled = canPick) { actions.onAct(s, "answer", null, index) }
                }
            }
        }
    }
}

private val letters = listOf("A", "B", "C", "D")

@Composable
private fun Option(index: Int, text: String, picked: Boolean, faded: Boolean, enabled: Boolean, onPick: () -> Unit) {
    val colors = Heartpact.colors
    val actor = Heartpact.actor
    val pick = pickedFlip(picked)
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp).alpha(if (faded) 0.5f else 1f)
            .graphicsLayer { scaleX = pick.bump; scaleY = pick.bump }
            .clip(Shapes.Field)
            .background(if (picked) actor.soft else colors.sticker)
            .border(2.dp, if (picked) actor.action else colors.rule, Shapes.Field)
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onPick)
            .semantics { selected = picked }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(30.dp).graphicsLayer { rotationY = pick.turn }.clip(CircleShape).background(if (picked) actor.action else colors.sticker2), contentAlignment = Alignment.Center) {
            Text(letters.getOrElse(index) { "${index + 1}" }, style = Heartpact.type.label, color = if (picked) colors.onAction else colors.ink2)
        }
        Text(text, style = Heartpact.type.bodyLarge, color = if (picked) actor.onSoft else colors.ink, modifier = Modifier.padding(start = 12.dp).weight(1f))
    }
}

@Composable
private fun RevealCard(reveal: TriviaReveal, me: String, partnerName: String) {
    val colors = Heartpact.colors
    val actor = Heartpact.actor
    val options = reveal.question.options
    val myPick = reveal.picks[me]
    val theirPick = reveal.picks.entries.firstOrNull { it.key != me }?.value
    val iWasRight = myPick == reveal.answer
    val theyWereRight = theirPick == reveal.answer
    val stamp = when {
        iWasRight && theyWereRight -> tr("Both right!")
        iWasRight -> tr("Right!")
        else -> null
    }
    RoundReveal(reveal.round, stamp, hearts = iWasRight, Modifier.padding(top = 12.dp)) { StickerCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(when {
                iWasRight && theyWereRight -> tr("You both got it right")
                iWasRight -> tr("You got it right")
                theyWereRight -> tr("{partner} got it right", "partner" to partnerName)
                else -> tr("Neither of you got it")
            }, style = Heartpact.type.label, color = colors.ink)
            Text(reveal.question.text, style = Heartpact.type.small, color = colors.ink2)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Check, null, Modifier.size(20.dp), tint = actor.action)
                Text(options.getOrElse(reveal.answer) { "" }, style = Heartpact.type.titleSmall, color = colors.ink, modifier = Modifier.padding(start = 6.dp))
            }
            Pick(tr("You picked {answer}", "answer" to options.getOrElse(myPick ?: -1) { "—" }), iWasRight)
            Pick(tr("{partner} picked {answer}", "partner" to partnerName, "answer" to options.getOrElse(theirPick ?: -1) { "—" }), theyWereRight)
        }
    } }
}

@Composable
private fun Pick(text: String, right: Boolean) {
    val colors = Heartpact.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(if (right) Icons.Rounded.Check else Icons.Rounded.Close, if (right) tr("Right") else tr("Wrong"), Modifier.size(16.dp), tint = if (right) Heartpact.actor.action else colors.ink3)
        Text(text, style = Heartpact.type.small, color = if (right) colors.ink else colors.ink3, modifier = Modifier.padding(start = 6.dp))
    }
}
