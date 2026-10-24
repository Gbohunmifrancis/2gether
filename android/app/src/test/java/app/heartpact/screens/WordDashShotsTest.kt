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
class WordDashShotsTest {
    private val now = Instant.parse("2026-10-11T18:00:00Z")
    private fun session(state: String, status: GameStatus = GameStatus.InProgress, turn: String? = "maya", deadline: String? = "2026-10-11T18:00:14Z") =
        GameSession("g", "c", GameType.WordDash, status, currentTurnUserId = turn, deadlineUtc = deadline, stateVersion = 9, stateJson = state, invitedByUserId = "theo")
    @androidx.compose.runtime.Composable private fun screen(s: GameSession, note: String? = null) = GamesScreen(GamesUi(listOf(s), loaded = true, note = note), "maya", "Theo", GameActions(), { now })

    private val chain = """[{"player":"maya","word":"sunset"},{"player":"theo","word":"tulip"},{"player":"maya","word":"picnic"},{"player":"theo","word":"candle"},{"player":"maya","word":"embrace"},{"player":"theo","word":"evening"}]"""
    private val shorter = chain.removeSuffix(""",{"player":"theo","word":"evening"}]""") + "]"
    private val myTurn = session("""{"players":["maya","theo"],"scores":{"maya":2,"theo":1},"letter":"G","startLetter":"S","chain":$chain,"chainNumber":4,"maxChains":15,"target":5,"turnSeconds":20,"phase":"playing"}""")
    private val theirTurn = session("""{"players":["maya","theo"],"scores":{"maya":2,"theo":1},"letter":"E","startLetter":"S","chain":$shorter,"chainNumber":4,"maxChains":15,"target":5,"turnSeconds":20,"phase":"playing"}""", turn = "theo", deadline = "2026-10-11T18:00:04Z")
    private val newChain = session("""{"players":["maya","theo"],"scores":{"maya":3,"theo":1},"letter":"M","startLetter":"M","chain":[],"chainNumber":5,"maxChains":15,"target":5,"turnSeconds":20,"phase":"playing","lastResult":"timeout","timedOut":"theo","lastChain":["sunset","tulip"]}""", turn = "theo", deadline = "2026-10-11T18:00:20Z")
    private val done = session("""{"players":["maya","theo"],"scores":{"maya":5,"theo":3},"letter":null,"startLetter":"B","chain":[],"chainNumber":8,"phase":"completed","lastResult":"timeout","timedOut":"theo","winnerUserId":"maya"}""", GameStatus.Completed, null, null)

    @Test fun myTurn() = shot("games-worddash") { screen(myTurn) }
    @Test fun myTurnNight() = shot("games-worddash-night", night = true) { screen(myTurn) }
    @Test fun refused() = shot("games-worddash-refused") { screen(myTurn, note = "\"glorp\" isn't in the dictionary. Try another.") }
    @Test fun partnersTurn() = shot("games-worddash-partner") { screen(theirTurn) }
    @Test fun timedOut() = shot("games-worddash-timeout") { screen(newChain) }
    @Test fun timedOutNight() = shot("games-worddash-timeout-night", night = true) { screen(newChain) }
    @Test fun completed() = shot("games-worddash-done") { screen(done) }
}
