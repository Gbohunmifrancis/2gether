package app.heartpact.ui.games

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.geometry.Offset
import app.heartpact.data.ApiResult
import app.heartpact.data.GameSession
import app.heartpact.data.GameStatus
import app.heartpact.data.OFFLINE_MESSAGE
import app.heartpact.data.TicTacToeStroke
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

/*
 * Real-time tic-tac-toe (docs/games.md): both draw at once, a square goes to whoever's mark reaches the server first,
 * and each pen needs a moment to refill after its own mark. A mark shows the instant it's made and goes to the server
 * on its own; if the partner got there first it un-draws with a "Too slow!". The partner's stroke shows live while
 * they draw it (ink relayed by the couple hub, never stored).
 */

/** One of my marks on its way to the server, shown on the board meanwhile. [extra] is a stroke added to my newest mark. */
data class PendingMark(
    val sessionId: String,
    val game: Int,
    val cell: Int,
    val strokes: List<TicTacToeStroke>,
    val id: Long,
    val extra: Boolean = false,
)

/** A mark that didn't land: the partner got there first ([taken]) or something else moved under it. Each has its own [id]. */
data class TooSlow(val sessionId: String, val game: Int, val cell: Int, val id: Long, val taken: Boolean)

/** My newest mark, which takes another stroke or two while its ink is wet (the second line of an X). */
data class WetMark(val sessionId: String, val game: Int, val cell: Int, val claimId: Long, val untilMs: Long, val strokes: Int)

/** The phone's side of a live game: marks in flight, the last "Too slow!", and my pen refilling (wall-clock ms). */
data class TicTacToeUi(
    val pending: List<PendingMark> = emptyList(),
    val tooSlow: TooSlow? = null,
    val penFromMs: Long = 0,
    val penReadyAtMs: Long = 0,
    val wet: WetMark? = null,
)

/** The partner's live stroke on one square: strokes in order, each a list of points as fractions of the square. */
data class InkTrail(val sessionId: String, val stroke: Int, val strokes: List<List<Offset>>, val atMs: Long)

/**
 * The partner's ink as it's drawn, by square. Snapshot state, so only the board redraws as it arrives (twenty times
 * a second while a finger moves), not the whole screen.
 */
class LiveInk {
    val trails = mutableStateMapOf<Int, InkTrail>()

    /** One message: [cell] -1 clears the partner's ink; a new [stroke] number starts a new stroke on the square. */
    fun receive(sessionId: String, cell: Int, stroke: Int, points: List<Float>, nowMs: Long) {
        if (cell < 0) { trails.clear(); return }
        if (cell > 8) return
        val fresh = points.chunked(2).filter { it.size == 2 }.map { Offset(it[0].coerceIn(0f, 1f), it[1].coerceIn(0f, 1f)) }
        val before = trails[cell]?.takeIf { it.sessionId == sessionId }
        val strokes = when {
            before == null -> listOf(fresh)
            before.stroke == stroke -> before.strokes.dropLast(1) + listOf(before.strokes.last() + fresh)
            else -> (before.strokes + listOf(fresh)).takeLast(TicTacToeInk.MAX_STROKES)
        }
        trails[cell] = InkTrail(sessionId, stroke, strokes, nowMs)
    }

    /** Ink nobody has added to for a while fades; a game that has moved on clears it all. */
    fun prune(nowMs: Long, sessionId: String?) {
        trails.entries.filter { it.value.sessionId != sessionId || nowMs - it.value.atMs > FADE_MS }.map { it.key }.forEach { trails.remove(it) }
    }

    companion object {
        const val FADE_MS = 1500L
    }
}

/** The move's value on the wire: `{"game":n,"strokes":[[[x,y],...],...]}`. */
@Serializable
private data class MarkMove(val game: Int? = null, val strokes: List<List<List<Double>>> = emptyList())

/** Pure: the wire format of a mark, a tap's ready-made X or O, and which partner is X. */
object TicTacToeInk {
    /** The server takes up to 64 points a stroke and three strokes a mark; the phone keeps well under. */
    const val MAX_POINTS = 48
    const val MAX_STROKES = 3
    /** Live ink: at most 32 points a message, and a message every 50 ms (20 a second) while a finger moves. */
    const val INK_POINTS = 32
    const val INK_EVERY_MS = 50L
    /** How long after a mark the phone still sends another stroke for it (the server allows two seconds). */
    const val WET_MS = 1500L

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** The couple's first player is X, the other O. */
    fun isX(players: List<String>, who: String) = players.indexOf(who) != 1

    fun encode(game: Int, strokes: List<TicTacToeStroke>): String =
        json.encodeToString(MarkMove.serializer(), MarkMove(game, strokes.take(MAX_STROKES).map { it.points }))

