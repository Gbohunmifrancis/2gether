package app.heartpact.ui.auth

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.heartpact.BuildConfig
import app.heartpact.data.ApiResult
import app.heartpact.data.AuthRepository
import app.heartpact.data.Countries
import app.heartpact.data.Gender
import app.heartpact.data.GoogleSignInRequest
import app.heartpact.data.OFFLINE_MESSAGE
import app.heartpact.i18n.tr
import app.heartpact.i18n.trn
import app.heartpact.ui.components.ButtonKind
import app.heartpact.ui.components.ConsentLine
import app.heartpact.ui.components.HeartButton
import app.heartpact.ui.components.HeartField
import app.heartpact.ui.components.NoteCard
import app.heartpact.ui.components.TopBar
import app.heartpact.ui.graphics.LiveLogo
import app.heartpact.ui.graphics.LogoMood
import app.heartpact.ui.theme.Heartpact
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private fun <T> ApiResult<T>.note(): String? = when (this) {
    is ApiResult.Ok -> null
    ApiResult.Offline -> OFFLINE_MESSAGE
    is ApiResult.Failure -> message
}

data class ForgotState(
    val email: String = "",
    val code: String = "",
    val password: String = "",
    /** The code was sent (or would have been): the second step shows. */
    val sent: Boolean = false,
    val note: String? = null,
    val error: String? = null,
    val busy: Boolean = false,
)

/** Forgot password: an emailed six-digit code, then a new password. Signs in at the end. */
class ForgotViewModel(private val auth: AuthRepository, email: String = "") : ViewModel() {
    private val _state = MutableStateFlow(ForgotState(email = email))
    val state: StateFlow<ForgotState> = _state.asStateFlow()

    fun email(value: String) = _state.update { it.copy(email = value, error = null) }
    fun code(value: String) = _state.update { it.copy(code = value.filter(Char::isDigit).take(6), error = null) }
    fun password(value: String) = _state.update { it.copy(password = value, error = null) }
    fun back() = _state.update { it.copy(sent = false, code = "", password = "", note = null, error = null) }

    fun send() {
        if (!looksLikeEmail(_state.value.email)) { _state.update { it.copy(error = tr("Enter the email you signed up with.")) }; return }
        _state.update { it.copy(busy = true, note = null) }
        viewModelScope.launch {
            val result = auth.forgot(_state.value.email)
            _state.update {
                if (result is ApiResult.Ok) it.copy(busy = false, sent = true, note = tr("If that email has an account, a code is on its way. It works for 15 minutes."))
                else it.copy(busy = false, note = result.note())
            }
        }
    }

    fun reset(onDone: () -> Unit) {
        val s = _state.value
        when {
            s.code.length != 6 -> { _state.update { it.copy(error = tr("Enter the six-digit code from the email.")) }; return }
            s.password.length < SignUpForm.MIN_PASSWORD -> { _state.update { it.copy(error = trn(SignUpForm.MIN_PASSWORD, "Use at least {count} character.", "Use at least {count} characters.")) }; return }
        }
        _state.update { it.copy(busy = true, note = null) }
        viewModelScope.launch {
            val result = auth.reset(s.email, s.code, s.password)
            _state.update { it.copy(busy = false, note = result.note()) }
            if (result is ApiResult.Ok) onDone()
        }
    }
}

