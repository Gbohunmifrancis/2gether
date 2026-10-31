package app.heartpact.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import app.heartpact.data.GameSession
import app.heartpact.data.GameStatus
import app.heartpact.data.GameType
import app.heartpact.i18n.Tr
import app.heartpact.ui.games.GameActions
import app.heartpact.ui.games.GamesScreen
import app.heartpact.ui.games.GamesUi
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.util.Locale

/**
 * Snakes and ladders, Spot the difference, Tic-tac-toe and Connect four in each language, at the states with the most
 * words (whose turn, a game won, the beat between pictures), to check they fit and Arabic runs right to left.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h900dp-xxhdpi")
class RaceGamesLocalizedShotsTest {
    private val english = Locale.getDefault()
    private val now = Instant.parse("2026-10-31T18:00:00Z")

    @After fun back() {
        Locale.setDefault(english)
        Tr.use("en", null)
    }

    @Test fun english() = screens("en")
    @Test fun french() = screens("fr")
    @Test fun portuguese() = screens("pt")
    @Test fun spanish() = screens("es")
    @Test fun arabic() = screens("ar")
    @Test fun hindi() = screens("hi")

    private fun cells(board: String) = board.map { if (it == 'm') "\"maya\"" else if (it == 't') "\"theo\"" else "null" }.joinToString(",", "[", "]")

    private fun session(type: GameType, state: String, turn: String? = "maya", deadline: String? = null) =
        GameSession("g", "c", type, GameStatus.InProgress, currentTurnUserId = turn, deadlineUtc = deadline, stateVersion = 7, stateJson = state, invitedByUserId = "theo")

    private val connectFour = """{"players":["maya","theo"],"columns":7,"rows":6,"cells":${cells(".".repeat(21) + "...t..." + ".mmmmtt" + "ttmtmmt")},"game":1,"totalGames":3,
        "winsNeeded":2,"phase":"gameOver","line":[29,30,31,32],"lastMove":32,"scores":{"maya":1,"theo":1},"results":["theo","maya"]}"""

    private val ticTacToe = """{"players":["maya","theo"],"columns":3,"rows":3,"cells":${cells("mtt.m...m")},"game":2,"totalGames":5,"winsNeeded":3,"phase":"gameOver",
        "line":[0,4,8],"scores":{"maya":2,"theo":1},"results":["maya","theo","maya"],"cooldownMs":650,"nextGameAtUtc":"2026-10-31T18:00:01Z"}"""

    private val spot = """{"players":["maya","theo"],"phase":"between","scene":1,"totalScenes":3,"differences":7,"sceneSeconds":90,"beatSeconds":3,"kind":"NightSky","seed":31337,
        "found":[{"index":1,"by":"theo"},{"index":4,"by":"maya"}],"scenes":[{"index":0,"kind":"Beach","seed":5,"found":[],"timedOut":true},
        {"index":1,"kind":"NightSky","seed":31337,"found":[{"index":1,"by":"theo"},{"index":4,"by":"maya"}],"timedOut":true}],"scores":{"maya":5,"theo":4},"winner":null,"tie":false}"""

    private val snakes = """{"players":["maya","theo"],"phase":"playing","goal":100,"first":"maya","turn":"theo","board":{"ladders":[{"from":3,"to":17},{"from":51,"to":71}],
        "snakes":[{"from":47,"to":34},{"from":93,"to":66}],"hearts":[{"square":30,"kind":"swap"},{"square":57,"kind":"boost"},{"square":27,"kind":"shield"},{"square":26,"kind":"heartbreak"}]},
        "positions":{"maya":34,"theo":58},"shields":{"maya":true,"theo":false},"stats":{},"sixes":0,"moves":20,
        "last":{"move":20,"player":"theo","roll":6,"from":52,"steps":[53,54,55,56,57,58],"events":[],"to":58,"extraTurn":true,"sixes":1},"winner":null}"""

    private fun screens(language: String) {
        val file = File("src/main/assets/i18n/$language.json")
        if (language != "en" && !file.exists()) return
        Locale.setDefault(Locale.forLanguageTag(language))
        Tr.use(language, if (language == "en") null else file.readText())
        val rtl = language == "ar"
        fun at(name: String, s: GameSession) = shot("$language/$name") {
            CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) { screen(s) }
        }
        at("games-connectfour", session(GameType.ConnectFour, connectFour, turn = "maya"))
        at("games-tictactoe", session(GameType.TicTacToe, ticTacToe, deadline = "2026-10-31T18:00:01Z"))
        at("games-spot", session(GameType.SpotTheDifference, spot, deadline = "2026-10-31T18:00:02Z"))
        at("games-snakes", session(GameType.SnakesAndLadders, snakes, turn = "theo"))
    }

    @Composable private fun screen(s: GameSession) = GamesScreen(GamesUi(listOf(s), loaded = true), "maya", "Théodore-Alexandre", GameActions(), { now }, myName = "Maya")
}
