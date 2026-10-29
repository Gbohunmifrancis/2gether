package app.heartpact.ui.map

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.heartpact.HeartpactApplication
import app.heartpact.data.ApiResult
import app.heartpact.data.LocationPoint
import app.heartpact.data.OFFLINE_MESSAGE
import app.heartpact.data.SharingState
import app.heartpact.data.SharingStatus
import app.heartpact.data.SharingStatusView
import app.heartpact.i18n.tr
import app.heartpact.location.LocationShareService
import app.heartpact.location.LocationStop
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

data class LocationUi(
    val status: SharingStatusView? = null,
    val points: List<LocationPoint> = emptyList(),
    /** This phone is sending fixes right now. */
    val sendingHere: Boolean = false,
    val note: String? = null,
    val busy: Boolean = false,
) {
    val me: LocationPoint? get() = points.firstOrNull { it.isCurrentUser }
    val partner: LocationPoint? get() = points.firstOrNull { !it.isCurrentUser }
    val myStatus: SharingStatus get() = status?.me?.status ?: SharingStatus.Off
}

private fun <T> ApiResult<T>.note(): String? = when (this) {
    is ApiResult.Ok -> null
    ApiResult.Offline -> OFFLINE_MESSAGE
    is ApiResult.Failure -> message
}

class LocationViewModel(application: Application) : AndroidViewModel(application) {
    private val app = (application as HeartpactApplication).container
    private val _ui = MutableStateFlow(LocationUi(sendingHere = LocationShareService.running))
    val ui: StateFlow<LocationUi> = _ui.asStateFlow()
    private var pauseEnd: Job? = null

    init {
        viewModelScope.launch {
            app.live.events.collect { event ->
                when (event.name) {
                    "LocationUpdated" -> decode(LocationPoint.serializer(), event.json)?.let { point ->
                        // A point can race this phone's own pause or stop by milliseconds: while that's off, nobody shows.
                        if (LocationMath.acceptsPoints(_ui.value.myStatus))
                            _ui.update { it.copy(points = it.points.filterNot { p -> p.userId == point.userId } + point) }
                    }
                    "LocationStopped" -> runCatching { app.json.parseToJsonElement(event.json).jsonPrimitive.content }.getOrNull()?.let { gone ->
                        _ui.update { it.copy(points = it.points.filterNot { p -> p.userId == gone }) }
                    }
                    "LocationSharingChanged" -> decode(SharingState.serializer(), event.json)?.let { state ->
                        _ui.update { it.copy(status = merge(it.status, state), points = visible(it, state)) }
                        watchPauseEnd()
                    }
                }
            }
        }
    }

    private fun <T> decode(serializer: kotlinx.serialization.KSerializer<T>, json: String): T? = runCatching { app.json.decodeFromString(serializer, json) }.getOrNull()

    private fun merge(current: SharingStatusView?, state: SharingState): SharingStatusView? {
        current ?: return null
        val me = if (state.userId == current.me.userId) state else current.me
        val partner = if (state.userId == current.partner?.userId) state else current.partner
        return SharingStatusView(me, partner, me.status == SharingStatus.Sharing && partner?.status == SharingStatus.Sharing)
    }

    /** The points still allowed once someone's sharing changed: none of a person who isn't sharing, and nobody while I'm not. */
    private fun visible(ui: LocationUi, state: SharingState): List<LocationPoint> = when {
        state.status == SharingStatus.Sharing -> ui.points
        state.userId == ui.status?.me?.userId -> emptyList()
        else -> ui.points.filterNot { it.userId == state.userId }
    }

    /** Nothing tells a screen that a pause ran out: refresh just after it ends. */
    private fun watchPauseEnd() {
        pauseEnd?.cancel()
        val wait = LocationMath.refreshAfterPauseMs(_ui.value.status, Instant.now()) ?: return
        pauseEnd = viewModelScope.launch { delay(wait); refresh() }
    }

    fun refresh() = viewModelScope.launch {
        val status = app.location.status()
        val points = app.location.points()
        _ui.update {
            it.copy(
                status = (status as? ApiResult.Ok)?.value ?: it.status,
                points = (points as? ApiResult.Ok)?.value ?: it.points,
                sendingHere = LocationShareService.running,
                note = status.note(),
            )
        }
        watchPauseEnd()
    }

    /** After the person tapped Share (or Resume on a phone that isn't sending) and granted location. Starting ends a pause. */
    fun share(partnerName: String) {
        if (!LocationShareService.canNotify(getApplication())) {
            _ui.update { it.copy(note = tr("Turn on notifications for Heartpact to share your location. The notification shows the whole time you share, so you can always see it and stop.")) }
            return
        }
        LocationShareService.start(getApplication(), partnerName)
        _ui.update { it.copy(sendingHere = true, note = null) }
        viewModelScope.launch { delay(1_500); refresh() }
    }

    fun pause(minutes: Int) = act { app.location.pause(minutes) }

    /** Only while this phone is sending; otherwise Resume goes through [share], which starts it. */
    fun resume() = act { app.location.resume() }

    fun stop() {
        if (LocationShareService.running) LocationShareService.stop(getApplication(), tellServer = false)
        LocationStop.request(app)
        _ui.update { it.copy(sendingHere = false, points = emptyList(), status = it.status?.let { s -> s.copy(me = s.me.copy(status = SharingStatus.Off, pausedUntilUtc = null), mutual = false) }) }
        viewModelScope.launch { delay(1_500); refresh() }
    }

    private fun act(call: suspend () -> ApiResult<SharingStatusView>) {
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = call()
            _ui.update { it.copy(busy = false, note = result.note(), status = (result as? ApiResult.Ok)?.value ?: it.status) }
            // The service turns location off while paused and back on after, and updates its notification.
            LocationShareService.refresh(getApplication())
            refresh()
        }
    }
}
