package app.heartpact.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.NotificationsPaused
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import app.heartpact.exercise.ChallengeUi
import app.heartpact.exercise.CounterState
import app.heartpact.exercise.ExerciseChecks
import app.heartpact.exercise.ExerciseType
import app.heartpact.exercise.Measure
import app.heartpact.exercise.MotionLog
import app.heartpact.exercise.Pose
import app.heartpact.exercise.RepCounter
import app.heartpact.exercise.SessionChecks
import app.heartpact.i18n.tr
import app.heartpact.i18n.trn
import app.heartpact.ui.components.HeartButton
import app.heartpact.ui.graphics.Character
import app.heartpact.ui.graphics.HalfState
import app.heartpact.ui.graphics.HeartSticker
import app.heartpact.ui.graphics.Who
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.Shapes
import app.heartpact.ui.theme.Side
import app.heartpact.ui.theme.partner
import app.heartpact.ui.graphics.Pose as CharacterPose
import kotlinx.coroutines.delay
import java.time.Instant
import java.util.concurrent.Executors

/** Glass over the live picture: the same in day and night, since the camera is what's behind it. */
private val Glass = Color(0xFF161A3D)
private val Bone = Color(0xFFB9C0FF)
private val Joint = Color(0xFF1F2557)
private val OutOfFrame = Color(0xFFFFB4AB)

/** How far the sheet reaches over the picture. */
private val Overlap = 30.dp

/** Lines drawn between landmarks for the overlay: shoulders, arms, torso, legs. */
private val BONES = listOf(11 to 12, 11 to 13, 13 to 15, 12 to 14, 14 to 16, 11 to 23, 12 to 24, 23 to 24, 23 to 25, 25 to 27, 24 to 26, 26 to 28)

/** What the camera screen shows, apart from the live picture. Pure, so it can be rendered in tests. */
data class CameraUi(
    val type: ExerciseType,
    val target: Int,
    val counter: CounterState = CounterState(),
    val pose: Pose? = null,
    /** Until the alarm stops ringing; null when the dare is done early and nothing rings. */
    val secondsLeft: Int? = null,
    val elapsedSeconds: Int = 0,
    val note: String? = null,
    val sealing: Boolean = false,
    val mySide: Side = Side.Left,
    /** Who set the dare, when it was the partner: they see each rep as it's counted. */
    val setBy: String? = null,
    /** Null when there's nothing ringing to silence. */
    val silenced: Boolean? = null,
    /** A surprise check-in on screen, or its result for a moment after. */
    val challenge: ChallengeUi? = null,
    /** The target was reached but the finish didn't save yet; the counted reps are kept. */
    val unsaved: Unsaved? = null,
)

/** A finish that didn't save: why, and whether it is being retried by itself. */
data class Unsaved(val message: String, val retrying: Boolean)

/** How sending a finish went: saved, worth retrying by itself (offline, the server busy), or refused for a reason to show. */
sealed interface FinishOutcome {
    data object Saved : FinishOutcome
    data class Retry(val message: String) : FinishOutcome
    data class Refused(val message: String) : FinishOutcome

    companion object {
        /**
         * From the server's answer: trouble on the way or a race retries by itself (a server clock a second
         * behind can make an honest finish "too fast"); a closed dare or a reason to fix waits for the person.
         */
        fun of(code: String, status: Int, message: String): FinishOutcome = when {
            code == "exercise.not_active" -> Refused(message)
            status >= 500 || status == 408 || status == 429 || code == "exercise.version_conflict" || code == "exercise.too_fast" -> Retry(message)
            else -> Refused(message)
        }
    }
}

/** The wait before the next automatic try: 2 s, doubling, at most 30 s. */
fun finishBackoffMs(attempt: Int): Long = (2_000L shl attempt.coerceIn(0, 4)).coerceAtMost(30_000L)

/** The words and numbers on the camera screen, apart from layout. */
object CameraText {
    fun clock(seconds: Int): String = "%d:%02d".format(seconds.coerceAtLeast(0) / 60, seconds.coerceAtLeast(0) % 60)

    fun title(type: ExerciseType, target: Int): String = type.label(target).replaceFirstChar { it.uppercase() }

    fun whose(setBy: String?): String = setBy?.let { tr("{partner}'s dare", "partner" to it) } ?: tr("Your dare")

    /** Under the big count: "of 15 arm raises", "of 30 seconds". */
    fun of(type: ExerciseType, target: Int): String = if (type.measure == Measure.Seconds) trn(target, "of {count} second", "of {count} seconds") else tr("of {total}", "total" to type.label(target))

