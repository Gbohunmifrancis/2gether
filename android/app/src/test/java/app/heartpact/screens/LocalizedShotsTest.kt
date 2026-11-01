package app.heartpact.screens

import app.heartpact.ui.theme.Side
import app.heartpact.ui.components.Face
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import app.heartpact.data.DailyQuestion
import app.heartpact.data.Gender
import app.heartpact.data.NotificationPreferences
import app.heartpact.exercise.ExerciseType
import app.heartpact.i18n.Tr
import app.heartpact.ui.auth.SignUpForm
import app.heartpact.ui.auth.SignUpScreen
import app.heartpact.ui.auth.SignUpState
import app.heartpact.ui.auth.WelcomeScreen
import app.heartpact.ui.home.HomeScreen
import app.heartpact.ui.home.Tab
import app.heartpact.ui.onboarding.OnboardingScreen
import app.heartpact.ui.today.NewDareScreen
import app.heartpact.ui.today.NewDareState
import app.heartpact.ui.us.UsTab
import app.heartpact.ui.us.UsUi
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.util.Locale

/** A few screens in each language, to check translations fit and Arabic lays out right to left (docs/translation.md). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class LocalizedShotsTest {
    private val english = Locale.getDefault()

    @After fun back() {
        Locale.setDefault(english)
        Tr.use("en", null)
    }

    @Test fun french() = screens("fr")
    @Test fun portuguese() = screens("pt")
    @Test fun spanish() = screens("es")
    @Test fun arabic() = screens("ar")
    @Test fun hindi() = screens("hi")

    private fun screens(language: String) {
        val file = File("src/main/assets/i18n/$language.json")
        if (!file.exists()) return
        Locale.setDefault(Locale.forLanguageTag(language))
        Tr.use(language, file.readText())
        val rtl = language == "ar"
        fun at(name: String, content: @Composable () -> Unit) = shot("$language/$name") {
            CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) { content() }
        }
        val question = DailyQuestion("q", "2026-10-11", "future-03", "future", "If we had one extra free weekend every month, what would you want us to do with it?",
            partnerAnswered = true, streakDays = 14)
        at("onboarding") { OnboardingScreen(onDone = {}, initialPage = 0) }
        at("welcome") { WelcomeScreen({}, {}, onGoogle = {}) }
        at("sign-up") { SignUpScreen(SignUpState(form = SignUpForm("Maya", "maya@example.com", "longenough", LocalDate.of(1996, 5, 14), Gender.Female, "NG")), {}, {}, {}, {}, onGoogle = {}) }
        at("sign-up-about") { SignUpScreen(SignUpState(form = SignUpForm("Maya", "maya@example.com", "longenough", LocalDate.of(1996, 5, 14), Gender.Female, "NG"), step = 2), {}, {}, {}, {}) }
        at("sign-in") { app.heartpact.ui.auth.SignInScreen(app.heartpact.ui.auth.SignInState(email = "maya@example.com", password = "longenough"), {}, {}, {}, {}, {}, {}, onGoogle = {}) }
        at("pair") {
            app.heartpact.ui.pair.PairScreen(app.heartpact.ui.pair.PairState(invite = app.heartpact.data.PartnerInvite("i1", "482039", "ab".repeat(24), "2026-10-11T10:12:00Z"), loadingInvite = false),
                app.heartpact.ui.pair.PairActions(), onLater = {}, now = java.time.Instant.parse("2026-10-11T10:00:00Z"))
        }
        at("pair-enter-code") {
            app.heartpact.ui.pair.PairScreen(app.heartpact.ui.pair.PairState(loadingInvite = false, entering = true, code = "482"), app.heartpact.ui.pair.PairActions(),
                pasteOffer = app.heartpact.ui.pair.InviteInput.Code("482031"))
        }
        at("linked") { app.heartpact.ui.pair.LinkedCelebration("Theo", {}, frozenAtMs = app.heartpact.ui.pair.LinkedTimeline.END_MS) }
        at("today") { HomeScreen("Maya", "Theo", {}) }
        at("new-dare") { NewDareScreen(NewDareState(type = ExerciseType.PushUp, target = 30), "Theo", {}, {}, {}, {}, {}, {}) }
        at("us") {
            HomeScreen("Maya", "Theo", {}, initialTab = Tab.Us, us = {
                UsTab(UsUi(question = question, preferences = NotificationPreferences(quietHoursEnabled = true)), Face("maya", "Maya", 0, Side.Left), Face("theo", "Theo", 0, Side.Right), "2 March", {}, {}, {})
            })
        }
    }
}
