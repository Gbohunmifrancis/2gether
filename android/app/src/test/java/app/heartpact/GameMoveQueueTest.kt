package app.heartpact

import androidx.compose.ui.geometry.Offset
import app.heartpact.data.ApiResult
import app.heartpact.data.DrawStroke
import app.heartpact.data.GameSession
import app.heartpact.data.GameStatus
import app.heartpact.data.GameType
import app.heartpact.ui.games.GameMoveQueue
import app.heartpact.ui.games.GamesUi
import app.heartpact.ui.games.PendingMove
import app.heartpact.ui.games.Sketching
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalCoroutinesApi::class)
class GameMoveQueueTest {
    private fun session(version: Long, round: Int = 0, status: GameStatus = GameStatus.InProgress) =
        GameSession("g", "c", GameType.Handwriting, status, stateVersion = version, stateJson = """{"players":["maya","theo"],"round":$round,"drawer":"maya"}""")

    private fun conflict() = ApiResult.Failure("game.version_conflict", "The game state changed.", 409)

    @Test fun moves_go_one_at_a_time_in_order_on_the_latest_version() = runTest {
        val ui = MutableStateFlow(GamesUi(listOf(session(1))))
        val sent = mutableListOf<Pair<Long, String>>()
        val first = CompletableDeferred<Unit>()
        val queue = GameMoveQueue(this, ui, send = { s, move ->
            sent += s.stateVersion to move.action
            if (sent.size == 1) first.await()
            ApiResult.Ok(session(s.stateVersion + 1))
        }, load = { ApiResult.Ok(ui.value.sessions.first()) }, pause = {})

        queue.add(ui.value.sessions.first(), "stroke", "a")
        queue.add(ui.value.sessions.first(), "stroke", "b")
        queue.add(ui.value.sessions.first(), "undo", null)
        runCurrent()
        // The first stroke is in flight; the others wait their turn and show on the board meanwhile.
        assertEquals(listOf(1L to "stroke"), sent)
        assertEquals(3, ui.value.pending.size)
        first.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(1L to "stroke", 2L to "stroke", 3L to "undo"), sent)
        assertTrue(ui.value.pending.isEmpty())
        assertEquals(4L, ui.value.sessions.first().stateVersion)
    }

    @Test fun a_version_conflict_reloads_and_sends_the_same_move_again() = runTest {
        val ui = MutableStateFlow(GamesUi(listOf(session(1))))
        var server = session(2) // the partner's guess landed first
        val sent = mutableListOf<Long>()
        val queue = GameMoveQueue(this, ui, send = { s, _ ->
            sent += s.stateVersion
            if (s.stateVersion != server.stateVersion) conflict() else ApiResult.Ok(session(s.stateVersion + 1).also { server = it })
        }, load = { ApiResult.Ok(server) }, pause = {})

        queue.add(ui.value.sessions.first(), "stroke", "a")
        advanceUntilIdle()
        assertEquals(listOf(1L, 2L), sent)
        assertTrue(ui.value.pending.isEmpty())
        assertNull(ui.value.note)
        assertEquals(3L, ui.value.sessions.first().stateVersion)
    }

    @Test fun retries_are_bounded() = runTest {
        val ui = MutableStateFlow(GamesUi(listOf(session(1))))
        var attempts = 0
        val queue = GameMoveQueue(this, ui, send = { _, _ -> attempts++; conflict() }, load = { ApiResult.Ok(session(1)) }, pause = {})
        queue.add(ui.value.sessions.first(), "guess", "cat")
        queue.add(ui.value.sessions.first(), "guess", "dog")
        advanceUntilIdle()
        assertEquals(GameMoveQueue.MAX_RETRIES + 1, attempts)
        assertTrue(ui.value.pending.isEmpty())
        assertEquals("The game state changed.", ui.value.note)
    }

    @Test fun a_move_for_a_round_that_ended_is_dropped() = runTest {
        val ui = MutableStateFlow(GamesUi(listOf(session(1, round = 0))))
        var sent = 0
        val queue = GameMoveQueue(this, ui, send = { _, _ -> sent++; conflict() }, load = { ApiResult.Ok(session(2, round = 1)) }, pause = {})
        queue.add(ui.value.sessions.first(), "stroke", "a")
        advanceUntilIdle()
        // One try, a conflict, and the reload shows the round is over: nothing more is sent and nothing complains.
        assertEquals(1, sent)
        assertTrue(ui.value.pending.isEmpty())
        assertNull(ui.value.note)
    }

    @Test fun a_refused_move_drops_whatever_is_queued_behind_it() = runTest {
        val ui = MutableStateFlow(GamesUi(listOf(session(1))))
        var sent = 0
        val queue = GameMoveQueue(this, ui, send = { _, _ -> sent++; ApiResult.Failure("game.invalid_action", "That stroke couldn't be drawn.", 422) },
            load = { ApiResult.Ok(session(1)) }, pause = {})
        queue.add(ui.value.sessions.first(), "stroke", "a")
        queue.add(ui.value.sessions.first(), "stroke", "b")
        advanceUntilIdle()
        assertEquals(1, sent)
        assertTrue(ui.value.pending.isEmpty())
        assertEquals("That stroke couldn't be drawn.", ui.value.note)
    }

    @Test fun strokes_are_simplified_under_the_limit_and_encoded_for_the_wire() {
        val scribble = (0 until 2000).map { i -> val a = i / 40.0; Offset((0.5 + 0.4 * cos(a) * (i / 2000.0)).toFloat(), (0.5 + 0.4 * sin(a) * (i / 2000.0)).toFloat()) }
        val encoded = Sketching.encode(scribble, Sketching.PEN_WIDTH)
        val points = Json.parseToJsonElement(encoded).jsonObject["points"]!!.jsonArray
        assertTrue(points.size in 2..Sketching.MAX_POINTS)
        assertTrue(encoded.length < 4000)
        assertEquals(setOf("points", "width"), Json.parseToJsonElement(encoded).jsonObject.keys)

        val straight = (0..100).map { Offset(it / 100f, 0.5f) }
        assertEquals(2, Sketching.simplify(straight).size)
        assertEquals(1, Sketching.simplify(listOf(Offset(0.2f, 0.2f))).size)
    }

    @Test fun pending_moves_show_on_top_of_the_servers_drawing() {
        val server = listOf(DrawStroke(listOf(listOf(0.1, 0.1)), 0.01))
        val line = """{"points":[[0.2,0.2],[0.3,0.3]],"width":0.012}"""
        fun move(action: String, value: String? = null) = PendingMove("g", 0, action, value)
        assertEquals(2, Sketching.withPending(server, listOf(move("stroke", line))).size)
        assertEquals(1, Sketching.withPending(server, listOf(move("stroke", line), move("undo"))).size)
        assertEquals(0, Sketching.withPending(server, listOf(move("clear"))).size)
        assertEquals(1, Sketching.withPending(server, listOf(move("clear"), move("stroke", line))).size)
    }
}