    fun toGo(ui: CameraUi): String {
        val left = (ui.target - ui.counter.count).coerceAtLeast(0)
        return if (ui.type.measure == Measure.Seconds) tr("{seconds}s", "seconds" to left) else "$left"
    }

    fun frame(inFrame: Boolean): String = if (inFrame) tr("Whole body in frame") else tr("Step back so all of you is in frame")
}

/**
 * The workout: the camera full-bleed with the pose drawn over it, a bar filling toward your half of the
 * heart, and a sheet with the count, the time taken and the time left to ring. Phones held sideways put
 * the sheet beside the picture instead of under it.
 */
@Composable
fun CameraRepContent(ui: CameraUi, onFlip: () -> Unit, onStop: () -> Unit, onSilence: () -> Unit = {}, onRetry: () -> Unit = {}, preview: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Glass)) {
        val wide = maxWidth > maxHeight
        Layout(content = {
            Stage(ui, wide, onFlip, preview)
            Sheet(ui, wide, onStop, onSilence, onRetry)
        }) { measurables, constraints ->
            val overlap = Overlap.roundToPx()
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            if (wide) {
                val sheet = measurables[1].measure(Constraints.fixed(300.dp.roundToPx().coerceAtMost(width / 2), height))
                val stage = measurables[0].measure(Constraints.fixed((width - sheet.width + overlap).coerceAtLeast(0), height))
                layout(width, height) { stage.place(0, 0); sheet.place(width - sheet.width, 0) }
            } else {
                val sheet = measurables[1].measure(Constraints(minWidth = width, maxWidth = width, maxHeight = height))
                val stage = measurables[0].measure(Constraints.fixed(width, (height - sheet.height + overlap).coerceAtLeast(0)))
                layout(width, height) { stage.place(0, 0); sheet.place(0, height - sheet.height) }
            }
        }
    }
}

@Composable
private fun Stage(ui: CameraUi, wide: Boolean, onFlip: () -> Unit, preview: @Composable () -> Unit) {
    // Clear of the sheet's rounded edge, which lies over this side of the picture.
    val clear = if (wide) Modifier.padding(end = Overlap) else Modifier.padding(bottom = Overlap)
    Box(Modifier.fillMaxSize().clipToBounds()) {
        preview()
        ui.pose?.let { pose ->
            Canvas(Modifier.fillMaxSize()) {
                val points = pose.landmarks.map { Offset(it.x * size.width, it.y * size.height) }
                BONES.forEach { (a, b) ->
                    if (pose[a].visibility > 0.5f && pose[b].visibility > 0.5f) drawLine(Bone, points[a], points[b], 3.5.dp.toPx(), StrokeCap.Round)
                }
                listOf(11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28).filter { pose[it].visibility > 0.5f }.forEach {
                    drawCircle(Color.White, 6.dp.toPx(), points[it]); drawCircle(Joint, 6.dp.toPx(), points[it], style = Stroke(2.5.dp.toPx()))
                }
            }
        }
        // Keeps the title readable over a bright room.
        Box(Modifier.fillMaxWidth().height(190.dp).background(Brush.verticalGradient(listOf(Glass.copy(alpha = 0.72f), Glass.copy(alpha = 0f)))))
        Box(Modifier.fillMaxSize().then(clear).statusBarsPadding().padding(start = 20.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Icon(Icons.Rounded.Alarm, null, Modifier.size(16.dp), tint = Color.White.copy(alpha = 0.85f))
                        Text(CameraText.whose(ui.setBy), style = Heartpact.type.small, color = Color.White.copy(alpha = 0.85f))
                    }
                    Text(CameraText.title(ui.type, ui.target), style = Heartpact.type.headline, color = Color.White, modifier = Modifier.padding(top = 2.dp))
                    Chip(CameraText.frame(ui.counter.inFrame), dot = if (ui.counter.inFrame) Bone else OutOfFrame, modifier = Modifier.padding(top = 10.dp))
                }
                IconButton(onClick = onFlip, modifier = Modifier.size(46.dp).clip(Shapes.Pill).background(Glass.copy(alpha = 0.62f))) {
                    Icon(Icons.Rounded.Cameraswitch, tr("Switch camera"), tint = Color.White)
                }
            }
            // A check-in takes the middle of the picture; the bar steps aside until it's answered.
            val checkingIn = ui.challenge != null && ui.challenge.passed == null
            if (!checkingIn) Progress(ui, trackHeight = if (wide) 76.dp else 150.dp, modifier = Modifier.align(if (wide) Alignment.BottomEnd else Alignment.CenterEnd))
            ui.challenge?.let { CheckIn(it, Modifier.align(Alignment.Center).padding(horizontal = if (wide) 0.dp else 24.dp)) }
            ui.setBy?.let { partner ->
                val who = if (ui.mySide == Side.Left) Who.Right else Who.Left
                Row(
                    Modifier.align(if (wide) Alignment.BottomStart else Alignment.BottomEnd).clip(Shapes.Pill).background(Glass.copy(alpha = 0.62f)).padding(start = 6.dp, end = 14.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Character(who, CharacterPose.Cheer, 36.dp)
                    Text(tr("{partner} is watching", "partner" to partner), style = Heartpact.type.labelSmall, color = Color.White)
                }
            }
        }
    }
}

