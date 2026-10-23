package app.heartpact.ui.pair

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.heartpact.data.ApiResult
import app.heartpact.data.CoupleRepository
import app.heartpact.data.CoupleStatus
import app.heartpact.data.OFFLINE_MESSAGE
import app.heartpact.data.PartnerInvite
import app.heartpact.i18n.tr
import app.heartpact.i18n.trn
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PairState(
    val invite: PartnerInvite? = null,
    val loadingInvite: Boolean = true,
    val inviteError: String? = null,
    val entering: Boolean = false,
    val code: String = "",
    val codeError: String? = null,
    val accepting: Boolean = false,
    val linked: Boolean = false,
)

/**
 * Pairing: shows a fresh invite to share, accepts a partner's code (or an invite link that opened
 * the app), and notices when the partner joins from their phone.
 */
class PairViewModel(private val couple: CoupleRepository, private val userId: String, pendingLinkToken: String?) : ViewModel() {
    private val _state = MutableStateFlow(PairState())
    val state: StateFlow<PairState> = _state.asStateFlow()
    private var watcher: Job? = null

    init {
        if (pendingLinkToken != null) accept(token = pendingLinkToken) else newInvite()
    }

    fun newInvite() {
        _state.update { it.copy(loadingInvite = true, inviteError = null) }
        viewModelScope.launch {
            when (val result = couple.createInvite()) {
                is ApiResult.Ok -> {
                    _state.update { it.copy(invite = result.value, loadingInvite = false) }
                    watchForPartner()
                }
                is ApiResult.Failure -> if (result.code == "couple.already_linked") linked() else _state.update { it.copy(loadingInvite = false, inviteError = result.message) }
                ApiResult.Offline -> _state.update { it.copy(loadingInvite = false, inviteError = OFFLINE_MESSAGE) }
            }
        }
    }

    fun startEntering() = _state.update { it.copy(entering = true, codeError = null) }
    fun stopEntering() = _state.update { it.copy(entering = false, code = "", codeError = null) }
    fun code(value: String) = _state.update { it.copy(code = value.filter(Char::isDigit).take(CODE_DIGITS), codeError = null) }

    fun submitCode() {
        val code = _state.value.code
        if (code.length != CODE_DIGITS) {
            _state.update { it.copy(codeError = trn(CODE_DIGITS, "Enter all {count} digit.", "Enter all {count} digits.")) }
            return
        }
        accept(code = code)
    }

    private fun accept(code: String? = null, token: String? = null) {
        if (_state.value.accepting) return
        _state.update { it.copy(accepting = true, codeError = null, entering = it.entering || code != null) }
        viewModelScope.launch {
            when (val result = couple.accept(code = code, token = token)) {
                is ApiResult.Ok -> linked()
                is ApiResult.Failure -> {
                    val message = when (result.code) {
                        "invite.invalid" -> if (token != null) tr("That invite link has expired or was already used. Ask your partner for a new one.") else tr("That code didn't work. Check it with your partner, or ask for a new one.")
                        else -> result.message
                    }
                    _state.update { it.copy(accepting = false, entering = true, codeError = message) }
                    if (token != null && _state.value.invite == null) newInvite()
                }
                ApiResult.Offline -> _state.update { it.copy(accepting = false, codeError = OFFLINE_MESSAGE) }
            }
        }
    }

    /** Partners usually join within a minute or two of seeing the code; check every few seconds while it is on screen. */
    private fun watchForPartner() {
        watcher?.cancel()
        watcher = viewModelScope.launch {
            while (isActive) {
                delay(POLL_MS)
                val result = couple.current(userId)
                if (result is ApiResult.Ok && result.value.status == CoupleStatus.Active) {
                    linked()
                    break
                }
            }
        }
    }

    private fun linked() {
        watcher?.cancel()
        _state.update { it.copy(linked = true, accepting = false) }
    }

    companion object {
        const val CODE_DIGITS = 6
        const val POLL_MS = 4_000L
        fun formatCode(code: String) = if (code.length == 6) "${code.take(3)} ${code.drop(3)}" else code
        fun inviteLink(token: String) = "https://heartpact.app/invite/$token"
    }
}
