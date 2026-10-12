package app.heartpact.ui.today

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.heartpact.HeartpactApplication
import app.heartpact.alarm.AlarmPlan
import app.heartpact.alarm.AlarmReadiness
import app.heartpact.alarm.AlarmScheduler
import app.heartpact.data.ApiResult
import app.heartpact.data.OFFLINE_MESSAGE
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import app.heartpact.push.Push
import app.heartpact.push.PushInbox
import java.time.Instant

data class TodayUi(
    val view: TodayView? = null,
    val loading: Boolean = true,
    val note: String? = null,
    val readiness: AlarmReadiness = AlarmReadiness(true, true, true),
    val busy: Boolean = false,
    /** Requests the person chose to keep ringing, hidden on this phone. */
    val kept: Set<String> = emptySet(),
)

/**
 * Today: my dare and my partner's, from the server. Every refresh also re-arms this phone's
 * alarms from the server's schedule, so a dare set, changed or called off elsewhere rings right.
 */
class TodayViewModel(application: Application, private val me: String, private val partnerName: String) : AndroidViewModel(application) {
    private val app = (application as HeartpactApplication).container
    private val _ui = MutableStateFlow(TodayUi())
    val ui: StateFlow<TodayUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            // With push, a check-in a minute is a safety net; without it, every 15 seconds while Today is open.
            while (isActive) {
                refreshNow()
                delay(if (Push.enabled) 60_000 else 15_000)
            }
        }
        viewModelScope.launch { app.pushEvents.collect { if (PushInbox.refreshesToday(it)) refreshNow() } }
        // The partner's live count: patch the ringing card in place rather than refetch per rep.
        viewModelScope.launch {
            app.live.events.collect { event ->
                if (event.name != "ExerciseProgress") return@collect
                val progress = runCatching { app.json.decodeFromString(LiveProgress.serializer(), event.json) }.getOrNull() ?: return@collect
                _ui.update { state ->
                    val view = state.view ?: return@update state
                    val ringing = view.partnerRinging?.takeIf { it.id == progress.occurrenceId } ?: return@update state
                    state.copy(view = view.copy(partnerRinging = ringing.copy(achievedCount = progress.achievedCount)))
                }
            }
        }
    }

    fun refresh() = viewModelScope.launch { refreshNow() }

    fun recheckReadiness() = _ui.update { it.copy(readiness = AlarmReadiness.check(getApplication())) }

    private suspend fun refreshNow() {
        recheckReadiness()
        val today = app.exercise.today()
        val history = (app.exercise.history(7) as? ApiResult.Ok)?.value
        when (today) {
            is ApiResult.Ok -> {
                val now = Instant.now()
                AlarmScheduler.apply(getApplication(), AlarmPlan.plan(today.value, me, partnerName, now), now)
                _ui.update { it.copy(view = TodayBuilder.build(today.value, history, me, now), loading = false, note = null) }
            }
            ApiResult.Offline -> _ui.update { it.copy(loading = false, note = OFFLINE_MESSAGE) }
            is ApiResult.Failure -> _ui.update { it.copy(loading = false, note = today.message) }
        }
    }

    /** Granting the partner's request: the existing partner-only cancel. */
    fun callOffTogether(occurrenceId: String) {
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            when (val result = app.exercise.cancel(occurrenceId)) {
                is ApiResult.Ok -> refreshNow()
                is ApiResult.Failure -> _ui.update { it.copy(note = result.message) }
                ApiResult.Offline -> _ui.update { it.copy(note = OFFLINE_MESSAGE) }
            }
            _ui.update { it.copy(busy = false) }
        }
    }

    fun keepIt(occurrenceId: String) = _ui.update { it.copy(kept = it.kept + occurrenceId) }
}

@kotlinx.serialization.Serializable
private data class LiveProgress(val occurrenceId: String, val userId: String = "", val achievedCount: Int, val target: Int = 0, val confidence: Double = 0.0)
