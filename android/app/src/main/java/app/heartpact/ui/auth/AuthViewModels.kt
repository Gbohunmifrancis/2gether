package app.heartpact.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.heartpact.data.ApiResult
import app.heartpact.data.AuthRepository
import app.heartpact.data.OFFLINE_MESSAGE
import app.heartpact.data.RegisterRequest
import app.heartpact.i18n.tr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SignUpState(
    val form: SignUpForm = SignUpForm(),
    /** Field errors show only after a first attempt, so nobody is scolded while typing. */
    val showErrors: Boolean = false,
    val serverFieldErrors: Map<SignUpForm.Field, String> = emptyMap(),
    val note: String? = null,
    val busy: Boolean = false,
) {
    val fieldErrors: Map<SignUpForm.Field, String> get() = (if (showErrors) form.errors() else emptyMap()) + serverFieldErrors
}

class SignUpViewModel(private val auth: AuthRepository) : ViewModel() {
    private val _state = MutableStateFlow(SignUpState())
    val state: StateFlow<SignUpState> = _state.asStateFlow()

    fun edit(change: (SignUpForm) -> SignUpForm) = _state.update { it.copy(form = change(it.form), serverFieldErrors = emptyMap(), note = null) }

    fun submit(onDone: () -> Unit) {
        val current = _state.value
        if (current.busy) return
        if (current.form.errors().isNotEmpty()) {
            _state.update { it.copy(showErrors = true) }
            return
        }
        val form = current.form
        _state.update { it.copy(busy = true, showErrors = true, note = null) }
        viewModelScope.launch {
            val result = auth.register(RegisterRequest(form.email.trim(), form.password, form.name.trim(), form.dateOfBirth.toString(), form.gender!!, form.country))
            when (result) {
                is ApiResult.Ok -> onDone()
                is ApiResult.Failure -> _state.update {
                    if (result.code == "auth.invalid_country") it.copy(busy = false, serverFieldErrors = mapOf(SignUpForm.Field.Country to result.message))
                    else if (result.code == "auth.email_exists") it.copy(busy = false, serverFieldErrors = mapOf(SignUpForm.Field.Email to tr("An account with this email already exists. Sign in instead?")))
                    else it.copy(busy = false, note = result.message)
                }
                ApiResult.Offline -> _state.update { it.copy(busy = false, note = OFFLINE_MESSAGE) }
            }
        }
    }
}

data class SignInState(val email: String = "", val password: String = "", val error: String? = null, val note: String? = null, val busy: Boolean = false)

class SignInViewModel(private val auth: AuthRepository) : ViewModel() {
    private val _state = MutableStateFlow(SignInState())
    val state: StateFlow<SignInState> = _state.asStateFlow()

    fun email(value: String) = _state.update { it.copy(email = value, error = null, note = null) }
    fun password(value: String) = _state.update { it.copy(password = value, error = null, note = null) }

    fun submit(onDone: () -> Unit) {
        val s = _state.value
        if (s.busy) return
        if (!looksLikeEmail(s.email) || s.password.isEmpty()) {
            _state.update { it.copy(error = tr("Enter your email and password.")) }
            return
        }
        _state.update { it.copy(busy = true, error = null, note = null) }
        viewModelScope.launch {
            when (val result = auth.login(s.email, s.password)) {
                is ApiResult.Ok -> onDone()
                is ApiResult.Failure -> _state.update { it.copy(busy = false, error = if (result.status == 401) result.message else null, note = if (result.status == 401) null else result.message) }
                ApiResult.Offline -> _state.update { it.copy(busy = false, note = OFFLINE_MESSAGE) }
            }
        }
    }
}
