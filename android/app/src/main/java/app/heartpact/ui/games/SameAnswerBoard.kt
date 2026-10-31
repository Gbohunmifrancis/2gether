package app.heartpact.ui.games

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.heartpact.data.GameSession
import app.heartpact.data.GameStatus
import app.heartpact.data.QuizReveal
import app.heartpact.i18n.tr
import app.heartpact.ui.components.ButtonKind
import app.heartpact.ui.components.HeartButton
import app.heartpact.ui.components.HeartField
import app.heartpact.ui.components.NoteCard
import app.heartpact.ui.components.StickerCard
import app.heartpact.ui.graphics.Who
import app.heartpact.ui.play.Reaction
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.LocalActor
import app.heartpact.ui.theme.Shapes
import app.heartpact.ui.theme.Side
import app.heartpact.ui.theme.partner

/** The most an answer can be: a few words is what matches (the server keeps up to this many characters). */
private const val MAX_ANSWER = 200

/** The move that counts a round that didn't match as a match ("That counts!", `index` the round). */
internal const val COUNTS = "counts"

/**
 * Same answer (`coupleQuiz` on the wire): one question about the two of you, you both answer at once, in a word or two
 * or, for a "Who…" question, by picking one of you. Once you're both in, both answers turn face up; a heart each when
 * they match. Answers that didn't quite match ("pizza", "pepperoni pizza") either of you can count anyway ("That
 * counts!"): a heart each too. A finished game shows every round under the result, still countable.
 */
@Composable
internal fun QuizBoard(s: GameSession, me: String, partnerName: String, busy: Boolean, actions: GameActions) {
    val colors = Heartpact.colors
    val q = GamePlay.quiz(s)
    if (s.status == GameStatus.Completed) {
        // The result card above has the total; under it, how each round went (the last one too).
        // A round that didn't match can still be counted from here, while the finished game is on screen.
        RecapCard(q.history.map { quizRecapRow(it, me, partnerName, busy) { round -> actions.onAct(s, COUNTS, null, round) } })
        return
    }
    val playing = s.status == GameStatus.InProgress
    TogetherScore(q.scores[me] ?: 0, tr("Question {round} of {rounds}", "round" to minOf(q.round + 1, q.totalRounds), "rounds" to q.totalRounds),
        if (playing) waitingOn(q.players, me, q.answered) else Turn.None)
    q.last?.let { last -> QuizRevealCard(last, me, partnerName, Modifier.padding(top = 10.dp), busy) { actions.onAct(s, COUNTS, null, last.round) } }
    // An answer half typed survives the app going to the background, and starts empty on each new question.
    var answer by rememberSaveable(q.round) { mutableStateOf("") }
    val partnerIn = q.answered.any { it != me }
    val who = q.kind == "who"
    DealIn(q.round) {
        Column(Modifier.fillMaxWidth()) {
            Text(q.prompt, style = Heartpact.type.headline.copy(fontSize = 26.sp, lineHeight = 30.sp), color = colors.ink, modifier = Modifier.padding(top = 16.dp))
            val mine = q.mine
            when {
                mine != null -> {
                    Locked(if (who) whoLabel(mine, me, partnerName) else mine)
                    Text(tr("Waiting for {partner}'s answer…", "partner" to partnerName), style = Heartpact.type.bodyLarge, color = colors.ink2,
                        modifier = Modifier.padding(top = 14.dp).semantics { liveRegion = LiveRegionMode.Polite })
                }
                !playing -> Unit
                who -> {
                    Hint(partnerIn, partnerName, tr("Pick one of you. {partner} picks too, then you'll both see.", "partner" to partnerName))
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max).padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        WhoChoice(tr("Me"), myWho(), LocalActor.current, enabled = !busy, Modifier.weight(1f).fillMaxHeight()) { actions.onAct(s, "answer", me, null) }
                        val partnerId = q.players.firstOrNull { it != me }
                        WhoChoice(partnerName, theirWho(), theirSide(), enabled = !busy && partnerId != null, Modifier.weight(1f).fillMaxHeight()) {
                            partnerId?.let { actions.onAct(s, "answer", it, null) }
                        }
                    }
                }
                else -> {
                    Hint(partnerIn, partnerName, tr("A word or two matches best. {partner} answers too, then you'll both see.", "partner" to partnerName))
                    val send = { if (answer.isNotBlank() && !busy) actions.onAct(s, "answer", answer.trim(), null) }
                    HeartField(tr("Your answer"), answer, { answer = it.take(MAX_ANSWER) }, Modifier.padding(top = 10.dp),
                        keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                        actions = KeyboardActions(onSend = { send() }))
                    HeartButton(tr("Send"), send, Modifier.padding(top = 12.dp), enabled = answer.isNotBlank(), busy = busy)
                }
            }
        }
    }
}

