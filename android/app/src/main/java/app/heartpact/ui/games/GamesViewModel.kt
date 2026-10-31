package app.heartpact.ui.games

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.heartpact.HeartpactApplication
import app.heartpact.data.ApiResult
import app.heartpact.data.GameSession
import app.heartpact.data.GameStatus
import app.heartpact.data.GameType
import app.heartpact.data.OFFLINE_MESSAGE
import app.heartpact.data.utc
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class GamesUi(
    val sessions: List<GameSession> = emptyList(),
    val busy: Boolean = false,
    val note: String? = null,
    val loaded: Boolean = false,
    /** Draw and guess moves on their way to the server (GameMoveQueue), shown on the board meanwhile. */
    val pending: List<PendingMove> = emptyList(),
    /** The couple's game region from the server ("ng" or "global"); null until it has loaded. */
    val region: String? = null,
    /** Finished games the person has closed, so the picker shows again. */
    val closed: Set<String> = emptySet(),
    /** Tic-tac-toe: my marks in flight, the last "Too slow!", my pen refilling (TicTacToeClaims). */
    val ttt: TicTacToeUi = TicTacToeUi(),
    /** Tic-tac-toe: the partner's stroke as they draw it, relayed by the couple hub. */
    val ink: LiveInk = LiveInk(),
) {
    /** The couple's one open game, if any. */
    val active: GameSession? get() = sessions.firstOrNull { it.open }
    /** The game just finished, kept on screen until the next one starts. */
    val finished: GameSession? get() = sessions.firstOrNull()?.takeIf { it.status == GameStatus.Completed }
    /** A game that finished in the last few minutes stays on screen, final board and all, until it's closed. */
    fun justFinished(now: Instant): GameSession? = finished?.takeIf { s ->
        s.id !in closed && s.endedAtUtc?.let { Duration.between(utc(it), now) < KEEP_FINISHED } == true
    }
    val history: List<GameSession> get() = sessions.filter { it.status == GameStatus.Completed }.take(8)

    /** A fresh list from the server, keeping any session the hub has already brought further along meanwhile. */
    fun newest(fetched: List<GameSession>): List<GameSession> = fetched.map { s ->
        sessions.firstOrNull { it.id == s.id }?.takeIf { it.stateVersion > s.stateVersion } ?: s
    }

    /** Newer state wins: an echo of my own move (or an out-of-order event) never rolls the board back. */
    fun withSession(session: GameSession): GamesUi {
        val current = sessions.firstOrNull { it.id == session.id }
        return if (current != null && current.stateVersion > session.stateVersion) this
        else copy(sessions = (listOf(session) + sessions.filterNot { it.id == session.id }).sortedWith(compareByDescending<GameSession> { it.open }))
    }
}

private val KEEP_FINISHED: Duration = Duration.ofMinutes(15)

/** The couple hub's `GameInk`: the partner's live stroke on a tic-tac-toe square ([cell] -1 clears it). */
@Serializable
private data class GameInkEvent(val sessionId: String, val userId: String = "", val cell: Int, val stroke: Int = 0, val points: List<Float> = emptyList())

private fun <T> ApiResult<T>.note(): String? = when (this) {
    is ApiResult.Ok -> null
    ApiResult.Offline -> OFFLINE_MESSAGE
    is ApiResult.Failure -> message
}

