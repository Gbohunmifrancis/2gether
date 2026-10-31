package app.heartpact.ui.today

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.heartpact.HeartpactApplication
import app.heartpact.data.ApiResult
import app.heartpact.data.GameSession
import app.heartpact.data.GameStatus
import app.heartpact.data.GameType
import app.heartpact.i18n.tr
import app.heartpact.ui.components.ButtonKind
import app.heartpact.ui.components.HeartButton
import app.heartpact.ui.components.StickerCard
import app.heartpact.ui.games.GameEndedEvent
import app.heartpact.ui.games.GameIcon
import app.heartpact.ui.games.GamePlay
import app.heartpact.ui.games.GamesUi
import app.heartpact.ui.games.blob
import app.heartpact.ui.games.reconnections
import app.heartpact.ui.games.wouldYouRather
import app.heartpact.ui.play.blobPath
import app.heartpact.ui.theme.Heartpact
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What a game is waiting for me to do, for the card on Today. */
enum class GameWait {
    /** My partner invited me: accept or say not now. */
    Invite,
    /** A game played in turn, and it's my turn. */
    Turn,
    /** A game we both play at once, and I haven't moved in this round yet. */
    Move,
}

/** The couple's open game, when it waits on me; [partnerWaiting] when my partner has already moved this round. */
data class WaitingGame(val session: GameSession, val wait: GameWait, val partnerWaiting: Boolean = false)

/** Pure: whether the couple's one open game is waiting on me (docs/games.md). */
object TodayGames {
    /** Played in turn without a clock: `currentTurnUserId` is whoever moves next (the one to choose or to judge in truth or dare). */
    private val inTurn = setOf(GameType.GuessMyAnswer, GameType.MemoryMatch, GameType.ConnectFour, GameType.SnakesAndLadders, GameType.TruthOrDare)

    /**
     * The open game, if it waits on me: an invite from my partner, my turn in a game played in turn, or my move in a
     * game we both play at once with no clock where it's cheap to know (Same answer, Would you rather, Trivia, Heart
     * to heart). Games on a clock or played live (I call on, Word dash, Spot the difference, Tic-tac-toe, Draw and
     * guess) are played with both in the app, so they wait on nobody here; nor does a game this app doesn't know.
     */
    fun waiting(sessions: List<GameSession>, me: String): WaitingGame? {
        val s = sessions.firstOrNull { it.open } ?: return null
        if (s.gameType == GameType.Unknown) return null
        return when (s.status) {
            GameStatus.Waiting -> WaitingGame(s, GameWait.Invite).takeIf { s.invitedByUserId != null && s.invitedByUserId != me }
            GameStatus.InProgress -> inProgress(s, me)
            else -> null
        }
    }

    private fun inProgress(s: GameSession, me: String): WaitingGame? {
        if (s.gameType in inTurn) return WaitingGame(s, GameWait.Turn, partnerWaiting = true).takeIf { s.currentTurnUserId == me }
        fun move(mine: Boolean, partnerDone: Boolean) = if (mine) WaitingGame(s, GameWait.Move, partnerDone) else null
        return when (s.gameType) {
            GameType.CoupleQuiz -> GamePlay.quiz(s).let { q ->
                move(me in q.players && q.round < q.totalRounds && q.mine == null && me !in q.answered, q.answered.any { it != me })
            }
            GameType.WouldYouRather -> GamePlay.wouldYouRather(s).let { w ->
                move(me in w.players && w.phase != "completed" && w.round < w.totalRounds && w.myPick == null && me !in w.picked, w.picked.any { it != me })
            }
            GameType.Trivia -> GamePlay.trivia(s).let { t ->
                move(me in t.players && !t.completed && t.question != null && t.myAnswer == null && me !in t.answered, t.answered.any { it != me })
            }
            GameType.SkillDuel -> GamePlay.skillDuel(s).let { d ->
                move(me in d.players && d.totals == null && d.rounds.any { it.mine == null }, d.rounds.any { it.mine == null && it.played })
            }
            else -> null
        }
    }
}

data class TodayGamesUi(val sessions: List<GameSession> = emptyList(), val busy: Boolean = false)

/**
 * The games for Today's card: the couple's sessions, kept current by the same couple hub events the games screen
 * hears (no connection of its own), refetched on resume and whenever the hub comes back.
 */
