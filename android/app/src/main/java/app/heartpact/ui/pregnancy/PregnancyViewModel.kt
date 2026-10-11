package app.heartpact.ui.pregnancy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.heartpact.data.ApiResult
import app.heartpact.data.Appointment
import app.heartpact.data.AppointmentRequest
import app.heartpact.data.KickSession
import app.heartpact.data.OFFLINE_MESSAGE
import app.heartpact.data.Pregnancy
import app.heartpact.data.PregnancyRepository
import app.heartpact.data.PregnancyStatus
import app.heartpact.data.StartPregnancyRequest
import app.heartpact.data.UpdatePregnancyRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

data class PregnancyUi(
    val pregnancy: Pregnancy? = null,
    val appointments: List<Appointment> = emptyList(),
    val kicks: List<KickSession> = emptyList(),
    val counting: KickSession? = null,
    val kickCount: Int = 0,
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val note: String? = null,
)

private fun <T> ApiResult<T>.note(): String? = when (this) {
    is ApiResult.Ok -> null
    ApiResult.Offline -> OFFLINE_MESSAGE
    is ApiResult.Failure -> message
}

class PregnancyViewModel(private val repo: PregnancyRepository, private val today: () -> LocalDate = { LocalDate.now() }) : ViewModel() {
    private val _ui = MutableStateFlow(PregnancyUi())
    val ui: StateFlow<PregnancyUi> = _ui.asStateFlow()
    private var recording: Job? = null

    fun load() = viewModelScope.launch {
        val list = repo.list(today().toString())
        if (list !is ApiResult.Ok) { _ui.update { it.copy(note = list.note(), loaded = true) }; return@launch }
        // Mine first; otherwise the partner's, when they share it.
        val current = list.value.filter { it.status == PregnancyStatus.Active }.sortedByDescending { it.isMine }.firstOrNull()
        val appointments = current?.let { (repo.appointments(it.id) as? ApiResult.Ok)?.value }.orEmpty()
        val kicks = current?.let { (repo.kicks(it.id) as? ApiResult.Ok)?.value }.orEmpty()
        _ui.update { it.copy(pregnancy = current, appointments = appointments.sortedBy { a -> a.atUtc }, kicks = kicks, loaded = true, note = null) }
    }

    fun start(dueDate: LocalDate?, lastPeriod: LocalDate?, share: Boolean) = act {
        repo.start(StartPregnancyRequest(dueDate?.toString(), lastPeriod?.toString(), share))
    }

    fun share(on: Boolean) {
        val p = _ui.value.pregnancy ?: return
        _ui.update { it.copy(pregnancy = p.copy(shareWithPartner = on)) }
        act { repo.update(p.id, UpdatePregnancyRequest(p.dueDate, on)) }
    }

    fun end() { val p = _ui.value.pregnancy ?: return; act { repo.end(p.id) } }

    fun addAppointment(title: String, at: LocalDateTime, location: String?) {
        val p = _ui.value.pregnancy ?: return
        val utc = at.atZone(ZoneId.systemDefault()).toInstant().toString()
        act { repo.addAppointment(p.id, AppointmentRequest(title.trim(), utc, location?.trim()?.takeIf { it.isNotEmpty() })) }
    }

    fun deleteAppointment(id: String) = act { repo.deleteAppointment(id) }

    fun startKicks() {
        val p = _ui.value.pregnancy ?: return
        viewModelScope.launch {
            when (val session = repo.startKicks(p.id)) {
                is ApiResult.Ok -> _ui.update { it.copy(counting = session.value, kickCount = 0, note = null) }
                else -> _ui.update { it.copy(note = session.note()) }
            }
        }
    }

    /** Each tap counts at once; the count saves after a pause, so a run of kicks is one request. */
    fun kick() {
        val session = _ui.value.counting ?: return
        val count = (_ui.value.kickCount + 1).coerceAtMost(200)
        _ui.update { it.copy(kickCount = count) }
        recording?.cancel()
        recording = viewModelScope.launch { delay(1500); repo.recordKicks(session.id, count, end = false) }
    }

    fun finishKicks() {
        val session = _ui.value.counting ?: return
        recording?.cancel()
        val count = _ui.value.kickCount
        viewModelScope.launch {
            val result = repo.recordKicks(session.id, count, end = true)
            _ui.update { it.copy(counting = null, note = result.note()) }
            load()
        }
    }

    private fun act(call: suspend () -> ApiResult<*>) {
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = call()
            _ui.update { it.copy(busy = false, note = result.note()) }
            load()
        }
    }
}