/** REST for every move; the couple hub's game events keep both phones on the same board. */
class GamesViewModel(application: Application) : AndroidViewModel(application) {
    private val app = (application as HeartpactApplication).container
    private val _ui = MutableStateFlow(GamesUi())
    val ui: StateFlow<GamesUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            app.live.events.collect { event ->
                when (event.name) {
                    "GameStarted", "GameStateChanged", "GameCompleted" ->
                        runCatching { app.json.decodeFromString(GameSession.serializer(), event.json) }.getOrNull()?.let(::upsert)
                    "GameInvitation", "GameEnded", "GameCountdown" -> refresh()
                    "GameInk" -> runCatching { app.json.decodeFromString(GameInkEvent.serializer(), event.json) }.getOrNull()
                        ?.let { _ui.value.ink.receive(it.sessionId, it.cell, it.stroke, it.points, System.currentTimeMillis()) }
                }
            }
        }
        // Events sent while the hub was down (the app in the background, the network gone) never arrive: when it
        // connects again, read the games afresh so nobody sits on "Waiting for your partner…" after they've moved.
        viewModelScope.launch {
            var was = app.live.connected.value
            app.live.connected.collect { now ->
                if (now && !was) refresh()
                was = now
            }
        }
    }

    private fun upsert(session: GameSession) = _ui.update { it.withSession(session) }

    /** Draw and guess: both move at once, so its moves queue and retry on a version conflict. */
    private val moves = GameMoveQueue(viewModelScope, _ui,
        send = { s, move -> app.games.act(s.id, s.stateVersion, move.action, move.value) },
        load = { app.games.session(it) })

    /** Spot the difference: both spot at once, so spots go one at a time and retry on a version conflict. */
    private val spots = SpotSender(viewModelScope, _ui,
        send = { s, index -> app.games.act(s.id, s.stateVersion, "spot", null, index) },
        load = { app.games.session(it) })

    fun refresh() = viewModelScope.launch {
        (app.games.region() as? ApiResult.Ok)?.value?.let { found -> _ui.update { it.copy(region = found.region) } }
        when (val result = app.games.sessions()) {
            is ApiResult.Ok -> _ui.update { it.copy(sessions = it.newest(result.value), loaded = true, note = null) }
            else -> _ui.update { it.copy(loaded = true, note = result.note()) }
        }
    }

    fun invite(type: GameType, letter: String? = null) = call { app.games.create(type, letter) }
    fun accept(id: String) = call { app.games.accept(id) }
    fun abandon(id: String) = call { app.games.abandon(id) }

    /** Tic-tac-toe: both mark at once, so each mark goes at once and shows meanwhile. */
    private val marks = TicTacToeClaims(viewModelScope, _ui,
        send = { s, action, value, index -> app.games.act(s.id, s.stateVersion, action, value, index) },
        load = { app.games.session(it) })

    /** Tic-tac-toe: the stroke under my finger, for my partner to watch live (dropped when the hub isn't connected). */
    fun ink(session: GameSession, cell: Int, stroke: Int, points: FloatArray) = app.live.ink(session.id, cell, stroke, points)

    /**
     * A move at the version on screen. If the partner moved first (`409`), reload; in a game you both play at once
     * (Same answer, Would you rather, Trivia) a pick that still stands on the new board goes again by itself
     * ([GamePlay.resendable]), so a tap made at the same moment as the partner's is never lost. Otherwise the person
     * sees the new board and tries again.
     */
    fun act(session: GameSession, action: String, value: String? = null, index: Int? = null) {
        if (session.gameType == GameType.Handwriting) return moves.add(session, action, value)
        if (session.gameType == GameType.TicTacToe) return marks.act(session, action, value, index)
        if (session.gameType == GameType.SpotTheDifference && action == "spot" && index != null) return spots.spot(session, index)
        // One move at a time: a second tap while the first is on its way would only be refused.
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true, note = null) }
        viewModelScope.launch {
            var on = session
            var tries = 0
            while (true) {
                when (val result = app.games.act(on.id, on.stateVersion, action, value, index)) {
                    is ApiResult.Ok -> { upsert(result.value); _ui.update { it.copy(busy = false) }; return@launch }
                    is ApiResult.Failure -> {
                        val latest = (app.games.session(session.id) as? ApiResult.Ok)?.value
                        latest?.let(::upsert)
                        val conflict = result.code == GameMoveQueue.VERSION_CONFLICT
                        if (conflict && latest != null && tries < GameMoveQueue.MAX_RETRIES && GamePlay.resendable(session, latest)) {
                            tries++
                            on = latest
                            continue
                        }
                        _ui.update { it.copy(busy = false, note = if (conflict) null else result.message) }
                        return@launch
                    }
                    ApiResult.Offline -> { _ui.update { it.copy(busy = false, note = OFFLINE_MESSAGE) }; return@launch }
                }
            }
        }
    }

    fun close(id: String) = _ui.update { it.copy(closed = it.closed + id) }
    fun dismissNote() = _ui.update { it.copy(note = null) }

    private fun call(block: suspend () -> ApiResult<GameSession>) {
        _ui.update { it.copy(busy = true, note = null) }
        viewModelScope.launch {
            val result = block()
            if (result is ApiResult.Ok) upsert(result.value)
            _ui.update { it.copy(busy = false, note = result.note()) }
            if (result !is ApiResult.Ok) refresh()
        }
    }

    companion object {
        /** The ICallOn letter the inviter chose, if any. */
        fun chosenLetter(session: GameSession, json: kotlinx.serialization.json.Json): String? =
            runCatching { json.parseToJsonElement(session.setupJson).jsonObject["letter"]?.jsonPrimitive?.content }.getOrNull()
    }
}