class TodayGamesViewModel(application: Application) : AndroidViewModel(application) {
    private val app = (application as HeartpactApplication).container
    private val _ui = MutableStateFlow(TodayGamesUi())
    val ui: StateFlow<TodayGamesUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            app.live.events.collect { event ->
                when (event.name) {
                    "GameStarted", "GameStateChanged", "GameCompleted" ->
                        runCatching { app.json.decodeFromString(GameSession.serializer(), event.json) }.getOrNull()?.let { session ->
                            _ui.update { it.copy(sessions = GamesUi(it.sessions).withSession(session).sessions) }
                        }
                    "GameInvitation", "GameCountdown" -> refresh()
                    "GameEnded" -> {
                        // An invite taken back or a game ended: it leaves the card at once, then the list catches up.
                        runCatching { app.json.decodeFromString(GameEndedEvent.serializer(), event.json) }.getOrNull()?.let { ended ->
                            _ui.update { ui -> ui.copy(sessions = ui.sessions.filterNot { it.id == ended.sessionId }) }
                        }
                        refresh()
                    }
                }
            }
        }
        viewModelScope.launch { reconnections(app.live.connected).collect { refresh() } }
    }

    fun refresh() = viewModelScope.launch {
        (app.games.sessions() as? ApiResult.Ok)?.value?.let { fetched -> _ui.update { it.copy(sessions = GamesUi(it.sessions).withSessions(fetched).sessions) } }
    }

    /** Accept in place; once it's on, [then] opens the game for its countdown. */
    fun accept(id: String, then: () -> Unit) = call({ app.games.accept(id) }, then)

    /** "Not now": the invite ends, and whoever sent it hears so. */
    fun decline(id: String) = call({ app.games.abandon(id) }, {})

    private fun call(block: suspend () -> ApiResult<GameSession>, then: () -> Unit) {
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = block()
            if (result is ApiResult.Ok) _ui.update { it.copy(sessions = GamesUi(it.sessions).withSession(result.value).sessions) }
            _ui.update { it.copy(busy = false) }
            // Taken back, or accepted on the other phone meanwhile: the list says how things stand.
            if (result is ApiResult.Ok) then() else refresh()
        }
    }
}

/**
 * Today's card for a game that waits on me: my partner's invite (play it or not now, right here), my turn, or my move.
 * Nothing shows when no game waits on me.
 */
@Composable
fun GameWaitingCard(waiting: WaitingGame, partnerName: String, busy: Boolean, onAccept: (String) -> Unit, onDecline: (String) -> Unit, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Heartpact.colors
    val s = waiting.session
    val region = GamePlay.region(s)
    val lead = when (waiting.wait) {
        GameWait.Invite -> tr("{partner} invited you to play", "partner" to partnerName)
        GameWait.Turn -> tr("Your turn")
        GameWait.Move -> tr("Your move")
    }
    val note = when {
        waiting.wait == GameWait.Invite -> GamePlay.blurb(s.gameType, region)
        waiting.partnerWaiting -> tr("{partner} is waiting for you", "partner" to partnerName)
        else -> tr("Pick up where you left off")
    }
    val invite = waiting.wait == GameWait.Invite
    StickerCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = if (invite) 6.dp else 16.dp)) {
            // The header reads as one sentence to TalkBack, and is said out loud when it turns up; the buttons stand apart.
            Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { heading(); liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).drawWithCache {
                    val path = blobPath(s.gameType.ordinal * 7 + 3, size)
                    onDrawBehind { drawPath(path, s.gameType.blob.fill) }
                }, contentAlignment = Alignment.Center) { GameIcon(s.gameType, s.gameType.blob.ink, 26.dp) }
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(lead, style = Heartpact.type.small, color = colors.ink2)
                    Text(GamePlay.title(s), style = Heartpact.type.title, color = colors.ink, modifier = Modifier.padding(top = 2.dp))
                    Text(note, style = Heartpact.type.small, color = colors.ink3, modifier = Modifier.padding(top = 2.dp))
                }
                if (!invite) HeartButton(tr("Play"), onOpen, Modifier.padding(start = 10.dp), height = 48.dp, fillWidth = false)
            }
            if (invite) {
                HeartButton(GamePlay.play(s.gameType, region), { onAccept(s.id) }, Modifier.padding(top = 14.dp), busy = busy, height = 48.dp)
                HeartButton(tr("Not now"), { onDecline(s.id) }, Modifier.padding(top = 2.dp), kind = ButtonKind.Text, enabled = !busy)
            }
        }
    }
}
