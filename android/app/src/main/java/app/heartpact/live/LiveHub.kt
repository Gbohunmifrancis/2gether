package app.heartpact.live

import android.util.Log
import app.heartpact.data.SessionStore
import com.google.gson.JsonElement
import com.microsoft.signalr.HubConnection
import com.microsoft.signalr.HubConnectionBuilder
import com.microsoft.signalr.HubConnectionState
import io.reactivex.rxjava3.core.Single
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One event from the couple hub: its name, and the payload as JSON text for kotlinx to read. */
data class LiveEvent(val name: String, val json: String)

/**
 * The couple hub (SignalR, /hubs/couple): live dare progress, location, and "something changed,
 * refetch" events. Open while anything holds it (the app on screen, a location share running) and
 * a session exists; it reconnects with backoff and closes when the last holder lets go.
 */
class LiveHub(private val baseUrl: String, private val session: SessionStore, private val onEvent: (LiveEvent) -> Unit = {}) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val holders = mutableSetOf<String>()
    private var connection: HubConnection? = null
    private var reconnect: Job? = null

    private val _events = MutableSharedFlow<LiveEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<LiveEvent> = _events.asSharedFlow()
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    fun hold(key: String) = scope.launch { lock.withLock { holders += key }; ensure() }
    fun release(key: String) = scope.launch {
        val empty = lock.withLock { holders -= key; holders.isEmpty() }
        if (empty) close()
    }

    /** Signed out: drop every holder and the connection. */
    fun stopAll() = scope.launch { lock.withLock { holders.clear() }; close() }

    /** After signing in or linking: the token changed, so connect again. */
    fun restart() = scope.launch { close(); ensure() }

    /** [fixedAtMs] is when the phone took the fix; the server keeps it (capped at its own clock) as the point's time. */
    fun updateLocation(latitude: Double, longitude: Double, accuracy: Double, fixedAtMs: Long = System.currentTimeMillis()) =
        send("UpdateLocationAt", latitude, longitude, accuracy, fixedAtMs)
    fun reportExerciseProgress(occurrenceId: String, count: Int, confidence: Double) = send("ReportExerciseProgress", occurrenceId, count, confidence)
    /** Tic-tac-toe's live ink for the partner: a square (-1 clears), a stroke number, and x,y pairs as fractions of the square. */
    fun ink(sessionId: String, cell: Int, stroke: Int, points: FloatArray) = send("Ink", sessionId, cell, stroke, points)

    private fun send(method: String, vararg args: Any) = scope.launch {
        ensure()
        val hub = connection ?: return@launch
        if (hub.connectionState != HubConnectionState.CONNECTED) return@launch
        runCatching { hub.send(method, *args) }.onFailure { Log.w(TAG, "$method failed", it) }
    }

    private suspend fun ensure() {
        lock.withLock {
            if (holders.isEmpty()) return
            val token = session.token ?: session.load()?.token ?: return
            if (connection?.connectionState == HubConnectionState.CONNECTED || connection?.connectionState == HubConnectionState.CONNECTING) return
            val hub = HubConnectionBuilder.create(baseUrl.trimEnd('/') + "/hubs/couple")
                .withAccessTokenProvider(Single.defer { Single.just(session.token ?: token) })
                .build()
            EVENTS.forEach { name -> hub.on(name, { payload: JsonElement? -> emit(name, payload) }, JsonElement::class.java) }
            hub.onClosed { error ->
                _connected.value = false
                if (error != null) Log.i(TAG, "Hub closed: ${error.message}")
                scheduleReconnect()
            }
            connection = hub
            val started = runCatching { hub.start().blockingAwait() }
            _connected.value = started.isSuccess
            if (started.isFailure) { Log.i(TAG, "Hub start failed: ${started.exceptionOrNull()?.message}"); scheduleReconnect() } else attempts = 0
        }
    }

    private var attempts = 0

    private fun scheduleReconnect() {
        if (reconnect?.isActive == true) return
        reconnect = scope.launch {
            val wait = BACKOFF_MS[minOf(attempts, BACKOFF_MS.lastIndex)]
            attempts++
            delay(wait)
            lock.withLock { if (holders.isEmpty()) return@launch; connection = null }
            ensure()
        }
    }

    private suspend fun close() {
        reconnect?.cancel()
        lock.withLock {
            val hub = connection ?: return
            connection = null
            _connected.value = false
            runCatching { hub.stop().blockingAwait() }
        }
    }

    private fun emit(name: String, payload: JsonElement?) {
        val event = LiveEvent(name, payload?.toString() ?: "null")
        _events.tryEmit(event)
        onEvent(event)
    }

    companion object {
        private const val TAG = "LiveHub"
        private val BACKOFF_MS = longArrayOf(1_000, 2_000, 5_000, 10_000, 30_000)

        /** What the app listens for; everything else on the hub is for the web app. */
        val EVENTS = listOf(
            "ExerciseAlarmStarted", "ExerciseStarted", "ExerciseProgress", "ExerciseCompleted", "ExerciseMissed",
            "ExerciseCancelled", "ExerciseCallOffAsked", "ExerciseGoalsChanged",
            "LocationUpdated", "LocationStopped", "LocationSharingChanged",
            "FoodDayUpdated", "DailyQuestionAnswered", "PregnancyChanged", "MemoriesChanged",
            "CoupleEnded", "AccountDeleted", "NotificationCreated",
            "GameInvitation", "GameCountdown", "GameStarted", "GameStateChanged", "GameCompleted", "GameEnded", "GameInk",
        )

        /** Hub names to the app's event names: "ExerciseCompleted" becomes "exerciseCompleted", as pushes use. */
        fun appName(hubName: String) = hubName.replaceFirstChar { it.lowercase() }
    }
}
