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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
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
 * Same answer, Would you rather, Guess my answer and Trivia in every language, with the questions as the server sends
 * them in that language (its catalog), to check long questions and answers fit and Arabic reads right to left.
 * Shots land in build/screens/<language>/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class CoupleGamesLocalizedShotsTest {
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

    private fun screens(language: String) {
        val app = File("src/main/assets/i18n/$language.json")
        if (language != "en") {
            if (!app.exists()) return
            Locale.setDefault(Locale.forLanguageTag(language))
            Tr.use(language, app.readText())
        }
        // The server's own translations of its questions, as a partner reading this language gets them.
        val server = File("../../src/Twogether.Shared/Localization/i18n/$language.json").takeIf { it.exists() }
            ?.let { Json.parseToJsonElement(it.readText()).jsonObject }
        fun s(english: String) = (server?.get(english) as? JsonPrimitive)?.content ?: english
        fun q(english: String) = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(s(english)))
        val rtl = language == "ar"
        fun at(name: String, session: GameSession) = shot("$language/$name") {
            CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) { Screen(session) }
        }

        at("games-same", session(GameType.CoupleQuiz, """{"players":["maya","theo"],"scores":{"maya":3,"theo":3},"round":4,"totalRounds":10,
            "prompt":${q("What's our favourite way to unwind after a long day?")},"kind":"text","answerCount":1,"answered":["theo"],"mine":null,
            "last":{"round":3,"prompt":${q("What's our go-to takeaway?")},"kind":"text","answers":{"maya":"Thai green curry","theo":"thai green curry!"},"match":true}}"""))
        at("games-same-who", session(GameType.CoupleQuiz, """{"players":["maya","theo"],"scores":{"maya":3,"theo":3},"round":4,"totalRounds":10,
            "prompt":${q("Who is more likely to cry at a film?")},"kind":"who","answerCount":0,"answered":[],"mine":null,
            "last":{"round":3,"prompt":${q("Who remembers birthdays better?")},"kind":"who","answers":{"maya":"maya","theo":"theo"},"match":false}}"""))
        at("games-rather", session(GameType.WouldYouRather, """{"players":["maya","theo"],"scores":{"maya":3,"theo":3},"round":4,"totalRounds":10,"phase":"picking",
            "a":${q("Let your partner plan every date")},"b":${q("Plan every date yourself")},"picked":["theo"],"myPick":null,
            "last":{"round":3,"a":${q("Always be a little too warm")},"b":${q("Always be a little too cold")},"picks":{"maya":1,"theo":1},"match":true}}"""))
        at("games-guess", session(GameType.GuessMyAnswer, """{"players":["maya","theo"],"scores":{"maya":0,"theo":1},"round":1,"totalRounds":8,"phase":"guessing","subject":"theo","guesser":"maya",
            "question":${q("What kind of gift do you love most?")},"choices":[${q("A surprise")},${q("Something I asked for")},${q("Something handmade")},${q("An experience")}],"answered":true,"answer":null,
            "last":{"round":0,"subject":"maya","guesser":"theo","question":${q("Your favourite way to travel?")},"choices":[${q("By plane")},${q("By train")},${q("By car")},${q("By boat")}],"answer":1,"guess":1,"right":true}}"""))
        at("games-trivia", session(GameType.Trivia, """{"players":["maya","theo"],"scores":{"maya":2,"theo":1},"round":3,"totalRounds":10,
            "question":{"text":${q("Which shape has four equal sides and four right angles?")},"options":[${q("A rectangle")},${q("A square")},${q("A rhombus")},${q("A triangle")}]},
            "answered":["theo"],"myAnswer":null,"completed":false,
            "reveal":{"round":2,"question":{"text":${q("Which ocean lies between Africa and Australia?")},"options":[${q("The Atlantic Ocean")},${q("The Indian Ocean")},${q("The Pacific Ocean")},${q("The Arctic Ocean")}]},"answer":1,"picks":{"maya":1,"theo":2}}}"""))
        at("games-same-finished", session(GameType.CoupleQuiz, """{"players":["maya","theo"],"scores":{"maya":2,"theo":2},"round":3,"totalRounds":3,"answered":[],
            "history":[{"round":0,"prompt":${q("What did we eat on our first date?")},"kind":"text","answers":{"maya":"Pizza","theo":"pizza"},"match":true},
            {"round":1,"prompt":${q("Who picks the restaurant most often?")},"kind":"who","answers":{"maya":"theo","theo":"theo"},"match":true},
            {"round":2,"prompt":${q("Which series should we watch next?")},"kind":"text","answers":{"maya":"Something funny","theo":"A documentary"},"match":false}]}""",
            GameStatus.Completed))
    }

    private fun session(type: GameType, state: String, status: GameStatus = GameStatus.InProgress) =
        GameSession("g", "c", type, status, currentTurnUserId = if (status == GameStatus.InProgress) "maya" else null, stateVersion = 5, stateJson = state,
            invitedByUserId = "theo", endedAtUtc = if (status == GameStatus.Completed) "2026-10-31T17:59:00Z" else null)

    @Composable private fun Screen(s: GameSession) = GamesScreen(GamesUi(listOf(s), loaded = true), "maya", "Theo", GameActions(), { now })
}
