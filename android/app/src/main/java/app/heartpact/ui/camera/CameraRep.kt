package app.heartpact.ui.camera

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import app.heartpact.exercise.CounterState
import app.heartpact.exercise.ExerciseType
import app.heartpact.exercise.Measure
import app.heartpact.exercise.Pose
import app.heartpact.exercise.RepCounter
import app.heartpact.ui.components.ButtonKind
import app.heartpact.ui.components.HeartButton
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.Shapes
import kotlinx.coroutines.delay
import java.util.concurrent.Executors

private val Rail = Color(0xFF161A3D)
private val Bone = Color(0xFFB9C0FF)

/** Lines drawn between landmarks for the overlay: shoulders, arms, torso, legs. */
private val BONES = listOf(11 to 12, 11 to 13, 13 to 15, 12 to 14, 14 to 16, 11 to 23, 12 to 24, 23 to 24, 23 to 25, 25 to 27, 24 to 26, 26 to 28)

/** What the camera screen shows, apart from the live picture. Pure, so it can be rendered in tests. */
data class CameraUi(
    val type: ExerciseType,
    val target: Int,
    val counter: CounterState = CounterState(),
    val pose: Pose? = null,
    val minutesLeft: Int? = null,
    val note: String? = null,
    val sealing: Boolean = false,
)

@Composable
fun CameraRepContent(ui: CameraUi, onFlip: () -> Unit, onStop: () -> Unit, preview: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Rail)) {
        val wide = maxWidth > maxHeight
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight()) { Stage(ui, onFlip, preview) }
                RailPanel(ui, onStop, Modifier.width(200.dp).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) { Stage(ui, onFlip, preview) }
                RailPanel(ui, onStop, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun Stage(ui: CameraUi, onFlip: () -> Unit, preview: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        preview()
        ui.pose?.let { pose ->
            Canvas(Modifier.fillMaxSize()) {
                val points = pose.landmarks.map { Offset(it.x * size.width, it.y * size.height) }
                BONES.forEach { (a, b) ->
                    if (pose[a].visibility > 0.5f && pose[b].visibility > 0.5f) drawLine(Bone, points[a], points[b], 3.5.dp.toPx(), StrokeCap.Round)
                }
                listOf(11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28).filter { pose[it].visibility > 0.5f }.forEach {
                    drawCircle(Color.White, 6.dp.toPx(), points[it]); drawCircle(Color(0xFF1F2557), 6.dp.toPx(), points[it], style = androidx.compose.ui.graphics.drawscope.Stroke(2.5.dp.toPx()))
                }
            }
        }
        Row(Modifier.systemBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ui.minutesLeft?.let { Chip("Ringing · $it min left", Icons.Rounded.Alarm) }
            Chip(if (ui.counter.inFrame) "Whole body in frame" else "Step back so all of you is in frame", dot = if (ui.counter.inFrame) Bone else Color(0xFFFFB4AB))
        }
        IconButton(onClick = onFlip, modifier = Modifier.align(Alignment.BottomStart).systemBarsPadding().padding(12.dp).clip(Shapes.Pill).background(Rail.copy(alpha = 0.75f))) {
            Icon(Icons.Rounded.Cameraswitch, "Switch camera", tint = Color.White)
        }
    }
}

@Composable
private fun Chip(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, dot: Color? = null) {
    Row(Modifier.clip(Shapes.Pill).background(Rail.copy(alpha = 0.75f)).padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        icon?.let { Icon(it, null, Modifier.size(16.dp), tint = Color.White) }
        dot?.let { Box(Modifier.size(8.dp).clip(Shapes.Pill).background(it)) }
        Text(text, style = Heartpact.type.labelSmall, color = Color.White)
    }
}

@Composable
private fun RailPanel(ui: CameraUi, onStop: () -> Unit, modifier: Modifier) {
    Column(modifier.background(Rail).systemBarsPadding().padding(18.dp), horizontalAlignment = Alignment.End) {
        Text("${ui.counter.count}", style = Heartpact.type.display.copy(fontSize = 96.sp, lineHeight = 88.sp), color = Color.White)
        Text(if (ui.type.measure == Measure.Seconds) "of ${ui.target} seconds" else "of ${ui.target} ${ui.type.displayName.lowercase()}", style = Heartpact.type.titleSmall, color = Color.White)
        Text(ui.note ?: if (ui.sealing) "Sticking your half…" else ui.type.cue, style = Heartpact.type.small, color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.End, modifier = Modifier.padding(top = 10.dp))
        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f, fill = false).size(12.dp))
        Box(Modifier.fillMaxWidth().clip(Shapes.Pill).background(Color.White)) {
            HeartButton("Stop counting", onStop, kind = ButtonKind.Text, icon = Icons.Rounded.Close, height = 46.dp)
        }
    }
}

/**
 * The live rep count: the front camera, the pose tracker, the counter. When the target is reached
 * (and the server's minimum time has passed, so an honest fast finish is never rejected) it calls
 * [onReached] once with the count and confidence.
 */
@Composable
fun CameraRepScreen(type: ExerciseType, target: Int, minutesLeft: Int?, note: String?, onReached: (Int, Double) -> Unit, onStop: () -> Unit, onProgress: (Int, Double) -> Unit = { _, _ -> }) {
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
    val startedAt = remember { System.currentTimeMillis() }
    val reached by rememberUpdatedState(onReached)
    var sent by remember { mutableStateOf(false) }

    // Hold the finish until the minimum time the server checks for has passed.
    LaunchedEffect(counted.count >= target) {
        if (counted.count < target || sent) return@LaunchedEffect
        sealing = true
        val wait = type.minimumMillis(target) - (System.currentTimeMillis() - startedAt) + 500
        if (wait > 0) delay(wait)
        sent = true
        reached(counted.count, counted.confidence)
    }
    // Each new rep goes to the partner live (advisory: the server's completion check is what counts).
    val progress by rememberUpdatedState(onProgress)
    LaunchedEffect(counted.count) { if (counted.count > 0) progress(counted.count, counted.confidence) }
    // A rejected completion comes back as a note: let the person keep going and try again.
    LaunchedEffect(note) { if (note != null) { sent = false; sealing = false } }

    val shown = CameraUi(type, target, counted, pose, minutesLeft, error ?: note ?: if (!granted) "Heartpact needs the camera to count your reps. Nothing is recorded or sent." else null, sealing)
    CameraRepContent(shown, onFlip = { front = !front }, onStop = onStop) {
        if (granted) {
            val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
            AndroidView({ previewView }, Modifier.fillMaxSize())
            DisposableEffect(front) {
                val executor = Executors.newSingleThreadExecutor()
                val tracker = runCatching {
                    PoseTracker(context, onPose = { detected ->
                        counted = counter.update(detected)
                        pose = detected
                    }, onError = { error = it })
                }.onFailure { error = "The pose model couldn't load on this phone." }.getOrNull()
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
                    }.onFailure { error = "This phone's camera couldn't start." }
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
