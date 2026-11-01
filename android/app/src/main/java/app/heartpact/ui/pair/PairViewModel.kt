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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** A partner's invite link waiting to be used. [ask]: signed in already, so it asks "Join Maya?" first. */
data class PendingLink(val token: String, val ask: Boolean)

/** "Join Maya?": a link that opened the app while signed in. [name] is null when it couldn't be read. */
data class LinkConfirm(val token: String, val name: String?)

data class PairState(
    val invite: PartnerInvite? = null,
    val loadingInvite: Boolean = true,
    val inviteError: String? = null,
    /** "Get a new code" is on its way. */
    val renewing: Boolean = false,
    val entering: Boolean = false,
    val code: String = "",
    val codeError: String? = null,
    val accepting: Boolean = false,
    val confirm: LinkConfirm? = null,
    /** Joining through a link: "Joining Maya…"; the name is "" while unknown. */
    val joining: String? = null,
    /** Linked: the celebration plays with the partner's first name ("" if unknown), then [linked]. */
    val celebrating: String? = null,
    /** Done: the app moves on. */
    val linked: Boolean = false,
)

/**
 * Pairing: shows this person's invite (kept between visits, so a code already sent keeps working), accepts a partner's
 * code, link or QR code, and notices the moment the partner joins: the couple hub's CoupleConnected, with a poll
 * every few seconds as the fallback. Linking either way plays the celebration before the app moves on.
 */
