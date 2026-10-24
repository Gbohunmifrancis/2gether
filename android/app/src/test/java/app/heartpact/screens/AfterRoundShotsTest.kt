package app.heartpact.screens

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
class AfterRoundShotsTest {
    private val now = Instant.parse("2026-10-24T18:00:00Z")
    private fun session(state: String, status: GameStatus = GameStatus.InProgress, endedAt: String? = null) =
        GameSession("g", "c", GameType.AfterRound, status, currentTurnUserId = "ada", deadlineUtc = "2026-10-24T18:00:09Z", stateVersion = 7,
            stateJson = state, invitedByUserId = "tunde", endedAtUtc = endedAt, setupJson = """{"region":"ng"}""")
    private fun screen(s: GameSession, night: Boolean = false, name: String) =
        shot(name, night) { GamesScreen(GamesUi(listOf(s), region = "ng"), "ada", "Tunde", GameActions(), { now }) }

    private val last = """{"round":2,"calls":{"ada":7,"tunde":4},"fingers":{"ada":3,"tunde":4},"total":7,"winner":"ada","auto":[]}"""

    @Test fun calling() = screen(session("""{"players":["ada","tunde"],"scores":{"ada":2,"tunde":1},"round":3,"target":5,"phase":"calling",
        "calls":{"tunde":6},"reachable":[1,2,3,4,5,6,7,8,9,10],"lastFingers":{"ada":0,"tunde":0},"myFingers":[],"laid":[],"myLay":null,"last":$last}"""),
        name = "games-afterround-calling")

    @Test fun laying() = screen(session("""{"players":["ada","tunde"],"scores":{"ada":2,"tunde":1},"round":3,"target":5,"phase":"laying",
        "calls":{"ada":5,"tunde":6},"reachable":[0,1,2,3,4,5,6,7,8,9,10],"lastFingers":{"ada":3,"tunde":4},"myFingers":[0,1,2,4,5],"laid":["tunde"],"myLay":null,"last":$last}"""),
        night = true, name = "games-afterround-laying-night")

    @Test fun duel() = screen(session("""{"players":["ada","tunde"],"scores":{"ada":5,"tunde":3},"round":9,"target":5,"phase":"duel",
        "calls":{},"reachable":[],"lastFingers":{},"myFingers":[],"laid":[],"myLay":null,"last":$last,"winnerUserId":"ada",
        "duel":{"slapper":"ada","defender":"tunde","zone":0.46,"periodMs":1100,"windowMs":4000,"pressed":[],"result":null}}"""),
        name = "games-afterround-duel")

    @Test fun finished() = screen(session("""{"players":["ada","tunde"],"scores":{"ada":5,"tunde":3},"round":9,"target":5,"phase":"completed",
        "last":$last,"winnerUserId":"ada","duel":{"slapper":"ada","defender":"tunde","zone":0.46,"pressed":["ada","tunde"],
        "result":{"outcome":"hit","slapper":{"accuracy":0.97,"rating":"perfect"},"defender":{"accuracy":0.6,"rating":"weak"}}}}""",
        GameStatus.Completed, endedAt = "2026-10-24T17:58:00Z"), name = "games-afterround-finished")

    @Test fun picker() = shot("games-picker-afterround") { GamesScreen(GamesUi(emptyList(), loaded = true, region = "ng"), "ada", "Tunde", GameActions(), { now }) }
}
