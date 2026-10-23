package app.heartpact.ui.us

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.heartpact.data.ApiResult
import app.heartpact.data.AuthRepository
import app.heartpact.data.AuthUser
import app.heartpact.data.DailyQuestion
import app.heartpact.data.DeleteAccountRequest
import app.heartpact.data.NotificationPreferences
import app.heartpact.data.OFFLINE_MESSAGE
import app.heartpact.data.UsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

private fun <T> ApiResult<T>.note(): String? = when (this) {
    is ApiResult.Ok -> null
    ApiResult.Offline -> OFFLINE_MESSAGE
    is ApiResult.Failure -> message
}

data class UsUi(
    val question: DailyQuestion? = null,
    val preferences: NotificationPreferences? = null,
    val note: String? = null,
    val busy: Boolean = false,
    /** Set when deleting failed on the confirmation, so the dialog can say why. */
    val deleteError: String? = null,
)

class UsViewModel(private val us: UsRepository, events: Flow<String> = emptyFlow(), private val account: AuthRepository? = null) : ViewModel() {
    private val _ui = MutableStateFlow(UsUi())
    val ui: StateFlow<UsUi> = _ui.asStateFlow()

    init { viewModelScope.launch { events.collect { if (it == "dailyQuestionAnswered") refresh() } } }

    fun refresh() = viewModelScope.launch {
        val question = us.question(LocalDate.now().toString())
        val prefs = us.preferences()
        _ui.update { it.copy(question = (question as? ApiResult.Ok)?.value ?: it.question, preferences = (prefs as? ApiResult.Ok)?.value ?: it.preferences, note = question.note()) }
    }

    /** Ends the couple for both. The app then goes back to pairing. */
    fun endPact(onDone: () -> Unit) {
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = us.endPact()
            _ui.update { it.copy(busy = false, note = result.note()) }
            if (result is ApiResult.Ok) onDone()
        }
    }

    fun deleteAccount(password: String, onDone: () -> Unit) {
        _ui.update { it.copy(busy = true, deleteError = null) }
        viewModelScope.launch {
            val result = us.deleteAccount(DeleteAccountRequest(password = password))
            _ui.update { it.copy(busy = false, deleteError = result.note()) }
            if (result is ApiResult.Ok) onDone()
        }
    }

    fun clearDeleteError() = _ui.update { it.copy(deleteError = null) }

    /** Saved on the server; the stored session updates, so everything reading the user sees the new country. */
    fun setCountry(user: AuthUser, code: String) {
        val auth = account ?: return
        if (code == user.countryCode) return
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = auth.setCountry(user, code)
            _ui.update { it.copy(busy = false, note = result.note()) }
        }
    }
}

data class QuestionUi(
    val today: DailyQuestion? = null,
    val earlier: List<DailyQuestion> = emptyList(),
    val draft: String = "",
    val editing: Boolean = false,
    val busy: Boolean = false,
    val note: String? = null,
)

class QuestionViewModel(private val us: UsRepository, events: Flow<String> = emptyFlow()) : ViewModel() {
    private val _ui = MutableStateFlow(QuestionUi())
    val ui: StateFlow<QuestionUi> = _ui.asStateFlow()

    init {
        refresh()
        // The partner answered while this was open: their answer may now show.
        viewModelScope.launch { events.collect { if (it == "dailyQuestionAnswered") refresh() } }
    }

    fun refresh() = viewModelScope.launch {
        val today = us.question(LocalDate.now().toString())
        val history = us.history(30)
        _ui.update { state ->
            val q = (today as? ApiResult.Ok)?.value ?: state.today
            state.copy(
                today = q,
                earlier = (history as? ApiResult.Ok)?.value?.items?.filter { it.id != q?.id }?.sortedByDescending { it.localDate } ?: state.earlier,
                draft = if (state.draft.isEmpty()) q?.myAnswer?.body.orEmpty() else state.draft,
                note = today.note(),
            )
        }
    }

    fun draft(value: String) = _ui.update { it.copy(draft = value) }
    fun edit() = _ui.update { it.copy(editing = true, draft = it.today?.myAnswer?.body ?: it.draft) }

    fun send() {
        val q = _ui.value.today ?: return
        val body = _ui.value.draft.trim()
        if (body.isEmpty()) return
        _ui.update { it.copy(busy = true, note = null) }
        viewModelScope.launch {
            when (val result = us.answer(q.id, body)) {
                is ApiResult.Ok -> _ui.update { it.copy(today = result.value, busy = false, editing = false) }
                else -> _ui.update { it.copy(busy = false, note = result.note()) }
            }
        }
    }
}

/** Every change saves at once (after a short pause), with the phone's own time zone for quiet hours. */
class NotificationsViewModel(private val us: UsRepository, private val zone: () -> String = { ZoneId.systemDefault().id }) : ViewModel() {
    private val _prefs = MutableStateFlow<NotificationPreferences?>(null)
    val prefs: StateFlow<NotificationPreferences?> = _prefs.asStateFlow()
    private val _note = MutableStateFlow<String?>(null)
    val note: StateFlow<String?> = _note.asStateFlow()
    private var saving: Job? = null

    init {
        viewModelScope.launch {
            when (val result = us.preferences()) {
                is ApiResult.Ok -> _prefs.value = result.value
                else -> _note.value = result.note()
            }
        }
    }

    fun change(update: (NotificationPreferences) -> NotificationPreferences) {
        val next = update(_prefs.value ?: return)
        _prefs.value = next
        saving?.cancel()
        saving = viewModelScope.launch {
            delay(400)
            when (val result = us.savePreferences(next.copy(timeZoneId = zone()))) {
                is ApiResult.Ok -> { _prefs.value = result.value; _note.value = null }
                else -> _note.value = result.note()
            }
        }
    }
}
