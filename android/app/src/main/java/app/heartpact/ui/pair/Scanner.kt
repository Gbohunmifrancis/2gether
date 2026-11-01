package app.heartpact.ui.pair

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import app.heartpact.i18n.tr
import app.heartpact.ui.components.ButtonKind
import app.heartpact.ui.components.HeartButton
import app.heartpact.ui.theme.Heartpact
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.Executors

/** Reads a QR code from a camera frame's brightness plane. Pure (ZXing on plain bytes), so it is tested. */
object QrReading {
    private val hints = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.CHARACTER_SET to "UTF-8")

    fun decode(luma: ByteArray, rowStride: Int, width: Int, height: Int): String? {
        val source = PlanarYUVLuminanceSource(luma, rowStride, height, 0, 0, width, height, false)
        return try {
            QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        } catch (_: ReaderException) {
            // A dark-on-light code is the usual case; a phone in dark mode may show it inverted.
            try { QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source.invert())), hints).text } catch (_: ReaderException) { null }
        }
    }
}

/**
 * "Scan their code": the back camera reads the QR code on the partner's pair screen. A Heartpact invite joins like an
 * opened link; anything else says so and keeps looking. Frames are read on the phone and let go.
 */
@Composable
fun ScanScreen(onFound: (InviteInput) -> Unit, onBack: () -> Unit, onType: () -> Unit = onBack) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var asked by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; asked = true }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }
    var hint by remember { mutableStateOf<String?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }
    val found by rememberUpdatedState(onFound)

    val message = when {
        !granted && asked -> tr("Heartpact needs the camera to scan your partner's code. You can also type their code instead.")
        cameraError != null -> cameraError
        else -> hint
    }
    ScannerFrame(message, onBack, onType, preview = {
        if (granted) {
            val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
            AndroidView({ previewView }, Modifier.fillMaxSize())
            DisposableEffect(Unit) {
                val executor = Executors.newSingleThreadExecutor()
                var lastWrongMs = 0L
                val providerFuture = ProcessCameraProvider.getInstance(context)
                providerFuture.addListener({
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                    val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                    analysis.setAnalyzer(executor) { frame ->
                        val text = frame.use { read(it) }
                        if (text == null || done) return@setAnalyzer
                        val input = InviteParsing.parse(text)
                        ContextCompat.getMainExecutor(context).execute {
                            if (done) return@execute
                            if (input != null) {
                                done = true
                                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                found(input)
                            } else if (SystemClock.elapsedRealtime() - lastWrongMs > 2_000) {
                                lastWrongMs = SystemClock.elapsedRealtime()
                                hint = tr("That's not a Heartpact code. Scan the one on your partner's \"Find your other half\" screen.")
                            }
                        }
                    }
                    provider.unbindAll()
                    runCatching { provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis) }
                        .onFailure { cameraError = tr("This phone's camera couldn't start.") }
                }, ContextCompat.getMainExecutor(context))
                onDispose {
                    runCatching { providerFuture.get().unbindAll() }
                    executor.shutdown()
                }
            }
        }
    })
}

private fun read(frame: ImageProxy): String? {
    val plane = frame.planes.firstOrNull() ?: return null
    val buffer = plane.buffer.apply { rewind() }
    val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
    return QrReading.decode(bytes, plane.rowStride, frame.width, frame.height)
}

/** The scanner's look: the camera under a dimmed veil with a clear rounded window, corner marks in pen pink, and a line of help. */
@Composable
fun ScannerFrame(message: String?, onBack: () -> Unit, onType: () -> Unit = onBack, preview: @Composable () -> Unit) {
    val accent = Color(0xFFF2A3BA)
    Box(Modifier.fillMaxSize().background(Color(0xFF0E1130))) {
        preview()
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val window = minOf(maxWidth, maxHeight) * 0.68f
            Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
                val side = window.toPx()
                val topLeft = Offset((size.width - side) / 2, (size.height - side) / 2 - size.height * 0.04f)
                val radius = CornerRadius(28.dp.toPx())
                drawRect(Color(0xCC0E1130))
                drawRoundRect(Color.Transparent, topLeft, Size(side, side), radius, blendMode = BlendMode.Clear)
                // Corner marks, like a viewfinder.
                val arm = side * 0.16f
                val r = 28.dp.toPx()
                val stroke = Stroke(5.dp.toPx(), cap = StrokeCap.Round)
                for ((cx, cy) in listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f)) {
                    val x = topLeft.x + cx * side
                    val y = topLeft.y + cy * side
                    val sx = if (cx == 0f) 1f else -1f
                    val sy = if (cy == 0f) 1f else -1f
                    val path = Path().apply {
                        moveTo(x, y + sy * (r + arm))
                        lineTo(x, y + sy * r)
                        quadraticTo(x, y, x + sx * r, y)
                        lineTo(x + sx * (r + arm), y)
                    }
                    drawPath(path, accent, style = stroke)
                }
                drawRoundRect(Color.White.copy(alpha = 0.35f), topLeft, Size(side, side), radius, style = Stroke(1.5.dp.toPx()))
            }
        }
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            IconButton(onClick = onBack, modifier = Modifier.padding(4.dp)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, tr("Back"), tint = Color.White) }
            Text(tr("Scan their code"), style = Heartpact.type.headline, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp, start = 24.dp, end = 24.dp))
            Text(tr("Point the camera at the QR code on your partner's phone."), style = Heartpact.type.bodyLarge, color = Color.White.copy(alpha = 0.85f),
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp, start = 32.dp, end = 32.dp))
            Box(Modifier.weight(1f))
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                message?.let {
                    Text(it, style = Heartpact.type.body, color = Color.White, textAlign = TextAlign.Center,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                HeartButton(tr("Type their code instead"), onType, kind = ButtonKind.Text, contentColor = Color.White)
            }
        }
    }
}
