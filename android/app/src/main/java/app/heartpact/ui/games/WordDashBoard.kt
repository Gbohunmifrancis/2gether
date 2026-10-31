package app.heartpact.ui.games

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.heartpact.data.GameSession
import app.heartpact.data.GameStatus
import app.heartpact.data.GameType
import app.heartpact.data.WordDashState
import app.heartpact.i18n.tr
import app.heartpact.i18n.trn
import app.heartpact.ui.play.popSpring
import app.heartpact.ui.components.HeartButton
import app.heartpact.ui.components.HeartField
import app.heartpact.ui.components.NoteCard
import app.heartpact.ui.components.StickerCard
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.LocalActor
import app.heartpact.ui.theme.Shapes
import app.heartpact.ui.theme.Side
import app.heartpact.ui.theme.partner
import java.time.Instant

/** Word dash: the chain so far, whose turn it is, the letter to start with and the seconds left. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WordDashBoard(s: GameSession, me: String, partnerName: String, busy: Boolean, actions: GameActions, now: () -> Instant) {
    val colors = Heartpact.colors
    val w = GamePlay.wordDash(s)
    val left = secondsUntil(s.deadlineUtc, now)
    val playing = s.status == GameStatus.InProgress && w.phase != "completed"
    val myTurn = playing && s.currentTurnUserId == me
    // Keep a refused word in the field so it can be fixed; clear it once the chain moves on.
    // Kept through a rotation or a trip to another app, too.
    var word by rememberSaveable(s.id, w.chainNumber, w.chain.size) { mutableStateOf("") }
    if (playing) {
        Scoreboard(w.scores, me, partnerName, turnOf(s, me)) { trn(it, "{count} pt", "{count} pts") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            LetterBlob(w.letter.orEmpty(), GameType.WordDash.blob, key = w.chainNumber to w.chain.size)
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(tr("Starts with"), style = Heartpact.type.label, color = colors.ink2)
                Text(tr("Chain {chain} · first to {target}", "chain" to w.chainNumber, "target" to w.target), style = Heartpact.type.small, color = colors.ink2, modifier = Modifier.padding(top = 2.dp))
            }
            TimerBadge(left, w.turnSeconds)
        }
    }
    lastNote(w, me, partnerName)?.let { note ->
        RoundReveal(w.chainNumber, null, hearts = w.timedOut != me, Modifier.padding(top = 12.dp)) {
            Column {
                NoteCard(note)
                // The chain that just ended, so both can see how far it got.
                w.lastChain?.takeIf { it.isNotEmpty() }?.let { words ->
                    Text(trn(words.size, "The last chain, {count} word: {words}", "The last chain, {count} words: {words}", "words" to words.joinToString(" → ")),
                        style = Heartpact.type.small, color = colors.ink2, modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp))
                }
            }
        }
    }
    if (w.chain.isNotEmpty()) Chain(w, me)
    when {
        !playing -> Unit
        myTurn -> {
            Text(if (w.chain.isEmpty()) tr("Your turn: start the chain.") else tr("Your turn: keep the chain going."),
                style = Heartpact.type.title, color = colors.ink, modifier = Modifier.padding(top = 18.dp))
            val letter = w.letter.orEmpty()
            val ready = word.length >= 2 && left > 0 && !busy
            val send = { if (ready) actions.onAct(s, "play", word.trim(), null) }
            HeartField(tr("A word starting with {letter}", "letter" to letter), word, { word = it.filter { c -> !c.isWhitespace() }.take(30) }, Modifier.padding(top = 10.dp),
                // English words, A to Z: ask the keyboard for its Latin layout whatever the phone's language.
                keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Send),
                actions = KeyboardActions(onSend = { send() }))
            HeartButton(tr("Play it"), send, Modifier.padding(top = 12.dp), enabled = word.length >= 2 && left > 0, busy = busy)
            if (w.chainNumber == 1 && w.chain.isEmpty()) Text(tr("Any English word. The next one starts with its last letter."), style = Heartpact.type.small, color = colors.ink2, modifier = Modifier.padding(top = 8.dp))
        }
        else -> Text(tr("{partner} is thinking of a word…", "partner" to partnerName), style = Heartpact.type.bodyLarge, color = colors.ink2, modifier = Modifier.padding(top = 18.dp))
    }
}

private fun lastNote(w: WordDashState, me: String, partnerName: String): String? {
    // The server clears lastResult with the next word, and a finished game shows its outcome instead.
    if (w.lastResult != "timeout" || w.phase == "completed") return null
    return if (w.timedOut == me) tr("Your time ran out, so {partner} gets a point. New chain: you start.", "partner" to partnerName)
    else tr("{partner}'s time ran out: a point for you. New chain: {partner} starts.", "partner" to partnerName)
}

/** The words so far, oldest first, each in its player's colour. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chain(w: WordDashState, me: String) {
    val colors = Heartpact.colors
    val mine = Heartpact.actor
    val theirs = colors.partner(if (LocalActor.current == Side.Left) Side.Right else Side.Left)
    val still = motionOff()
    StickerCard(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(trn(w.chain.size, "The chain so far · {count} word", "The chain so far · {count} words"), style = Heartpact.type.label, color = colors.ink, modifier = Modifier.padding(bottom = 10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                w.chain.forEachIndexed { i, link ->
                    val side = if (link.player == me) mine else theirs
                    val latest = i == w.chain.lastIndex
                    // The newest word pops onto the end of the chain.
                    val pop = remember(i, link.word) { Animatable(if (latest && !still) 0f else 1f) }
                    LaunchedEffect(i, link.word) { pop.animateTo(1f, popSpring()) }
                    Text(
                        link.word,
                        style = if (latest) Heartpact.type.titleSmall else Heartpact.type.body,
                        color = if (latest) colors.onAction else side.onSoft,
                        modifier = Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value; rotationZ = (1f - pop.value) * 12f }
                            .clip(Shapes.Pill).background(if (latest) side.action else side.soft).padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}
