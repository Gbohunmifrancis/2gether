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
class WouldYouRatherShotsTest {
    private val now = Instant.parse("2026-10-11T18:00:00Z")
    @Composable private fun screen(state: String) = GamesScreen(
        GamesUi(listOf(GameSession("g", "c", GameType.WouldYouRather, GameStatus.InProgress, currentTurnUserId = "maya", stateVersion = 4, stateJson = state, invitedByUserId = "theo"))),
        "maya", "Theo", GameActions(), { now })

    /** Theo has picked; Maya still has to. Last round matched. */
    @Test fun picking() = shot("games-rather") {
        screen("""{"players":["maya","theo"],"scores":{"maya":3,"theo":3},"round":4,"totalRounds":10,"phase":"picking","a":"Breakfast in bed","b":"Dinner under the stars",
            "picked":["theo"],"myPick":null,"last":{"round":3,"a":"A beach holiday","b":"A mountain cabin","picks":{"maya":1,"theo":1},"match":true}}""")
    }

    /** Night, Naija questions: Maya picked, waiting for Theo; last round's picks differed. */
    @Test fun pickedNight() = shot("games-rather-picked-night", night = true) {
        screen("""{"players":["maya","theo"],"scores":{"maya":5,"theo":5},"round":7,"totalRounds":10,"phase":"picking","a":"Jollof at a party","b":"Suya at night",
            "picked":["maya"],"myPick":1,"last":{"round":6,"a":"Danfo in traffic","b":"Okada in the rain","picks":{"maya":0,"theo":1},"match":false}}""")
    }
}
