package app.heartpact.ui.games

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.heartpact.data.GameSession
import app.heartpact.data.GameStatus
import app.heartpact.data.GameType
import app.heartpact.data.ICallOnState
import app.heartpact.data.RoundReveal
import app.heartpact.data.VetVerdict
import app.heartpact.i18n.tr
import app.heartpact.i18n.trn
import app.heartpact.ui.components.ButtonKind
import app.heartpact.ui.components.HeartButton
import app.heartpact.ui.components.HeartField
import app.heartpact.ui.components.NoteCard
import app.heartpact.ui.components.StickerCard
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.Shapes
import app.heartpact.ui.theme.partner
import kotlinx.coroutines.delay
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant

/** The seconds a round of I call on runs for (docs/games.md); the ring runs down from here. */
private const val ROUND_SECONDS = 15

/** The longest answer the server keeps. */
private const val MAX_ANSWER = 40

/**
 * I call on: a letter pops up, both fill the categories against the clock, then each judges the other's answers.
 * What's typed is reported as it's typed ([GameActions.onDraft]), so it still counts if the clock runs out before
 * it's sent; [partner] is how far the partner has got.
 */
@Composable
internal fun ICallOnBoard(s: GameSession, me: String, partnerName: String, busy: Boolean, actions: GameActions, now: () -> Instant, partner: PartnerProgress? = null) {
    val colors = Heartpact.colors
    val c = GamePlay.iCallOn(s)
    val left = secondsUntil(s.deadlineUtc, now)
    // Kept across a rotation or a trip to another app: the answers are one JSON object, category to answer.
    var sheet by rememberSaveable(s.id, c.round, c.letter) { mutableStateOf("{}") }
    val answers = ICallOnSheet.read(sheet)
    val playing = s.status == GameStatus.InProgress
    val typing = playing && c.phase == "answering" && me !in c.submitted
    // What's typed goes to the server a moment after the typing pauses: it counts if the clock runs out first.
    val draft by rememberUpdatedState(actions.onDraft)
    LaunchedEffect(sheet, typing) {
        if (!typing || answers.values.all { it.isBlank() }) return@LaunchedEffect
        delay(350)
        draft(s, c.round, ICallOnSheet.encode(c.categories, answers))
    }
    if (playing) {
        val turn = when (c.phase) {
            "vetting" -> waitingOn(c.players, me, c.pendingVetting.filter { it.allowed == null }.map { it.judge }.let { judges -> c.players.filter { it !in judges } })
            else -> waitingOn(c.players, me, c.submitted)
        }
        Scoreboard(c.scores, me, partnerName, turn) { trn(it, "{count} pt", "{count} pts") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            LetterBlob(c.letter, GameType.ICallOn.blob, key = c.round to c.letter)
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(tr("Round {round} of {rounds}", "round" to minOf(c.round + 1, c.totalRounds), "rounds" to c.totalRounds), style = Heartpact.type.label, color = colors.ink2)
                // The scoring, said once where it's needed: on the first round.
                if (c.round == 0 && c.phase == "answering") Text(tr("2 pts if only you said it, 1 each if you both did."), style = Heartpact.type.small, color = colors.ink2, modifier = Modifier.padding(top = 2.dp))
            }
            TimerBadge(left, maxOf(ROUND_SECONDS.toLong(), left).toInt())
        }
    }
    c.reveal?.takeIf { it.entries.isNotEmpty() }?.let { reveal ->
        val myPoints = reveal.entries.filter { it.player == me && it.counted }.sumOf { it.points }
        RoundReveal(reveal.letter to reveal.round, null, hearts = myPoints > 0, Modifier.padding(top = 12.dp)) {
            RevealTable(reveal, c, me, partnerName)
        }
    }
    when {
        s.status == GameStatus.Completed -> Unit
        c.phase == "vetting" -> {
            val mineToJudge = c.pendingVetting.filter { it.judge == me && it.allowed == null }
            Text(if (mineToJudge.isEmpty()) tr("Waiting for {partner} to judge your answers…", "partner" to partnerName) else tr("Does it count?"), style = Heartpact.type.title, color = colors.ink,
                modifier = Modifier.padding(top = 16.dp).semantics { liveRegion = LiveRegionMode.Polite })
            if (mineToJudge.isNotEmpty()) Text(tr("Anything you don't judge in time counts."), style = Heartpact.type.small, color = colors.ink2, modifier = Modifier.padding(top = 4.dp))
            mineToJudge.forEach { item ->
                DealIn(item.player to item.category, Modifier.padding(top = 10.dp)) {
                    StickerCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text(GamePlay.categoryVet(item.category, partnerName, item.answer), style = Heartpact.type.body, color = colors.ink)
                            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val verdict = { allow: Boolean -> Json.encodeToString(VetVerdict.serializer(), VetVerdict(item.player, item.category, allow)) }
                                HeartButton(tr("It counts"), { actions.onAct(s, "vet", verdict(true), null) }, kind = ButtonKind.Tonal, fillWidth = false, height = 48.dp, enabled = !busy)
                                HeartButton(tr("Doesn't"), { actions.onAct(s, "vet", verdict(false), null) }, kind = ButtonKind.Text, fillWidth = false, height = 48.dp, enabled = !busy)
                            }
                        }
                    }
                }
            }
        }
        me in c.submitted -> {
            Text(tr("Sent. Waiting for {partner}…", "partner" to partnerName), style = Heartpact.type.bodyLarge, color = colors.ink2, modifier = Modifier.padding(top = 18.dp))
            PartnerTyping(partner, c, partnerName)
        }
        else -> {
            if (c.submitted.isNotEmpty()) NoteCard(tr("{partner} is done. Be quick!", "partner" to partnerName), Modifier.padding(top = 12.dp))
            else PartnerTyping(partner, c, partnerName)
            val complete = c.categories.all { !answers[it].isNullOrBlank() }
            val send = {
                if (complete && !busy) actions.onAct(s, "submit", ICallOnSheet.encode(c.categories, answers), null)
            }
            c.categories.forEachIndexed { i, category ->
                val last = i == c.categories.lastIndex
                HeartField(GamePlay.categoryField(category, c.letter), answers[category].orEmpty(),
                    { sheet = ICallOnSheet.write(answers + (category to it.replace('\n', ' ').take(MAX_ANSWER))) }, Modifier.padding(top = 10.dp),
                    // Answers start with a Latin letter: ask the keyboard for its Latin layout, and Next through the four.
                    keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Words, keyboardType = KeyboardType.Ascii, imeAction = if (last) ImeAction.Send else ImeAction.Next),
                    actions = KeyboardActions(onSend = { send() }))
            }
            HeartButton(tr("Send my answers"), send, Modifier.padding(top = 12.dp), enabled = complete, busy = busy)
            if (!complete) Text(tr("Whatever you've typed when time runs out still counts."), style = Heartpact.type.small, color = colors.ink2, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** How far the partner has got with their answers this round, while they're still typing. */
@Composable
private fun PartnerTyping(partner: PartnerProgress?, c: ICallOnState, partnerName: String) {
    val p = partner?.takeIf { it.round == c.round && it.total > 0 && it.userId !in c.submitted } ?: return
    Text(trn(p.filled, "{partner} has filled {count} of {total}", "{partner} has filled {count} of {total}", "partner" to partnerName, "total" to p.total),
        style = Heartpact.type.small, color = Heartpact.colors.ink2, modifier = Modifier.padding(top = 8.dp))
}

/** A round's answers side by side: a row per category, yours then your partner's, each with what it scored. */
@Composable
private fun RevealTable(reveal: RoundReveal, c: ICallOnState, me: String, partnerName: String) {
    val colors = Heartpact.colors
    val categories = c.categories.ifEmpty { reveal.entries.map { it.category }.distinct() }
    val partnerId = c.players.firstOrNull { it != me }
    val mine = Heartpact.actor
    val theirs = colors.partner(theirSide())
    StickerCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(tr("Last round, {letter}", "letter" to reveal.letter), style = Heartpact.type.label, color = colors.ink)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp)) {
                Text("", Modifier.weight(0.8f))
                Text(tr("You"), Modifier.weight(1f), style = Heartpact.type.labelSmall, color = colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(partnerName, Modifier.weight(1f), style = Heartpact.type.labelSmall, color = colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            categories.forEach { category ->
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(GamePlay.categoryLabel(category).replaceFirstChar { it.titlecase(java.util.Locale.getDefault()) }, Modifier.weight(0.8f), style = Heartpact.type.small, color = colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    listOf(me to mine, partnerId to theirs).forEach { (player, side) ->
                        val entry = reveal.entries.firstOrNull { it.player == player && it.category == category }
                        Row(Modifier.weight(1f).padding(end = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(entry?.answer?.ifBlank { "—" } ?: "—", Modifier.weight(1f, fill = false), style = Heartpact.type.body.copy(textDecoration = if (entry?.counted == true || entry?.answer.isNullOrBlank()) null else TextDecoration.LineThrough),
                                color = if (entry?.counted == true) colors.ink else colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (entry?.counted == true) Text(signed(entry.points), style = Heartpact.type.labelSmall, color = side.onSoft,
                                modifier = Modifier.clip(Shapes.Pill).background(side.soft).padding(horizontal = 7.dp, vertical = 2.dp))
                        }
                    }
                }
            }
            val myRound = reveal.roundScores[me] ?: reveal.entries.filter { it.player == me && it.counted }.sumOf { it.points }
            val theirRound = partnerId?.let { id -> reveal.roundScores[id] ?: reveal.entries.filter { it.player == id && it.counted }.sumOf { it.points } } ?: 0
            Text(tr("This round: you {mine}, {partner} {theirs}", "mine" to signed(myRound), "partner" to partnerName, "theirs" to signed(theirRound)),
                style = Heartpact.type.label, color = colors.ink, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

/** "+2", kept in that order in a right-to-left sentence too. */
private fun signed(points: Int) = "\u2066+$points\u2069"

/** I call on's answer sheet as the board keeps it: a JSON object of category to answer. Pure, for tests. */
object ICallOnSheet {
    private val json = Json { ignoreUnknownKeys = true }
    private val sheetSerializer = MapSerializer(String.serializer(), String.serializer())

    fun read(sheet: String): Map<String, String> =
        runCatching { json.decodeFromString(sheetSerializer, sheet) }.getOrDefault(emptyMap())

    fun write(answers: Map<String, String>): String =
        json.encodeToString(sheetSerializer, answers)

    /** What goes to the server: every category, trimmed, in the game's order. */
    fun encode(categories: List<String>, answers: Map<String, String>): String =
        buildJsonObject { categories.forEach { put(it, JsonPrimitive(answers[it].orEmpty().trim())) } }.toString()
}
