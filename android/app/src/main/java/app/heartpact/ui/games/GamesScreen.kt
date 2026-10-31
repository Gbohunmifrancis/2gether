package app.heartpact.ui.games

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.heartpact.data.GameSession
import app.heartpact.data.GameStatus
import app.heartpact.data.GameType
import app.heartpact.data.utc
import app.heartpact.i18n.tr
import app.heartpact.ui.components.ButtonKind
import app.heartpact.ui.components.HeartButton
import app.heartpact.ui.components.NoteCard
import app.heartpact.ui.components.ReadableWidth
import app.heartpact.ui.components.TopBar
import app.heartpact.ui.components.TwoPaneWidth
import app.heartpact.ui.components.readable
import app.heartpact.ui.play.Reaction
import app.heartpact.ui.play.SkillBests
import app.heartpact.ui.play.rememberSkillBests
import app.heartpact.ui.play.SkillKind
import app.heartpact.ui.play.SkillPracticeScreen
import app.heartpact.ui.play.rememberPlayHaptics
import app.heartpact.ui.theme.Heartpact
import kotlinx.coroutines.delay
import java.time.Instant

data class GameActions(
    val onInvite: (GameType, String?) -> Unit = { _, _ -> },
    val onAccept: (String) -> Unit = {},
    val onAbandon: (String) -> Unit = {},
    val onAct: (GameSession, String, String?, Int?) -> Unit = { _, _, _, _ -> },
    val onBack: () -> Unit = {},
    /** Leave a finished game's final board for the picker. */
    val onClose: (String) -> Unit = {},
    /** Tic-tac-toe: the stroke being drawn on a square, for the partner to watch (square, stroke number, x,y pairs). */
    val onInk: (GameSession, Int, Int, FloatArray) -> Unit = { _, _, _, _ -> },
    /** I call on: the answers typed so far this round (round, answers as JSON), so they count if time runs out. */
    val onDraft: (GameSession, Int, String) -> Unit = { _, _, _ -> },
)

/**
 * Play together: pick a game (or practice a Heart skill), accept one, play it, see how it went.
 * [bests] keeps Heart skills' best practice scores; without one, none are shown.
 */
@Composable
fun GamesScreen(ui: GamesUi, me: String, partnerName: String, actions: GameActions, now: () -> Instant = { Instant.now() }, bests: SkillBests = rememberSkillBests(), myName: String = "") {
    // A Heart skill being practiced opens over the hub, full screen; back comes home to the hub.
    var practicing by rememberSaveable { mutableStateOf<SkillKind?>(null) }
    practicing?.let { kind ->
        BackHandler { practicing = null }
        Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) { SkillPracticeScreen(kind) { practicing = null } }
        return
    }
    // Every move, invite and accept gets a tap under the finger.
    val haptics = rememberPlayHaptics()
    val moves = remember(actions, haptics) {
        actions.copy(
            onAct = { s, action, value, index -> haptics.tick(); actions.onAct(s, action, value, index) },
            onInvite = { type, letter -> haptics.tick(); actions.onInvite(type, letter) },
            onAccept = { haptics.tick(); actions.onAccept(it) },
        )
    }
    val active = ui.active ?: ui.justFinished(now())
    // The keyboard pushes the board up rather than covering the answer being typed.
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        TopBar(active?.let { GamePlay.title(it) } ?: tr("Games"), actions.onBack)
        // On a tablet the boards keep a readable width, and the hub spreads its two grids side by side.
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).readable(if (active == null) TwoPaneWidth else ReadableWidth).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            ui.note?.let { NoteCard(it, Modifier.padding(bottom = 12.dp)) }
            when {
                active == null -> GamesHub(ui, me, partnerName, moves, bests) { practicing = it }
                active.status == GameStatus.Waiting -> Waiting(active, me, partnerName, ui.busy, moves)
                else -> {
                    // A finished game leads with its result; the final board stays under it.
                    if (active.status == GameStatus.Completed) GameResultCard(active, me, partnerName)
                    Playing(active, me, partnerName, ui.busy, moves, now, ui.pending, ui, myName)
                }
            }
        }
    }
}

@Composable
private fun Waiting(s: GameSession, me: String, partnerName: String, busy: Boolean, actions: GameActions) {
    val colors = Heartpact.colors
    val mine = s.invitedByUserId == me
    Column(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // Whoever sent the invite waits; whoever got it is waved at.
        Couple(110.dp, if (mine) Reaction.Thinking else Reaction.Waiting, if (mine) Reaction.Waiting else Reaction.Idle)
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
private fun Playing(s: GameSession, me: String, partnerName: String, busy: Boolean, actions: GameActions, now: () -> Instant, pending: List<PendingMove> = emptyList(), ui: GamesUi = GamesUi(), myName: String = "") {
    val countdown = secondsUntil(s.countdownEndsAtUtc, now)
    if (s.status == GameStatus.InProgress && countdown > 0) {
        Countdown(countdown)
        return
    }
    when (s.gameType) {
        GameType.MemoryMatch -> MemoryBoard(s, me, partnerName, busy, actions)
        GameType.ICallOn -> ICallOnBoard(s, me, partnerName, busy, actions, now, ui.progress[s.id])
        GameType.TruthOrDare -> TruthOrDareBoard(s, me, partnerName, busy, actions)
        GameType.WordDash -> WordDashBoard(s, me, partnerName, busy, actions, now)
        GameType.GuessMyAnswer -> GuessMyAnswerBoard(s, me, partnerName, busy, actions)
        GameType.WouldYouRather -> WouldYouRatherBoard(s, me, partnerName, busy, actions)
        GameType.TicTacToe -> TicTacToeBoard(s, me, partnerName, ui.ttt, ui.ink, actions, now)
        GameType.ConnectFour -> ConnectFourBoard(s, me, partnerName, busy, actions)
        GameType.Handwriting -> DrawAndGuessBoard(s, me, partnerName, pending, actions)
        GameType.Trivia -> TriviaBoard(s, me, partnerName, busy, actions)
        GameType.SkillDuel -> SkillDuelBoard(s, me, partnerName, busy, actions)
        GameType.SpotTheDifference -> SpotTheDifferenceBoard(s, me, myName, partnerName, actions, now)
        GameType.SnakesAndLadders -> SnakesAndLaddersBoard(s, me, partnerName, busy, actions)
        // A game from a newer server: say so, and leave "End this game" under it.
        GameType.Unknown -> NoteCard(tr("This game needs a newer Heartpact. Update the app to play it."))
        else -> QuizBoard(s, me, partnerName, busy, actions)
    }
    if (s.status == GameStatus.InProgress) HeartButton(tr("End this game"), { actions.onAbandon(s.id) }, Modifier.padding(top = 18.dp), kind = ButtonKind.Text)
    if (s.status == GameStatus.Completed) HeartButton(tr("Back to games"), { actions.onClose(s.id) }, Modifier.padding(top = 18.dp), kind = ButtonKind.Tonal)
}