/** Under the question: your partner is already in (said out loud by TalkBack), or how to play it. */
@Composable
private fun Hint(partnerIn: Boolean, partnerName: String, howTo: String) {
    if (partnerIn) NoteCard(tr("{partner} has answered. Your turn: see if you match.", "partner" to partnerName), Modifier.padding(top = 12.dp))
    else Text(howTo, style = Heartpact.type.body, color = Heartpact.colors.ink2, modifier = Modifier.padding(top = 10.dp))
}

/** My answer, sent and waiting for my partner's: a ticked sticker of it. */
@Composable
private fun Locked(text: String) {
    val actor = Heartpact.actor
    Row(
        Modifier.padding(top = 14.dp).clip(Shapes.Field).background(actor.soft).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(actor.action), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = Heartpact.colors.onAction)
        }
        Column {
            Text(tr("Your answer"), style = Heartpact.type.small, color = actor.onSoft)
            Text(text, style = Heartpact.type.titleSmall.copy(textDirection = TextDirection.Content), color = actor.onSoft, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** One of you two, for a "Who…" question: that half of the heart, waving, and the name under it. */
@Composable
private fun WhoChoice(name: String, who: Who, side: Side, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = Heartpact.colors
    val tone = colors.partner(side)
    val shape = Shapes.Card
    Column(
        modifier.heightIn(min = 150.dp)
            .shadow(6.dp, shape, ambientColor = colors.left.art.copy(alpha = 0.5f), spotColor = colors.left.art.copy(alpha = 0.55f))
            .clip(shape).background(colors.sticker).border(2.dp, tone.soft, shape)
            .alpha(if (enabled) 1f else 0.7f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        PlayCharacter(who, Reaction.Idle, 76.dp)
        Text(name, style = Heartpact.type.title, color = colors.ink, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp))
    }
}

/** A "who" answer (a partner's id) as the phone holder reads it: "Me" or the partner's name, as on the buttons. */
@Composable
private fun whoLabel(id: String, me: String, partnerName: String) = if (id == me) tr("Me") else partnerName

/**
 * The last question, both answers face up: stamped and bursting hearts when they match. When they don't, either of you
 * can say "That counts!" ([onCount]); once counted it's stamped and the hearts burst on both phones.
 */
@Composable
private fun QuizRevealCard(last: QuizReveal, me: String, partnerName: String, modifier: Modifier = Modifier, busy: Boolean = false, onCount: () -> Unit = {}) {
    val colors = Heartpact.colors
    val mine = last.answers[me]
    val theirs = last.answers.entries.firstOrNull { it.key != me }?.value
    val stamp = when {
        last.accepted -> tr("Counted!")
        last.match -> tr("Same answer!")
        else -> null
    }
    RoundReveal(last.round, stamp, hearts = last.match, modifier) {
        StickerCard(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
            // A match is stamped across the top corner: the question starts below it.
            Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp, top = if (last.match) 34.dp else 14.dp)) {
                Text(tr("Last question: {question}", "question" to last.prompt), style = Heartpact.type.small, color = colors.ink2)
                if (last.kind == "who") {
                    Text(whoLine(last, me, partnerName), style = Heartpact.type.titleSmall, color = colors.ink, modifier = Modifier.padding(top = 8.dp))
                } else {
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max).padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Said(tr("You"), mine, LocalActor.current, last.match, Modifier.weight(1f).fillMaxHeight())
                        Said(partnerName, theirs, theirSide(), last.match, Modifier.weight(1f).fillMaxHeight())
                    }
                }
                val line = when {
                    last.accepted -> countedLine(last, me, partnerName)
                    last.match -> tr("A heart each.")
                    last.countable -> tr("Not quite. Close enough? Either of you can count it.")
                    else -> tr("Not quite. Next one!")
                }
                Text(line, style = Heartpact.type.small, color = colors.ink2, modifier = Modifier.padding(top = 8.dp))
                if (last.countable) CountsButton(tr("That counts! Count these answers as the same, for a heart each"), Modifier.padding(top = 10.dp), busy = busy, onClick = onCount)
            }
        }
    }
}

