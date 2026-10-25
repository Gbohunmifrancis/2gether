package app.heartpact.ui.games

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Cake
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LocalFlorist
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.heartpact.data.GameSession
import app.heartpact.data.GameStatus
import app.heartpact.data.GameType
import app.heartpact.data.VetVerdict
import app.heartpact.data.utc
import app.heartpact.i18n.tr
import app.heartpact.i18n.trn
import app.heartpact.ui.components.ButtonKind
import app.heartpact.ui.components.CardRow
import app.heartpact.ui.components.HeartButton
import app.heartpact.ui.components.HeartChip
import app.heartpact.ui.components.HeartField
import app.heartpact.ui.components.NoteCard
import app.heartpact.ui.components.StickerCard
import app.heartpact.ui.components.TopBar
import app.heartpact.ui.graphics.Character
import app.heartpact.ui.graphics.HalfState
import app.heartpact.ui.graphics.HeartSticker
import app.heartpact.ui.graphics.Pose
import app.heartpact.ui.graphics.Who
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.Shapes
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant

data class GameActions(
    val onInvite: (GameType, String?) -> Unit = { _, _ -> },
    val onAccept: (String) -> Unit = {},
    val onAbandon: (String) -> Unit = {},
    val onAct: (GameSession, String, String?, Int?) -> Unit = { _, _, _, _ -> },
    val onBack: () -> Unit = {},
    /** Leave a finished game's final board for the picker. */
    val onClose: (String) -> Unit = {},
)

