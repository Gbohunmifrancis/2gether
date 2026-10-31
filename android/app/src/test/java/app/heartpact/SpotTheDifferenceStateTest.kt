package app.heartpact

import app.heartpact.data.ApiResult
import app.heartpact.data.GameSession
import app.heartpact.data.GameStatus
import app.heartpact.data.GameType
import app.heartpact.ui.games.GamePlay
import app.heartpact.ui.games.GamesUi
import app.heartpact.ui.games.SpotSender
import app.heartpact.ui.games.score
import app.heartpact.ui.games.spot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpotTheDifferenceStateTest {
    private fun state(found: String = "", scene: Int = 0, phase: String = "spotting", scores: String = """{"maya":0,"theo":0}""") =
        """{"players":["maya","theo"],"phase":"$phase","scene":$scene,"totalScenes":3,"differences":7,"kind":"Picnic","seed":42,"found":[$found],
            "scenes":[{"index":0,"kind":"Picnic","seed":42,"found":[$found],"timedOut":false}],"scores":$scores}"""

    private fun session(version: Long, json: String = state(), status: GameStatus = GameStatus.InProgress) =
        GameSession("g", "c", GameType.SpotTheDifference, status, stateVersion = version, stateJson = json)

    private fun conflict() = ApiResult.Failure("game.version_conflict", "The game state changed.", 409)

    @Test fun reads_the_public_state() {
        val g = GamePlay.spot(session(1, state(found = """{"index":3,"by":"maya"}""", scores = """{"maya":1,"theo":0}""")))
        assertEquals("Picnic", g.kind)
        assertEquals(42, g.seed)
        assertEquals(3, g.found.single().index)
        assertEquals("maya", g.found.single().by)
        assertEquals(1, g.scores["maya"])
        assertEquals(7, g.differences)
    }

    @Test fun the_history_line_counts_both_partners_spots() {
        val done = session(9, """{"players":["maya","theo"],"phase":"completed","scene":2,"scores":{"maya":12,"theo":9},"winner":"maya"}""", GameStatus.Completed)
        assertEquals("You spotted 12, Theo 9", GamePlay.outcome(done, "maya", "Theo"))
        val score = GamePlay.score(done, "maya")!!
        assertTrue(score.won)
        assertEquals(12, score.mine)
        assertEquals(9, score.theirs)
    }

    @Test fun a_game_type_this_app_does_not_know_still_reads() {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }
        val sessions = json.decodeFromString<List<GameSession>>("""[{"id":"a","gameType":"moonBounce","status":"inProgress"},{"id":"b","gameType":"spotTheDifference","status":"completed"}]""")
        assertEquals(GameType.Unknown, sessions[0].gameType)
        assertEquals(GameType.SpotTheDifference, sessions[1].gameType)
        assertTrue(sessions[0].open)
    }

    @Test fun a_spot_meeting_a_conflict_is_sent_again_on_the_latest_board() = runTest {
        val ui = MutableStateFlow(GamesUi(listOf(session(1))))
        var server = session(2, state(found = """{"index":5,"by":"theo"}""")) // the partner's spot landed first
        val sent = mutableListOf<Pair<Long, Int>>()
        val spots = SpotSender(this, ui, send = { s, index ->
            sent += s.stateVersion to index
            if (s.stateVersion != server.stateVersion) conflict()
            else ApiResult.Ok(session(s.stateVersion + 1, state(found = """{"index":5,"by":"theo"},{"index":$index,"by":"maya"}""")).also { server = it })
        }, load = { ApiResult.Ok(server) }, pause = {})

        spots.spot(ui.value.sessions.first(), 2)
        advanceUntilIdle()
        assertEquals(listOf(1L to 2, 2L to 2), sent)
        assertNull(ui.value.note)
        assertEquals(2, GamePlay.spot(ui.value.sessions.first()).found.size)
    }

    @Test fun a_spot_the_partner_got_first_is_dropped_without_an_error() = runTest {
        val ui = MutableStateFlow(GamesUi(listOf(session(1))))
        val server = session(2, state(found = """{"index":4,"by":"theo"}"""))
        var sends = 0
        val spots = SpotSender(this, ui, send = { _, _ -> sends++; conflict() }, load = { ApiResult.Ok(server) }, pause = {})

        spots.spot(ui.value.sessions.first(), 4)
        advanceUntilIdle()
        // Reloaded after the conflict: theirs now, so it isn't sent again and nothing reads as an error.
        assertEquals(1, sends)
        assertNull(ui.value.note)
        assertEquals("theo", GamePlay.spot(ui.value.sessions.first()).found.single().by)
    }

    @Test fun spots_go_one_at_a_time_and_far_enough_apart() = runTest {
        val ui = MutableStateFlow(GamesUi(listOf(session(1))))
        var clock = 1_000L
        val waits = mutableListOf<Long>()
        var found = ""
        val spots = SpotSender(this, ui, send = { s, index ->
            found = if (found.isEmpty()) """{"index":$index,"by":"maya"}""" else """$found,{"index":$index,"by":"maya"}"""
            ApiResult.Ok(session(s.stateVersion + 1, state(found = found)))
        }, load = { ApiResult.Ok(ui.value.sessions.first()) }, gap = 320L, clock = { clock }, pause = { waits += it; clock += it })

        spots.spot(ui.value.sessions.first(), 0)
        spots.spot(ui.value.sessions.first(), 1)
        advanceUntilIdle()
        assertEquals(2, GamePlay.spot(ui.value.sessions.first()).found.size)
        assertEquals(listOf(320L), waits)
    }

    @Test fun a_refused_spot_says_why_unless_it_was_lost_to_the_partner() = runTest {
        val ui = MutableStateFlow(GamesUi(listOf(session(1))))
        var sends = 0
        val spots = SpotSender(this, ui, send = { _, _ -> sends++; ApiResult.Failure("game.invalid_action", "Not so fast: one spot at a time.", 422) },
            load = { ApiResult.Ok(session(1)) }, pause = {})
        spots.spot(ui.value.sessions.first(), 3)
        advanceUntilIdle()
        // One more go a gap later (spots can arrive closer together than they left), then it says why.
        assertEquals(2, sends)
        assertEquals("Not so fast: one spot at a time.", ui.value.note)
    }
}