    fun decode(value: String?): Pair<Int?, List<TicTacToeStroke>> = value
        ?.let { runCatching { json.decodeFromString(MarkMove.serializer(), it) }.getOrNull() }
        ?.let { it.game to it.strokes.map(::TicTacToeStroke) } ?: (null to emptyList())

    /** A finger's stroke, simplified and rounded for the wire. */
    fun stroke(points: List<Offset>): TicTacToeStroke =
        TicTacToeStroke(Sketching.simplify(points, MAX_POINTS).map { listOf(round3(it.x), round3(it.y)) })

    /** Live ink's numbers: x then y for each point, rounded. */
    fun inkNumbers(points: List<Offset>): FloatArray = points.flatMap { listOf(it.x, it.y) }.map { (it * 1000).roundToLong() / 1000f }.toFloatArray()

    /**
     * A tap's mark, drawn quickly as if by hand: an X in two strokes or an O in one, wobbling a little differently on
     * each square and game. The same on both phones, since it goes to the server as strokes.
     */
    fun tapMark(x: Boolean, cell: Int, game: Int): List<TicTacToeStroke> {
        val seed = cell * 31 + game * 7
        fun wobble(i: Int) = (sin((seed + i) * 12.9898) * 43758.5453).let { it - kotlin.math.floor(it) }.toFloat() * 0.06f - 0.03f
        return if (x) listOf(
            line(Offset(0.24f + wobble(1), 0.22f + wobble(2)), Offset(0.78f + wobble(3), 0.8f + wobble(4))),
            line(Offset(0.77f + wobble(5), 0.23f + wobble(6)), Offset(0.22f + wobble(7), 0.78f + wobble(8))),
        ) else {
            // A loop from the top, round the left, back past where it began.
            val r = 0.29f + wobble(9)
            val points = (0..20).map { i ->
                val a = -PI / 2 - i / 20.0 * 2.15 * PI
                val rr = r * (1f + 0.06f * sin(i * 0.9 + seed).toFloat())
                Offset((0.5f + rr * cos(a).toFloat() * 1.05f).coerceIn(0f, 1f), (0.5f + rr * sin(a).toFloat()).coerceIn(0f, 1f))
            }
            listOf(stroke(points))
        }
    }

    private fun line(from: Offset, to: Offset) = stroke((0..6).map { i ->
        val t = i / 6f
        // A slight bow, the way a quick pen line curves.
        val bow = sin(t * PI).toFloat() * 0.025f
        Offset((from.x + (to.x - from.x) * t + bow).coerceIn(0f, 1f), (from.y + (to.y - from.y) * t - bow).coerceIn(0f, 1f))
    })

    fun points(stroke: TicTacToeStroke): List<Offset> = stroke.points.map { Offset(it.getOrElse(0) { 0.0 }.toFloat(), it.getOrElse(1) { 0.0 }.toFloat()) }

    private fun round3(value: Float) = (value * 1000).roundToLong() / 1000.0
}

/**
 * Sends my marks. Unlike the turn-by-turn games nothing waits: each mark goes at once, in parallel, and the server
 * judges it against the board as it is when it arrives. A `game.version_conflict` (two writes at the same instant)
 * reloads and sends it again while the square is still free; a mark for the next game that arrives a moment before
 * the server has started it is sent again shortly. A mark that doesn't land is taken off the board with a
 * [TooSlow], never an error note, and the pen is refilled at once. Extra strokes on my newest mark go after it as `ink`.
 */
