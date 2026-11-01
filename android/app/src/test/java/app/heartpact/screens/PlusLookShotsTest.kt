package app.heartpact.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import app.heartpact.data.DailyQuestion
import app.heartpact.data.Entitlement
import app.heartpact.data.ExerciseGoal
import app.heartpact.data.ExerciseHistory
import app.heartpact.data.ExerciseToday
import app.heartpact.data.NotificationPreferences
import app.heartpact.data.Plan
import app.heartpact.data.PlanLimits
import app.heartpact.data.SubscriptionSummary
import app.heartpact.exercise.ExerciseType
import app.heartpact.i18n.Tr
import app.heartpact.ui.components.Face
import app.heartpact.ui.home.HomeScreen
import app.heartpact.ui.home.Tab
import app.heartpact.ui.plus.Celebration
import app.heartpact.ui.plus.CelebrationKind
import app.heartpact.ui.plus.PlusCelebrationScreen
import app.heartpact.ui.plus.PlusUsRow
import app.heartpact.ui.theme.Side
import app.heartpact.ui.today.TodayBuilder
import app.heartpact.ui.today.TodayTab
import app.heartpact.ui.today.TodayUi
import app.heartpact.ui.us.UsTab
import app.heartpact.ui.us.UsUi
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.util.Locale

/** The Plus celebration in its three variants (and dark, and Arabic), and Today and Us with the Plus look. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class PlusLookShotsTest {
    private val english = Locale.getDefault()
    private val limits = PlanLimits(20, 2L * 1024 * 1024 * 1024, 90)
    private val mine = SubscriptionSummary("googlePlay", "heartpact_plus_yearly", "active", "2027-10-11T10:00:00Z", autoRenewing = true, purchasedByUserId = "maya", purchasedByMe = true)
    private val plus = Entitlement(Plan.Plus, limits, mine, plusLimits = limits)
    private val now = Instant.parse("2026-10-11T07:48:00Z")

    @After fun back() {
        Locale.setDefault(english)
        Tr.use("en", null)
    }

    @Test fun boughtByMe() = shot("plus-celebration-mine") { PlusCelebrationScreen(Celebration(CelebrationKind.BoughtByMe, limits = limits), "Theo", {}) }

    @Test fun boughtByPartner() = shot("plus-celebration-partner") { PlusCelebrationScreen(Celebration(CelebrationKind.BoughtByPartner, limits = limits), "Theo", {}) }

    @Test fun gift() = shot("plus-celebration-gift") { PlusCelebrationScreen(Celebration(CelebrationKind.Gift, "2026-12-01T12:00:00Z", limits), "Theo", {}) }

    @Test fun night() = shot("plus-celebration-partner-night", night = true) { PlusCelebrationScreen(Celebration(CelebrationKind.BoughtByPartner, limits = limits), "Theo", {}) }

    @Test fun arabic() {
        val file = File("src/main/assets/i18n/ar.json")
        Locale.setDefault(Locale.forLanguageTag("ar"))
        Tr.use("ar", file.readText())
        shot("ar/plus-celebration-gift") {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                PlusCelebrationScreen(Celebration(CelebrationKind.Gift, "2026-12-01T12:00:00Z", limits), "Theo", {})
            }
        }
        shot("ar/us-plus") {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { UsPage() }
        }
    }

    @Test fun today() {
        val goal = ExerciseGoal("g2", "theo", "Theo", "maya", ExerciseType.PushUp, "Push-ups", 30, scheduledTime = "18:30", timeZoneId = "UTC", nextDueAtUtc = "2026-10-11T18:30:00Z")
        val view = TodayBuilder.build(ExerciseToday("x", listOf(goal), emptyList()), ExerciseHistory(7, emptyList()), "maya", now, java.time.ZoneOffset.UTC)
        shot("today-plus") {
            HomeScreen("Maya", "Theo", {}, today = { TodayTab(TodayUi(view = view, loading = false), "Theo", {}, onCallOff = {}, onKeep = {}, onNewDare = {}, now = now, isPlus = true) })
        }
    }

    @Test fun us() {
        shot("us-plus") { UsPage() }
        shot("us-plus-night", night = true) { UsPage() }
        // Free: nothing new.
        shot("us-free") { UsPage(Entitlement(Plan.Free, PlanLimits(3, 500L * 1024 * 1024, 7), plusLimits = limits)) }
    }

    @androidx.compose.runtime.Composable
    private fun UsPage(entitlement: Entitlement = plus) {
        val question = DailyQuestion("q", "2026-10-11", "future-03", "future", "If we had one extra free weekend every month, what would you want us to do with it?",
            partnerAnswered = true, streakDays = 14)
        HomeScreen("Maya", "Theo", {}, initialTab = Tab.Us, us = {
            UsTab(UsUi(question = question, preferences = NotificationPreferences()), Face("maya", "Maya", 0, Side.Left), Face("theo", "Theo", 0, Side.Right),
                "together since 2 March", {}, {}, {}, extraRows = { PlusUsRow(entitlement, "Theo", onClick = {}) }, isPlus = entitlement.plan == Plan.Plus)
        })
    }
}
