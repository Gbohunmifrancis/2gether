package app.heartpact.ui.games

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.heartpact.HeartpactApplication
import app.heartpact.data.ApiResult
import app.heartpact.data.GameSession
import app.heartpact.data.GameStatus
import app.heartpact.data.GameType
import app.heartpact.data.HISTORY_SIZE
import app.heartpact.data.OFFLINE_MESSAGE
import app.heartpact.data.utc
import app.heartpact.i18n.tr
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
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
    /** Draw and guess: the drawer's strokes as they're drawn, relayed by the couple hub, for the guesser to watch. */
    val sketch: LiveSketch = LiveSketch(),
    /** I call on: how many categories the partner has filled so far, by session (the couple hub's `GameProgress`). */
    val progress: Map<String, PartnerProgress> = emptyMap(),
    /** The couple's finished games from the history (`api/game/history`), which reaches past the sessions listed. */
    val played: List<GameSession> = emptyList(),
) {
    /** The couple's one open game, if any. */
    val active: GameSession? get() = sessions.firstOrNull { it.open }
    /** The game just finished, kept on screen until the next one starts. */
    val finished: GameSession? get() = sessions.firstOrNull()?.takeIf { it.status == GameStatus.Completed }
    /** A game that finished in the last few minutes stays on screen, final board and all, until it's closed. */
    fun justFinished(now: Instant): GameSession? = finished?.takeIf { s ->
        s.id !in closed && s.endedAtUtc?.let { Duration.between(utc(it), now) < KEEP_FINISHED } == true
    }
    /**
     * The games played to the end, newest first: the history, with any finished since it loaded (from the sessions) on
     * top. Without a history (an older server) it's the finished games among the sessions, as before.
     */
    val history: List<GameSession> get() = (sessions + played).filter { it.status == GameStatus.Completed }.distinctBy { it.id }
        .sortedByDescending { it.endedAtUtc?.let { at -> runCatching { utc(at) }.getOrNull() } ?: Instant.MIN }.take(HISTORY_SIZE)

    /**
     * The list as fetched, except that a game this phone already holds at a newer version (an event that arrived while
     * the fetch was on its way) keeps that version: a refresh never rolls a board back.
     */
    fun withSessions(fetched: List<GameSession>): GamesUi = copy(sessions = fetched.map { session ->
        sessions.firstOrNull { it.id == session.id && it.stateVersion > session.stateVersion } ?: session
    })

    /** The fetched list with [withSessions]' rule: a session the hub already brought further along stays as it is. */
    fun newest(fetched: List<GameSession>): List<GameSession> = withSessions(fetched).sessions

    /** Newer state wins: an echo of my own move (or an out-of-order event) never rolls the board back. */
    fun withSession(session: GameSession): GamesUi {
        val current = sessions.firstOrNull { it.id == session.id }
        return if (current != null && current.stateVersion > session.stateVersion) this
        else copy(sessions = (listOf(session) + sessions.filterNot { it.id == session.id }).sortedWith(compareByDescending<GameSession> { it.open }))
    }

    /**
     * The list as the server just sent it, except that a game this phone already holds at a newer version (a move or
     * an event that landed while the list was on its way) keeps that version, so a refresh never rolls a board back.
     */
    fun withList(list: List<GameSession>): GamesUi = withSessions(list)
}

/** The couple hub's `GameEnded`: who ended the game (an empty id when the server ended a forgotten one). */
@Serializable
internal data class GameEndedEvent(val sessionId: String, val endedByUserId: String = "", val endedByName: String = "")

private const val NOBODY = "00000000-0000-0000-0000-000000000000"

/**
 * What to tell the person when a game ends without them: their partner took back an invite, said not now, or ended a
 * game part way, or the server ended one left too long. Null when they ended it themselves.
 */
internal fun endedNote(ended: GameEndedEvent, session: GameSession?, me: String?): String? {
    if (me != null && ended.endedByUserId == me) return null
    if (ended.endedByUserId.isEmpty() || ended.endedByUserId == NOBODY) return tr("That game sat untouched for too long, so it ended.")
    val partner = ended.endedByName.ifBlank { tr("your partner") }
    val invite = session != null && session.inviteAcceptedAtUtc == null && session.startedAtUtc == null
    return when {
        invite && session?.invitedByUserId == ended.endedByUserId -> tr("{partner} took back the invite.", "partner" to partner)
        invite -> tr("{partner} can't play right now.", "partner" to partner)
        else -> tr("{partner} ended the game.", "partner" to partner)
    }
}

private val KEEP_FINISHED: Duration = Duration.ofMinutes(15)

/** Each time the hub comes up after being down (not when it is already up as the screen opens, which loads anyway). */
internal fun reconnections(connected: Flow<Boolean>): Flow<Unit> = flow {
    var before: Boolean? = null
    connected.distinctUntilChanged().collect { up ->
        if (up && before == false) emit(Unit)
        before = up
    }
}

/** I call on: the partner has filled [filled] of [total] categories in [round] (the couple hub's `GameProgress`). */
@Serializable
data class PartnerProgress(val sessionId: String = "", val userId: String = "", val round: Int = 0, val filled: Int = 0, val total: Int = 0)