/** The bar toward your half: your partner's half is on, yours sticks when it fills. */
@Composable
private fun Progress(ui: CameraUi, trackHeight: Dp, modifier: Modifier) {
    val colors = Heartpact.colors
    val mine = colors.partner(ui.mySide)
    val done = ui.counter.count >= ui.target
    val fraction = if (ui.target > 0) (ui.counter.count.toFloat() / ui.target).coerceIn(0f, 1f) else 0f
    val fill by animateFloatAsState(fraction, label = "progress")
    val myHalf = if (done) HalfState.Stuck else HalfState.Slot
    Column(
        modifier.width(70.dp).shadow(12.dp, Shapes.Pill, ambientColor = Glass, spotColor = Glass).clip(Shapes.Pill).background(colors.sticker).padding(top = 14.dp, bottom = 12.dp)
            .semantics(mergeDescendants = true) { contentDescription = tr("{count} of {target}, {left} to go", "count" to ui.counter.count, "target" to ui.target, "left" to CameraText.toGo(ui)) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (ui.mySide == Side.Left) HeartSticker(myHalf, HalfState.Stuck, 34.dp) else HeartSticker(HalfState.Stuck, myHalf, 34.dp)
        Box(Modifier.width(14.dp).height(trackHeight).clip(Shapes.Pill).background(mine.soft), contentAlignment = Alignment.BottomCenter) {
            Box(Modifier.fillMaxWidth().fillMaxHeight(fill).clip(Shapes.Pill).background(mine.action))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(CameraText.toGo(ui), style = Heartpact.type.titleSmall, color = colors.ink)
            Text(tr("to go"), style = Heartpact.type.small.copy(fontSize = 11.sp, lineHeight = 13.sp), color = colors.ink2)
        }
    }
}

/** A surprise check-in, big and central so it can't be missed mid-rep. */
@Composable
private fun CheckIn(challenge: ChallengeUi, modifier: Modifier) {
    val accent = when (challenge.passed) { true -> Bone; false -> OutOfFrame; null -> Color.White }
    Column(
        modifier.widthIn(max = 300.dp).clip(Shapes.Card).background(Glass.copy(alpha = 0.88f)).padding(horizontal = 22.dp, vertical = 18.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Assertive },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(challenge.title, style = Heartpact.type.title, color = accent, textAlign = TextAlign.Center)
        Text(challenge.body, style = Heartpact.type.body, color = Color.White.copy(alpha = 0.9f), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
        if (challenge.passed == null) {
            Box(Modifier.padding(top = 14.dp).fillMaxWidth().height(8.dp).clip(Shapes.Pill).background(Color.White.copy(alpha = 0.18f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(challenge.progress).clip(Shapes.Pill).background(Bone))
            }
            Text(tr("{seconds}s left", "seconds" to (challenge.msLeft + 999) / 1_000), style = Heartpact.type.small, color = Color.White.copy(alpha = 0.75f), modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun Chip(text: String, dot: Color, modifier: Modifier = Modifier) {
    Row(modifier.clip(Shapes.Pill).background(Glass.copy(alpha = 0.62f)).padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(Modifier.size(8.dp).clip(Shapes.Pill).background(dot))
        Text(text, style = Heartpact.type.labelSmall, color = Color.White)
    }
}

/** The count and the clocks, then the way out. Sticker stock, so it follows day and night. */
@Composable
private fun Sheet(ui: CameraUi, wide: Boolean, onStop: () -> Unit, onSilence: () -> Unit, onRetry: () -> Unit) {
    val colors = Heartpact.colors
    val shape = if (wide) RoundedCornerShape(topStart = 30.dp, bottomStart = 30.dp) else Shapes.Sheet
    val bars = if (wide) Modifier.statusBarsPadding().navigationBarsPadding() else Modifier.navigationBarsPadding()
    Column(
        Modifier.shadow(24.dp, shape, ambientColor = Glass, spotColor = Glass).clip(shape).background(colors.sticker).then(bars)
            .padding(start = 20.dp, end = 20.dp, top = if (wide) 16.dp else 22.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val elapsed = @Composable { align: Alignment.Horizontal -> Stat(tr("Elapsed"), CameraText.clock(ui.elapsedSeconds), align) }
        val left = @Composable { align: Alignment.Horizontal ->
            ui.secondsLeft?.let { Stat(tr("Left to ring"), CameraText.clock(it), align) } ?: Stat(tr("To go"), CameraText.toGo(ui), align)
        }
        if (wide) {
            Row(Modifier.fillMaxWidth()) {
                Box(Modifier.weight(1f)) { elapsed(Alignment.Start) }
                Box(Modifier.weight(1f), contentAlignment = Alignment.TopEnd) { left(Alignment.End) }
            }
            Count(ui, Modifier.padding(top = 12.dp).fillMaxWidth())
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { elapsed(Alignment.Start) }
                Count(ui, Modifier.width(132.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { left(Alignment.End) }
            }
        }
        val unsaved = ui.unsaved?.let { if (it.retrying) tr("Couldn't save, retrying. Your reps are kept.") else it.message }
        val note = ui.note ?: unsaved ?: if (ui.sealing) tr("Sticking your half…") else ui.counter.cue
        Text(
            note ?: ui.type.cue,
            style = if (note != null) Heartpact.type.label else Heartpact.type.small,
            color = if (note != null) colors.ink else colors.ink2,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp).fillMaxWidth(),
        )
        if (ui.unsaved != null && !ui.sealing) HeartButton(tr("Try again"), onRetry, Modifier.padding(top = 10.dp), kind = app.heartpact.ui.components.ButtonKind.Tonal, height = 48.dp)
        if (wide) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
        Row(
            Modifier.padding(top = 14.dp).fillMaxWidth().clip(Shapes.Pill).background(colors.page).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ui.silenced?.let { silenced ->
                IconButton(onClick = onSilence, enabled = !silenced, modifier = Modifier.size(52.dp).clip(Shapes.Pill).background(colors.sticker)) {
                    Icon(
                        if (silenced) Icons.Rounded.NotificationsPaused else Icons.Rounded.NotificationsOff,
                        if (silenced) tr("Silenced on this phone") else tr("Silence on this phone"),
                        tint = if (silenced) colors.ink3 else colors.ink,
                    )
                }
            }
            HeartButton(tr("Stop counting"), onStop, Modifier.weight(1f), icon = Icons.Rounded.Stop, height = 52.dp)
        }
    }
}

@Composable
private fun Stat(label: String, value: String, align: Alignment.Horizontal) {
    Column(horizontalAlignment = align) {
        Text(label, style = Heartpact.type.small, color = Heartpact.colors.ink3)
        Text(value, style = Heartpact.type.title.copy(fontSize = 21.sp), color = Heartpact.colors.ink, modifier = Modifier.padding(top = 2.dp))
    }
}

/** The big number, on your soft colour. */
@Composable
private fun Count(ui: CameraUi, modifier: Modifier) {
    val actor = Heartpact.actor
    Column(
        modifier.heightIn(min = 104.dp).clip(RoundedCornerShape(26.dp)).background(actor.soft).padding(horizontal = 8.dp, vertical = 10.dp).semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("${ui.counter.count}", style = Heartpact.type.display.copy(fontSize = 58.sp, lineHeight = 54.sp), color = actor.onSoft)
        Text(CameraText.of(ui.type, ui.target), style = Heartpact.type.labelSmall, color = actor.onSoft, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
    }
}

/**
 * The live rep count: the front camera, the pose tracker, the counter. When the target is reached
 * (and the server's minimum time has passed, so an honest fast finish is never rejected) it sends
 * the finish with [onReached]. If that doesn't save, the reps as first counted are kept: it tries
 * again by itself with a growing wait when it's worth it (offline, the server busy), and "Try
 * again" sends it at once.
 */
@Composable
fun CameraRepScreen(
    type: ExerciseType,
    target: Int,
    /** When the alarm stops ringing; null when the dare is done early. */
    ringsUntil: Instant?,
    note: String?,
    onReached: suspend (Int, Double, ExerciseChecks) -> FinishOutcome,
    onStop: () -> Unit,
    onProgress: (Int, Double) -> Unit = { _, _ -> },
    mySide: Side = Side.Left,
    setBy: String? = null,
    silenced: Boolean? = null,
    onSilence: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }

    var front by remember { mutableStateOf(true) }
    // Written from the pose thread; each is replaced whole, never read-modify-written across threads.
    var counted by remember { mutableStateOf(CounterState()) }
    var pose by remember { mutableStateOf<Pose?>(null) }
    var sealing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val counter = remember { RepCounter(type) }
    var challenge by remember { mutableStateOf<ChallengeUi?>(null) }
    val motion = remember { MotionLog() }
    val checks = remember { SessionChecks(type, target, motion) }
    DisposableEffect(Unit) {
        val sensors = PhoneMotion(context, motion)
        onDispose { sensors.close() }
    }
    val startedAt = remember { System.currentTimeMillis() }
    var clock by remember { mutableLongStateOf(startedAt) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); clock = System.currentTimeMillis() } }
    val reached by rememberUpdatedState(onReached)
    // Each failed send bumps this, which runs the finish again; "Try again" bumps it too.
    var attempt by remember { mutableIntStateOf(0) }
    var unsaved by remember { mutableStateOf<Unsaved?>(null) }
    // The finish as first counted, kept for every retry: stepping away to fix the Wi-Fi can't lose a rep or lower the confidence.
    var finish by remember { mutableStateOf<Triple<Int, Double, ExerciseChecks>?>(null) }

    // Hold the finish until the minimum time the server checks for has passed.
    LaunchedEffect(counted.count >= target, attempt) {
        if (counted.count < target) return@LaunchedEffect
        sealing = true
        val wait = type.minimumMillis(target) - (System.currentTimeMillis() - startedAt) + 500
        if (wait > 0) delay(wait)
        val sending = finish ?: Triple(counted.count, counted.confidence, checks.report(counted)).also { finish = it }
        when (val outcome = reached(sending.first, sending.second, sending.third)) {
            FinishOutcome.Saved -> unsaved = null
            is FinishOutcome.Retry -> {
                unsaved = Unsaved(outcome.message, retrying = true)
                sealing = false
                delay(finishBackoffMs(attempt))
                attempt++
            }
            is FinishOutcome.Refused -> {
                // Something to fix first (the light, say): the next try counts afresh, when the person taps it.
                unsaved = Unsaved(outcome.message, retrying = false)
                sealing = false
                finish = null
            }
        }
    }
    // Each new rep goes to the partner live (advisory: the server's completion check is what counts).
    val progress by rememberUpdatedState(onProgress)
    LaunchedEffect(counted.count) { if (counted.count > 0) progress(counted.count, counted.confidence) }

    val shown = CameraUi(
        type, target, counted, pose,
        secondsLeft = ringsUntil?.let { ((it.toEpochMilli() - clock) / 1_000).toInt().coerceAtLeast(0) },
        elapsedSeconds = ((clock - startedAt) / 1_000).toInt(),
        note = error ?: note ?: if (!granted) tr("Heartpact needs the camera to count your reps. Nothing is recorded or sent.") else null,
        sealing = sealing,
        mySide = mySide,
        setBy = setBy,
        silenced = silenced,
        challenge = challenge,
        unsaved = unsaved,
    )
    CameraRepContent(shown, onFlip = { front = !front }, onStop = onStop, onSilence = onSilence, onRetry = { attempt++ }) {
        if (granted) {
            val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
            AndroidView({ previewView }, Modifier.fillMaxSize())
            DisposableEffect(front) {
                val executor = Executors.newSingleThreadExecutor()
                val tracker = runCatching {
                    PoseTracker(context, onPose = { detected ->
                        val state = counter.update(detected)
                        challenge = checks.update(detected, state, detected?.timestampMs ?: SystemClock.uptimeMillis(), mirrored = front)
                        counted = state
                        pose = detected
                    }, onError = { error = it })
                }.onFailure { error = tr("The pose model couldn't load on this phone.") }.getOrNull()
                val providerFuture = ProcessCameraProvider.getInstance(context)
                providerFuture.addListener({
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build()
                    if (tracker != null) analysis.setAnalyzer(executor) { frame -> tracker.analyze(frame, mirror = front) }
                    provider.unbindAll()
                    runCatching {
                        provider.bindToLifecycle(lifecycle, if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    }.onFailure { error = tr("This phone's camera couldn't start.") }
                }, ContextCompat.getMainExecutor(context))
                onDispose {
                    runCatching { providerFuture.get().unbindAll() }
                    executor.shutdown()
                    tracker?.close()
                }
            }
        }
    }
}