/** Who counted it, from the phone holder's side. */
@Composable
private fun countedLine(played: QuizReveal, me: String, partnerName: String) = when (played.acceptedBy) {
    me -> tr("Close enough! You counted it: a heart each.")
    null -> tr("Close enough! Counted.")
    else -> tr("Close enough! {partner} counted it: a heart each.", "partner" to partnerName)
}

/** "That counts!": a small tonal pill with a heart, read out by TalkBack as [description]. */
@Composable
internal fun CountsButton(description: String, modifier: Modifier = Modifier, busy: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    HeartButton(tr("That counts!"), onClick, modifier.semantics { contentDescription = description }, kind = ButtonKind.Tonal, icon = Icons.Rounded.FavoriteBorder,
        enabled = enabled, busy = busy, height = 48.dp, fillWidth = false)
}

/** One partner's answer in the reveal, in their own colours when the two match. */
@Composable
private fun Said(name: String, answer: String?, side: Side, match: Boolean, modifier: Modifier) {
    val colors = Heartpact.colors
    val tone = colors.partner(side)
    Column(modifier.clip(Shapes.Field).background(if (match) tone.soft else colors.sticker2).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(name, style = Heartpact.type.small, color = if (match) tone.onSoft else colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        // An answer reads in its own direction ("curry!" stays "curry!" on an Arabic screen).
        Text(answer ?: "—", style = Heartpact.type.titleSmall.copy(textDirection = TextDirection.Content), color = if (match) tone.onSoft else colors.ink, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
    }
}

/** A "who" round in one sentence, from the phone holder's side: whom each of you picked. */
@Composable
private fun whoLine(last: QuizReveal, me: String, partnerName: String): String {
    val mine = last.answers[me]
    val theirs = last.answers.entries.firstOrNull { it.key != me }?.value
    return when {
        mine == null || theirs == null -> tr("Not quite. Next one!")
        last.matched && mine == me -> tr("You both picked you!")
        last.matched -> tr("You both picked {partner}!", "partner" to partnerName)
        mine == me -> tr("You each picked yourself.")
        else -> tr("You each picked the other.")
    }
}

/**
 * A Same answer round in the recap: the question, and what each of you said. A round that didn't match can still be
 * counted ([onCount], the round); one that was counted says so.
 */
@Composable
private fun quizRecapRow(played: QuizReveal, me: String, partnerName: String, busy: Boolean = false, onCount: (Int) -> Unit = {}): RecapRow {
    val mine = played.answers[me]
    val theirs = played.answers.entries.firstOrNull { it.key != me }?.value
    val said = if (played.kind == "who") listOf(whoLine(played, me, partnerName))
    else listOf(tr("You: {answer}", "answer" to (mine ?: "—")), tr("{partner}: {answer}", "partner" to partnerName, "answer" to (theirs ?: "—")))
    val lines = if (played.accepted) said + tr("Close enough! Counted.") else said
    val action = if (!played.countable) null else {
        val description = tr("That counts! Count question {number} as the same, for a heart each", "number" to played.round + 1)
        RecapAction { CountsButton(description, Modifier.padding(top = 8.dp), enabled = !busy) { onCount(played.round) } }
    }
    return RecapRow(played.prompt, lines, played.match, action)
}
