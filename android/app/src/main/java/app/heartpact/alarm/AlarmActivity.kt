package app.heartpact.alarm

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
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
 */
class AlarmActivity : ComponentActivity() {
    private val slot by lazy { AlarmNotifier.decode(intent.getStringExtra(AlarmNotifier.EXTRA_SLOT)) }
    private val model: AlarmViewModel by viewModels { viewModelFactory { initializer { AlarmViewModel(application, slot!!) } } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val slot = slot ?: return finish()
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
            val side by sessions.collectAsStateWithLifecycle(Side.Left)
            val ui by model.ui.collectAsStateWithLifecycle()
            HeartpactTheme(night = false, actor = side) {
                when (val phase = ui.phase) {
                    AlarmPhase.Loading -> AlarmLoading(ui.note)
                    is AlarmPhase.Ringing -> AlarmRinging(
                        occurrence = phase.occurrence,
                        partnerName = ui.partnerName,
                        setByPartner = slot.setByName != null,
                        mySide = side,
                        busy = ui.busy,
                        note = ui.note,
                        onStart = model::startCamera,
                        onAskOff = model::askOff,
                        onSilence = { model.silence(); finish() },
                    )
                    is AlarmPhase.Camera -> {
                        // Early: nothing is ringing, so stopping just closes; otherwise back to the alarm.
                        val leave = { if (model.early) finish() else model.backToAlarm() }
                        BackHandler { leave() }
                        val deadline = app.heartpact.data.utc(phase.occurrence.completeByUtc ?: phase.occurrence.deadlineUtc)
                        // The workout follows the phone's day or night; the ringing screen stays on ink.
                        HeartpactTheme(night = isSystemInDarkTheme(), actor = side) {
                            CameraRepScreen(
                                type = phase.occurrence.exerciseType,
                                target = phase.occurrence.target,
                                ringsUntil = if (model.early) null else deadline,
                                note = ui.note,
                                onReached = { count, confidence -> model.complete(count, confidence) { message -> model.showNote(message) } },
                                onStop = leave,
                                onProgress = model::progress,
                                mySide = side,
                                setBy = slot.setByName,
                                silenced = if (model.early) null else ui.silenced,
                                onSilence = model::silence,
                            )
                        }
                    }
                    is AlarmPhase.Sealed -> HeartComplete(phase.occurrence, ui.partnerName, onDone = ::finish, mySide = side)
                    is AlarmPhase.Ended -> AlarmEnded(phase.reason, ui.partnerName, onDone = ::finish)
                }
            }
        }
    }
}
