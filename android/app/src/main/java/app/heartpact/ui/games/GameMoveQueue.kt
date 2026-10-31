package app.heartpact.ui.games

import app.heartpact.data.ApiResult
import app.heartpact.data.DrawStroke
import app.heartpact.data.GameSession
import app.heartpact.data.GameStatus
import app.heartpact.data.OFFLINE_MESSAGE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A move on its way to the server: a stroke, an undo, a guess. [round] is the round it was made in. */
data class PendingMove(val sessionId: String, val round: Int, val action: String, val value: String? = null)

/**
 * Sends Draw and guess moves one at a time, in the order they were made. Both partners move at once there (strokes
 * cross guesses), and every move names the state version it was made on, so the second of two moves at the same
 * moment gets `409 game.version_conflict`. Then the queue reloads the session and sends the same move again on the
 * new version, up to [MAX_RETRIES] times. A move whose round has ended meanwhile is dropped quietly; a move the
 * server refuses drops everything queued behind it (it was drawn on the same picture) and shows why.
 *
 * A dropped connection doesn't lose the drawing: the move waits and goes again ([offlinePause], up to
 * [MAX_OFFLINE_RETRIES] times) while it shows on the board. A request whose answer was lost may still have landed,
 * so before sending again the queue reloads and checks ([landed]); only the drawer changes the strokes, so whether a
 * stroke, an undo or a clear got there can be told for sure, and nothing is drawn or undone twice.
 *
 * Moves wait in [GamesUi.pending] so the board can show them straight away, and leave it in the same update that
 * brings the server's answer, so nothing flickers.
 */
class GameMoveQueue(
    private val scope: CoroutineScope,
    private val ui: MutableStateFlow<GamesUi>,
    private val send: suspend (GameSession, PendingMove) -> ApiResult<GameSession>,
    private val load: suspend (String) -> ApiResult<GameSession>,
    private val pause: suspend (attempt: Int) -> Unit = { delay(150L * it) },
    private val offlinePause: suspend (attempt: Int) -> Unit = { delay(1_000L * it) },
) {
    private var worker: Job? = null

    fun add(session: GameSession, action: String, value: String?) {
        val move = PendingMove(session.id, GamePlay.drawAndGuess(session).round, action, value)
        ui.update { it.copy(pending = it.pending + move, note = null) }
        if (worker?.isActive != true) worker = scope.launch { drain() }
    }

    private suspend fun drain() {
        while (true) deliver(ui.value.pending.firstOrNull() ?: return)
    }

    private fun current(id: String) = ui.value.sessions.firstOrNull { it.id == id }

    private suspend fun deliver(move: PendingMove) {
        var conflicts = 0
        var offline = 0
        // The board a request went out on whose answer never came back: it may have landed all the same.
        var unsure: GameSession? = null
        while (true) {
            val latest = current(move.sessionId)
            if (latest == null || latest.status != GameStatus.InProgress || GamePlay.drawAndGuess(latest).round != move.round) {
                ui.update { it.copy(pending = it.pending - move) }
                return
            }
            if (unsure != null && latest.stateVersion > unsure.stateVersion && landed(unsure, latest, move)) {
                ui.update { it.copy(pending = it.pending - move) }
                return
            }
            when (val result = send(latest, move)) {
                is ApiResult.Ok -> {
                    ui.update { it.withSession(result.value).copy(pending = it.pending - move) }
                    return
                }
                is ApiResult.Failure -> {
                    if (result.code == VERSION_CONFLICT && conflicts < MAX_RETRIES) {
                        conflicts++
                        reload(move.sessionId)
                        pause(conflicts)
                        continue
                    }
                    ui.update { state -> state.copy(pending = state.pending.filterNot { it.sessionId == move.sessionId }, note = result.message) }
                    reload(move.sessionId)
                    return
                }
                ApiResult.Offline -> {
                    if (offline < MAX_OFFLINE_RETRIES) {
                        offline++
                        if (unsure == null) unsure = latest
                        offlinePause(offline)
                        reload(move.sessionId)
                        continue
                    }
                    ui.update { state -> state.copy(pending = state.pending.filterNot { it.sessionId == move.sessionId }, note = OFFLINE_MESSAGE) }
                    return
                }
            }
        }
    }

    private suspend fun reload(id: String) {
        (load(id) as? ApiResult.Ok)?.value?.let { session -> ui.update { it.withSession(session) } }
    }

    companion object {
        const val MAX_RETRIES = 4
        const val MAX_OFFLINE_RETRIES = 5
        const val VERSION_CONFLICT = "game.version_conflict"

        /**
         * Whether [move], sent on [before], is already on [after]. Only the drawer's own moves change the strokes, one
         * at a time and in order, so the stroke count says it; a guess shows in the wrong guesses (a right one ends the
         * round, and a move for an ended round is dropped anyway).
         */
        fun landed(before: GameSession, after: GameSession, move: PendingMove): Boolean {
            val was = GamePlay.drawAndGuess(before)
            val now = GamePlay.drawAndGuess(after)
            if (was.round != now.round) return true
            return when (move.action) {
                "stroke" -> now.strokes.size == was.strokes.size + 1 && Sketching.decode(move.value)?.let { sent -> same(sent, now.strokes.last()) } == true
                "undo" -> now.strokes.size == was.strokes.size - 1
                "clear" -> now.strokes.isEmpty() && was.strokes.isNotEmpty()
                "guess" -> now.wrongGuesses.size > was.wrongGuesses.size && now.wrongGuesses.last().trim().equals(move.value?.trim(), ignoreCase = true)
                else -> false
            }
        }

        private fun same(a: DrawStroke, b: DrawStroke) = a.points.size == b.points.size &&
            a.points.zip(b.points).all { (p, q) -> p.size == q.size && p.zip(q).all { (x, y) -> kotlin.math.abs(x - y) < 0.002 } }
    }
}
