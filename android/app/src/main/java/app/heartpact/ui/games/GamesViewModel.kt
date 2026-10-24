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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class GamesUi(
    val sessions: List<GameSession> = emptyList(),
    val busy: Boolean = false,
    val note: String? = null,
    val loaded: Boolean = false,
    /** Draw and guess moves on their way to the server (GameMoveQueue), shown on the board meanwhile. */
    val pending: List<PendingMove> = emptyList(),
) {
    /** The couple's one open game, if any. */
    val active: GameSession? get() = sessions.firstOrNull { it.open }
    /** The game just finished, kept on screen until the next one starts. */
    val finished: GameSession? get() = sessions.firstOrNull()?.takeIf { it.status == GameStatus.Completed }
    val history: List<GameSession> get() = sessions.filter { it.status == GameStatus.Completed }.take(8)

    /** Newer state wins: an echo of my own move (or an out-of-order event) never rolls the board back. */
    fun withSession(session: GameSession): GamesUi {
        val current = sessions.firstOrNull { it.id == session.id }
        return if (current != null && current.stateVersion > session.stateVersion) this
        else copy(sessions = (listOf(session) + sessions.filterNot { it.id == session.id }).sortedWith(compareByDescending<GameSession> { it.open }))
    }
}

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
                }
            }
        }
    }

    private fun upsert(session: GameSession) = _ui.update { it.withSession(session) }

    /** Draw and guess: both move at once, so its moves queue and retry on a version conflict. */
    private val moves = GameMoveQueue(viewModelScope, _ui,
        send = { s, move -> app.games.act(s.id, s.stateVersion, move.action, move.value) },
        load = { app.games.session(it) })

    fun refresh() = viewModelScope.launch {
        when (val result = app.games.sessions()) {
            is ApiResult.Ok -> _ui.update { it.copy(sessions = result.value, loaded = true, note = null) }
            else -> _ui.update { it.copy(loaded = true, note = result.note()) }
        }
    }

    fun invite(type: GameType, letter: String? = null) = call { app.games.create(type, letter) }
    fun accept(id: String) = call { app.games.accept(id) }
    fun abandon(id: String) = call { app.games.abandon(id) }

    /** A move at the version on screen; if the partner moved first, reload and let the person try again. */
    fun act(session: GameSession, action: String, value: String? = null, index: Int? = null) {
        if (session.gameType == GameType.Handwriting) return moves.add(session, action, value)
        _ui.update { it.copy(busy = true, note = null) }
        viewModelScope.launch {
            val result = app.games.act(session.id, session.stateVersion, action, value, index)
            when (result) {
                is ApiResult.Ok -> { upsert(result.value); _ui.update { it.copy(busy = false) } }
                is ApiResult.Failure -> {
                    _ui.update { it.copy(busy = false, note = if (result.code == "game.version_conflict") null else result.message) }
                    (app.games.session(session.id) as? ApiResult.Ok)?.value?.let(::upsert)
                }
                ApiResult.Offline -> _ui.update { it.copy(busy = false, note = OFFLINE_MESSAGE) }
            }
        }
    }

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
