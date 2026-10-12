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
import app.heartpact.location.LocationShareService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonPrimitive

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

    init {
        viewModelScope.launch {
            app.live.events.collect { event ->
                when (event.name) {
                    "LocationUpdated" -> decode(LocationPoint.serializer(), event.json)?.let { point ->
                        _ui.update { it.copy(points = it.points.filterNot { p -> p.userId == point.userId } + point) }
                    }
                    "LocationStopped" -> runCatching { app.json.parseToJsonElement(event.json).jsonPrimitive.content }.getOrNull()?.let { gone ->
                        _ui.update { it.copy(points = it.points.filterNot { p -> p.userId == gone }) }
                    }
                    "LocationSharingChanged" -> decode(SharingState.serializer(), event.json)?.let { state -> _ui.update { it.copy(status = merge(it.status, state)) } }
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
    }

    /** After the person tapped Share and granted location. */
    fun share(partnerName: String) {
        LocationShareService.start(getApplication(), partnerName)
        _ui.update { it.copy(sendingHere = true) }
        if (_ui.value.myStatus == SharingStatus.Paused) resume()
    }

    fun pause(minutes: Int) = act { app.location.pause(minutes) }
    fun resume() = act { app.location.resume() }

    fun stop() {
        LocationShareService.stop(getApplication())
        app.live.stopLocationSharing()
        _ui.update { it.copy(sendingHere = false, points = it.points.filterNot { p -> !p.isCurrentUser }) }
        viewModelScope.launch { kotlinx.coroutines.delay(800); refresh() }
    }

    private fun act(call: suspend () -> ApiResult<SharingStatusView>) {
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = call()
            _ui.update { it.copy(busy = false, note = result.note(), status = (result as? ApiResult.Ok)?.value ?: it.status) }
            refresh()
        }
    }
}
