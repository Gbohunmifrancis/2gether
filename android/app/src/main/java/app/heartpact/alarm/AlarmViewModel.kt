package app.heartpact.alarm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.heartpact.HeartpactApplication
import app.heartpact.data.ApiResult
import app.heartpact.data.ExerciseOccurrence
import app.heartpact.data.OFFLINE_MESSAGE
import app.heartpact.data.OccurrenceStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The alarm on screen moves through these; the server's occurrence decides every step. */
sealed interface AlarmPhase {
    data object Loading : AlarmPhase
    data class Ringing(val occurrence: ExerciseOccurrence) : AlarmPhase
    data class Camera(val occurrence: ExerciseOccurrence) : AlarmPhase
    data class Sealed(val occurrence: ExerciseOccurrence) : AlarmPhase
    data class Ended(val reason: EndReason) : AlarmPhase
}

enum class EndReason { LetOff, Missed, Gone }

data class AlarmUi(val phase: AlarmPhase = AlarmPhase.Loading, val note: String? = null, val busy: Boolean = false, val partnerName: String = "your partner")

class AlarmViewModel(application: Application, private val slot: AlarmSlot) : AndroidViewModel(application) {
    private val app = (application as HeartpactApplication).container
    private val _ui = MutableStateFlow(AlarmUi(partnerName = slot.setByName ?: "your partner"))
    val ui: StateFlow<AlarmUi> = _ui.asStateFlow()
    private var watcher: Job? = null

    init {
        viewModelScope.launch { load() }
    }

    /**
     * Finds the day's occurrence. The worker opens it at the due second, so allow a minute for it to
     * appear. Offline never ends the alarm: it keeps trying until the deadline, still silenceable.
     */
    private suspend fun load() {
        var attempt = 0
        while (System.currentTimeMillis() < slot.deadlineEpochMs) {
            when (val result = app.exercise.today()) {
                is ApiResult.Ok -> {
                    val mine = result.value.occurrences.firstOrNull { it.goalId == slot.goalId && (slot.occurrenceId == null || it.id == slot.occurrenceId) }
                    if (mine != null) { show(mine); return }
                    if (System.currentTimeMillis() > slot.dueAtEpochMs + APPEAR_WITHIN_MS) { end(EndReason.Gone); return }
                }
                ApiResult.Offline -> _ui.update { it.copy(note = OFFLINE_MESSAGE) }
                is ApiResult.Failure -> _ui.update { it.copy(note = result.message) }
            }
            delay(if (++attempt < 6) 2_000 else 10_000)
        }
        end(EndReason.Missed)
    }

    private fun show(occurrence: ExerciseOccurrence) {
        when (occurrence.status) {
            OccurrenceStatus.Alarming, OccurrenceStatus.InProgress -> {
                if (_ui.value.phase !is AlarmPhase.Camera) _ui.update { it.copy(phase = AlarmPhase.Ringing(occurrence), note = null) }
                watch()
            }
            OccurrenceStatus.Completed -> { stopRinging(); _ui.update { it.copy(phase = AlarmPhase.Sealed(occurrence)) } }
            OccurrenceStatus.Cancelled -> end(EndReason.LetOff)
            OccurrenceStatus.Missed -> end(EndReason.Missed)
        }
    }

    /** While it rings, check in every few seconds: the partner may call it off. */
    private fun watch() {
        if (watcher?.isActive == true) return
        watcher = viewModelScope.launch {
            while (isActive) {
                delay(5_000)
                val today = app.exercise.today()
                if (today is ApiResult.Ok) {
                    val current = today.value.occurrences.firstOrNull { it.id == currentOccurrence()?.id } ?: continue
                    if (!current.status.ringing) { show(current); break }
                    if (_ui.value.phase is AlarmPhase.Ringing) _ui.update { it.copy(phase = AlarmPhase.Ringing(current)) }
                }
            }
        }
    }

    private fun currentOccurrence() = when (val p = _ui.value.phase) {
        is AlarmPhase.Ringing -> p.occurrence
        is AlarmPhase.Camera -> p.occurrence
        else -> null
    }

    fun askOff(note: String?) {
        val occurrence = currentOccurrence() ?: return
        _ui.update { it.copy(busy = true, note = null) }
        viewModelScope.launch {
            when (val result = app.exercise.askOff(occurrence.id, note)) {
                is ApiResult.Ok -> _ui.update { it.copy(busy = false, phase = AlarmPhase.Ringing(result.value)) }
                is ApiResult.Failure -> _ui.update {
                    if (result.code == "exercise.already_asked") it.copy(busy = false, phase = AlarmPhase.Ringing(occurrence.copy(callOffRequestedAtUtc = occurrence.callOffRequestedAtUtc ?: java.time.Instant.now().toString())))
                    else it.copy(busy = false, note = result.message)
                }
                ApiResult.Offline -> _ui.update { it.copy(busy = false, note = OFFLINE_MESSAGE) }
            }
        }
    }

    fun startCamera() {
        val occurrence = currentOccurrence() ?: return
        _ui.update { it.copy(busy = true, note = null) }
        viewModelScope.launch {
            when (val result = app.exercise.start(occurrence.id)) {
                is ApiResult.Ok -> _ui.update { it.copy(busy = false, phase = AlarmPhase.Camera(result.value)) }
                is ApiResult.Failure -> _ui.update { it.copy(busy = false, note = result.message) }
                ApiResult.Offline -> _ui.update { it.copy(busy = false, note = OFFLINE_MESSAGE) }
            }
        }
    }

    /** The camera counted the target; the server re-checks the count, the confidence and the time taken. */
    fun complete(count: Int, confidence: Double, onRejected: (String) -> Unit) {
        val occurrence = currentOccurrence() ?: return
        viewModelScope.launch {
            when (val result = app.exercise.complete(occurrence.id, count, confidence)) {
                is ApiResult.Ok -> { stopRinging(); _ui.update { it.copy(phase = AlarmPhase.Sealed(result.value)) } }
                is ApiResult.Failure -> onRejected(result.message)
                ApiResult.Offline -> onRejected(OFFLINE_MESSAGE)
            }
        }
    }

    fun showNote(message: String) = _ui.update { it.copy(note = message) }

    fun backToAlarm() {
        val occurrence = currentOccurrence() ?: return
        _ui.update { it.copy(phase = AlarmPhase.Ringing(occurrence)) }
    }

    fun silence() = AlarmNotifier.silence(getApplication(), slot)

    private fun stopRinging() = AlarmNotifier.stop(getApplication(), slot)

    private fun end(reason: EndReason) {
        stopRinging()
        watcher?.cancel()
        _ui.update { it.copy(phase = AlarmPhase.Ended(reason)) }
    }

    private companion object {
        const val APPEAR_WITHIN_MS = 60_000L
    }
}
