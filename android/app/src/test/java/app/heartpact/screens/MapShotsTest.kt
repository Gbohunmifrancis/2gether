package app.heartpact.screens

import app.heartpact.data.DailyQuestion
import app.heartpact.data.LocationPoint
import app.heartpact.data.NotificationPreferences
import app.heartpact.data.SharingState
import app.heartpact.data.SharingStatus
import app.heartpact.data.SharingStatusView
import app.heartpact.ui.home.HomeScreen
import app.heartpact.ui.home.Tab
import app.heartpact.ui.map.LocationCard
import app.heartpact.ui.map.LocationUi
import app.heartpact.ui.map.MapActions
import app.heartpact.ui.map.MapScreen
import app.heartpact.ui.us.UsTab
import app.heartpact.ui.us.UsUi
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class MapShotsTest {
    private val now = Instant.parse("2026-10-11T18:04:00Z")
    private val points = listOf(
        LocationPoint("maya", "Maya", latitude = 6.4300, longitude = 3.4200, updatedAtUtc = "2026-10-11T18:03:00Z", isCurrentUser = true),
        LocationPoint("theo", "Theo", latitude = 6.4400, longitude = 3.4350, updatedAtUtc = "2026-10-11T18:02:00Z"),
    )
    private val both = SharingStatusView(SharingState("maya", "Maya", SharingStatus.Sharing), SharingState("theo", "Theo", SharingStatus.Sharing), mutual = true)

    @Test fun map() {
        shot("map") { MapScreen(LocationUi(both, points, sendingHere = true), "Theo", MapActions(), now, drawn = true, myName = "Maya") }
        shot("map-off") { MapScreen(LocationUi(SharingStatusView(SharingState("maya"), SharingState("theo", "Theo", SharingStatus.Sharing))), "Theo", MapActions(), now, drawn = true) }
    }

    @Test fun usWithLocation() = shot("us-location") {
        HomeScreen("Maya", "Theo", {}, initialTab = Tab.Us, us = {
            UsTab(UsUi(question = DailyQuestion("q", "2026-10-11", text = "?", partnerAnswered = true), preferences = NotificationPreferences(quietHoursEnabled = true)),
                "Maya", "Theo", "together since 2 March", {}, {}, {}, {}, {}, {},
                location = { LocationCard(LocationUi(both, points, sendingHere = true), "Theo", {}, {}, {}, {}, now) })
        })
    }
}
