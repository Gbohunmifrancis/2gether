package app.heartpact.ui.games

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.HeartBroken
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.heartpact.data.GameSession
import app.heartpact.data.GameStatus
import app.heartpact.data.GuessMyAnswerState
import app.heartpact.data.GuessReveal
import app.heartpact.i18n.tr
import app.heartpact.i18n.trn
import app.heartpact.ui.components.StickerCard
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.Shapes
import kotlinx.serialization.json.Json

private val guessJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }

fun GamePlay.guessMyAnswer(s: GameSession): GuessMyAnswerState =
    runCatching { guessJson.decodeFromString(GuessMyAnswerState.serializer(), s.stateJson) }.getOrDefault(GuessMyAnswerState())

/** Guess my answer: one of you answers about yourself from four choices, the other guesses. Swap each round. */
@Composable
internal fun GuessMyAnswerBoard(s: GameSession, me: String, partnerName: String, busy: Boolean, actions: GameActions) {
    val colors = Heartpact.colors
    val g = GamePlay.guessMyAnswer(s)
    val finished = s.status == GameStatus.Completed
    if (!finished) {
        Scoreboard(g.scores, me, partnerName, turnOf(s, me)) { trn(it, "{count} point", "{count} points") }
        Text(tr("Round {round} of {rounds}", "round" to minOf(g.round + 1, g.totalRounds), "rounds" to g.totalRounds), style = Heartpact.type.small, color = colors.ink2)
    }
    g.last?.let { GuessRevealCard(it, me, partnerName, Modifier.padding(top = 12.dp)) }

    val question = g.question
    if (finished) return
    if (question == null) {
        Text(GamePlay.outcome(s, me, partnerName), style = Heartpact.type.headline, color = colors.ink, modifier = Modifier.padding(top = 20.dp))
        return
    }
    val aboutMe = g.subject == me
    val myMove = s.status == GameStatus.InProgress && s.currentTurnUserId == me
    val canAnswer = myMove && aboutMe && !g.answered
    val canGuess = myMove && !aboutMe && g.answered
    DealIn(g.round) {
        Column(Modifier.fillMaxWidth()) {
            Text(if (aboutMe) tr("About you") else tr("About {partner}", "partner" to partnerName), style = Heartpact.type.label, color = Heartpact.actor.onSoft, modifier = Modifier.padding(top = 20.dp))
            Text(question, style = Heartpact.type.headline.copy(fontSize = 26.sp, lineHeight = 30.sp), color = colors.ink, modifier = Modifier.padding(top = 6.dp))
            Text(
                when {
                    aboutMe && !g.answered -> tr("Pick your honest answer. {partner} will try to guess it.", "partner" to partnerName)
                    aboutMe -> tr("Locked in. Waiting for {partner} to guess…", "partner" to partnerName)
                    !g.answered -> tr("{partner} is picking an answer. Your guess comes next.", "partner" to partnerName)
                    else -> tr("{partner} has answered. What did they pick?", "partner" to partnerName)
                },
                style = Heartpact.type.body, color = colors.ink2, modifier = Modifier.padding(top = 8.dp),
            )
            Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                g.choices.forEachIndexed { i, choice ->
                    GuessChoice("ABCD".getOrElse(i) { '?' }.toString(), choice, selected = g.answer == i, enabled = (canAnswer || canGuess) && !busy) {
                        actions.onAct(s, if (canAnswer) "answer" else "guess", null, i)
                    }
                }
            }
        }
    }
}

/** One of the four answers. The subject's own pick stays highlighted while their partner guesses. */
@Composable
private fun GuessChoice(letter: String, text: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = Heartpact.colors
    val actor = Heartpact.actor
    val shape = Shapes.Field
    val pick = pickedFlip(selected)
    var m = Modifier.fillMaxWidth().heightIn(min = 58.dp).graphicsLayer { scaleX = pick.bump; scaleY = pick.bump }
    if (enabled) m = m.shadow(4.dp, shape, ambientColor = colors.left.art.copy(alpha = 0.4f), spotColor = colors.left.art.copy(alpha = 0.45f))
    m = m.clip(shape).background(if (selected) actor.soft else colors.sticker)
    if (!selected) m = m.border(2.dp, colors.rule, shape)
    m = m.alpha(if (enabled || selected) 1f else 0.7f).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    Row(m.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(34.dp).graphicsLayer { rotationY = pick.turn }.clip(CircleShape).background(if (selected) actor.action else colors.sticker2), contentAlignment = Alignment.Center) {
            Text(letter, style = Heartpact.type.label, color = if (selected) colors.onAction else colors.ink2)
        }
        Text(text, style = Heartpact.type.bodyLarge, color = if (selected) actor.onSoft else colors.ink, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Rounded.Check, null, Modifier.size(20.dp), tint = actor.onSoft)
    }
}

/** The last round, once the guess is in: what the subject picked, what the guesser said, and who scored. */
@Composable
private fun GuessRevealCard(last: GuessReveal, me: String, partnerName: String, modifier: Modifier = Modifier) {
    val colors = Heartpact.colors
    val answer = last.choices.getOrElse(last.answer) { "" }
    val guess = last.choices.getOrElse(last.guess) { "" }
    val iGuessed = last.guesser == me
    val line = when {
        iGuessed && last.right -> tr("You guessed it: {answer}. A point for you.", "answer" to answer)
        iGuessed -> tr("{partner} picked {answer}. You guessed {guess}.", "partner" to partnerName, "answer" to answer, "guess" to guess)
        last.right -> tr("{partner} guessed it: {answer}. A point for {partner}.", "partner" to partnerName, "answer" to answer)
        else -> tr("You picked {answer}. {partner} guessed {guess}.", "answer" to answer, "partner" to partnerName, "guess" to guess)
    }
    // A right guess is stamped and bursts hearts, whoever made it: one of you knew the other.
    RoundReveal(last.round, if (last.right) tr("Got it!") else null, hearts = last.right, modifier) {
        StickerCard(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(if (last.right) Heartpact.actor.soft else colors.sticker2), contentAlignment = Alignment.Center) {
                    Icon(if (last.right) Icons.Rounded.Favorite else Icons.Rounded.HeartBroken, null, Modifier.size(22.dp), tint = if (last.right) Heartpact.actor.onSoft else colors.ink3)
                }
                Column(Modifier.weight(1f)) {
                    Text(tr("Last round: {question}", "question" to last.question), style = Heartpact.type.small, color = colors.ink2)
                    Text(line, style = Heartpact.type.label, color = colors.ink, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}
