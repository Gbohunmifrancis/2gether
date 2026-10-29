package app.heartpact.screens

import androidx.compose.runtime.Composable
import app.heartpact.data.GameSession
import app.heartpact.data.GameStatus
import app.heartpact.data.GameType
import app.heartpact.ui.games.GameActions
import app.heartpact.ui.games.GamesScreen
import app.heartpact.ui.games.GamesUi
import app.heartpact.ui.play.SkillBests
import app.heartpact.ui.play.SkillKind
import app.heartpact.ui.play.SkillResult
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/** The games hub: the two of you, how the last game went, and the two grids of blobs. Day and night, phone and tablet. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class GamesHubShotsTest {
    private val now = Instant.parse("2026-10-11T18:00:00Z")
    private fun finished(id: String, type: GameType, state: String) =
        GameSession(id, "c", type, GameStatus.Completed, endedAtUtc = "2026-10-10T20:00:00Z", stateVersion = 9, stateJson = state, invitedByUserId = "theo")

    private val history = listOf(
        finished("g1", GameType.MemoryMatch, """{"players":["maya","theo"],"scores":{"maya":4,"theo":2},"cards":[]}"""),
        finished("g2", GameType.TicTacToe, """{"players":["maya","theo"],"cells":[],"game":3,"totalGames":5,"winsNeeded":3,"phase":"matchOver","scores":{"maya":1,"theo":3},"results":["maya","theo","theo","theo"]}"""),
        finished("g3", GameType.WouldYouRather, """{"players":["maya","theo"],"scores":{"maya":7,"theo":7},"round":10,"totalRounds":10}"""),
    )

    private val bests = object : SkillBests {
        private val kept = mapOf(SkillKind.FairShare to 88, SkillKind.FillTheHeart to 83, SkillKind.PromiseRing to 71)
        override fun best(kind: SkillKind) = kept[kind]
        override fun record(result: SkillResult) {}
    }

    @Composable private fun hub(sessions: List<GameSession> = history) = GamesScreen(GamesUi(sessions, loaded = true), "maya", "Theo", GameActions(), { now }, bests)

    @Test fun phone() = shot("games-hub") { hub() }
    @Test fun phoneNight() = shot("games-hub-night", night = true) { hub() }

    /** The whole hub on one tall screen: both grids, their picks, and the recent games. */
    @Test @Config(qualifiers = "w390dp-h1900dp-xxhdpi") fun phoneWhole() {
        shot("games-hub-whole") { hub() }
        shot("games-hub-whole-night", night = true) { hub() }
    }

    /** No games played yet. */
    @Test fun firstTime() = shot("games-hub-new") { hub(emptyList()) }

    @Test @Config(qualifiers = "w800dp-h1280dp-xhdpi") fun tablet() = shot("games-hub-tablet") { hub() }
    @Test @Config(qualifiers = "w1280dp-h800dp-land-xhdpi") fun tabletLandscape() {
        shot("games-hub-tablet-land") { hub() }
        shot("games-hub-tablet-land-night", night = true) { hub() }
    }
}