/** Play together: start a game, accept one, play it, see how the last ones went. */
@Composable
fun GamesScreen(ui: GamesUi, me: String, partnerName: String, actions: GameActions, now: () -> Instant = { Instant.now() }) {
    val active = ui.active ?: ui.justFinished(now())
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        TopBar(active?.let { GamePlay.title(it) } ?: tr("Play together"), actions.onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            ui.note?.let { NoteCard(it, Modifier.padding(bottom = 12.dp)) }
            when {
                active == null -> Picker(ui, me, partnerName, actions)
                active.status == GameStatus.Waiting -> Waiting(active, me, partnerName, ui.busy, actions)
                else -> Playing(active, me, partnerName, ui.busy, actions, now, ui.pending)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Picker(ui: GamesUi, me: String, partnerName: String, actions: GameActions) {
    val colors = Heartpact.colors
    var letter by remember { mutableStateOf<String?>(null) }
    ui.finished?.let { last ->
        StickerCard(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HeartSticker(HalfState.Stuck, HalfState.Stuck, 32.dp)
                Column {
                    Text(tr("Last game: {game}", "game" to GamePlay.title(last)), style = Heartpact.type.label, color = colors.ink)
                    Text(GamePlay.outcome(last, me, partnerName), style = Heartpact.type.body, color = colors.ink2)
                }
            }
        }
    }
    Text(tr("Pick a game and {partner} gets an invite. It starts when they join.", "partner" to partnerName), style = Heartpact.type.bodyLarge, color = colors.ink2, modifier = Modifier.padding(bottom = 14.dp))
    val region = ui.region ?: ui.sessions.firstOrNull()?.let(GamePlay::region)
    if (region == "ng") AfterRoundCard()
    GamePlay.offered.forEach { type ->
        StickerCard(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text(GamePlay.title(type, region), style = Heartpact.type.title, color = colors.ink)
                Text(GamePlay.blurb(type, region), style = Heartpact.type.body, color = colors.ink2, modifier = Modifier.padding(top = 4.dp))
                if (type == GameType.ICallOn) {
                    Text(tr("First letter"), style = Heartpact.type.label, color = colors.ink, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        HeartChip(tr("Surprise me"), letter == null, { letter = null })
                        listOf("A", "B", "M", "S", "T").forEach { l -> HeartChip(l, letter == l, { letter = l }) }
                    }
                }
                HeartButton(tr("Invite {partner}", "partner" to partnerName), { actions.onInvite(type, if (type == GameType.ICallOn) letter else null) },
                    Modifier.padding(top = 12.dp), kind = ButtonKind.Tonal, enabled = !ui.busy)
            }
        }
    }
    if (ui.history.size > 1) {
        Text(tr("Recent"), style = Heartpact.type.titleSmall, color = colors.ink, modifier = Modifier.padding(top = 10.dp, bottom = 8.dp))
        StickerCard(Modifier.fillMaxWidth()) {
            ui.history.drop(1).forEachIndexed { i, s -> CardRow(GamePlay.title(s), subtitle = GamePlay.outcome(s, me, partnerName), divider = i > 0) }
        }
    }
}

@Composable
private fun Waiting(s: GameSession, me: String, partnerName: String, busy: Boolean, actions: GameActions) {
    val colors = Heartpact.colors
    val mine = s.invitedByUserId == me
    Column(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Character(Who.Both, if (mine) Pose.Stand else Pose.Wave, 130.dp)
        Text(if (mine) tr("Waiting for {partner} to join", "partner" to partnerName) else tr("{partner} invited you to play", "partner" to partnerName), style = Heartpact.type.title, color = colors.ink, modifier = Modifier.padding(top = 16.dp), textAlign = TextAlign.Center)
        Text(GamePlay.blurb(s.gameType, GamePlay.region(s)), style = Heartpact.type.body, color = colors.ink2, modifier = Modifier.padding(top = 6.dp), textAlign = TextAlign.Center)
    }
    if (mine) HeartButton(tr("Cancel the invite"), { actions.onAbandon(s.id) }, Modifier.padding(top = 24.dp), kind = ButtonKind.Text)
    else {
        HeartButton(GamePlay.play(s.gameType, GamePlay.region(s)), { actions.onAccept(s.id) }, Modifier.padding(top = 24.dp), busy = busy)
        HeartButton(tr("Not now"), { actions.onAbandon(s.id) }, Modifier.padding(top = 4.dp), kind = ButtonKind.Text)
    }
}

/** Seconds left until [iso], ticking. */
@Composable
internal fun secondsUntil(iso: String?, now: () -> Instant): Long {
    var left by remember(iso) { mutableLongStateOf(iso?.let { (utc(it).toEpochMilli() - now().toEpochMilli() + 999) / 1000 } ?: 0) }
    LaunchedEffect(iso) {
        while (iso != null) {
            left = ((utc(iso).toEpochMilli() - now().toEpochMilli() + 999) / 1000).coerceAtLeast(0)
            if (left <= 0) break
            delay(250)
        }
    }
    return left
}

@Composable
private fun Playing(s: GameSession, me: String, partnerName: String, busy: Boolean, actions: GameActions, now: () -> Instant, pending: List<PendingMove> = emptyList()) {
    val colors = Heartpact.colors
    val countdown = secondsUntil(s.countdownEndsAtUtc, now)
    if (s.status == GameStatus.InProgress && countdown > 0) {
        Column(Modifier.fillMaxWidth().padding(top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$countdown", style = Heartpact.type.display.copy(fontSize = 120.sp, lineHeight = 110.sp), color = colors.ink)
            Text(tr("Get ready"), style = Heartpact.type.title, color = colors.ink2)
        }
        return
    }
    when (s.gameType) {
        GameType.MemoryMatch -> MemoryBoard(s, me, partnerName, busy, actions)
        GameType.ICallOn -> ICallOnBoard(s, me, partnerName, busy, actions, now)
        GameType.TruthOrDare -> TruthOrDareBoard(s, me, partnerName, busy, actions)
        GameType.WordDash -> WordDashBoard(s, me, partnerName, busy, actions, now)
        GameType.GuessMyAnswer -> GuessMyAnswerBoard(s, me, partnerName, busy, actions)
        GameType.WouldYouRather -> WouldYouRatherBoard(s, me, partnerName, busy, actions)
        GameType.TicTacToe -> TicTacToeBoard(s, me, partnerName, busy, actions)
        GameType.ConnectFour -> ConnectFourBoard(s, me, partnerName, busy, actions)
        GameType.Handwriting -> DrawAndGuessBoard(s, me, partnerName, pending, actions)
        GameType.Trivia -> TriviaBoard(s, me, partnerName, busy, actions)
        else -> QuizBoard(s, me, partnerName, busy, actions)
    }
    if (s.status == GameStatus.InProgress) HeartButton(tr("End this game"), { actions.onAbandon(s.id) }, Modifier.padding(top = 18.dp), kind = ButtonKind.Text)
    if (s.status == GameStatus.Completed) HeartButton(tr("Back to games"), { actions.onClose(s.id) }, Modifier.padding(top = 18.dp), kind = ButtonKind.Tonal)
}

@Composable
internal fun Scores(scores: Map<String, Int>, me: String, partnerName: String, count: (Int) -> String) {
    val colors = Heartpact.colors
    Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(tr("You {score}", "score" to count(scores[me] ?: 0)), style = Heartpact.type.titleSmall, color = colors.ink)
        Text("$partnerName ${count(scores.entries.firstOrNull { it.key != me }?.value ?: 0)}", style = Heartpact.type.titleSmall, color = colors.ink)
    }
}

@Composable
private fun QuizBoard(s: GameSession, me: String, partnerName: String, busy: Boolean, actions: GameActions) {
    val colors = Heartpact.colors
    val q = GamePlay.quiz(s)
    var answer by remember(q.round) { mutableStateOf("") }
    val myTurn = s.currentTurnUserId == me && s.status == GameStatus.InProgress
    Text(trn(q.scores[me] ?: 0, "Question {round} of {rounds} · {count} heart so far", "Question {round} of {rounds} · {count} hearts so far",
        "round" to minOf(q.round + 1, q.totalRounds), "rounds" to q.totalRounds), style = Heartpact.type.small, color = colors.ink2)
    when (q.lastResult) {
        "match" -> NoteCard(tr("You both said the same thing. A heart for each of you."), Modifier.padding(top = 10.dp))
        "different" -> NoteCard(tr("Different answers last time. Next one!"), Modifier.padding(top = 10.dp))
    }
    if (s.status == GameStatus.Completed) {
        Text(tr("You matched {matched} of {rounds}", "matched" to (q.scores[me] ?: 0), "rounds" to q.totalRounds), style = Heartpact.type.headline, color = colors.ink, modifier = Modifier.padding(top = 20.dp))
        return
    }
    Text(q.prompt, style = Heartpact.type.headline.copy(fontSize = 26.sp, lineHeight = 30.sp), color = colors.ink, modifier = Modifier.padding(top = 16.dp))
    if (myTurn) {
        Text(if (q.answerCount == 1) tr("{partner} has answered. Your turn: see if you match.", "partner" to partnerName) else tr("You go first. {partner} answers after you.", "partner" to partnerName), style = Heartpact.type.body, color = colors.ink2, modifier = Modifier.padding(top = 10.dp))
        HeartField(tr("Your answer"), answer, { answer = it.take(200) }, Modifier.padding(top = 10.dp), keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
        HeartButton(tr("Send"), { actions.onAct(s, "answer", answer.trim(), null) }, Modifier.padding(top = 12.dp), enabled = answer.isNotBlank(), busy = busy)
    } else {
        Text(tr("Waiting for {partner}'s answer…", "partner" to partnerName), style = Heartpact.type.bodyLarge, color = colors.ink2, modifier = Modifier.padding(top = 18.dp))
    }
}

@Composable
private fun MemoryBoard(s: GameSession, me: String, partnerName: String, busy: Boolean, actions: GameActions) {
    val colors = Heartpact.colors
    val m = GamePlay.memory(s)
    val myTurn = s.currentTurnUserId == me && s.status == GameStatus.InProgress
    Scores(m.scores, me, partnerName) { trn(it, "{count} pair", "{count} pairs") }
    Text(when {
        s.status == GameStatus.Completed -> GamePlay.outcome(s, me, partnerName)
        myTurn && m.revealed.size == 1 -> tr("Now find its pair")
        myTurn -> if (m.lastResult == "match") tr("A pair! Go again") else tr("Your turn: flip a card")
        else -> if (m.lastResult == "different") tr("No pair. {partner}'s turn", "partner" to partnerName) else tr("{partner}'s turn", "partner" to partnerName)
    }, style = Heartpact.type.title, color = colors.ink, modifier = Modifier.padding(bottom = 12.dp))
    val shown = m.lastPair.orEmpty().associate { it.index to it.value }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        m.cards.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { card ->
                    val value = card.value ?: shown[card.index]
                    val faded = card.value == null && value != null
                    val alpha by animateFloatAsState(if (card.matched) 0.55f else if (faded) 0.75f else 1f, label = "card")
                    Box(
                        Modifier.weight(1f).aspectRatio(0.8f).clip(Shapes.Tile)
                            .background(if (value != null) colors.sticker else Heartpact.actor.action)
                            .border(2.dp, colors.rule, Shapes.Tile)
                            .alpha(alpha)
                            .clickable(enabled = myTurn && !busy && value == null || myTurn && !busy && faded) { actions.onAct(s, "flip", null, card.index) }
                            .semantics { contentDescription = value?.let { GamePlay.cardLabel(it, m.theme) } ?: tr("Face-down card {number}", "number" to card.index + 1) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (value != null) Icon(cardIcon(value, m.theme), null, Modifier.size(34.dp), tint = Heartpact.actor.action)
                        else HeartSticker(HalfState.Stuck, HalfState.Stuck, 26.dp, Modifier.alpha(0.35f))
                    }
                }
            }
        }
    }
}

