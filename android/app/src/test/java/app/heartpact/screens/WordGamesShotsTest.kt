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
import app.heartpact.ui.games.PartnerProgress
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
 * I call on, Word dash, Draw and guess and Truth or dare in the states the other shot tests don't cover, and every
 * one of them in each language (build/screens/<language>/games-*.png), Arabic right to left.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class WordGamesShotsTest {
    private val now = Instant.parse("2026-10-11T18:00:00Z")
    private val english = Locale.getDefault()

    @After fun back() {
        Locale.setDefault(english)
        Tr.use("en", null)
    }

    private fun session(type: GameType, state: String, turn: String? = "maya", deadline: String? = null, status: GameStatus = GameStatus.InProgress) =
        GameSession("g", "c", type, status, currentTurnUserId = turn, deadlineUtc = deadline, stateVersion = 7, stateJson = state, invitedByUserId = "theo")

    @Composable private fun screen(s: GameSession, ui: GamesUi = GamesUi(listOf(s), loaded = true)) = GamesScreen(ui, "maya", "Theo", GameActions(), { now })

    private val categories = """["Name","Place","Thing","Food"]"""

    /** Round 1: nobody has sent yet, Theo is typing. */
    private val iCallOnFirst = session(GameType.ICallOn, """{"players":["maya","theo"],"scores":{"maya":0,"theo":0},"round":0,"totalRounds":3,"letter":"B","categories":$categories,"phase":"answering","submitted":[],"pendingVetting":[]}""",
        deadline = "2026-10-11T18:00:11Z")

    /** Round 2, with round 1's answers side by side above it. */
    private val iCallOnReveal = session(GameType.ICallOn, """{"players":["maya","theo"],"scores":{"maya":5,"theo":4},"round":1,"totalRounds":3,"letter":"T","categories":$categories,"phase":"answering","submitted":["maya"],"pendingVetting":[],
        "reveal":{"round":0,"letter":"B","roundScores":{"maya":5,"theo":4},"entries":[
        {"player":"maya","category":"Name","answer":"Bisi","counted":true,"points":1},{"player":"theo","category":"Name","answer":"Bisi","counted":true,"points":1},
        {"player":"maya","category":"Place","answer":"Barcelona","counted":true,"points":2},{"player":"theo","category":"Place","answer":"Benin City","counted":true,"points":2},
        {"player":"maya","category":"Thing","answer":"Bumbershoot","counted":false,"points":0},{"player":"theo","category":"Thing","answer":"","counted":false,"points":0},
        {"player":"maya","category":"Food","answer":"Bammy","counted":true,"points":2},{"player":"theo","category":"Food","answer":"Bread","counted":true,"points":1}]}}""",
        deadline = "2026-10-11T18:00:09Z")

    private val iCallOnJudging = session(GameType.ICallOn, """{"players":["maya","theo"],"scores":{"maya":0,"theo":0},"round":0,"totalRounds":3,"letter":"B","categories":$categories,"phase":"vetting","submitted":["maya","theo"],
        "pendingVetting":[{"player":"theo","judge":"maya","category":"Thing","answer":"Bumbershoot","allowed":null},{"player":"theo","judge":"maya","category":"Food","answer":"Bammy","allowed":null}]}""",
        deadline = "2026-10-11T18:00:17Z")

    private val wordDashStart = session(GameType.WordDash, """{"players":["maya","theo"],"scores":{"maya":0,"theo":0},"letter":"S","startLetter":"S","chain":[],"chainNumber":1,"maxChains":15,"target":5,"turnSeconds":20,"phase":"playing"}""",
        deadline = "2026-10-11T18:00:18Z")

    private val wordDashTimeout = session(GameType.WordDash, """{"players":["maya","theo"],"scores":{"maya":1,"theo":2},"letter":"M","startLetter":"M","chain":[],"chainNumber":4,"maxChains":15,"target":5,"turnSeconds":20,"phase":"playing",
        "lastResult":"timeout","timedOut":"maya","lastChain":["sunset","tulip","picnic","candle"]}""", deadline = "2026-10-11T18:00:20Z")

    private val truthChooser = session(GameType.TruthOrDare, """{"players":["maya","theo"],"scores":{"maya":2,"theo":2},"turn":4,"totalTurns":10,"phase":"judging","chooser":"maya","judge":"theo","choice":"dare",
        "prompt":"Send a voice note singing our song.","swapped":false,"last":{"chooser":"theo","choice":"truth","prompt":"When do you miss me the most?","result":"skipped","passed":true}}""", turn = "theo")

    private val truthSwapped = session(GameType.TruthOrDare, truthChooser.stateJson.replace("\"swapped\":false", "\"swapped\":true"), turn = "theo")

    private val drawGuesser = session(GameType.Handwriting, """{"players":["maya","theo"],"scores":{"maya":1,"theo":1},"round":1,"totalRounds":6,"phase":"drawing","drawer":"theo","guesser":"maya",
        "word":null,"strokes":[{"points":[[0.2,0.7],[0.5,0.2],[0.8,0.7],[0.2,0.7]],"width":0.012}],"wrongGuesses":["mountain"],"guessesLeft":2,"maxGuesses":3,
        "lastRound":{"round":0,"drawer":"maya","word":"rocket","outcome":"guessed","guess":"rockets","strokes":[],"wrongGuesses":[]}}""", turn = "theo")

    private val drawDrawer = session(GameType.Handwriting, """{"players":["maya","theo"],"scores":{"maya":1,"theo":1},"round":2,"totalRounds":6,"phase":"drawing","drawer":"maya","guesser":"theo",
        "word":"umbrella","strokes":[{"points":[[0.2,0.5],[0.5,0.2],[0.8,0.5]],"width":0.012}],"wrongGuesses":[],"guessesLeft":3,"maxGuesses":3,
        "lastRound":{"round":1,"drawer":"theo","word":"tent","outcome":"guessed","guess":"tent","strokes":[],"wrongGuesses":["mountain"]}}""")

    private val progress = mapOf("g" to PartnerProgress("g", "theo", 0, 3, 4))

    @Test fun english() {
        shot("games-icallon-first") { screen(iCallOnFirst, GamesUi(listOf(iCallOnFirst), loaded = true, progress = progress)) }
        shot("games-icallon-reveal") { screen(iCallOnReveal) }
        shot("games-icallon-reveal-night", night = true) { screen(iCallOnReveal) }
        shot("games-icallon-judging") { screen(iCallOnJudging) }
        shot("games-worddash-start") { screen(wordDashStart) }
        shot("games-worddash-lastchain") { screen(wordDashTimeout) }
        shot("games-truthordare-wayout") { screen(truthChooser) }
        shot("games-truthordare-wayout-night", night = true) { screen(truthChooser) }
        shot("games-truthordare-swapped") { screen(truthSwapped) }
        shot("games-draw-drawer-fresh") { screen(drawDrawer) }
        shot("games-draw-guesser") { screen(drawGuesser) }
    }

    /** Sideways on a phone: the canvas shrinks to fit the height, so the whole drawing stays on screen. */
    @Test @Config(qualifiers = "w844dp-h390dp-xxhdpi") fun landscape() {
        shot("games-draw-landscape") { screen(drawDrawer) }
        shot("games-draw-guesser-landscape") { screen(drawGuesser) }
    }

    @Test fun french() = everyLanguage("fr")
    @Test fun portuguese() = everyLanguage("pt")
    @Test fun spanish() = everyLanguage("es")
    @Test fun arabic() = everyLanguage("ar")
    @Test fun hindi() = everyLanguage("hi")

    private fun everyLanguage(language: String) {
        val file = File("src/main/assets/i18n/$language.json")
        if (!file.exists()) return
        Locale.setDefault(Locale.forLanguageTag(language))
        Tr.use(language, file.readText())
        val rtl = language == "ar"
        fun at(name: String, content: @Composable () -> Unit) = shot("$language/$name") {
            CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) { content() }
        }
        at("games-icallon-first") { screen(iCallOnFirst, GamesUi(listOf(iCallOnFirst), loaded = true, progress = progress)) }
        at("games-icallon-reveal") { screen(iCallOnReveal) }
        at("games-icallon-judging") { screen(iCallOnJudging) }
        at("games-worddash-start") { screen(wordDashStart) }
        at("games-worddash-lastchain") { screen(wordDashTimeout) }
        at("games-truthordare-wayout") { screen(truthChooser) }
        at("games-draw-drawer") { screen(drawDrawer) }
        at("games-draw-guesser") { screen(drawGuesser) }
    }
}
