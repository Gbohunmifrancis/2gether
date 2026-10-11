package app.heartpact.ui.cycle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.heartpact.data.ApiResult
import app.heartpact.data.CycleDayLog
import app.heartpact.data.CycleLogRequest
import app.heartpact.data.CycleMood
import app.heartpact.data.CyclePrediction
import app.heartpact.data.CycleProfile
import app.heartpact.data.CycleRepository
import app.heartpact.data.CycleShare
import app.heartpact.data.FlowLevel
import app.heartpact.data.Gender
import app.heartpact.data.OFFLINE_MESSAGE
import app.heartpact.data.SharedCycle
import app.heartpact.data.UpdateCycleProfile
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

enum class CycleMode { Loading, Owner, Setup, Claim, Partner, Unavailable }

data class CycleUi(
    val mode: CycleMode = CycleMode.Loading,
    val profile: CycleProfile = CycleProfile(),
    val prediction: CyclePrediction = CyclePrediction(),
    val logs: List<CycleDayLog> = emptyList(),
    val shared: SharedCycle? = null,
    val note: String? = null,
    val busy: Boolean = false,
) {
    val today: LocalDate get() = LocalDate.now()
    val todayLog: CycleDayLog get() = logs.firstOrNull { it.logDate == today.toString() } ?: CycleDayLog(today.toString())
}

private fun <T> ApiResult<T>.note(): String? = when (this) {
    is ApiResult.Ok -> null
    ApiResult.Offline -> OFFLINE_MESSAGE
    is ApiResult.Failure -> message
}

class CycleViewModel(private val cycle: CycleRepository, private val today: () -> LocalDate = { LocalDate.now() }) : ViewModel() {
    private val _ui = MutableStateFlow(CycleUi())
    val ui: StateFlow<CycleUi> = _ui.asStateFlow()
    private var saving: Job? = null

    fun load() = viewModelScope.launch {
        val day = today().toString()
        val me = cycle.me()
        if (me !is ApiResult.Ok) { _ui.update { it.copy(note = me.note()) }; return@launch }
        if (me.value.cycleOwner) {
            // No profile yet comes back as an empty body: treat it as defaults.
            val profile = (cycle.profile() as? ApiResult.Ok)?.value as? CycleProfile ?: CycleProfile(userId = me.value.id)
            if (profile.lastPeriodStartDate == null) { _ui.update { it.copy(mode = CycleMode.Setup, profile = profile, note = null) }; return@launch }
            val prediction = (cycle.prediction(day) as? ApiResult.Ok)?.value ?: CyclePrediction()
            val from = (prediction.cycleStart?.let(LocalDate::parse) ?: today().minusDays(40)).minusDays(1).toString()
            val logs = (cycle.logs(from, day) as? ApiResult.Ok)?.value.orEmpty()
            _ui.update { it.copy(mode = CycleMode.Owner, profile = profile, prediction = prediction, logs = logs, note = null) }
        } else {
            val shared = (cycle.shared(day) as? ApiResult.Ok)?.value
            _ui.update {
                when {
                    shared?.profile != null -> it.copy(mode = CycleMode.Partner, shared = shared, note = null)
                    me.value.gender == Gender.Female -> it.copy(mode = CycleMode.Claim, note = null)
                    else -> it.copy(mode = CycleMode.Unavailable, note = null)
                }
            }
        }
    }

    fun claim() {
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = cycle.claim()
            _ui.update { it.copy(busy = false, note = result.note()) }
            if (result is ApiResult.Ok) load()
        }
    }

    fun saveSetup(lastStart: LocalDate, cycleLength: Int, periodLength: Int, share: CycleShare = _ui.value.profile.shareLevel) {
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = cycle.saveProfile(UpdateCycleProfile(cycleLength, periodLength, lastStart.toString(), share))
            _ui.update { it.copy(busy = false, note = result.note()) }
            if (result is ApiResult.Ok) load()
        }
    }

    fun share(level: CycleShare) {
        val p = _ui.value.profile
        _ui.update { it.copy(profile = p.copy(shareLevel = level)) }
        viewModelScope.launch {
            val result = cycle.saveProfile(UpdateCycleProfile(p.averageCycleLengthDays, p.averagePeriodLengthDays, p.lastPeriodStartDate, level))
            _ui.update { state -> if (result is ApiResult.Ok) state.copy(profile = result.value, note = null) else state.copy(profile = p, note = result.note()) }
        }
    }

    fun flow(level: FlowLevel) = changeToday { it.copy(flowLevel = level) }
    fun symptom(name: String) = changeToday { log -> log.copy(symptoms = if (name in log.symptoms) log.symptoms - name else log.symptoms + name) }
    fun mood(mood: CycleMood?) = changeToday { it.copy(mood = mood) }
    fun notes(text: String) = changeToday { it.copy(notes = text.take(2000)) }

    /** Today's log changes at once on screen and saves after a short pause. */
    private fun changeToday(change: (CycleDayLog) -> CycleDayLog) {
        val state = _ui.value
        val updated = change(state.todayLog)
        _ui.update { it.copy(logs = it.logs.filterNot { l -> l.logDate == updated.logDate } + updated) }
        saving?.cancel()
        saving = viewModelScope.launch {
            delay(500)
            val result = cycle.log(updated.logDate, CycleLogRequest(updated.flowLevel, updated.symptoms, updated.mood, updated.hadSex, updated.protectionUsed, updated.notes?.takeIf { it.isNotBlank() }))
            if (result !is ApiResult.Ok) { _ui.update { it.copy(note = result.note()) }; return@launch }
            val s = _ui.value
            val date = LocalDate.parse(updated.logDate)
            if (CyclePlan.startsNewPeriod(updated.flowLevel, date, s.prediction.cycleStart?.let(LocalDate::parse), s.profile.averagePeriodLengthDays, s.logs)) {
                val p = s.profile
                cycle.saveProfile(UpdateCycleProfile(p.averageCycleLengthDays, p.averagePeriodLengthDays, updated.logDate, p.shareLevel))
                load()
            }
        }
    }
}