private fun cardIcon(value: Int, theme: String?) = if (theme == "naija") NaijaStickers.card(value) else when (value) {
    1 -> Icons.Rounded.Favorite
    2 -> Icons.Rounded.Star
    3 -> Icons.Rounded.LocalFlorist
    4 -> Icons.Rounded.WbSunny
    5 -> Icons.Rounded.Bedtime
    else -> Icons.Rounded.Cake
}

@Composable
private fun ICallOnBoard(s: GameSession, me: String, partnerName: String, busy: Boolean, actions: GameActions, now: () -> Instant) {
    val colors = Heartpact.colors
    val c = GamePlay.iCallOn(s)
    val left = secondsUntil(s.deadlineUtc, now)
    val answers = remember(c.round, c.letter) { mutableStateMapOf<String, String>() }
    Scores(c.scores, me, partnerName) { trn(it, "{count} pt", "{count} pts") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(c.letter, style = Heartpact.type.display.copy(fontSize = 84.sp, lineHeight = 80.sp), color = colors.ink, modifier = Modifier.weight(1f))
        if (s.status == GameStatus.InProgress) Text(tr("{seconds}s", "seconds" to left), style = Heartpact.type.headline, color = if (left <= 5) colors.error else colors.ink2)
    }
    Text(tr("Round {round} of {rounds}", "round" to minOf(c.round + 1, c.totalRounds), "rounds" to c.totalRounds), style = Heartpact.type.small, color = colors.ink2)
    c.reveal?.takeIf { it.entries.isNotEmpty() }?.let { reveal ->
        StickerCard(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(tr("Last round, {letter}", "letter" to reveal.letter), style = Heartpact.type.label, color = colors.ink)
                reveal.entries.forEach { e ->
                    Text("${if (e.player == me) tr("You") else partnerName} · ${GamePlay.categoryLabel(e.category)}: ${e.answer.ifBlank { "—" }} · ${if (e.counted) "+${e.points}" else "0"}",
                        style = Heartpact.type.small, color = if (e.counted) colors.ink else colors.ink3)
                }
            }
        }
    }
    when {
        s.status == GameStatus.Completed -> Text(GamePlay.outcome(s, me, partnerName), style = Heartpact.type.headline, color = colors.ink, modifier = Modifier.padding(top = 20.dp))
        c.phase == "vetting" -> {
            val mineToJudge = c.pendingVetting.filter { it.judge == me && it.allowed == null }
            Text(if (mineToJudge.isEmpty()) tr("Waiting for {partner} to judge your answers…", "partner" to partnerName) else tr("Does it count?"), style = Heartpact.type.title, color = colors.ink, modifier = Modifier.padding(top = 16.dp))
            mineToJudge.forEach { item ->
                StickerCard(Modifier.fillMaxWidth().padding(top = 10.dp)) {
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

/** After Round 1 on Google Play: a Naija finger-count game for playing with friends, shown to Nigerian couples. */
private const val AFTER_ROUND_PLAY_URL = "https://play.google.com/store/apps/details?id=com.afterround1.com"

@Composable
private fun AfterRoundCard() {
    val colors = Heartpact.colors
    val uri = LocalUriHandler.current
    StickerCard(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(tr("After Round 1"), style = Heartpact.type.title, color = colors.ink)
            Text(tr("The Naija finger-count game: call a number, lay your fingers, and the winner slaps. Play it with friends."),
                style = Heartpact.type.body, color = colors.ink2, modifier = Modifier.padding(top = 4.dp))
            HeartButton(tr("Get it on Google Play"), { runCatching { uri.openUri(AFTER_ROUND_PLAY_URL) } }, Modifier.padding(top = 12.dp), kind = ButtonKind.Tonal)
        }
    }
}