class PairViewModel(
    private val couple: CoupleRepository,
    private val userId: String,
    pending: PendingLink?,
    /** App events ("coupleConnected" from the hub or a push). */
    events: Flow<String> = emptyFlow(),
    private val pollMs: Long = POLL_MS,
) : ViewModel() {
    private val _state = MutableStateFlow(PairState())
    val state: StateFlow<PairState> = _state.asStateFlow()
    private var watcher: Job? = null

    init {
        viewModelScope.launch { events.collect { if (it == "coupleConnected") checkPartner() } }
        if (pending == null || pending.ask) loadInvite()
        pending?.let(::offerLink)
    }

    /** A partner's link: signed in already, it asks "Join Maya?" first; followed through signing up or in, it joins at once. */
    fun offerLink(link: PendingLink) {
        if (_state.value.celebrating != null || _state.value.linked) return
        if (!link.ask) return joinLink(link.token)
        viewModelScope.launch {
            val name = previewName(link.token)
            _state.update { it.copy(confirm = LinkConfirm(link.token, name)) }
        }
    }

    /** The kept invite, or a new one when it has run out; [fresh] always makes a new one ("Get a new code"). */
    fun loadInvite(fresh: Boolean = false) {
        _state.update { it.copy(loadingInvite = it.invite == null, renewing = fresh, inviteError = null) }
        viewModelScope.launch {
            val result = if (fresh) couple.createInvite(userId) else couple.invite(userId)
            when (result) {
                is ApiResult.Ok -> {
                    _state.update { it.copy(invite = result.value, loadingInvite = false, renewing = false) }
                    watchForPartner()
                }
                is ApiResult.Failure -> if (result.code == "couple.already_linked") checkPartner()
                    else _state.update { it.copy(loadingInvite = false, renewing = false, inviteError = result.message) }
                ApiResult.Offline -> _state.update { it.copy(loadingInvite = false, renewing = false, inviteError = OFFLINE_MESSAGE) }
            }
        }
    }

    fun newInvite() = loadInvite(fresh = true)

    fun startEntering() = _state.update { it.copy(entering = true, codeError = null) }
    fun stopEntering() = _state.update { it.copy(entering = false, code = "", codeError = null) }

    /** Each digit as typed; the sixth sends it, with no button to find. */
    fun code(value: String) {
        val before = _state.value.code
        val digits = value.filter { it in '0'..'9' }.take(CODE_DIGITS)
        _state.update { it.copy(code = digits, codeError = null, entering = true) }
        if (digits.length == CODE_DIGITS && before != digits) submitCode()
    }

    fun submitCode() {
        val code = _state.value.code
        if (code.length != CODE_DIGITS) {
            _state.update { it.copy(codeError = trn(CODE_DIGITS, "Enter all {count} digit.", "Enter all {count} digits.")) }
            return
        }
        accept(code = code)
    }

    /** What a scan or the clipboard held: a link joins like an opened link, a code like a typed one. */
    fun use(input: InviteInput) = when (input) {
        is InviteInput.Link -> joinLink(input.token)
        is InviteInput.Code -> code(input.digits)
    }

    fun confirmLink() {
        val confirm = _state.value.confirm ?: return
        _state.update { it.copy(confirm = null) }
        joinLink(confirm.token, confirm.name)
    }

    fun declineLink() = _state.update { it.copy(confirm = null) }

    /** The celebration has played: on into the app. */
    fun celebrated() = _state.update { it.copy(linked = true) }

    private fun joinLink(token: String, knownName: String? = null) {
        if (_state.value.accepting) return
        _state.update { it.copy(joining = knownName.orEmpty(), accepting = true, codeError = null) }
        viewModelScope.launch {
            val name = knownName ?: previewName(token)
            if (name != null) _state.update { it.copy(joining = name) }
            accept(token = token, fallbackName = name, alreadyAccepting = true)
        }
    }

    /** The inviter's first name for "Joining Maya…", quickly or not at all. */
    private suspend fun previewName(token: String): String? =
        (withTimeoutOrNull(PREVIEW_TIMEOUT_MS) { couple.preview(token) } as? ApiResult.Ok)?.value?.inviterFirstName?.takeIf { it.isNotBlank() }

    private fun accept(code: String? = null, token: String? = null, fallbackName: String? = null, alreadyAccepting: Boolean = false) {
        if (_state.value.accepting && !alreadyAccepting) return
        _state.update { it.copy(accepting = true, codeError = null) }
        viewModelScope.launch {
            when (val result = couple.accept(code = code, token = token)) {
                is ApiResult.Ok -> celebrate(fallbackName)
                is ApiResult.Failure -> {
                    val message = when (result.code) {
                        "invite.invalid" -> if (token != null) tr("That invite link has expired or was already used. Ask your partner for a new one.") else tr("That code didn't work. Check it with your partner, or ask for a new one.")
                        "invite.self" -> tr("That's your own invite. Send it to your partner instead.")
                        else -> result.message
                    }
                    _state.update { it.copy(accepting = false, joining = null, entering = true, codeError = message) }
                    if (_state.value.invite == null && !_state.value.loadingInvite) loadInvite()
                }
                ApiResult.Offline -> _state.update { it.copy(accepting = false, joining = null, entering = true, codeError = OFFLINE_MESSAGE) }
            }
        }
    }

    /** Partners usually join within a minute or two of seeing the code: the hub says so at once, and this checks every few seconds in case it can't. */
    private fun watchForPartner() {
        watcher?.cancel()
        watcher = viewModelScope.launch {
            while (isActive) {
                delay(pollMs)
                if (checkPartner()) break
            }
        }
    }

    private suspend fun checkPartner(): Boolean {
        if (_state.value.celebrating != null || _state.value.linked) return true
        val result = couple.current(userId)
        if (result is ApiResult.Ok && result.value.status == CoupleStatus.Active) {
            celebrate(null, result.value)
            return true
        }
        return false
    }

    private suspend fun celebrate(fallbackName: String?, known: app.heartpact.data.Couple? = null) {
        watcher?.cancel()
        if (_state.value.celebrating != null) return
        val fresh = known ?: (couple.current(userId) as? ApiResult.Ok)?.value
        val partner = fresh?.members?.firstOrNull { it.userId != userId }?.displayName?.trim()?.substringBefore(' ')
        _state.update { it.copy(accepting = false, joining = null, confirm = null, celebrating = partner ?: fallbackName.orEmpty()) }
    }

    companion object {
        const val CODE_DIGITS = InviteParsing.CODE_DIGITS
        const val POLL_MS = 4_000L
        private const val PREVIEW_TIMEOUT_MS = 3_000L
        fun formatCode(code: String) = InviteParsing.format(code)
        fun inviteLink(token: String) = InviteParsing.link(token)
    }
}
