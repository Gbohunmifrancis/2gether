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
class TriviaShotsTest {
    private val now = Instant.parse("2026-10-24T18:00:00Z")
    private fun session(state: String, status: GameStatus = GameStatus.InProgress, region: String = "global") =
        GameSession("g", "c", GameType.Trivia, status, currentTurnUserId = "maya", stateVersion = 7, stateJson = state, invitedByUserId = "theo", setupJson = """{"region":"$region"}""")
    private fun screen(s: GameSession, night: Boolean = false, name: String) =
        shot(name, night) { GamesScreen(GamesUi(listOf(s)), "maya", "Theo", GameActions(), { now }) }

    private val lastQuestion = """{"round":2,"question":{"text":"Which planet is known as the Red Planet?","options":["Venus","Mars","Jupiter","Mercury"]},"answer":1,"picks":{"maya":1,"theo":2}}"""

    @Test fun question() = screen(session("""{"players":["maya","theo"],"scores":{"maya":2,"theo":1},"round":3,"totalRounds":10,
        "question":{"text":"Who painted the Mona Lisa?","options":["Michelangelo","Leonardo da Vinci","Raphael","Vincent van Gogh"]},
        "answered":["theo"],"myAnswer":null,"reveal":$lastQuestion,"completed":false}"""), name = "games-trivia")

    @Test fun lockedIn() = screen(session("""{"players":["maya","theo"],"scores":{"maya":2,"theo":1},"round":3,"totalRounds":10,
        "question":{"text":"Who painted the Mona Lisa?","options":["Michelangelo","Leonardo da Vinci","Raphael","Vincent van Gogh"]},
        "answered":["maya"],"myAnswer":1,"reveal":$lastQuestion,"completed":false}"""), night = true, name = "games-trivia-locked-night")

    /** Both answered question 9: the reveal shows above the last question. (A finished game goes back to the picker.) */
    @Test fun reveal() = screen(session("""{"players":["maya","theo"],"scores":{"maya":6,"theo":7},"round":9,"totalRounds":10,
        "question":{"text":"Which city is known as the Coal City?","options":["Jos","Enugu","Lokoja","Aba"]},"answered":[],"myAnswer":null,"completed":false,
        "reveal":{"round":8,"question":{"text":"In which year did Abuja become Nigeria's capital?","options":["1976","1991","1999","1983"]},"answer":1,"picks":{"maya":3,"theo":1}}}""",
        region = "ng"), name = "games-trivia-reveal")

    @Test fun naija() = screen(session("""{"players":["maya","theo"],"scores":{"maya":0,"theo":0},"round":0,"totalRounds":10,
        "question":{"text":"In Pidgin, what does \"wahala\" mean?","options":["Money","Trouble","Friend","Food"]},
        "answered":[],"myAnswer":null,"reveal":null,"completed":false}""", region = "ng"), name = "games-trivia-naija")

    @Test fun picker() = shot("games-picker-naija") {
        GamesScreen(GamesUi(listOf(session("""{"players":["maya","theo"],"scores":{"maya":6,"theo":4}}""", GameStatus.Completed, region = "ng")), loaded = true), "maya", "Theo", GameActions(), { now })
    }
}