@Composable
fun ForgotPasswordScreen(state: ForgotState, onEmail: (String) -> Unit, onCode: (String) -> Unit, onPassword: (String) -> Unit, onSend: () -> Unit, onReset: () -> Unit, onBack: () -> Unit) {
    val colors = Heartpact.colors
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        TopBar(tr("Reset your password"), onBack) { LiveLogo(30.dp, if (state.error != null) LogoMood.Worried else LogoMood.Hello, night = colors.isNight) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!state.sent) {
                Text(tr("We'll email you a six-digit code. Your pact, dares and photos stay just as they are."), style = Heartpact.type.bodyLarge, color = colors.ink2)
                HeartField(tr("Email"), state.email, onEmail, error = state.error,
                    keyboard = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done))
            } else {
                Text(tr("Check {email} for the code, then choose a new password. Signing in with it signs you out everywhere else.", "email" to state.email), style = Heartpact.type.bodyLarge, color = colors.ink2)
                HeartField(tr("Six-digit code"), state.code, onCode, keyboard = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Next))
                HeartField(tr("New password"), state.password, onPassword, password = true, error = state.error, hint = trn(SignUpForm.MIN_PASSWORD, "At least {count} character", "At least {count} characters"),
                    keyboard = KeyboardOptions(imeAction = ImeAction.Done))
                HeartButton(tr("Send a new code"), onSend, kind = ButtonKind.Text, fillWidth = false, height = 40.dp)
            }
            state.note?.let { NoteCard(it) }
        }
        HeartButton(if (state.sent) tr("Set password and sign in") else tr("Email me a code"), if (state.sent) onReset else onSend,
            Modifier.padding(horizontal = 20.dp, vertical = 14.dp), busy = state.busy)
    }
}

/** Google sign-in through Credential Manager; available when the build has the web client id. */
object GoogleSignIn {
    val available: Boolean get() = BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()

    sealed interface Outcome {
        data class Token(val idToken: String, val name: String?) : Outcome
        data object Cancelled : Outcome
        data class Failed(val message: String) : Outcome
    }

    suspend fun requestToken(context: Context): Outcome {
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID)
            .setFilterByAuthorizedAccounts(false)
            .build()
        return try {
            val credential = CredentialManager.create(context).getCredential(context, GetCredentialRequest.Builder().addCredentialOption(option).build()).credential
            if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val google = GoogleIdTokenCredential.createFrom(credential.data)
                Outcome.Token(google.idToken, google.displayName)
            } else Outcome.Failed(tr("Google didn't return an account. Try again."))
        } catch (_: GetCredentialCancellationException) {
            Outcome.Cancelled
        } catch (e: GetCredentialException) {
            Outcome.Failed(tr("Google sign-in isn't available on this phone right now."))
        }
    }
}

data class GoogleState(
    val busy: Boolean = false,
    val note: String? = null,
    /** A new account: Google gave the name and email; step 2 (birthday, country, gender) is what's left. */
    val finishing: Boolean = false,
    val name: String = "",
    /** Google came without a name: ask for it too. */
    val askName: Boolean = false,
    val dateOfBirth: LocalDate? = null,
    val gender: Gender? = null,
    val country: String? = null,
    val showErrors: Boolean = false,
) {
    /** The same checks as step 2 of signing up (and the name, when asked). */
    val errors: Map<SignUpForm.Field, String> get() = if (!showErrors) emptyMap() else
        SignUpForm(name = name.ifBlank { if (askName) "" else "-" }, email = "google@example.com", password = "x".repeat(SignUpForm.MIN_PASSWORD), dateOfBirth = dateOfBirth, gender = gender, country = country)
            .errors().filterKeys { it.step == 2 || (askName && it == SignUpForm.Field.Name) }
}

class GoogleViewModel(private val auth: AuthRepository) : ViewModel() {
    private val _state = MutableStateFlow(GoogleState())
    val state: StateFlow<GoogleState> = _state.asStateFlow()
    private var token: String? = null

