package app.heartpact.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import app.heartpact.data.Gender
import app.heartpact.data.PartnerInvite
import app.heartpact.i18n.Tr
import app.heartpact.ui.auth.SignUpForm
import app.heartpact.ui.auth.SignUpScreen
import app.heartpact.ui.auth.SignUpState
import app.heartpact.ui.pair.InviteInput
import app.heartpact.ui.pair.JoiningMoment
import app.heartpact.ui.pair.LinkConfirm
import app.heartpact.ui.pair.LinkedCelebration
import app.heartpact.ui.pair.LinkedTimeline
import app.heartpact.ui.pair.PairActions
import app.heartpact.ui.pair.PairScreen
import app.heartpact.ui.pair.PairState
import app.heartpact.ui.pair.ScannerFrame
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

/** Linking up: the code boxes with a paste offer, the QR card, the scanner, joining through a link and the celebration. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class LinkingShotsTest {
    private val now = Instant.parse("2026-10-11T10:00:00Z")
    private val token = "3f9a0c2b7d41e8a6b5c90d1e2f3a4b5c6d7e8f9a0b1c2d3e"
    private val invite = PartnerInvite("i1", "482039", token, "2026-10-13T09:14:00Z")
    private val english = Locale.getDefault()

    @After fun back() {
        Locale.setDefault(english)
        Tr.use("en", null)
    }

    @Test fun codeBoxes() {
        shot("pair-code-boxes") { PairScreen(PairState(invite = invite, loadingInvite = false, entering = true, code = "48"), PairActions(), now = now, pasteOffer = InviteInput.Code("482031")) }
        shot("pair-code-boxes-link") { PairScreen(PairState(invite = invite, loadingInvite = false, entering = true), PairActions(), now = now, pasteOffer = InviteInput.Link(token)) }
        shot("pair-code-boxes-checking") { PairScreen(PairState(invite = invite, loadingInvite = false, entering = true, code = "482031", accepting = true), PairActions(), now = now, pasteOffer = InviteInput.Code("000000")) }
    }

    @Test fun qrAndCountdown() {
        shot("pair-qr") { PairScreen(PairState(invite = invite, loadingInvite = false), PairActions(), onLater = {}, now = now) }
        shot("pair-countdown") { PairScreen(PairState(invite = invite.copy(expiresAtUtc = "2026-10-11T10:12:00Z"), loadingInvite = false), PairActions(), onLater = {}, now = now) }
        shot("pair-expired") { PairScreen(PairState(invite = invite.copy(expiresAtUtc = "2026-10-11T09:59:00Z"), loadingInvite = false), PairActions(), onLater = {}, now = now) }
        shot("pair-confirm-link") { PairScreen(PairState(invite = invite, loadingInvite = false, confirm = LinkConfirm(token, "Theo")), PairActions(), onLater = {}, now = now) }
        shot("pair-night", night = true) { PairScreen(PairState(invite = invite, loadingInvite = false), PairActions(), onLater = {}, now = now) }
    }

    @Test fun scanner() {
        // The camera itself can't run here: a soft blurred room stands in for it.
        val room: @Composable () -> Unit = {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF6B5A4E), Color(0xFF3A3340), Color(0xFF231F2B)))))
        }
        shot("pair-scanner") { ScannerFrame(null, {}, preview = room) }
        shot("pair-scanner-wrong") { ScannerFrame("That's not a Heartpact code. Scan the one on your partner's \"Find your other half\" screen.", {}, preview = room) }
    }

    @Test fun joiningAndLinked() {
        shot("pair-joining") { JoiningMoment("Theo") }
        shot("linked-walking") { LinkedCelebration("Theo", {}, frozenAtMs = LinkedTimeline.WALK_MS / 2) }
        shot("linked") { LinkedCelebration("Theo", {}, frozenAtMs = LinkedTimeline.END_MS) }
        shot("linked-night", night = true) { LinkedCelebration("Maya", {}, frozenAtMs = LinkedTimeline.END_MS) }
    }

    @Test fun nightSignUp() {
        val filled = SignUpForm("Maya", "maya@example.com", "Maya&Theo1996", LocalDate.of(1996, 5, 14), Gender.Female, "NG")
        shot("sign-up-night", night = true) { SignUpScreen(SignUpState(form = filled), {}, {}, {}, {}, onGoogle = {}) }
        shot("sign-up-about-night", night = true) { SignUpScreen(SignUpState(form = filled, step = 2), {}, {}, {}, {}) }
    }

    @Test fun arabic() {
        val file = File("src/main/assets/i18n/ar.json")
        if (!file.exists()) return
        Locale.setDefault(Locale.forLanguageTag("ar"))
        Tr.use("ar", file.readText())
        fun at(name: String, content: @Composable () -> Unit) = shot("ar/$name") {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { content() }
        }
        at("pair-code-boxes") { PairScreen(PairState(invite = invite, loadingInvite = false, entering = true, code = "48"), PairActions(), now = now, pasteOffer = InviteInput.Code("482031")) }
        at("pair-qr") { PairScreen(PairState(invite = invite.copy(expiresAtUtc = "2026-10-11T10:12:00Z"), loadingInvite = false), PairActions(), onLater = {}, now = now) }
        at("pair-joining") { JoiningMoment("Theo") }
        at("sign-up-about") { SignUpScreen(SignUpState(form = SignUpForm("Maya", "maya@example.com", "longenough", LocalDate.of(1996, 5, 14), Gender.Female, "NG"), step = 2), {}, {}, {}, {}) }
    }
}
