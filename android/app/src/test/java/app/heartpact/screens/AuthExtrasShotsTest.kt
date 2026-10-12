package app.heartpact.screens

import app.heartpact.data.Gender
import app.heartpact.ui.auth.ForgotPasswordScreen
import app.heartpact.ui.auth.ForgotState
import app.heartpact.ui.auth.GoogleFinishScreen
import app.heartpact.ui.auth.GoogleState
import app.heartpact.ui.auth.WelcomeScreen
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class AuthExtrasShotsTest {
    @Test fun forgot() {
        shot("forgot") { ForgotPasswordScreen(ForgotState(email = "maya@example.com"), {}, {}, {}, {}, {}, {}) }
        shot("forgot-code") { ForgotPasswordScreen(ForgotState(email = "maya@example.com", code = "0429", sent = true, note = "If that email has an account, a code is on its way. It works for 15 minutes."), {}, {}, {}, {}, {}, {}) }
    }

    @Test fun google() {
        shot("welcome-google") { WelcomeScreen({}, {}, onGoogle = {}) }
        shot("google-finish") { GoogleFinishScreen(GoogleState(finishing = true, name = "Maya Okafor", dateOfBirth = LocalDate.of(1996, 5, 14), gender = Gender.Female), {}, {}, {}, {}, {}) }
    }
}
