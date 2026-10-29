package app.heartpact.ui.games

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.heartpact.data.GameSession
import app.heartpact.data.GameStatus
import app.heartpact.data.GameType
import app.heartpact.data.VetVerdict
import app.heartpact.i18n.tr
import app.heartpact.i18n.trn
import app.heartpact.ui.components.ButtonKind
import app.heartpact.ui.components.HeartButton
import app.heartpact.ui.components.HeartField
import app.heartpact.ui.components.NoteCard
import app.heartpact.ui.components.StickerCard
import app.heartpact.ui.theme.Heartpact
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant

/** The seconds a round of I call on runs for (docs/games.md); the ring runs down from here. */
private const val ROUND_SECONDS = 15

/** I call on: a letter pops up, both fill the categories against the clock, then each judges the other's answers. */
@Composable
internal fun ICallOnBoard(s: GameSession, me: String, partnerName: String, busy: Boolean, actions: GameActions, now: () -> Instant) {
    val colors = Heartpact.colors
    val c = GamePlay.iCallOn(s)
    val left = secondsUntil(s.deadlineUtc, now)
    val answers = remember(c.round, c.letter) { mutableStateMapOf<String, String>() }
    val playing = s.status == GameStatus.InProgress
    if (playing) {
        val turn = when (c.phase) {
            "vetting" -> waitingOn(c.players, me, c.pendingVetting.filter { it.allowed == null }.map { it.judge }.let { judges -> c.players.filter { it !in judges } })
            else -> waitingOn(c.players, me, c.submitted)
        }
        Scoreboard(c.scores, me, partnerName, turn) { trn(it, "{count} pt", "{count} pts") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            LetterBlob(c.letter, GameType.ICallOn.blob, key = c.round to c.letter)
            Text(tr("Round {round} of {rounds}", "round" to minOf(c.round + 1, c.totalRounds), "rounds" to c.totalRounds), style = Heartpact.type.label, color = colors.ink2,
                modifier = Modifier.weight(1f).padding(start = 14.dp))
            TimerBadge(left, maxOf(ROUND_SECONDS.toLong(), left).toInt())
        }
    }
    c.reveal?.takeIf { it.entries.isNotEmpty() }?.let { reveal ->
        val myPoints = reveal.entries.filter { it.player == me && it.counted }.sumOf { it.points }
        RoundReveal(reveal.letter to c.round, null, hearts = myPoints > 0, Modifier.padding(top = 12.dp)) {
            StickerCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(tr("Last round, {letter}", "letter" to reveal.letter), style = Heartpact.type.label, color = colors.ink)
                    reveal.entries.forEach { e ->
                        Text("${if (e.player == me) tr("You") else partnerName} · ${GamePlay.categoryLabel(e.category)}: ${e.answer.ifBlank { "—" }} · ${if (e.counted) "+${e.points}" else "0"}",
                            style = Heartpact.type.small, color = if (e.counted) colors.ink else colors.ink3)
                    }
                }
            }
        }
    }
    when {
        s.status == GameStatus.Completed -> Unit
        c.phase == "vetting" -> {
            val mineToJudge = c.pendingVetting.filter { it.judge == me && it.allowed == null }
            Text(if (mineToJudge.isEmpty()) tr("Waiting for {partner} to judge your answers…", "partner" to partnerName) else tr("Does it count?"), style = Heartpact.type.title, color = colors.ink, modifier = Modifier.padding(top = 16.dp))
            mineToJudge.forEach { item ->
                DealIn(item.player to item.category, Modifier.padding(top = 10.dp)) {
                    StickerCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text(GamePlay.categoryVet(item.category, partnerName, item.answer), style = Heartpact.type.body, color = colors.ink)
                            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val verdict = { allow: Boolean -> Json.encodeToString(VetVerdict.serializer(), VetVerdict(item.player, item.category, allow)) }
                                HeartButton(tr("It counts"), { actions.onAct(s, "vet", verdict(true), null) }, kind = ButtonKind.Tonal, fillWidth = false, height = 44.dp, enabled = !busy)
                                HeartButton(tr("Doesn't"), { actions.onAct(s, "vet", verdict(false), null) }, kind = ButtonKind.Text, fillWidth = false, height = 44.dp, enabled = !busy)
                            }
                        }
                    }
                }
            }
        }
        me in c.submitted -> Text(tr("Sent. Waiting for {partner}…", "partner" to partnerName), style = Heartpact.type.bodyLarge, color = colors.ink2, modifier = Modifier.padding(top = 18.dp))
        else -> {
            if (c.submitted.isNotEmpty()) NoteCard(tr("{partner} is done. Be quick!", "partner" to partnerName), Modifier.padding(top = 12.dp))
            c.categories.forEach { category ->
                HeartField(GamePlay.categoryField(category, c.letter), answers[category].orEmpty(), { answers[category] = it.take(40) }, Modifier.padding(top = 10.dp),
                    keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Words))
            }
            val complete = c.categories.all { !answers[it].isNullOrBlank() }
            HeartButton(tr("Send my answers"), {
                val value = buildJsonObject { c.categories.forEach { put(it, JsonPrimitive(answers[it].orEmpty().trim())) } }.toString()
                actions.onAct(s, "submit", value, null)
            }, Modifier.padding(top = 12.dp), enabled = complete, busy = busy)
        }
    }
}
