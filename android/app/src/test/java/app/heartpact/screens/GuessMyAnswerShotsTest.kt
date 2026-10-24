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
class GuessMyAnswerShotsTest {
    private val now = Instant.parse("2026-10-11T18:00:00Z")
    @Composable private fun screen(state: String, turn: String?) = GamesScreen(
        GamesUi(listOf(GameSession("g", "c", GameType.GuessMyAnswer, GameStatus.InProgress, currentTurnUserId = turn, stateVersion = 4, stateJson = state, invitedByUserId = "theo"))),
        "maya", "Theo", GameActions(), { now })

    /** Theo has answered about himself; Maya guesses, with round one's reveal above. */
    @Test fun guessing() = shot("games-guess") {
        screen("""{"players":["maya","theo"],"scores":{"maya":0,"theo":1},"round":1,"totalRounds":8,"phase":"guessing","subject":"theo","guesser":"maya",
            "question":"Your ideal Saturday?","choices":["Lie-in","Long walk","Friends over","Market run"],"answered":true,"answer":null,
            "last":{"round":0,"subject":"maya","guesser":"theo","question":"Your dream holiday?","choices":["Beach","Mountains","Big city","Road trip"],"answer":0,"guess":0,"right":true}}""", "maya")
    }

    /** Maya answers about herself. */
    @Test fun answering() = shot("games-guess-answer") {
        screen("""{"players":["maya","theo"],"scores":{"maya":1,"theo":1},"round":2,"totalRounds":8,"phase":"answering","subject":"maya","guesser":"theo",
            "question":"Your go-to comfort food?","choices":["Pizza","Noodles","Soup","Chocolate"],"answered":false,"answer":null,
            "last":{"round":1,"subject":"theo","guesser":"maya","question":"Your ideal Saturday?","choices":["Lie-in","Long walk","Friends over","Market run"],"answer":1,"guess":1,"right":true}}""", "maya")
    }

    /** Night, Naija questions: Maya's answer is locked in while Theo guesses; last round's miss is revealed. */
    @Test fun revealNight() = shot("games-guess-reveal-night", night = true) {
        screen("""{"players":["maya","theo"],"scores":{"maya":2,"theo":3},"round":6,"totalRounds":8,"phase":"guessing","subject":"maya","guesser":"theo",
            "question":"Your owambe go-to?","choices":["Jollof","Small chops","Asun","Moi moi"],"answered":true,"answer":0,
            "last":{"round":5,"subject":"theo","guesser":"maya","question":"Your swallow of choice?","choices":["Pounded yam","Eba","Amala","Semo"],"answer":2,"guess":0,"right":false}}""", "theo")
    }
}
