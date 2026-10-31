package app.heartpact

import androidx.compose.ui.geometry.Offset
import app.heartpact.data.ApiResult
import app.heartpact.data.GameSession
import app.heartpact.data.GameStatus
import app.heartpact.data.GameType
import app.heartpact.ui.games.GamePlay
import app.heartpact.ui.games.GamesUi
import app.heartpact.ui.games.LiveInk
import app.heartpact.ui.games.TicTacToeClaims
import app.heartpact.ui.games.TicTacToeInk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real-time tic-tac-toe on the phone: marks show at once, cross in flight, and un-draw with "Too slow!" (docs/games.md). */
@OptIn(ExperimentalCoroutinesApi::class)
class TicTacToeLiveTest {
    /** One letter per square, row by row: m for Maya, t for Theo, a dot for empty. */
    private fun session(board: String, version: Long, game: Int = 0, phase: String = "playing") = GameSession(
        "g", "c", GameType.TicTacToe, GameStatus.InProgress, currentTurnUserId = "maya", stateVersion = version,
        stateJson = """{"players":["maya","theo"],"cells":${board.map { if (it == 'm') "\"maya\"" else if (it == 't') "\"theo\"" else "null" }.joinToString(",", "[", "]")},"game":$game,"phase":"$phase","cooldownMs":650,"scores":{"maya":0,"theo":0},"results":[]}""",
    )

    private fun place(board: String, cell: Int, who: Char) = board.substring(0, cell) + who + board.substring(cell + 1)

    /** A server holding the board: a mark on a free square lands, a taken one is refused as the server refuses it. */
    private class Server(var board: GameSession) {
        val sent = mutableListOf<Triple<String, Int?, Long>>()
    }

    private fun TestScope.claims(ui: MutableStateFlow<GamesUi>, server: Server, clock: () -> Long = { 1_000L }, gate: CompletableDeferred<Unit>? = null) =
        TicTacToeClaims(this, ui, send = { s, action, _, index ->
            server.sent += Triple(action, index, s.stateVersion)
            gate?.await()
            val cells = GamePlay.ticTacToe(server.board).cells
            when {
                action == "place" && cells[index!!] != null -> ApiResult.Failure("game.invalid_action", "That square is taken.", 422)
                action == "place" -> {
                    val board = cells.joinToString("") { if (it == "maya") "m" else if (it == "theo") "t" else "." }
                    server.board = session(place(board, index!!, 'm'), server.board.stateVersion + 1)
                    ApiResult.Ok(server.board)
                }
                else -> ApiResult.Ok(server.board.copy(stateVersion = server.board.stateVersion + 1).also { server.board = it })
            }
        }, load = { ApiResult.Ok(server.board) }, now = clock, pause = {})

    @Test fun a_mark_shows_at_once_starts_the_pen_refilling_and_lands() = runTest {
        val ui = MutableStateFlow(GamesUi(listOf(session(".........", 1))))
        val server = Server(session(".........", 1))
        val gate = CompletableDeferred<Unit>()
        val claims = claims(ui, server, gate = gate)

        claims.act(ui.value.sessions.first(), "place", TicTacToeInk.encode(0, TicTacToeInk.tapMark(true, 4, 0)), 4)
        runCurrent()
        // On its way: on the board already, and the pen is refilling for the game's cooldown.
        assertEquals(listOf(4), ui.value.ttt.pending.map { it.cell })
        assertEquals(2, ui.value.ttt.pending.single().strokes.size)
        assertEquals(1_000L to 1_650L, ui.value.ttt.penFromMs to ui.value.ttt.penReadyAtMs)

        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(ui.value.ttt.pending.isEmpty())
        assertEquals("maya", GamePlay.ticTacToe(ui.value.sessions.first()).cells[4])
        assertNull(ui.value.ttt.tooSlow)
    }

