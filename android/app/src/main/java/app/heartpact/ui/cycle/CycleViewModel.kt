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

/**
 * Owner logs; Setup asks for (or, when [CycleUi.editing], changes) the settings; Partner shows what is shared.
 * Anyone else sees Unavailable, whatever the reason, so the screen never says whether the partner tracks a cycle.
 */
enum class CycleMode { Loading, Owner, Setup, Partner, Unavailable }

data class CycleUi(
    val mode: CycleMode = CycleMode.Loading,
    val profile: CycleProfile = CycleProfile(),
    val prediction: CyclePrediction = CyclePrediction(),
    val logs: List<CycleDayLog> = emptyList(),
    val shared: SharedCycle? = null,
    val note: String? = null,
    val busy: Boolean = false,
    /** Setup opened from the owner screen to change settings, rather than first use. */
    val editing: Boolean = false,
    /** The day being logged; null is today. */
    val day: LocalDate? = null,
) {
    fun logFor(date: LocalDate): CycleDayLog = logs.firstOrNull { it.logDate == date.toString() } ?: CycleDayLog(date.toString())
}

private fun <T> ApiResult<T>.note(): String? = when (this) {
    is ApiResult.Ok -> null
    ApiResult.Offline -> OFFLINE_MESSAGE
    is ApiResult.Failure -> message
}

class CycleViewModel(private val cycle: CycleRepository, private val today: () -> LocalDate = { LocalDate.now() }) : ViewModel() {
    private val _ui = MutableStateFlow(CycleUi())
    val ui: StateFlow<CycleUi> = _ui.asStateFlow()
    // One pending save per day, so changing another day never drops this one.
    private val saving = mutableMapOf<String, Job>()

    fun load() = viewModelScope.launch {
        val now = today()
        val day = now.toString()
        val me = cycle.me()
        if (me !is ApiResult.Ok) { _ui.update { it.copy(note = me.note()) }; return@launch }
        if (me.value.cycleOwner) {
            // No profile yet comes back as an empty body: treat it as defaults.
            val profile = (cycle.profile() as? ApiResult.Ok)?.value as? CycleProfile ?: CycleProfile(userId = me.value.id)
            if (profile.lastPeriodStartDate == null) { _ui.update { it.copy(mode = CycleMode.Setup, profile = profile, editing = false, note = null) }; return@launch }
            val prediction = (cycle.prediction(day) as? ApiResult.Ok)?.value ?: CyclePrediction()
            // The whole cycle for the strip, and the days that can still be logged.
            val earliest = now.minusDays(CyclePlan.LOG_DAYS_BACK)
            val from = minOf(prediction.cycleStart?.let(LocalDate::parse)?.minusDays(1) ?: earliest, earliest).toString()
            val logs = (cycle.logs(from, day) as? ApiResult.Ok)?.value.orEmpty()
            _ui.update { it.copy(mode = CycleMode.Owner, profile = profile, prediction = prediction, logs = logs, editing = false, note = null) }
        } else {
            // A woman whose partner already owns the calendar can't claim it: she sees the same as anyone else
            // who isn't shown a cycle, so nothing hints at whether her partner tracks one.
            val shared = (cycle.shared(day) as? ApiResult.Ok)?.value
            _ui.update { if (shared?.profile != null) it.copy(mode = CycleMode.Partner, shared = shared, note = null) else it.copy(mode = CycleMode.Unavailable, note = null) }
        }
    }

    /** Opens the settings from the owner screen. */
    fun edit() = _ui.update { it.copy(mode = CycleMode.Setup, editing = true, note = null) }

    fun cancelEdit() = _ui.update { if (it.editing) it.copy(mode = CycleMode.Owner, editing = false) else it }

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

    /** Picks the day to log: today, or one of the last [CyclePlan.LOG_DAYS_BACK] days. */
    fun pickDay(date: LocalDate) {
        val now = today()
        if (!CyclePlan.canLog(date, now)) return
        _ui.update { it.copy(day = if (date == now) null else date) }
    }

    fun flow(level: FlowLevel) = changeDay(reload = true) { it.copy(flowLevel = level) }
    fun symptom(name: String) = changeDay { log -> log.copy(symptoms = if (name in log.symptoms) log.symptoms - name else log.symptoms + name) }
    fun mood(mood: CycleMood?) = changeDay { it.copy(mood = mood) }
    fun notes(text: String) = changeDay { it.copy(notes = text.take(2000)) }

    /**
     * The chosen day's log changes at once on screen and saves after a short pause. Flow can move the last period
     * start, which the server works out from the logs, so a flow change reloads the forecast.
     */
    private fun changeDay(reload: Boolean = false, change: (CycleDayLog) -> CycleDayLog) {
        val state = _ui.value
        val updated = change(state.logFor(state.day ?: today()))
        _ui.update { it.copy(logs = it.logs.filterNot { l -> l.logDate == updated.logDate } + updated) }
        // A pending save may be replaced by the next change, so a flow change is remembered until one lands.
        reloadAfterSave = reloadAfterSave || reload
        saving[updated.logDate]?.cancel()
        saving[updated.logDate] = viewModelScope.launch {
            delay(500)
            val result = cycle.log(updated.logDate, CycleLogRequest(updated.flowLevel, updated.symptoms, updated.mood, updated.hadSex, updated.protectionUsed, updated.notes?.takeIf { it.isNotBlank() }))
            if (result !is ApiResult.Ok) { _ui.update { it.copy(note = result.note()) }; return@launch }
            if (reloadAfterSave) {
                reloadAfterSave = false
                val prediction = cycle.prediction(today().toString())
                if (prediction is ApiResult.Ok) _ui.update { it.copy(prediction = prediction.value, note = null) }
            }
        }
    }

    private var reloadAfterSave = false
}