/** The couple hub's `GameInk`: the partner's live stroke on a tic-tac-toe square, or the drawer's in Draw and guess ([cell] -1 un-draws). */
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
                    "GameInvitation", "GameCountdown" -> refresh()
                    "GameEnded" -> {
                        val ended = runCatching { app.json.decodeFromString(GameEndedEvent.serializer(), event.json) }.getOrNull()
                        launch {
                            val me = runCatching { app.session.load()?.user?.id }.getOrNull()
                            refresh().join()
                            // Whether it was ever accepted says if this was an invite or a game part way.
                            val session = ended?.let { e -> _ui.value.sessions.firstOrNull { it.id == e.sessionId } }
                            ended?.let { endedNote(it, session, me) }?.let { note -> _ui.update { it.copy(note = note) } }
                        }
                    }
                    "GameProgress" -> runCatching { app.json.decodeFromString(PartnerProgress.serializer(), event.json) }.getOrNull()
                        ?.let { p -> _ui.update { it.copy(progress = it.progress + (p.sessionId to p)) } }
                    "GameInk" -> runCatching { app.json.decodeFromString(GameInkEvent.serializer(), event.json) }.getOrNull()
                        ?.let { e ->
                            val drawing = _ui.value.sessions.firstOrNull { it.id == e.sessionId }?.gameType == GameType.Handwriting
                            if (drawing) _ui.value.sketch.receive(e.sessionId, e.userId, e.cell, e.stroke, e.points)
                            else _ui.value.ink.receive(e.sessionId, e.cell, e.stroke, e.points, System.currentTimeMillis())
                        }
                }
            }
        }
        // Events sent while the hub was down (the app in the background, the network gone) never arrive: when it
        // connects again, fetch the games as they stand, so a turn that came round meanwhile isn't missed.
        viewModelScope.launch { reconnections(app.live.connected).collect { refresh() } }
    }

    private fun upsert(session: GameSession) = _ui.update { it.withSession(session) }

    /** Draw and guess: both move at once, so its moves queue and retry on a version conflict. */
    private val moves = GameMoveQueue(viewModelScope, _ui,
        send = { s, move -> app.games.act(s.id, s.stateVersion, move.action, move.value) },
        load = { app.games.session(it) })

    /** Spot the difference: both spot at once, so spots go one at a time and retry on a version conflict. */
    private val spots = SpotSender(viewModelScope, _ui,
        send = { s, index -> app.games.act(s.id, s.stateVersion, "spot", spotValue(GamePlay.spot(s).scene), index) },
        load = { app.games.session(it) })

    fun refresh() = viewModelScope.launch {
        (app.games.region() as? ApiResult.Ok)?.value?.let { found -> _ui.update { it.copy(region = found.region) } }
        (app.games.history() as? ApiResult.Ok)?.value?.let { items -> _ui.update { it.copy(played = items.mapNotNull { item -> item.session }) } }
        when (val result = app.games.sessions()) {
            // Back online clears the offline note; a refusal just shown (a refresh follows it) stays until the next tap.
            is ApiResult.Ok -> _ui.update { it.withSessions(result.value).copy(loaded = true, note = it.note?.takeUnless { n -> n == OFFLINE_MESSAGE }) }
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

    /** Tic-tac-toe and Draw and guess: the stroke under my finger, for my partner to watch live (dropped when the hub isn't connected). */
    fun ink(session: GameSession, cell: Int, stroke: Int, points: FloatArray) = app.live.ink(session.id, cell, stroke, points)

    /** I call on: what's typed so far, so it still counts if the clock runs out before it's sent. */
    fun draft(session: GameSession, round: Int, answersJson: String) = app.live.draft(session.id, round, answersJson)

    /**
     * A move at the version on screen. If the partner moved first (`409`), reload; a move that still means something on
     * the new board goes again by itself, so a tap made at the same moment as the partner's never quietly vanishes:
     * I call on's answers and verdicts (both judge at once, GamePlay.iCallOnStillWanted), and a pick in a game you both
     * play at once (Same answer, Would you rather, Trivia: GamePlay.resendable). Otherwise the person sees the new board
     * and tries again.
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
                val result = app.games.act(on.id, on.stateVersion, action, value, index)
                if (result is ApiResult.Failure) {
                    val conflict = result.code == GameMoveQueue.VERSION_CONFLICT
                    val latest = (app.games.session(session.id) as? ApiResult.Ok)?.value?.also(::upsert)
                    val again = latest != null && when (session.gameType) {
                        GameType.ICallOn -> tries < RETRIES && GamePlay.iCallOnStillWanted(session, latest, action, value)
                        // Same answer's "That counts!": again while the round is still there to count.
                        else -> tries < GameMoveQueue.MAX_RETRIES && if (action == COUNTS) GamePlay.countStillWanted(latest, index) else GamePlay.resendable(session, latest)
                    }
                    if (conflict && again) {
                        tries++
                        on = latest!!
                        continue
                    }
                    _ui.update { it.copy(busy = false, note = if (conflict) null else result.message) }
                } else if (result is ApiResult.Ok) {
                    upsert(result.value)
                    _ui.update { it.copy(busy = false) }
                } else {
                    _ui.update { it.copy(busy = false, note = OFFLINE_MESSAGE) }
                }
                break
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
            // Both invited at once, or the game moved on under the tap: the reload shows what happened, so no note.
            val quiet = result is ApiResult.Failure && result.code in RELOAD_ONLY
            _ui.update { it.copy(busy = false, note = if (quiet) null else result.note()) }
            if (result !is ApiResult.Ok) refresh()
        }
    }

    companion object {
        /** How many times an I call on answer or verdict is sent again after crossing the partner's move. */
        private const val RETRIES = 3
        /** Refusals the reload explains by itself: the partner invited first, or a move landed as the game was ended. */
        private val RELOAD_ONLY = setOf("game.active_session", "game.version_conflict")

        /** The ICallOn letter the inviter chose, if any. */
        fun chosenLetter(session: GameSession, json: kotlinx.serialization.json.Json): String? =
            runCatching { json.parseToJsonElement(session.setupJson).jsonObject["letter"]?.jsonPrimitive?.content }.getOrNull()
    }
}
