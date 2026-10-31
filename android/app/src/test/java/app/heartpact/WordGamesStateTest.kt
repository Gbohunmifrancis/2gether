package app.heartpact

import app.heartpact.data.ApiResult
import app.heartpact.data.DrawStroke
import app.heartpact.data.GameSession
import app.heartpact.data.GameStatus
import app.heartpact.data.GameType
import app.heartpact.data.OFFLINE_MESSAGE
import app.heartpact.ui.games.GameMoveQueue
import app.heartpact.ui.games.GamePlay
import app.heartpact.ui.games.GamesUi
import app.heartpact.ui.games.ICallOnSheet
import app.heartpact.ui.games.PendingMove
import app.heartpact.ui.games.Sketching
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Draw and guess through a dropped connection, and I call on's answers and verdicts crossing the partner's. */
@OptIn(ExperimentalCoroutinesApi::class)
class WordGamesStateTest {
    private val line = """{"points":[[0.2,0.2],[0.3,0.3]],"width":0.012}"""
    private val dot = """{"points":[[0.5,0.5]],"width":0.012}"""

    private fun drawing(version: Long, strokes: List<String> = emptyList(), round: Int = 0, wrong: List<String> = emptyList()) =
        GameSession("g", "c", GameType.Handwriting, GameStatus.InProgress, stateVersion = version,
            stateJson = """{"players":["maya","theo"],"round":$round,"drawer":"maya","strokes":[${strokes.joinToString(",")}],"wrongGuesses":[${wrong.joinToString(",") { "\"$it\"" }}]}""")

    @Test fun a_stroke_drawn_while_the_connection_drops_goes_once_it_is_back() = runTest {
        val ui = MutableStateFlow(GamesUi(listOf(drawing(1))))
        var server = drawing(1)
        var online = false
        var sent = 0
        val queue = GameMoveQueue(this, ui, send = { s, move ->
            sent++
            if (!online) ApiResult.Offline
            else if (s.stateVersion != server.stateVersion) ApiResult.Failure("game.version_conflict", "changed", 409)
            else ApiResult.Ok(drawing(s.stateVersion + 1, listOf(move.value!!)).also { server = it })
        }, load = { if (online) ApiResult.Ok(server) else ApiResult.Offline }, pause = {}, offlinePause = { if (it == 2) online = true })

        queue.add(ui.value.sessions.first(), "stroke", line)
        advanceUntilIdle()
        assertTrue(ui.value.pending.isEmpty())
        assertNull(ui.value.note)
        assertEquals(1, GamePlay.drawAndGuess(ui.value.sessions.first()).strokes.size)
        assertEquals(3, sent)
    }

    @Test fun a_stroke_that_landed_though_its_answer_was_lost_is_not_drawn_twice() = runTest {
        val ui = MutableStateFlow(GamesUi(listOf(drawing(1, listOf(dot)))))
        var server = drawing(1, listOf(dot))
        var sent = 0
        val queue = GameMoveQueue(this, ui, send = { s, move ->
            sent++
            // The first request reaches the server, but its answer never comes back.
            server = drawing(s.stateVersion + 1, listOf(dot, move.value!!))
            ApiResult.Offline
        }, load = { ApiResult.Ok(server) }, pause = {}, offlinePause = {})

        queue.add(ui.value.sessions.first(), "stroke", line)
        advanceUntilIdle()
        assertEquals(1, sent)
        assertTrue(ui.value.pending.isEmpty())
        assertEquals(2, GamePlay.drawAndGuess(ui.value.sessions.first()).strokes.size)
    }

    @Test fun an_undo_whose_answer_was_lost_is_not_undone_twice() {
        val before = drawing(4, listOf(dot, line))
        assertTrue(GameMoveQueue.landed(before, drawing(5, listOf(dot)), PendingMove("g", 0, "undo")))
        assertFalse(GameMoveQueue.landed(before, drawing(5, listOf(dot, line), wrong = listOf("cat")), PendingMove("g", 0, "undo")))
        assertTrue(GameMoveQueue.landed(before, drawing(5), PendingMove("g", 0, "clear")))
        assertTrue(GameMoveQueue.landed(before, drawing(5, wrong = listOf("Cat")), PendingMove("g", 0, "guess", "cat ")))
        assertFalse(GameMoveQueue.landed(before, drawing(5, listOf(dot, line, dot)), PendingMove("g", 0, "stroke", line)))
        assertTrue(GameMoveQueue.landed(before, drawing(5, listOf(dot, line, line)), PendingMove("g", 0, "stroke", line)))
    }

