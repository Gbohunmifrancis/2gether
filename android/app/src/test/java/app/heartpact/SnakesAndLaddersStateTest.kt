package app.heartpact

import androidx.compose.ui.geometry.Offset
import app.heartpact.data.GameSession
import app.heartpact.data.GameStatus
import app.heartpact.data.GameType
import app.heartpact.data.SnakesEvent
import app.heartpact.data.SnakesLink
import app.heartpact.data.SnakesMove
import app.heartpact.ui.games.DieMath
import app.heartpact.ui.games.GamePlay
import app.heartpact.ui.games.SnakesBeat
import app.heartpact.ui.games.SnakesGeometry
import app.heartpact.ui.games.SnakesReplay
import app.heartpact.ui.games.lastLine
import app.heartpact.ui.games.score
import app.heartpact.ui.games.snakes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class SnakesAndLaddersStateTest {
    private val board = """{"ladders":[{"from":3,"to":22},{"from":50,"to":91}],"snakes":[{"from":17,"to":4},{"from":99,"to":80}],
        "hearts":[{"square":6,"kind":"shield"},{"square":8,"kind":"swap"},{"square":10,"kind":"boost"},{"square":30,"kind":"heartbreak"}]}"""

    private fun state(maya: Int, theo: Int, last: String = "null", phase: String = "playing", winner: String = "null", turn: String = "\"theo\"", extra: String = "") =
        """{"players":["maya","theo"],"phase":"$phase","goal":100,"first":"maya","turn":$turn,"board":$board,
            "positions":{"maya":$maya,"theo":$theo},"shields":{"maya":false,"theo":true},
            "stats":{"maya":{"rolls":12,"ladders":2,"snakes":1,"sixes":3,"swaps":1,"saves":0},"theo":{"rolls":11,"ladders":1,"snakes":3,"sixes":1,"swaps":0,"saves":1}},
            "sixes":0,"moves":23,"last":$last,"winner":$winner$extra}"""

    private fun session(json: String, status: GameStatus = GameStatus.InProgress) =
        GameSession("g", "c", GameType.SnakesAndLadders, status, currentTurnUserId = "theo", stateVersion = 9, stateJson = json)

    private fun move(player: String, roll: Int, from: Int, steps: List<Int>, events: List<SnakesEvent>, to: Int, n: Int = 1) =
        SnakesMove(n, player, roll, from, steps, events, to)

    @Test fun reads_the_public_state() {
        val last = """{"move":23,"player":"maya","roll":3,"from":0,"steps":[1,2,3],"events":[{"kind":"ladder","from":3,"to":22}],"to":22,"extraTurn":false,"sixes":0}"""
        val g = GamePlay.snakes(session(state(22, 40, last)))
        assertEquals(listOf("maya", "theo"), g.players)
        assertEquals("theo", g.turn)
        assertEquals(22, g.positions["maya"])
        assertEquals(true, g.shields["theo"])
        assertEquals(2, g.board.ladders.size)
        assertEquals(SnakesLink(17, 4), g.board.snakes[0])
        assertEquals("shield", g.board.hearts[0].kind)
        assertEquals(12, g.stats["maya"]?.rolls)
        assertEquals(1, g.stats["theo"]?.saves)
        val m = g.last!!
        assertEquals(23, m.move)
        assertEquals(listOf(1, 2, 3), m.steps)
        assertEquals("ladder", m.events.single().kind)
        assertFalse(g.finished)
    }

    @Test fun unknown_fields_and_junk_never_break_the_board() {
        val g = GamePlay.snakes(session(state(5, 7, extra = ""","futureThing":{"a":[1,2]},"weather":"sunny"""")))
        assertEquals(5, g.positions["maya"])
        // A junk state reads as an empty one, not a crash.
        val junk = GamePlay.snakes(session("{not json"))
        assertTrue(junk.players.isEmpty())
        assertNull(junk.last)
        // An event kind this app doesn't know is skipped in the replay.
        val beats = SnakesReplay.beats(move("maya", 2, 0, listOf(1, 2), listOf(SnakesEvent("rainbow", 2, 9)), 9), "theo")
        assertEquals(2, beats.size)
    }

    @Test fun squares_snake_up_the_board_row_by_row() {
        assertEquals(Offset(0.5f, 9.5f), SnakesGeometry.center(1))
        assertEquals(Offset(9.5f, 9.5f), SnakesGeometry.center(10))
        // Row two runs right to left.
        assertEquals(Offset(9.5f, 8.5f), SnakesGeometry.center(11))
        assertEquals(Offset(0.5f, 8.5f), SnakesGeometry.center(20))
        assertEquals(Offset(0.5f, 0.5f), SnakesGeometry.center(100))
        // 0 is the start strip under the board.
        assertTrue(SnakesGeometry.center(0).y > 10f)
        // A snake's curve starts at its head and ends at its tail, whatever the wiggle.
        val snake = SnakesLink(87, 24)
        for (sway in listOf(0f, 1f, 2.5f)) {
            assertEquals(SnakesGeometry.center(87).x, SnakesGeometry.onSnake(snake, 3, 0f, sway).x, 0.001f)
            assertEquals(SnakesGeometry.center(24).y, SnakesGeometry.onSnake(snake, 3, 1f, sway).y, 0.001f)
        }
    }

    @Test fun a_roll_becomes_hops_then_its_events_in_order() {
        val m = move("maya", 5, 1, listOf(2, 3, 4, 5, 6), listOf(SnakesEvent("hug", 6, 6)), 6)
        val beats = SnakesReplay.beats(m, "theo")
        assertEquals(listOf(SnakesBeat.Hop("maya", 1, 2, false), SnakesBeat.Hop("maya", 2, 3, false), SnakesBeat.Hop("maya", 3, 4, false),
            SnakesBeat.Hop("maya", 4, 5, false), SnakesBeat.Hop("maya", 5, 6, false), SnakesBeat.Hug("maya", 6)), beats)

        val climb = SnakesReplay.beats(move("theo", 3, 0, listOf(1, 2, 3), listOf(SnakesEvent("ladder", 3, 22)), 22), "maya")
        assertEquals(SnakesBeat.Climb("theo", 3, 22), climb.last())
        val slide = SnakesReplay.beats(move("theo", 2, 15, listOf(16, 17), listOf(SnakesEvent("snake", 17, 4)), 4), "maya")
        assertEquals(SnakesBeat.Slide("theo", 17, 4), slide.last())
        assertEquals(listOf(4), listOf(SnakesReplay.after(slide, mapOf("theo" to 15))["theo"]))
    }

    @Test fun a_bounce_off_100_hops_back_and_can_land_on_a_snake() {
        val m = move("maya", 3, 98, listOf(99, 100, 99), listOf(SnakesEvent("bounce", 100, 99), SnakesEvent("snake", 99, 80)), 80)
        val beats = SnakesReplay.beats(m, "theo")
        assertEquals(listOf(SnakesBeat.Hop("maya", 98, 99, false), SnakesBeat.Hop("maya", 99, 100, false), SnakesBeat.Hop("maya", 100, 99, true), SnakesBeat.Slide("maya", 99, 80)), beats)
        assertEquals(80, SnakesReplay.after(beats, mapOf("maya" to 98, "theo" to 50))["maya"])
    }

    @Test fun a_swap_sends_both_tokens_past_each_other_and_starts_from_where_the_partner_was() {
        val m = move("maya", 3, 5, listOf(6, 7, 8), listOf(SnakesEvent("swap", 8, 40)), 40)
        // After the move the partner is on 8, but the replay starts with them still on 40.
        val start = SnakesReplay.before(m, "theo", partnerNow = 8)
        assertEquals(mapOf("maya" to 5, "theo" to 40), start)
        val beats = SnakesReplay.beats(m, "theo")
        assertEquals(SnakesBeat.Swap("maya", "theo", 8, 40), beats.last())
        assertEquals(mapOf("maya" to 40, "theo" to 8), SnakesReplay.after(beats, start))
        // Mid-swap they fly opposite ways, one over and one under.
        val beat = beats.last()
        val half = mapOf("maya" to 8, "theo" to 40)
        val mover = SnakesReplay.tokenAt(beat, 0.5f, "maya", half, emptyList())
        val partner = SnakesReplay.tokenAt(beat, 0.5f, "theo", half, emptyList())
        assertTrue(mover.lift > 0f)
        assertTrue(partner.lift < 0f)
    }

    @Test fun every_kind_of_move_ends_where_the_server_says() {
        val moves = listOf(
            move("maya", 3, 7, listOf(8, 9, 10), listOf(SnakesEvent("boost", 10, 13)), 13),
            move("maya", 3, 27, listOf(28, 29, 30), listOf(SnakesEvent("heartbreak", 30, 25)), 25),
            move("maya", 2, 15, listOf(16, 17), listOf(SnakesEvent("shield", 17, 17)), 17),
            move("maya", 3, 5, listOf(6, 7, 8), listOf(SnakesEvent("kiss", 8, 8)), 8),
            move("maya", 6, 52, emptyList(), listOf(SnakesEvent("threeSixes", 52, 40)), 40),
            move("maya", 6, 94, listOf(95, 96, 97, 98, 99, 100), listOf(SnakesEvent("win", 100, 100)), 100),
        )
        moves.forEach { m ->
            val beats = SnakesReplay.beats(m, "theo")
            assertEquals(m.to, SnakesReplay.after(beats, SnakesReplay.before(m, "theo", 3))["maya"])
            assertTrue(SnakesReplay.durationMs(beats) in 300..9000)
        }
        assertTrue(SnakesReplay.beats(moves[4], "theo").single() is SnakesBeat.ThreeSixes)
        assertTrue(SnakesReplay.beats(moves[5], "theo").last() is SnakesBeat.Win)
    }

    @Test fun a_move_is_replayed_once_and_never_one_already_on_screen() {
        val m = move("theo", 4, 0, listOf(1, 2, 3, 4), emptyList(), 4, n = 7)
        assertTrue(SnakesReplay.shouldReplay(6, m))
        assertFalse(SnakesReplay.shouldReplay(7, m))
        assertFalse(SnakesReplay.shouldReplay(9, m))
        assertFalse(SnakesReplay.shouldReplay(0, null))
    }

    @Test fun a_hop_arcs_and_lands_and_a_slide_rides_the_snake() {
        val hop = SnakesBeat.Hop("maya", 3, 4, false)
        val mid = SnakesReplay.tokenAt(hop, 0.5f, "maya", mapOf("maya" to 3), emptyList())
        assertTrue(mid.lift > 0.4f)
        assertEquals(SnakesGeometry.center(4), SnakesReplay.tokenAt(hop, 1f, "maya", mapOf("maya" to 3), emptyList()).at)
        val snake = SnakesLink(17, 4)
        val slide = SnakesBeat.Slide("maya", 17, 4)
        val end = SnakesReplay.tokenAt(slide, 1f, "maya", mapOf("maya" to 17), listOf(snake))
        assertEquals(SnakesGeometry.center(4).x, end.at.x, 0.01f)
        assertEquals(SnakesGeometry.center(4).y, end.at.y, 0.01f)
        val halfway = SnakesReplay.tokenAt(slide, 0.6f, "maya", mapOf("maya" to 17), listOf(snake))
        val onCurve = (0..100).map { SnakesGeometry.onSnake(snake, 0, it / 100f) }.minOf { (it - halfway.at).getDistance() }
        assertTrue(onCurve < 0.05f)
        // Whoever isn't moving stays put.
        assertEquals(SnakesGeometry.center(50), SnakesReplay.tokenAt(slide, 0.6f, "theo", mapOf("maya" to 17, "theo" to 50), listOf(snake)).at)
    }

    @Test fun the_die_lands_on_the_servers_number() {
        for (face in 1..6) {
            val (x, y, z) = DieMath.rest(face)
            assertEquals(face, DieMath.facing(x, y, z))
            // From any spin, the landing angles show the same face, and are ahead of where it was.
            val from = Triple(3.3f * face, 1.7f * face, 0.4f * face)
            val (lx, ly, lz) = DieMath.landing(from, face, turns = 1)
            assertEquals(face, DieMath.facing(lx, ly, lz))
            assertTrue(lx >= from.first + 2 * PI.toFloat() - 0.001f)
            assertTrue(lz >= from.third)
        }
    }

    @Test fun the_history_line_and_the_hub_line() {
        val done = session(state(100, 87, phase = "completed", winner = "\"maya\"", turn = "null"), GameStatus.Completed)
        assertEquals("You won on square 100, Theo was on 87", GamePlay.outcome(done, "maya", "Theo"))
        assertEquals("Maya won on square 100, you were on 87", GamePlay.outcome(done, "theo", "Maya"))
        assertEquals("Last time, Snakes and ladders: You won on square 100, Theo was on 87", GamePlay.lastLine(done, "maya", "Theo"))
        // The board celebrates on its own; the shared result card stays out of the way.
        assertNull(GamePlay.score(done, "maya"))
        assertEquals("You're on 34, Theo on 52", GamePlay.outcome(session(state(34, 52)), "maya", "Theo"))
        assertEquals("Snakes and ladders", GamePlay.title(GameType.SnakesAndLadders))
        assertTrue(GameType.SnakesAndLadders in GamePlay.offered)
    }

    @Test fun an_older_app_reads_the_type_as_unknown_and_this_one_knows_it() {
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        val s = json.decodeFromJsonElement<GameSession>(json.parseToJsonElement("""{"id":"g","gameType":"snakesAndLadders","status":"inProgress"}""").jsonObject)
        assertEquals(GameType.SnakesAndLadders, s.gameType)
    }
}