    fun signIn(context: Context, onSignedIn: () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, note = null) }
        viewModelScope.launch {
            when (val outcome = GoogleSignIn.requestToken(context)) {
                GoogleSignIn.Outcome.Cancelled -> _state.update { it.copy(busy = false) }
                is GoogleSignIn.Outcome.Failed -> _state.update { it.copy(busy = false, note = outcome.message) }
                is GoogleSignIn.Outcome.Token -> {
                    token = outcome.idToken
                    when (val result = auth.google(GoogleSignInRequest(outcome.idToken))) {
                        is ApiResult.Ok -> { _state.update { it.copy(busy = false) }; onSignedIn() }
                        is ApiResult.Failure -> _state.update {
                            if (result.code == "auth.profile_required") it.copy(busy = false, finishing = true, name = outcome.name.orEmpty().trim(), askName = outcome.name.isNullOrBlank())
                            else it.copy(busy = false, note = result.message)
                        }
                        ApiResult.Offline -> _state.update { it.copy(busy = false, note = OFFLINE_MESSAGE) }
                    }
                }
            }
        }
    }

    fun name(value: String) = _state.update { it.copy(name = value, note = null) }
    fun birthday(value: LocalDate) = _state.update { it.copy(dateOfBirth = value, note = null) }
    fun gender(value: Gender) = _state.update { it.copy(gender = value, note = null) }
    fun country(value: String) = _state.update { it.copy(country = value, note = null) }
    fun cancel() = _state.update { GoogleState() }

    fun finish(onSignedIn: () -> Unit) {
        val s = _state.value
        if (s.busy) return
        val idToken = token ?: return
        if (s.copy(showErrors = true).errors.isNotEmpty()) { _state.update { it.copy(showErrors = true) }; return }
        _state.update { it.copy(busy = true, note = null, showErrors = true) }
        viewModelScope.launch {
            val result = auth.google(GoogleSignInRequest(idToken, s.dateOfBirth.toString(), s.gender ?: Gender.PreferNotToSay, s.name.trim().takeIf { it.isNotEmpty() }, s.country))
            _state.update { it.copy(busy = false, note = result.note()) }
            if (result is ApiResult.Ok) onSignedIn()
        }
    }
}

/** The last step of signing up with Google: step 2 alone, since Google gave the name and email. */
@Composable
fun GoogleFinishScreen(state: GoogleState, onName: (String) -> Unit, onBirthday: (LocalDate) -> Unit, onGender: (Gender) -> Unit, onCountry: (String) -> Unit, onFinish: () -> Unit, onBack: () -> Unit,
                       invitedBy: String? = null) {
    val colors = Heartpact.colors
    val context = LocalContext.current
    val guess = remember { Countries.guess(context) }
    LaunchedEffect(guess) { if (state.country == null && guess != null) onCountry(guess) }
    val errors = state.errors
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        TopBar(tr("About you"), onBack) { LiveLogo(30.dp, if (errors.isNotEmpty() || state.note != null) LogoMood.Worried else LogoMood.Happy, night = colors.isNight) }
        StepIndicator(2, 2, Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            invitedBy?.let { InvitedCard(tr("You'll be linked with {name} as soon as your account is ready.", "name" to it)) }
            Text(
                if (state.name.isNotBlank() && !state.askName) tr("Hi {name}, Google signed you in. Three quick things and you're in.", "name" to state.name.trim().substringBefore(' '))
                else tr("Google signed you in. A few quick things and you're in."),
                style = Heartpact.type.bodyLarge, color = colors.ink2,
            )
            if (state.askName) HeartField(tr("Your name"), state.name, onName, error = errors[SignUpForm.Field.Name],
                keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done), contentType = ContentType.PersonFullName)
            AboutYouFields(
                dateOfBirth = state.dateOfBirth, onDateOfBirth = onBirthday, country = state.country, onCountry = onCountry,
                gender = state.gender, onGender = onGender, errors = errors, suggestedCountry = guess,
                // Under 18 is refused at once, before anything is sent (the server refuses it too).
                dateError = state.dateOfBirth?.takeIf { !isAdult(it) }?.let { TOO_YOUNG },
            )
            state.note?.let { NoteCard(it) }
        }
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp)) {
            HeartButton(tr("Create account"), onFinish, busy = state.busy)
            ConsentLine(Modifier.padding(top = 10.dp).fillMaxWidth())
        }
    }
}
