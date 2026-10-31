package app.heartpact

import app.heartpact.data.GameSession
import app.heartpact.data.GameStatus
import app.heartpact.data.GameType
import app.heartpact.ui.games.GamePlay
import app.heartpact.ui.play.SkillKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Heart to heart's state as the server sends it (docs/games.md), read for the person holding the phone. */
class SkillDuelStateTest {
    private fun session(state: String, status: GameStatus = GameStatus.InProgress) =
        GameSession("g", "c", GameType.SkillDuel, status, stateJson = state)

    private val midDuel = """{"players":["maya","theo"],"phase":"playing","totalRounds":5,"rounds":[
        {"index":0,"kind":"FairShare","seed":123456789,"mine":87,"partner":64,"played":true,"winner":"maya","tie":false},
        {"index":1,"kind":"Heartbeat","seed":5,"mine":null,"partner":null,"played":true,"winner":null,"tie":false},
        {"index":2,"kind":"PromiseRing","seed":6,"mine":71,"partner":null,"played":false,"winner":null,"tie":false},
        {"index":3,"kind":"OtherHalf","seed":7,"mine":50,"partner":50,"played":true,"winner":null,"tie":true},
        {"index":4,"kind":"KissTheCenter","seed":8,"mine":null,"partner":null,"played":false,"winner":null,"tie":false}],
        "hearts":{"maya":2,"theo":1},"totals":null,"winner":null,"tie":false,"margin":null}"""

    @Test fun `reads each round with only what this phone may see`() {
        val d = GamePlay.skillDuel(session(midDuel))
        assertEquals(5, d.rounds.size)
        assertFalse(d.finished)
        assertEquals(SkillKind.FairShare.name, d.rounds[0].kind)
        assertEquals(123456789L, d.rounds[0].seed)
        assertTrue(d.rounds[0].revealed)
        assertEquals("maya", d.rounds[0].winner)
        // Theo played round 1, Maya hasn't: his score stays sealed.
        assertTrue(d.rounds[1].played)
        assertNull(d.rounds[1].partner)
        assertFalse(d.rounds[1].revealed)
        assertEquals(71, d.rounds[2].mine)
        assertFalse(d.rounds[2].played)
        assertTrue(d.rounds[3].tie)
        assertEquals(2, d.hearts["maya"])
        assertEquals(1, d.hearts["theo"])
    }

    @Test fun `every kind the server draws is one the app knows`() {
        val names = SkillKind.entries.map { it.name }.toSet()
        listOf("FairShare", "FillTheHeart", "OtherHalf", "Heartbeat", "KissTheCenter", "PromiseRing", "CupidsAim", "SlowKiss", "DrawAHeart").forEach { assertTrue(it, it in names) }
    }

    @Test fun `a broken or empty state reads as an empty duel`() {
        assertEquals(0, GamePlay.skillDuel(session("not json")).rounds.size)
        assertEquals(5, GamePlay.skillDuel(session("{}")).totalRounds)
    }

    private fun finished(hearts: Pair<Int, Int>, points: Pair<Int, Int>, winner: String?, decidedBy: String?) = session(
        """{"players":["maya","theo"],"phase":"completed","rounds":[],"hearts":{"maya":${hearts.first},"theo":${hearts.second}},
        "totals":{"maya":${points.first},"theo":${points.second}},"winner":${winner?.let { "\"$it\"" }},"tie":${winner == null},
        "decidedBy":${decidedBy?.let { "\"$it\"" }},"margin":${kotlin.math.abs(hearts.first - hearts.second)},"pointMargin":${kotlin.math.abs(points.first - points.second)}}""",
        GameStatus.Completed)

    @Test fun `the outcome is the duel won on hearts, and the points only when the hearts were level`() {
        assertEquals("You won the duel by 2 hearts", GamePlay.outcome(finished(4 to 2, 355 to 280, "maya", "hearts"), "maya", "Theo"))
        // More hearts win even with fewer points.
        assertEquals("Theo won the duel by 1 heart", GamePlay.outcome(finished(2 to 3, 350 to 173, "theo", "hearts"), "maya", "Theo"))
        assertEquals("Level on hearts, you won on points", GamePlay.outcome(finished(4 to 4, 250 to 230, "maya", "points"), "maya", "Theo"))
        assertEquals("Level on hearts, Theo won on points", GamePlay.outcome(finished(4 to 4, 230 to 250, "theo", "points"), "maya", "Theo"))
        assertEquals("A perfect tie, 3 hearts each", GamePlay.outcome(finished(3 to 3, 250 to 250, null, null), "maya", "Theo"))
        assertEquals("Ended early", GamePlay.outcome(session(midDuel, GameStatus.Abandoned), "maya", "Theo"))
    }

    @Test fun `the result explains the tiebreak only when it was used`() {
        fun margin(s: GameSession) = GamePlay.duelMargin(GamePlay.skillDuel(s), "maya")
        assertEquals("by 2 hearts", margin(finished(4 to 2, 355 to 280, "maya", "hearts")))
        assertEquals("by 1 heart", margin(finished(2 to 3, 350 to 173, "theo", "hearts")))
        assertEquals("Level on hearts, won by 20 points", margin(finished(4 to 4, 250 to 230, "maya", "points")))
        assertEquals("Level on hearts and on points", margin(finished(3 to 3, 250 to 250, null, null)))
        assertEquals(GamePlay.DuelDecision.Points, GamePlay.duelDecision(GamePlay.skillDuel(finished(4 to 4, 250 to 230, "maya", "points"))))
        assertEquals(GamePlay.DuelDecision.Tie, GamePlay.duelDecision(GamePlay.skillDuel(finished(3 to 3, 250 to 250, null, null))))
        assertEquals(GamePlay.DuelDecision.Hearts, GamePlay.duelDecision(GamePlay.skillDuel(finished(4 to 2, 355 to 280, "maya", "hearts"))))
    }

    @Test fun `the duel is offered first`() {
        assertEquals(GameType.SkillDuel, GamePlay.offered.first())
        assertEquals("Heart to heart", GamePlay.title(GameType.SkillDuel))
    }
}
