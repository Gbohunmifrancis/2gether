package app.heartpact.alarm

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.heartpact.HeartpactApplication
import app.heartpact.data.CoupleSide
import app.heartpact.ui.camera.CameraRepScreen
import app.heartpact.ui.theme.HeartpactTheme
import app.heartpact.ui.theme.Side
import kotlinx.coroutines.flow.map

/**
 * The full-screen alarm, shown over the lock screen: ringing, then the camera, then the heart.
 * Opened by the alarm notification, or from Today when a dare is already ringing.
 *
 * It is single-task, so a second dare ringing (or its notification tapped) arrives here as a new
 * intent. The screen switches to that dare, unless the person is on camera for this one: then the
 * new one waits and shows when this one is done. A dare switched away from waits the same way, so
 * whatever is still ringing comes back on screen rather than being lost behind the first.
 */
class AlarmActivity : ComponentActivity() {
    private var slot by mutableStateOf<AlarmSlot?>(null)
    private val waiting = ArrayDeque<AlarmSlot>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val first = AlarmNotifier.decode(intent.getStringExtra(AlarmNotifier.EXTRA_SLOT)) ?: return finish()
        slot = first
        savedInstanceState?.getStringArrayList(WAITING)?.mapNotNull(AlarmNotifier::decode)?.let(waiting::addAll)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        val sessions = (application as HeartpactApplication).container.session.session.map { if (it?.side == CoupleSide.Right) Side.Right else Side.Left }
        setContent {
            val slot = slot ?: return@setContent
            val side by sessions.collectAsStateWithLifecycle(Side.Left)
            // One view model per dare, so switching keeps each one's state (and its own check-ins with the server).
            key(slot.key) {
                val model = remember { model(slot) }
                val ui by model.ui.collectAsStateWithLifecycle()
                HeartpactTheme(night = false, actor = side) {
                    when (val phase = ui.phase) {
                        AlarmPhase.Loading -> AlarmLoading(ui.note, onSilence = if (ui.dueNow && !ui.silenced) ({ model.silence(); done() }) else null)
                        is AlarmPhase.Ringing -> AlarmRinging(
                            occurrence = phase.occurrence,
                            partnerName = ui.partnerName,
                            setByPartner = slot.setByName != null,
                            mySide = side,
                            busy = ui.busy,
                            note = ui.note,
                            onStart = model::startCamera,
                            onAskOff = model::askOff,
                            onSilence = { model.silence(); done() },
                        )
                        is AlarmPhase.Camera -> {
                            // Early: nothing is ringing, so stopping just closes; otherwise back to the alarm.
                            val leave = { if (model.early) done() else model.backToAlarm() }
                            BackHandler { leave() }
                            val deadline = app.heartpact.data.utc(phase.occurrence.completeByUtc ?: phase.occurrence.deadlineUtc)
                            // The workout follows the phone's day or night; the ringing screen stays on ink.
                            HeartpactTheme(night = isSystemInDarkTheme(), actor = side) {
                                CameraRepScreen(
                                    type = phase.occurrence.exerciseType,
                                    target = phase.occurrence.target,
                                    ringsUntil = if (model.early) null else deadline,
                                    note = ui.note,
                                    onReached = model::complete,
                                    onStop = leave,
                                    onProgress = model::progress,
                                    mySide = side,
                                    setBy = slot.setByName,
                                    // Done early, the alarm still rings at the due time: Silence shows from then on.
                                    silenced = if (ui.dueNow) ui.silenced else null,
                                    onSilence = model::silence,
                                )
                            }
                        }
                        is AlarmPhase.Sealed -> HeartComplete(phase.occurrence, ui.partnerName, onDone = ::done, mySide = side)
                        is AlarmPhase.Ended -> AlarmEnded(phase.reason, ui.partnerName, onDone = ::done)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val next = AlarmNotifier.decode(intent.getStringExtra(AlarmNotifier.EXTRA_SLOT)) ?: return
        val current = slot
        if (current == null || next.key == current.key) return
        waiting.removeAll { it.key == next.key }
        // Mid-workout: don't pull the camera away. The new dare keeps ringing and shows next.
        if (model(current).ui.value.phase is AlarmPhase.Camera) waiting.addLast(next) else show(next)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(WAITING, ArrayList(waiting.map(AlarmNotifier::encode)))
    }

    private fun model(slot: AlarmSlot): AlarmViewModel =
        ViewModelProvider(this, viewModelFactory { initializer { AlarmViewModel(application, slot) } })[slot.key, AlarmViewModel::class.java]

    /** Puts [next] on screen; the dare it replaces waits if it is still open, unless [keepCurrent] is false. */
    private fun show(next: AlarmSlot, keepCurrent: Boolean = true) {
        slot?.let { current -> if (keepCurrent && current.key != next.key && isOpen(current)) waiting.addFirst(current) }
        slot = next
        // A recreated activity (rotation) reads its dare from the intent.
        intent = Intent(intent).putExtra(AlarmNotifier.EXTRA_SLOT, AlarmNotifier.encode(next))
    }

    private fun isOpen(slot: AlarmSlot): Boolean {
        val phase = model(slot).ui.value.phase
        return phase !is AlarmPhase.Sealed && phase !is AlarmPhase.Ended && slot.deadlineEpochMs > System.currentTimeMillis()
    }

    /** This dare is finished with: show the next one still ringing here, or close. */
    private fun done() {
        val silenced = AlarmStore(this).silenced
        while (waiting.isNotEmpty()) {
            val next = waiting.removeFirst()
            if (next.key !in silenced && isOpen(next)) return show(next, keepCurrent = false)
        }
        finish()
    }

    private companion object {
        const val WAITING = "waiting"
    }
}
