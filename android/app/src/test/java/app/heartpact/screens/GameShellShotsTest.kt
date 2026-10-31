package app.heartpact.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import app.heartpact.data.GameSession
import app.heartpact.data.GameStatus
import app.heartpact.data.GameType
import app.heartpact.i18n.Tr
import app.heartpact.i18n.tr
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
 * The shell around every couple game, phase by phase and for both partners: invited and inviting, the partner said
 * not now, the countdown with its way out, a finished game with "Play again", the hub after a duel. Then the key
 * states again in each language (Arabic right to left).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class GameShellShotsTest {
    private val now = Instant.parse("2026-10-26T18:00:00Z")
    private val english = Locale.getDefault()

    @After fun back() {
        Locale.setDefault(english)
        Tr.use("en", null)
    }

    @Composable private fun screen(ui: GamesUi) = GamesScreen(ui, "maya", "Theo", GameActions(), { now })

    private fun invite(by: String) = GameSession("w-$by", "c", GameType.MemoryMatch, GameStatus.Waiting, invitedByUserId = by)

    private val duelState = """{"players":["maya","theo"],"phase":"completed","totalRounds":5,"rounds":[""" +
        listOf("FairShare" to (90 to 80), "Heartbeat" to (40 to 60), "PromiseRing" to (70 to 70), "OtherHalf" to (100 to 20), "KissTheCenter" to (55 to 50))
            .mapIndexed { i, (kind, s) ->
                val winner = if (s.first > s.second) "\"maya\"" else if (s.second > s.first) "\"theo\"" else "null"
                """{"index":$i,"kind":"$kind","seed":${1000 + i},"mine":${s.first},"partner":${s.second},"played":true,"winner":$winner,"tie":${s.first == s.second}}"""
            }.joinToString(",") +
        """],"hearts":{"maya":4,"theo":2},"totals":{"maya":355,"theo":280},"winner":"maya","tie":false,"decidedBy":"hearts","margin":2,"pointMargin":75}"""

    private fun duelDone(id: String) = GameSession(id, "c", GameType.SkillDuel, GameStatus.Completed, endedAtUtc = "2026-10-26T17:58:00Z", stateVersion = 11,
        stateJson = duelState, invitedByUserId = "theo")

    private val memoryCards = listOf(1, 4, 2, 6, 3, 5, 5, 2, 6, 1, 4, 3).mapIndexed { i, v -> """{"index":$i,"value":$v,"matched":true}""" }.joinToString(",")
    private val memoryWon = GameSession("m", "c", GameType.MemoryMatch, GameStatus.Completed, endedAtUtc = "2026-10-26T17:59:00Z", stateVersion = 30,
        stateJson = """{"players":["maya","theo"],"scores":{"maya":4,"theo":2},"cards":[$memoryCards],"lastResult":"match"}""", invitedByUserId = "theo")

    /** Mid game, Theo has just found a pair: the line says so on Maya's phone. */
    private val memoryPartnerPair = GameSession("mp", "c", GameType.MemoryMatch, GameStatus.InProgress, currentTurnUserId = "theo", stateVersion = 9,
        stateJson = """{"players":["maya","theo"],"scores":{"maya":1,"theo":2},"cards":[""" +
            listOf(1, 4, 2, 6, 3, 5, 5, 2, 6, 1, 4, 3).mapIndexed { i, v ->
                val matched = i in setOf(0, 9, 1, 10, 4, 11)
                """{"index":$i,"value":${if (matched) v else null},"matched":$matched}"""
            }.joinToString(",") + """],"revealed":[],"lastResult":"match","lastPair":[{"index":4,"value":3},{"index":11,"value":3}]}""", invitedByUserId = "theo")

    private val countdown = GameSession("c1", "c", GameType.MemoryMatch, GameStatus.InProgress, currentTurnUserId = "maya", countdownEndsAtUtc = "2026-10-26T18:00:03Z",
        stateVersion = 1, stateJson = """{"players":["maya","theo"],"scores":{},"cards":[]}""", invitedByUserId = "theo")

    @Test fun invited() = shot("games-shell-invited") { screen(GamesUi(listOf(invite("theo")), loaded = true)) }
    @Test fun inviting() = shot("games-shell-inviting") { screen(GamesUi(listOf(invite("maya")), loaded = true)) }

    /** Theo said not now: back on the hub, with a line saying so. */
    @Test fun declined() = shot("games-shell-declined") {
        screen(GamesUi(listOf(invite("maya").copy(status = GameStatus.Abandoned)), loaded = true, note = "Theo can't play right now."))
    }

    @Test fun countdownWithWayOut() = shot("games-shell-countdown") { screen(GamesUi(listOf(countdown), loaded = true)) }

    @Test fun memoryResult() {
        shot("games-shell-memory-done") { screen(GamesUi(listOf(memoryWon), loaded = true)) }
        shot("games-shell-memory-done-night", night = true) { screen(GamesUi(listOf(memoryWon), loaded = true)) }
    }

    @Test fun memoryPartnerFoundAPair() = shot("games-shell-memory-partner-pair") { screen(GamesUi(listOf(memoryPartnerPair), loaded = true)) }

    /** The end of a duel, scrolled to its foot on a tall screen: "Play again" under the rounds. */
    @Test @Config(qualifiers = "w390dp-h1500dp-xxhdpi") fun duelDone() = shot("games-shell-duel-done") { screen(GamesUi(listOf(duelDone("d1")), loaded = true)) }

    /** Closed, the hub says how the duel went and Maya cheers. */
    @Test fun hubAfterDuel() = shot("games-shell-hub-after-duel") { screen(GamesUi(listOf(duelDone("d2")), loaded = true, closed = setOf("d2"))) }

    /** A narrow phone and a long name: each side's count reads whole ("1 vitória", never a bare "1"), shrinking to fit. */
    @Test @Config(qualifiers = "w360dp-h740dp-xxhdpi") fun narrowScoreboard() {
        val cells = (0 until 42).joinToString(",", "[", "]") { i -> if (i == 38) "\"maya\"" else if (i == 39) "\"theo\"" else "null" }
        val s = GameSession("c4", "c", GameType.ConnectFour, GameStatus.InProgress, currentTurnUserId = "maya", stateVersion = 12, invitedByUserId = "theo",
            stateJson = """{"players":["maya","theo"],"columns":7,"rows":6,"cells":$cells,"game":2,"totalGames":3,"winsNeeded":2,"phase":"playing","scores":{"maya":1,"theo":1},"results":["maya","theo"]}""")
        shot("games-shell-narrow-scores") { GamesScreen(GamesUi(listOf(s), loaded = true), "maya", "Alessandra", GameActions(), { now }) }
        for (language in listOf("pt", "fr")) {
            Tr.use(language, File("src/main/assets/i18n/$language.json").readText())
            shot("$language/games-shell-narrow-scores") { GamesScreen(GamesUi(listOf(s), loaded = true), "maya", "Alessandra", GameActions(), { now }) }
        }
    }

    @Test fun french() = languages("fr")
    @Test fun portuguese() = languages("pt")
    @Test fun spanish() = languages("es")
    @Test fun arabic() = languages("ar")
    @Test fun hindi() = languages("hi")

    private fun languages(language: String) {
        val file = File("src/main/assets/i18n/$language.json")
        if (!file.exists()) return
        Locale.setDefault(Locale.forLanguageTag(language))
        Tr.use(language, file.readText())
        val rtl = language == "ar"
        fun at(name: String, ui: GamesUi) = shot("$language/games-shell-$name") {
            CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) { screen(ui) }
        }
        at("hub", GamesUi(listOf(duelDone("l-$language"), memoryWon.copy(id = "lm-$language")), loaded = true, closed = setOf("l-$language"),
            note = tr("{partner} can't play right now.", "partner" to "Theo")))
        at("invited", GamesUi(listOf(invite("theo")), loaded = true))
        at("memory-done", GamesUi(listOf(memoryWon.copy(id = "md-$language")), loaded = true))
        at("duel-done", GamesUi(listOf(duelDone("dd-$language")), loaded = true))
        at("memory-partner-pair", GamesUi(listOf(memoryPartnerPair.copy(id = "mp-$language")), loaded = true))
    }
}