class TicTacToeClaims(
    private val scope: CoroutineScope,
    private val ui: MutableStateFlow<GamesUi>,
    private val send: suspend (GameSession, String, String?, Int?) -> ApiResult<GameSession>,
    private val load: suspend (String) -> ApiResult<GameSession>,
    private val now: () -> Long = System::currentTimeMillis,
    private val pause: suspend (attempt: Int) -> Unit = { delay(120L * it) },
) {
    private var ids = 0L
    private val claims = mutableMapOf<Long, Job>()

    /** A move from the board: `place` (a new mark) or `ink` (another stroke on my newest mark). */
    fun act(session: GameSession, action: String, value: String?, index: Int?) {
        val cell = index ?: return
        val (game, strokes) = TicTacToeInk.decode(value)
        val on = game ?: GamePlay.ticTacToe(session).game
        when (action) {
            "place" -> place(session, on, cell, strokes)
            "ink" -> strokes.firstOrNull()?.let { addStroke(session, cell, it) }
        }
    }

    private fun place(session: GameSession, game: Int, cell: Int, strokes: List<TicTacToeStroke>) {
        val mark = PendingMark(session.id, game, cell, strokes, ++ids)
        val t = now()
        val cooldown = GamePlay.ticTacToe(session).cooldownMs.toLong()
        ui.update {
            it.copy(ttt = it.ttt.copy(pending = it.ttt.pending + mark, penFromMs = t, penReadyAtMs = t + cooldown,
                wet = WetMark(session.id, game, cell, mark.id, t + TicTacToeInk.WET_MS, strokes.size)))
        }
        claims[mark.id] = scope.launch { claim(mark) }
    }

    private fun addStroke(session: GameSession, cell: Int, stroke: TicTacToeStroke) {
        val wet = ui.value.ttt.wet?.takeIf { it.sessionId == session.id && it.cell == cell && now() <= it.untilMs && it.strokes < TicTacToeInk.MAX_STROKES } ?: return
        val extra = PendingMark(session.id, wet.game, cell, listOf(stroke), ++ids, extra = true)
        ui.update { it.copy(ttt = it.ttt.copy(pending = it.ttt.pending + extra, wet = wet.copy(strokes = wet.strokes + 1))) }
        val first = claims[wet.claimId]
        scope.launch {
            first?.join()
            ink(extra)
        }
    }

    private fun latest(id: String) = ui.value.sessions.firstOrNull { it.id == id }

    private suspend fun claim(mark: PendingMark) {
        try {
            var attempts = 0
            while (true) {
                val session = latest(mark.sessionId)?.takeIf { it.status == GameStatus.InProgress } ?: return bounce(mark, taken = false)
                when (val result = send(session, "place", TicTacToeInk.encode(mark.game, mark.strokes), mark.cell)) {
                    is ApiResult.Ok -> {
                        val board = GamePlay.ticTacToe(result.value)
                        val landed = board.game == mark.game && board.cells.getOrNull(mark.cell) != null
                        ui.update { it.withSession(result.value).copy(ttt = it.ttt.copy(pending = it.ttt.pending - mark)) }
                        if (!landed) bounce(mark, taken = true)
                        return
                    }
                    is ApiResult.Failure -> {
                        val fresh = reload(mark.sessionId)
                        val board = fresh?.let(GamePlay::ticTacToe)
                        val stillFree = fresh?.status == GameStatus.InProgress && board != null && board.phase == "playing" && board.game == mark.game && board.cells.getOrNull(mark.cell) == null
                        // The server hasn't started the next game yet (this phone's clock runs a little ahead): try again shortly.
                        val beatNotOver = fresh?.status == GameStatus.InProgress && board != null && board.phase == "gameOver" && board.game + 1 == mark.game
                        if ((result.code == GameMoveQueue.VERSION_CONFLICT && stillFree || beatNotOver) && attempts < MAX_RETRIES) {
                            attempts++
                            pause(attempts)
                            continue
                        }
                        val taken = board != null && (board.game != mark.game || board.phase != "playing" || board.cells.getOrNull(mark.cell) != null)
                        return bounce(mark, taken)
                    }
                    ApiResult.Offline -> {
                        bounce(mark, taken = false)
                        ui.update { it.copy(note = OFFLINE_MESSAGE) }
                        return
                    }
                }
            }
        } finally {
            claims.remove(mark.id)
        }
    }

    private suspend fun ink(extra: PendingMark) {
        repeat(2) {
            if (ui.value.ttt.pending.none { it.id == extra.id }) return
            val session = latest(extra.sessionId)?.takeIf { it.status == GameStatus.InProgress } ?: return drop(extra)
            when (val result = send(session, "ink", TicTacToeInk.encode(extra.game, extra.strokes), extra.cell)) {
                is ApiResult.Ok -> {
                    ui.update { it.withSession(result.value).copy(ttt = it.ttt.copy(pending = it.ttt.pending - extra)) }
                    return
                }
                is ApiResult.Failure -> if (result.code != GameMoveQueue.VERSION_CONFLICT) return drop(extra) else reload(extra.sessionId)
                ApiResult.Offline -> return drop(extra)
            }
        }
        drop(extra)
    }

    private fun drop(mark: PendingMark) = ui.update { it.copy(ttt = it.ttt.copy(pending = it.ttt.pending - mark)) }

    /** Takes the mark (and any strokes added to it) off the board, says why, and refills the pen: the server never counted it. */
    private fun bounce(mark: PendingMark, taken: Boolean) = ui.update {
        val t = it.ttt
        val gone: (PendingMark) -> Boolean = { p -> p.id == mark.id || p.extra && p.sessionId == mark.sessionId && p.game == mark.game && p.cell == mark.cell }
        it.copy(ttt = t.copy(
            pending = t.pending.filterNot(gone),
            tooSlow = TooSlow(mark.sessionId, mark.game, mark.cell, mark.id, taken),
            penReadyAtMs = if (t.wet?.claimId == mark.id) now() else t.penReadyAtMs,
            wet = t.wet?.takeIf { w -> w.claimId != mark.id },
        ))
    }

    private suspend fun reload(id: String): GameSession? {
        val found = (load(id) as? ApiResult.Ok)?.value ?: return latest(id)
        ui.update { it.withSession(found) }
        return latest(id)
    }

    companion object {
        const val MAX_RETRIES = 3
    }
}
