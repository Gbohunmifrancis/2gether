package app.heartpact.alarm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.heartpact.ui.ai.CoachLoader
import app.heartpact.ui.ai.CoachUi
import app.heartpact.HeartpactApplication
import app.heartpact.data.ApiResult
import app.heartpact.data.ExerciseOccurrence
import app.heartpact.data.OFFLINE_MESSAGE
import app.heartpact.data.OccurrenceStatus
import app.heartpact.data.PlayIntegrity
import app.heartpact.exercise.Cheer
import app.heartpact.exercise.CheerShown
import app.heartpact.exercise.ExerciseChecks
import app.heartpact.exercise.LiveCodec
import app.heartpact.exercise.LiveFrame
import app.heartpact.i18n.tr
import app.heartpact.ui.camera.FinishOutcome
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

data class AlarmUi(
    val phase: AlarmPhase = AlarmPhase.Loading,
    val note: String? = null,
    val busy: Boolean = false,
    val partnerName: String = tr("your partner"),
    /** Silenced on this phone: the dare stays open, the sound stops. */
    val silenced: Boolean = false,
    /** The dare's time has come, so the alarm may be ringing: the screen offers Silence. A dare done early isn't due yet. */
    val dueNow: Boolean = true,
    /** The coach's note under Heart complete (docs/ai.md); hidden unless the server writes one. */
    val coach: CoachUi = CoachUi.Hidden,
    /** The partner has the live view open: their name, for "{partner} is watching". */
    val watching: String? = null,
    /** The latest cheer from the live view, for the camera to show once. */
    val cheer: CheerShown? = null,
)

class AlarmViewModel(application: Application, private val slot: AlarmSlot) : AndroidViewModel(application) {
    private val app = (application as HeartpactApplication).container
    private val _ui = MutableStateFlow(AlarmUi(partnerName = slot.setByName ?: tr("your partner"), silenced = slot.key in AlarmStore(application).silenced,
        dueNow = !slot.early || System.currentTimeMillis() >= slot.dueAtEpochMs))
    val ui: StateFlow<AlarmUi> = _ui.asStateFlow()
    private var watcher: Job? = null

    init {
        viewModelScope.launch { if (slot.early) startEarly() else load() }
        // Done early and still going at the due time: the phone's own alarm rings then, so Silence appears.
        if (!_ui.value.dueNow) viewModelScope.launch { delay(slot.dueAtEpochMs - System.currentTimeMillis()); _ui.update { it.copy(dueNow = true) } }
        // Warm Play Integrity up while the person exercises, so the token at the finish is quick.
        viewModelScope.launch { app.integrity.prepare() }
        // The partner's live view: whether it's open, and the cheers it sends.
        viewModelScope.launch { app.live.events.collect(::partnerLive) }
    }

    private var watchTimeout: Job? = null

    private fun partnerLive(event: app.heartpact.live.LiveEvent) {
        if (event.name != "ExerciseWatching" && event.name != "ExerciseCheer") return
        val occurrence = currentOccurrence() ?: return
        val reply = runCatching { app.json.decodeFromString(LiveReply.serializer(), event.json) }.getOrNull() ?: return
        if (reply.occurrenceId != occurrence.id) return
        val name = reply.userName.ifBlank { _ui.value.partnerName }
        if (event.name == "ExerciseCheer") {
            val cheer = Cheer.of(reply.cheer) ?: return
            _ui.update { it.copy(cheer = CheerShown(cheer, name, (it.cheer?.id ?: 0) + 1), watching = name) }
            expireWatching()
            return
        }
        _ui.update { it.copy(watching = if (reply.watching) name else null) }
        if (reply.watching) expireWatching() else watchTimeout?.cancel()
    }

    /** The live view says it's open every few seconds; without that for a while, it was closed without saying so. */
    private fun expireWatching() {
        watchTimeout?.cancel()
        watchTimeout = viewModelScope.launch { delay(WATCHING_FOR_MS); _ui.update { it.copy(watching = null) } }
    }

    /** One moment of the workout for the partner's live view: only the stick figure's joints and the numbers. */
    fun live(frame: LiveFrame) {
        val occurrence = (_ui.value.phase as? AlarmPhase.Camera)?.occurrence ?: return
        app.live.exerciseLive(occurrence.id, frame.count, frame.phase.wire, frame.cue, LiveCodec.aspect(frame.aspect), LiveCodec.encode(frame.joints))
    }

    val early get() = slot.early

