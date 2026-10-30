package app.heartpact.ui.map

import androidx.lifecycle.Lifecycle

/** What MapLibre's [org.maplibre.android.maps.MapView] needs told about its host's lifecycle. */
interface MapHost {
    fun onCreate()
    fun onStart()
    fun onResume()
    fun onPause()
    fun onStop()
    fun onDestroy()
}

/**
 * Walks a [MapHost] through the callbacks MapLibre insists on, in order, from lifecycle events: create, start, resume,
 * then pause, stop, destroy. It tolerates what Compose does: the observer is added when the screen is already resumed
 * (so the earlier steps are replayed by the lifecycle itself), and the view is disposed while the host may still be
 * running (so [dispose] finishes whatever is left). No step runs twice or out of order.
 */
class MapLifecycleDriver(private val host: MapHost) {
    private enum class Stage { New, Created, Started, Resumed, Destroyed }

    private var stage = Stage.New

    val destroyed: Boolean get() = stage == Stage.Destroyed

    fun onEvent(event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_CREATE -> create()
            Lifecycle.Event.ON_START -> { create(); start() }
            Lifecycle.Event.ON_RESUME -> { create(); start(); resume() }
            Lifecycle.Event.ON_PAUSE -> pause()
            Lifecycle.Event.ON_STOP -> { pause(); stop() }
            Lifecycle.Event.ON_DESTROY -> dispose()
            Lifecycle.Event.ON_ANY -> Unit
        }
    }

    /** The view is going away: bring it down from wherever it is. */
    fun dispose() {
        if (stage == Stage.New) { stage = Stage.Destroyed; return }
        pause(); stop()
        if (stage != Stage.Destroyed) {
            stage = Stage.Destroyed
            host.onDestroy()
        }
    }

    private fun create() {
        if (stage != Stage.New) return
        stage = Stage.Created
        host.onCreate()
    }

    private fun start() {
        if (stage != Stage.Created) return
        stage = Stage.Started
        host.onStart()
    }

    private fun resume() {
        if (stage != Stage.Started) return
        stage = Stage.Resumed
        host.onResume()
    }

    private fun pause() {
        if (stage != Stage.Resumed) return
        stage = Stage.Started
        host.onPause()
    }

    private fun stop() {
        if (stage != Stage.Started) return
        stage = Stage.Created
        host.onStop()
    }
}
