package app.heartpact

import app.heartpact.data.GameHistoryItem
import app.heartpact.data.GameSession
import app.heartpact.data.GameStatus
import app.heartpact.data.GameType
import app.heartpact.data.GridMatchState
import app.heartpact.data.SkillDuelState
import app.heartpact.ui.games.GamePlay
import app.heartpact.ui.games.GamesUi
import app.heartpact.ui.games.resendable
import app.heartpact.ui.games.scoreJson
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/games.md: the history past the last sessions, Connect four's next game, Heart to heart's rounds dealt again. */
class GameTurnsTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun finished(id: String, endedAt: String, type: GameType = GameType.MemoryMatch) =
        GameSession(id, "c", type, GameStatus.Completed, endedAtUtc = endedAt, stateJson = "{}")

    @Test fun the_history_reaches_past_the_sessions_and_a_game_just_finished_tops_it() {
        val older = finished("old", "2026-10-01T10:00:00Z")
        val newer = finished("new", "2026-10-20T10:00:00Z")
        val justNow = finished("now", "2026-10-31T10:00:00Z", GameType.ConnectFour)
        // The sessions hold only invites nobody took up, and the game just finished; the history has the two before.
        val invites = (1..5).map { GameSession("i$it", "c", GameType.Trivia, GameStatus.Abandoned) }
        val ui = GamesUi(sessions = listOf(justNow) + invites, played = listOf(newer, older, justNow))

        assertEquals(listOf("now", "new", "old"), ui.history.map { it.id })
        // An older server has no history: the finished sessions, as before.
        assertEquals(listOf("now"), GamesUi(sessions = listOf(justNow) + invites).history.map { it.id })
        // A game ended part way is in the history list but not shown as played.
        assertEquals(listOf("now"), GamesUi(sessions = listOf(justNow), played = listOf(GameSession("x", "c", GameType.MemoryMatch, GameStatus.Abandoned))).history.map { it.id })
    }

    @Test fun a_history_item_reads_with_its_game() {
        val item = json.decodeFromString(GameHistoryItem.serializer(), """
            {"sessionId":"s","gameType":"skillDuel","outcome":"completed","winnerUserId":"maya","scores":{"maya":3,"theo":2},"roundsPlayed":5,
             "startedAtUtc":"2026-10-30T10:00:00Z","endedAtUtc":"2026-10-30T10:20:00Z",
             "session":{"id":"s","gameType":"skillDuel","status":"completed","stateJson":"{}"}}""")
        assertEquals(GameStatus.Completed, item.session?.status)
        assertEquals(3, item.scores["maya"])
        // A game type from a newer server still reads.
        assertEquals(GameType.Unknown, Json { ignoreUnknownKeys = true; coerceInputValues = true }
            .decodeFromString(GameHistoryItem.serializer(), """{"sessionId":"s","gameType":"bowling","outcome":"completed"}""").gameType)
    }

    private fun connectFour(phase: String, game: Int, cells: List<String?>, turn: String, version: Long, next: String? = null) = GameSession(
        "c4", "c", GameType.ConnectFour, GameStatus.InProgress, currentTurnUserId = turn, stateVersion = version,
        stateJson = """{"players":["maya","theo"],"columns":7,"rows":6,"cells":${cells.joinToString(",", "[", "]") { it?.let { o -> "\"$o\"" } ?: "null" }},"game":$game,"totalGames":3,"winsNeeded":2,"phase":"$phase","scores":{"maya":1,"theo":0},"results":["maya"]${next?.let { ",\"nextGameAtUtc\":\"$it\"" } ?: ""}}""",
    )

    @Test fun connect_four_reads_the_next_games_start() {
        val beat = connectFour("gameOver", 0, List(42) { if (it == 35) "maya" else null }, "theo", 9, "2026-10-31T18:00:01.5Z")
        val g: GridMatchState = GamePlay.grid(beat)
        assertEquals("2026-10-31T18:00:01.5Z", g.nextGameAtUtc)
        assertNull(GamePlay.grid(connectFour("playing", 0, List(42) { null }, "maya", 1)).nextGameAtUtc)
    }

    @Test fun the_first_disc_of_the_next_game_goes_again_when_the_server_dealt_the_board_first() {
        val beat = connectFour("gameOver", 0, List(42) { if (it == 35) "maya" else null }, "theo", 9, "2026-10-31T18:00:01.5Z")
        val fresh = connectFour("playing", 1, List(42) { null }, "theo", 10)
        assertTrue(GamePlay.resendable(beat, fresh))
        // Not once a disc is down, nor on someone else's turn, nor mid-game.
        assertFalse(GamePlay.resendable(beat, connectFour("playing", 1, List(42) { if (it == 38) "theo" else null }, "maya", 11)))
        assertFalse(GamePlay.resendable(beat, connectFour("playing", 1, List(42) { null }, "maya", 10)))
        assertFalse(GamePlay.resendable(connectFour("playing", 0, List(42) { null }, "theo", 3), connectFour("playing", 0, List(42) { if (it == 38) "maya" else null }, "theo", 4)))
    }

    @Test fun a_duel_round_carries_my_own_seed_and_a_score_names_it() {
        val d = json.decodeFromString(SkillDuelState.serializer(), """{"players":["maya","theo"],"rounds":[{"index":0,"kind":"FairShare","seed":2061,"opens":2}]}""")
        assertEquals(2061L, d.rounds[0].seed)
        assertEquals(2, d.rounds[0].opens)
        // From a server that doesn't count opens.
        assertEquals(0, json.decodeFromString(SkillDuelState.serializer(), """{"rounds":[{"index":0,"kind":"FairShare","seed":5}]}""").rounds[0].opens)
        assertEquals("""{"score":87,"seed":2061}""", scoreJson(87, 2061))
    }
}