    @Test fun marks_cross_in_flight_without_waiting_for_each_other() = runTest {
        val ui = MutableStateFlow(GamesUi(listOf(session(".........", 1))))
        val server = Server(session(".........", 1))
        val gate = CompletableDeferred<Unit>()
        val claims = claims(ui, server, gate = gate)
        val s = ui.value.sessions.first()
        claims.act(s, "place", null, 0)
        claims.act(s, "place", null, 8)
        runCurrent()
        // Both are already on their way, made on the same version.
        assertEquals(listOf(Triple("place", 0, 1L), Triple("place", 8, 1L)), server.sent)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("maya", null, null, null, null, null, null, null, "maya"), GamePlay.ticTacToe(ui.value.sessions.first()).cells)
    }

    @Test fun a_square_the_partner_got_first_un_draws_with_too_slow_and_no_error_note() = runTest {
        val ui = MutableStateFlow(GamesUi(listOf(session(".........", 1))))
        val server = Server(session("....t....", 2)) // Theo's mark reached the server first
        val claims = claims(ui, server)
        claims.act(ui.value.sessions.first(), "place", null, 4)
        advanceUntilIdle()

        assertTrue(ui.value.ttt.pending.isEmpty())
        val slow = ui.value.ttt.tooSlow!!
        assertEquals(4, slow.cell)
        assertTrue(slow.taken)
        assertNull(ui.value.note)
        // The server never counted it, so the pen is ready again straight away.
        assertEquals(1_000L, ui.value.ttt.penReadyAtMs)
        // And the board now shows Theo's mark.
        assertEquals("theo", GamePlay.ticTacToe(ui.value.sessions.first()).cells[4])
    }

    @Test fun a_version_conflict_reloads_and_claims_again_while_the_square_is_free() = runTest {
        val ui = MutableStateFlow(GamesUi(listOf(session(".........", 1))))
        val server = Server(session("t........", 2))
        var conflicts = 1
        val claims = TicTacToeClaims(this, ui, send = { s, _, _, index ->
            server.sent += Triple("place", index, s.stateVersion)
            if (conflicts-- > 0) ApiResult.Failure("game.version_conflict", "Your partner just updated the game.", 409)
            else ApiResult.Ok(session("t...m....", 3).also { server.board = it })
        }, load = { ApiResult.Ok(server.board) }, now = { 0L }, pause = {})
        claims.act(ui.value.sessions.first(), "place", null, 4)
        advanceUntilIdle()
        assertEquals(listOf(1L, 2L), server.sent.map { it.third })
        assertEquals("maya", GamePlay.ticTacToe(ui.value.sessions.first()).cells[4])
        assertNull(ui.value.ttt.tooSlow)
    }

    @Test fun a_version_conflict_on_a_square_now_taken_is_too_slow() = runTest {
        val ui = MutableStateFlow(GamesUi(listOf(session(".........", 1))))
        val server = Server(session("....t....", 2))
        val claims = TicTacToeClaims(this, ui, send = { _, _, _, _ -> ApiResult.Failure("game.version_conflict", "Changed.", 409) },
            load = { ApiResult.Ok(server.board) }, now = { 0L }, pause = {})
        claims.act(ui.value.sessions.first(), "place", null, 4)
        advanceUntilIdle()
        assertTrue(ui.value.ttt.tooSlow!!.taken)
        assertTrue(ui.value.ttt.pending.isEmpty())
    }

    @Test fun a_second_stroke_while_the_ink_is_wet_follows_its_mark_as_ink() = runTest {
        val ui = MutableStateFlow(GamesUi(listOf(session(".........", 1))))
        val server = Server(session(".........", 1))
        val gate = CompletableDeferred<Unit>()
        val claims = claims(ui, server, gate = gate)
        val s = ui.value.sessions.first()
        val slash = TicTacToeInk.stroke(listOf(Offset(0.1f, 0.1f), Offset(0.5f, 0.5f), Offset(0.9f, 0.9f)))
        val back = TicTacToeInk.stroke(listOf(Offset(0.9f, 0.1f), Offset(0.5f, 0.5f), Offset(0.1f, 0.9f)))
        claims.act(s, "place", TicTacToeInk.encode(0, listOf(slash)), 4)
        claims.act(s, "ink", TicTacToeInk.encode(0, listOf(back)), 4)
        runCurrent()
        // The second line waits for the first to land, showing meanwhile.
        assertEquals(listOf("place"), server.sent.map { it.first })
        assertEquals(listOf(false, true), ui.value.ttt.pending.map { it.extra })
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("place", "ink"), server.sent.map { it.first })
        assertTrue(ui.value.ttt.pending.isEmpty())

        // A stroke on another square isn't part of the mark: it isn't sent.
        claims.act(ui.value.sessions.first(), "ink", TicTacToeInk.encode(0, listOf(back)), 3)
        advanceUntilIdle()
        assertEquals(2, server.sent.size)
    }

    @Test fun live_ink_builds_strokes_by_square_and_fades() {
        val ink = LiveInk()
        ink.receive("g", 4, 1, listOf(0.1f, 0.1f, 0.2f, 0.2f), 0)
        ink.receive("g", 4, 1, listOf(0.3f, 0.3f), 50)
        ink.receive("g", 4, 2, listOf(0.9f, 0.1f), 100)
        val trail = ink.trails[4]!!
        assertEquals(listOf(3, 1), trail.strokes.map { it.size })
        assertEquals(100L, trail.atMs)

        ink.prune(100 + LiveInk.FADE_MS, "g")
        assertTrue(ink.trails.containsKey(4))
        ink.prune(101 + LiveInk.FADE_MS, "g")
        assertFalse(ink.trails.containsKey(4))

        ink.receive("g", 2, 1, listOf(0.5f, 0.5f), 0)
        ink.receive("g", -1, 0, emptyList(), 10)
        assertTrue(ink.trails.isEmpty())
        // Another game's ink goes as soon as the board moves on.
        ink.receive("old", 2, 1, listOf(0.5f, 0.5f), 0)
        ink.prune(1, "g")
        assertTrue(ink.trails.isEmpty())
    }

    @Test fun marks_on_the_wire_stay_inside_the_servers_limits() {
        val scribble = (0 until 500).map { Offset((it % 50) / 50f, (it / 10) / 50f) }
        val stroke = TicTacToeInk.stroke(scribble)
        assertTrue(stroke.points.size <= TicTacToeInk.MAX_POINTS)
        assertTrue(stroke.points.flatten().all { it in 0.0..1.0 })
        val value = TicTacToeInk.encode(2, listOf(stroke, stroke, stroke, stroke))
        assertTrue(value.length < 4000)
        val (game, strokes) = TicTacToeInk.decode(value)
        assertEquals(2, game)
        assertEquals(TicTacToeInk.MAX_STROKES, strokes.size)

        assertEquals(2, TicTacToeInk.tapMark(true, 0, 0).size)
        assertEquals(1, TicTacToeInk.tapMark(false, 0, 0).size)
        assertEquals(TicTacToeInk.tapMark(false, 5, 1), TicTacToeInk.tapMark(false, 5, 1))
        assertTrue((TicTacToeInk.tapMark(true, 3, 2) + TicTacToeInk.tapMark(false, 3, 2)).all { s -> s.points.flatten().all { it in 0.0..1.0 } })

        assertTrue(TicTacToeInk.isX(listOf("maya", "theo"), "maya"))
        assertFalse(TicTacToeInk.isX(listOf("maya", "theo"), "theo"))
        assertEquals(64, TicTacToeInk.inkNumbers(scribble.take(TicTacToeInk.INK_POINTS)).size)
    }
}