    @Test fun a_connection_that_stays_down_gives_up_and_says_so() = runTest {
        val ui = MutableStateFlow(GamesUi(listOf(drawing(1))))
        var sent = 0
        val queue = GameMoveQueue(this, ui, send = { _, _ -> sent++; ApiResult.Offline }, load = { ApiResult.Offline }, pause = {}, offlinePause = {})
        queue.add(ui.value.sessions.first(), "stroke", line)
        advanceUntilIdle()
        assertEquals(GameMoveQueue.MAX_OFFLINE_RETRIES + 1, sent)
        assertTrue(ui.value.pending.isEmpty())
        assertEquals(OFFLINE_MESSAGE, ui.value.note)
    }

    @Test fun a_stroke_draws_on_quickly_for_a_dot_and_about_a_second_across() {
        assertEquals(140, Sketching.drawOnMs(DrawStroke(listOf(listOf(0.5, 0.5)))))
        assertEquals(750, Sketching.drawOnMs(DrawStroke(listOf(listOf(0.0, 0.0), listOf(1.0, 1.0)))))
    }

    // I call on

    private fun iCallOn(version: Long, state: String) = GameSession("i", "c", GameType.ICallOn, GameStatus.InProgress, stateVersion = version, stateJson = state)
    private val answering = iCallOn(3, """{"players":["maya","theo"],"round":1,"letter":"B","categories":["Name","Place","Thing","Food"],"phase":"answering","submitted":[]}""")
    private val partnerSent = iCallOn(4, """{"players":["maya","theo"],"round":1,"letter":"B","categories":["Name","Place","Thing","Food"],"phase":"answering","submitted":["theo"]}""")
    private val judging = iCallOn(5, """{"players":["maya","theo"],"round":1,"letter":"B","phase":"vetting","pendingVetting":[
        {"player":"theo","judge":"maya","category":"Thing","answer":"Bumbershoot"},{"player":"theo","judge":"maya","category":"Food","answer":"Bammy"},
        {"player":"maya","judge":"theo","category":"Name","answer":"Bisi","allowed":true}]}""")
    private val nextRound = iCallOn(6, """{"players":["maya","theo"],"round":2,"letter":"T","phase":"answering","submitted":[]}""")

    private fun verdict(category: String) = """{"player":"theo","category":"$category","allow":true}"""

    @Test fun answers_that_crossed_the_partners_go_again_while_the_round_is_open() {
        assertTrue(GamePlay.iCallOnStillWanted(answering, partnerSent, "submit", "{}"))
        assertFalse(GamePlay.iCallOnStillWanted(answering, judging, "submit", "{}"))
        assertFalse(GamePlay.iCallOnStillWanted(answering, nextRound, "submit", "{}"))
    }

    @Test fun a_verdict_that_crossed_the_partners_goes_again_only_while_that_answer_waits_for_it() {
        val judgedOne = iCallOn(6, judging.stateJson.replace(""""answer":"Bumbershoot"}""", """"answer":"Bumbershoot","allowed":false}"""))
        assertTrue(GamePlay.iCallOnStillWanted(judging, judgedOne, "vet", verdict("Food")))
        assertFalse(GamePlay.iCallOnStillWanted(judging, judgedOne, "vet", verdict("Thing")))
        assertFalse(GamePlay.iCallOnStillWanted(judging, nextRound, "vet", verdict("Food")))
        assertFalse(GamePlay.iCallOnStillWanted(judging, judgedOne.copy(status = GameStatus.Completed), "vet", verdict("Food")))
        assertFalse(GamePlay.iCallOnStillWanted(judging, judgedOne, "vet", "not json"))
    }

    @Test fun the_answer_sheet_survives_a_round_trip_and_goes_out_trimmed_in_order() {
        val sheet = ICallOnSheet.write(mapOf("Food" to "Beans ", "Name" to "Bisi"))
        val answers = ICallOnSheet.read(sheet)
        assertEquals("Bisi", answers["Name"])
        val wire = Json.parseToJsonElement(ICallOnSheet.encode(listOf("Name", "Place", "Thing", "Food"), answers)).jsonObject
        assertEquals(listOf("Name", "Place", "Thing", "Food"), wire.keys.toList())
        assertEquals("Beans", wire["Food"]!!.jsonPrimitive.content)
        assertEquals("", wire["Place"]!!.jsonPrimitive.content)
        assertTrue(ICallOnSheet.read("garbage").isEmpty())
    }
}