    /** Before it rings: the server opens today's occurrence now and the camera starts. */
    private suspend fun startEarly() {
        when (val result = app.exercise.startEarly(slot.goalId)) {
            is ApiResult.Ok -> _ui.update { it.copy(phase = AlarmPhase.Camera(result.value)) }
            is ApiResult.Failure -> if (result.code == "exercise.not_due_today" || result.code == "exercise.not_active") load() else _ui.update { it.copy(phase = AlarmPhase.Ended(EndReason.Gone), note = result.message) }
            ApiResult.Offline -> _ui.update { it.copy(phase = AlarmPhase.Ended(EndReason.Gone), note = OFFLINE_MESSAGE) }
        }
    }

    private var coachFor: String? = null

    /** The coach's note on the heart just made: written on the server while Heart complete shows, so it fades in when ready. */
    private fun coach(occurrenceId: String) {
        if (coachFor == occurrenceId) return
        coachFor = occurrenceId
        viewModelScope.launch {
            CoachLoader.load({ app.ai.coach(occurrenceId) }, { coach -> _ui.update { it.copy(coach = coach) } })
        }
    }

    /** Once a dare is settled, re-plan this phone's alarms so today's never rings. */
    private fun replan() = viewModelScope.launch {
        val me = app.session.load()?.user?.id ?: return@launch
        val today = app.exercise.today()
        if (today is ApiResult.Ok) AlarmScheduler.apply(getApplication(), AlarmPlan.plan(today.value, me, slot.setByName, java.time.Instant.now()))
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
            OccurrenceStatus.Completed -> { stopRinging(); _ui.update { it.copy(phase = AlarmPhase.Sealed(occurrence)) }; coach(occurrence.id) }
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

    /** Live count for the partner's Today; the hub drops it unless the dare is in progress. */
    fun progress(count: Int, confidence: Double) {
        val occurrence = currentOccurrence() ?: return
        app.live.reportExerciseProgress(occurrence.id, count, confidence)
    }

    /**
     * The camera counted the target; the server re-checks the count, the confidence and the time taken.
     * Says whether the camera should try again by itself (offline, the server busy or racing) or show why.
     */
    suspend fun complete(count: Int, confidence: Double, checks: ExerciseChecks): FinishOutcome {
        val occurrence = currentOccurrence() ?: return FinishOutcome.Saved
        val token = app.integrity.token(PlayIntegrity.requestHash(occurrence.id, count, checks))
        return when (val result = app.exercise.complete(occurrence.id, count, confidence, checks, token)) {
            is ApiResult.Ok -> { stopRinging(); _ui.update { it.copy(phase = AlarmPhase.Sealed(result.value)) }; replan(); coach(result.value.id); FinishOutcome.Saved }
            is ApiResult.Failure -> FinishOutcome.of(result.code, result.status, result.message).also {
                // Closed meanwhile (missed, let off, or done on another phone): show how it ended.
                if (result.code == "exercise.not_active") recheck(occurrence.id)
            }
            ApiResult.Offline -> FinishOutcome.Retry(OFFLINE_MESSAGE)
        }
    }

    private fun recheck(occurrenceId: String) = viewModelScope.launch {
        val today = app.exercise.today() as? ApiResult.Ok ?: return@launch
        today.value.occurrences.firstOrNull { it.id == occurrenceId }?.takeIf { !it.status.ringing }?.let(::show)
    }

    fun backToAlarm() {
        val occurrence = currentOccurrence() ?: return
        _ui.update { it.copy(phase = AlarmPhase.Ringing(occurrence)) }
    }

    fun silence() {
        AlarmNotifier.silence(getApplication(), slot)
        _ui.update { it.copy(silenced = true) }
    }

    private fun stopRinging() = AlarmNotifier.stop(getApplication(), slot)

    private fun end(reason: EndReason) {
        stopRinging()
        replan()
        watcher?.cancel()
        _ui.update { it.copy(phase = AlarmPhase.Ended(reason)) }
    }

    private companion object {
        const val APPEAR_WITHIN_MS = 60_000L
        /** The live view repeats "watching" every 10 seconds. */
        const val WATCHING_FOR_MS = 25_000L
    }
}

/** "ExerciseWatching" and "ExerciseCheer" from the couple hub. */
@kotlinx.serialization.Serializable
private data class LiveReply(val occurrenceId: String, val userId: String = "", val userName: String = "", val watching: Boolean = false, val cheer: String? = null)
