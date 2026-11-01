package app.heartpact.screens

import app.heartpact.data.Gender
import app.heartpact.data.PartnerInvite
import app.heartpact.ui.auth.SignInScreen
import app.heartpact.ui.auth.SignInState
import app.heartpact.ui.auth.SignUpForm
import app.heartpact.ui.auth.SignUpScreen
import app.heartpact.ui.auth.SignUpState
import app.heartpact.ui.auth.WelcomeScreen
import app.heartpact.ui.home.HomeScreen
import app.heartpact.ui.home.Tab
import app.heartpact.ui.onboarding.OnboardingScreen
import app.heartpact.ui.pair.PairActions
import app.heartpact.ui.pair.PairScreen
import app.heartpact.ui.pair.PairState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.time.LocalDate

/** Every wired screen, rendered without a device, to hold up against design/heartpact-v3/png. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class ScreenShotsTest {
    @Test fun onboarding() {
        (0..2).forEach { page -> shot("onboarding-$page") { OnboardingScreen(onDone = {}, initialPage = page) } }
    }

    @Test fun welcome() = shot("welcome") { WelcomeScreen({}, {}) }

    @Test fun signUp() {
        val filled = SignUpForm("Maya", "maya@example.com", "Maya&Theo1996", LocalDate.of(1996, 5, 14), Gender.Female, "NG")
        // Step 1: the account, with Google first.
        shot("sign-up") { SignUpScreen(SignUpState(form = filled), {}, {}, {}, {}, onGoogle = {}) }
        shot("sign-up-google") { SignUpScreen(SignUpState(form = SignUpForm(country = "NG")), {}, {}, {}, {}, onGoogle = {}) }
        shot("sign-up-errors") { SignUpScreen(SignUpState(form = SignUpForm(email = "maya@", password = "short"), showErrors = true), {}, {}, {}, {}) }
        shot("sign-up-weak") { SignUpScreen(SignUpState(form = filled.copy(password = "password1")), {}, {}, {}, {}) }
        // Step 2: about you.
        shot("sign-up-about") { SignUpScreen(SignUpState(form = filled, step = 2), {}, {}, {}, {}) }
        shot("sign-up-about-empty") { SignUpScreen(SignUpState(form = SignUpForm(name = "Maya", country = "NG"), step = 2, showErrors = true), {}, {}, {}, {}) }
        shot("sign-up-too-young") { SignUpScreen(SignUpState(form = filled.copy(dateOfBirth = java.time.LocalDate.now().minusYears(17)), step = 2, showErrors = true), {}, {}, {}, {}) }
        shot("sign-up-invited") { SignUpScreen(SignUpState(form = SignUpForm(country = "NG")), {}, {}, {}, {}, onGoogle = {}, invitedBy = "Theo") }
    }

    @Test fun signIn() {
        shot("sign-in") { SignInScreen(SignInState(email = "maya@example.com", password = "longenough"), {}, {}, {}, {}, {}, {}, onGoogle = {}) }
        shot("sign-in-error") { SignInScreen(SignInState(email = "maya@example.com", password = "wrong", error = "Email or password is incorrect.", wrongPassword = true), {}, {}, {}, {}, {}, {}, onGoogle = {}) }
        shot("sign-in-no-google") { SignInScreen(SignInState(email = "maya@example.com", password = "longenough"), {}, {}, {}, {}, {}, {}) }
    }

    @Test fun pair() {
        val now = Instant.parse("2026-10-11T10:00:00Z")
        val invite = PartnerInvite("i1", "482039", "ab12cd34ef56ab12cd34ef56ab12cd34ef56ab12cd34ef56", "2026-10-13T09:14:00Z")
        shot("pair") { PairScreen(PairState(invite = invite, loadingInvite = false), PairActions(), onLater = {}, now = now) }
        shot("pair-from-home") { PairScreen(PairState(invite = invite, loadingInvite = false), PairActions(), onBack = {}, now = now) }
        shot("home-connect-banner") { app.heartpact.ui.home.ConnectBanner(onConnect = {}) }
        shot("pair-enter-code") {
            PairScreen(PairState(invite = invite, loadingInvite = false, entering = true, code = "48203", codeError = "That code didn't work. Check it with your partner, or ask for a new one."),
                PairActions(), now = now)
        }
    }

    @Test fun home() {
        shot("home-today") { HomeScreen("Maya", "Theo", {}) }
        shot("home-us") { HomeScreen("Maya", "Theo", {}, initialTab = Tab.Us) }
        shot("home-today-night", night = true) { HomeScreen("Maya", "Theo", {}) }
    }
}
