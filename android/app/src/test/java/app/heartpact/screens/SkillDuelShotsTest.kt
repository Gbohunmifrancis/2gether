package app.heartpact.screens

import androidx.compose.runtime.Composable
import app.heartpact.data.GameSession
import app.heartpact.data.GameStatus
import app.heartpact.data.GameType
import app.heartpact.ui.games.GameActions
import app.heartpact.ui.games.GamesScreen
import app.heartpact.ui.games.GamesUi
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class SkillDuelShotsTest {
    private val now = Instant.parse("2026-10-26T18:00:00Z")

    // Each shot its own session id: the board remembers which reveals it has shown, per session.
    @Composable private fun screen(id: String, state: String, status: GameStatus = GameStatus.InProgress) = GamesScreen(
        GamesUi(listOf(GameSession(id, "c", GameType.SkillDuel, status, currentTurnUserId = "maya", stateVersion = 6, stateJson = state, invitedByUserId = "theo",
            endedAtUtc = if (status == GameStatus.Completed) "2026-10-26T17:58:00Z" else null))),
        "maya", "Theo", GameActions(), { now })

    private fun round(i: Int, kind: String, mine: Int? = null, partner: Int? = null, played: Boolean = partner != null, winner: String? = null, tie: Boolean = false) =
        """{"index":$i,"kind":"$kind","seed":${1000 + i},"mine":$mine,"partner":$partner,"played":$played,"winner":${winner?.let { "\"$it\"" }},"tie":$tie}"""

    private fun state(rounds: List<String>, hearts: Pair<Int, Int>, end: String = """"totals":null,"winner":null,"tie":false,"margin":null""", phase: String = "playing") =
        """{"players":["maya","theo"],"phase":"$phase","totalRounds":5,"rounds":[${rounds.joinToString(",")}],"hearts":{"maya":${hearts.first},"theo":${hearts.second}},$end}"""

    private val fresh = state(listOf(
        round(0, "FairShare"), round(1, "Heartbeat"), round(2, "PromiseRing"), round(3, "OtherHalf"), round(4, "KissTheCenter"),
    ), 0 to 0)

    /** Round 1 revealed (Maya's heart), Theo has played round 2 that Maya hasn't, Maya waits on Theo for round 3. */
    private val midDuel = state(listOf(
        round(0, "FairShare", mine = 87, partner = 64, winner = "maya"),
        round(1, "Heartbeat", played = true),
        round(2, "PromiseRing", mine = 71),
        round(3, "OtherHalf"),
        round(4, "KissTheCenter"),
    ), 1 to 0)

    /** Only Theo has played: his scores sealed, Maya yet to start. */
    private val partnerOnly = state(listOf(
        round(0, "FillTheHeart", played = true), round(1, "OtherHalf", played = true), round(2, "Heartbeat"), round(3, "FairShare"), round(4, "PromiseRing"),
    ), 0 to 0)

    private val finished = state(listOf(
        round(0, "FairShare", 90, 80, winner = "maya"),
        round(1, "Heartbeat", 40, 60, winner = "theo"),
        round(2, "PromiseRing", 70, 70, tie = true),
        round(3, "OtherHalf", 100, 20, winner = "maya"),
        round(4, "KissTheCenter", 55, 50, winner = "maya"),
    ), 4 to 2, """"totals":{"maya":355,"theo":280},"winner":"maya","tie":false,"margin":75""", phase = "completed")

    @Test fun inviteWaiting() = shot("games-duel-invite") {
        GamesScreen(GamesUi(listOf(GameSession("w1", "c", GameType.SkillDuel, GameStatus.Waiting, invitedByUserId = "theo"))), "maya", "Theo", GameActions(), { now })
    }
    @Test fun inviteWaitingNight() = shot("games-duel-invite-night", night = true) {
        GamesScreen(GamesUi(listOf(GameSession("w2", "c", GameType.SkillDuel, GameStatus.Waiting, invitedByUserId = "maya"))), "maya", "Theo", GameActions(), { now })
    }

    @Test fun start() = shot("games-duel-start") { screen("s1", fresh) }

    @Test fun mid() = shot("games-duel-mid") { screen("m1", midDuel) }
    @Test fun midNight() = shot("games-duel-mid-night", night = true) { screen("m2", midDuel) }

    @Test fun partnerPlayed() = shot("games-duel-partner") { screen("p1", partnerOnly) }
    @Test fun partnerPlayedNight() = shot("games-duel-partner-night", night = true) { screen("p2", partnerOnly) }

    @Test fun done() = shot("games-duel-done") { screen("d1", finished, GameStatus.Completed) }
    @Test fun doneNight() = shot("games-duel-done-night", night = true) { screen("d2", finished, GameStatus.Completed) }
}
