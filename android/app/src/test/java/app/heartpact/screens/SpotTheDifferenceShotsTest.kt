package app.heartpact.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.heartpact.data.GameSession
import app.heartpact.data.GameStatus
import app.heartpact.data.GameType
import app.heartpact.ui.games.GameActions
import app.heartpact.ui.games.GamesScreen
import app.heartpact.ui.games.GamesUi
import app.heartpact.ui.games.SceneKind
import app.heartpact.ui.games.SpotScenes
import app.heartpact.ui.games.drawSpotScene
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class SpotTheDifferenceShotsTest {
    private val now = Instant.parse("2026-10-31T18:00:00Z")

    @Composable private fun screen(id: String, state: String, status: GameStatus = GameStatus.InProgress, deadline: String? = "2026-10-31T18:01:02Z") = GamesScreen(
        GamesUi(listOf(GameSession(id, "c", GameType.SpotTheDifference, status, currentTurnUserId = "maya", deadlineUtc = deadline, stateVersion = 6, stateJson = state,
            invitedByUserId = "theo", endedAtUtc = if (status == GameStatus.Completed) "2026-10-31T17:58:00Z" else null))),
        "maya", "Theo", GameActions(), { now }, myName = "Maya")

    private fun finds(vararg who: Pair<Int, String>) = who.joinToString(",") { (i, by) -> """{"index":$i,"by":"$by"}""" }

    private fun picture(index: Int, kind: String, seed: Int, found: String = "", timedOut: Boolean = false) =
        """{"index":$index,"kind":"$kind","seed":$seed,"found":[$found],"timedOut":$timedOut}"""

    private fun state(scene: Int, phase: String, pictures: List<String>, current: Pair<String, Int>, found: String, maya: Int, theo: Int, end: String = """"winner":null,"tie":false""") =
        """{"players":["maya","theo"],"phase":"$phase","scene":$scene,"totalScenes":3,"differences":7,"sceneSeconds":90,"beatSeconds":3,
            "kind":"${current.first}","seed":${current.second},"found":[$found],"scenes":[${pictures.joinToString(",")}],"scores":{"maya":$maya,"theo":$theo},$end}"""

    /** The first picture, nothing found yet. */
    @Test fun start() = shot("games-spot-start") {
        screen("s1", state(0, "spotting", listOf(picture(0, "Picnic", 1_204_811)), "Picnic" to 1_204_811, "", 0, 0))
    }

    /** The second picture, both partners' rings on it. */
    @Test fun midGame() {
        val found = finds(0 to "maya", 2 to "theo", 3 to "maya", 5 to "theo")
        val state = state(1, "spotting", listOf(picture(0, "Kitchen", 77, finds(0 to "maya", 1 to "maya", 2 to "theo", 3 to "maya", 4 to "theo", 5 to "maya", 6 to "theo")),
            picture(1, "LivingRoom", 90_210, found)), "LivingRoom" to 90_210, found, 6, 5)
        shot("games-spot-mid") { screen("s2", state, deadline = "2026-10-31T18:00:41Z") }
        shot("games-spot-mid-night", night = true) { screen("s2n", state, deadline = "2026-10-31T18:00:41Z") }
    }

    /** Time ran out on a picture: the ones nobody found circled, the next one coming up. */
    @Test fun betweenPictures() {
        val found = finds(1 to "theo", 4 to "maya", 6 to "maya")
        shot("games-spot-between") {
            screen("s3", state(1, "between", listOf(picture(0, "Beach", 5), picture(1, "NightSky", 31_337, found, timedOut = true)), "NightSky" to 31_337, found, 6, 4),
                deadline = "2026-10-31T18:00:02Z")
        }
    }

    /** The end: Maya won 12 to 9, each picture small with its rings. */
    @Test @Config(qualifiers = "w390dp-h1300dp-xxhdpi") fun end() = shot("games-spot-end") {
        val all = listOf(
            picture(0, "Garden", 404, finds(0 to "maya", 1 to "theo", 2 to "maya", 3 to "maya", 4 to "theo", 5 to "maya", 6 to "theo")),
            picture(1, "Cafe", 8_080, finds(0 to "theo", 1 to "maya", 2 to "maya", 3 to "theo", 4 to "maya", 5 to "maya", 6 to "theo")),
            picture(2, "Bedroom", 1_999, finds(0 to "maya", 1 to "theo", 2 to "maya", 4 to "theo", 5 to "maya"), timedOut = true),
        )
        screen("s4", state(2, "completed", all, "Bedroom" to 1_999, "", 12, 9, """"winner":"maya","tie":false"""), GameStatus.Completed, deadline = null)
    }

    /** On a tablet the two pictures sit side by side. */
    @Test @Config(qualifiers = "w900dp-h700dp-xhdpi") fun tablet() = shot("games-spot-tablet") {
        val found = finds(1 to "maya", 2 to "theo")
        screen("s5", state(0, "spotting", listOf(picture(0, "Garden", 2_024, found)), "Garden" to 2_024, found, 1, 1))
    }

    /** Every kind of picture with its twin, the differences ringed: for checking the art and that each difference shows. */
    @Test @Config(qualifiers = "w820dp-h2700dp-xhdpi") fun gallery() = shot("games-spot-gallery") {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SceneKind.entries.forEach { kind ->
                val scene = SpotScenes.build(kind, kind.ordinal * 1_000 + 7)
                Text("$kind: ${scene.spots.joinToString { it.kind.name }}")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(false, true).forEach { changed ->
                        Canvas(Modifier.weight(1f).aspectRatio(1.5f)) {
                            drawSpotScene(scene, changed)
                            val unit = size.width / SpotScenes.WIDTH
                            if (changed) scene.spots.forEach { s -> drawCircle(Color.Red, s.r * unit, Offset(s.x * unit, s.y * unit), style = Stroke(1.5f)) }
                        }
                    }
                }
            }
        }
    }

    /** The hub with Spot the difference picked. */
    @Test @Config(qualifiers = "w390dp-h1100dp-xxhdpi") fun hub() = shot("games-spot-hub") {
        GamesScreen(GamesUi(loaded = true, region = "global"), "maya", "Theo", GameActions(), { now })
    }
}
