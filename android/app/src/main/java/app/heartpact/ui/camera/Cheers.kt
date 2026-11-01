package app.heartpact.ui.camera

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.heartpact.R
import app.heartpact.exercise.Cheer
import app.heartpact.exercise.CheerShown
import app.heartpact.i18n.tr
import app.heartpact.ui.components.rememberStill
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.Shapes
import kotlin.math.sin

/** A heart shape filling [size] around [center]. */
internal fun DrawScope.heart(center: Offset, size: Float, color: Color, alpha: Float = 1f) {
    val w = size; val h = size * 0.92f
    val path = Path().apply {
        moveTo(0f, h * 0.30f)
        cubicTo(0f, -h * 0.05f, w * 0.48f, -h * 0.05f, w * 0.5f, h * 0.22f)
        cubicTo(w * 0.52f, -h * 0.05f, w, -h * 0.05f, w, h * 0.30f)
        cubicTo(w, h * 0.62f, w * 0.6f, h * 0.80f, w * 0.5f, h)
        cubicTo(w * 0.4f, h * 0.80f, 0f, h * 0.62f, 0f, h * 0.30f)
        close()
    }
    translate(center.x - w / 2, center.y - h / 2) { drawPath(path, color, alpha = alpha) }
}

/**
 * A cheer from the partner on the camera screen: a little burst of hearts rising (held still when animations are off)
 * and, for words, a pill with them. Shown while [cheer] is set; the screen clears it after [CHEER_MS].
 */
@Composable
internal fun CheerBurst(cheer: CheerShown?, modifier: Modifier = Modifier, burst: Dp = 150.dp, pill: Boolean = true) {
    val current = cheer ?: return
    val still = rememberStill()
    val rise = remember { Animatable(0f) }
    LaunchedEffect(current.id) { if (!still) { rise.snapTo(0f); rise.animateTo(1f, tween(CHEER_MS.toInt(), easing = LinearEasing)) } }
    // The heart button's red, with white: clear over a camera picture and over the stage alike.
    val accent = CheerRed
    Box(modifier, contentAlignment = Alignment.BottomCenter) {
        Canvas(Modifier.size(burst, burst * 1.4f)) {
            val t = if (still) 0.55f else rise.value
            // Five hearts, each on its own path and size, rising and fading.
            for (i in 0 until 5) {
                val lag = i * 0.09f
                val p = ((t - lag) / (1f - lag)).coerceIn(0f, 1f)
                if (!still && (p <= 0f || p >= 1f)) continue
                val x = size.width * (0.5f + 0.32f * sin(i * 2.1f + p * 3f) * (if (i % 2 == 0) 1 else -1))
                val y = size.height * (1f - 0.85f * p) - 18.dp.toPx()
                val s = (16 + (i * 7) % 15).dp.toPx() * (if (i == 0) 1.6f else 1f)
                heart(Offset(x, y), s, if (i % 2 == 0) accent else Color.White, alpha = if (still) 0.92f else (1f - p * p))
            }
        }
        if (!pill) return@Box
        val words = current.cheer.words
        Row(
            Modifier.padding(bottom = 6.dp).clip(Shapes.Pill).background(Color.White).padding(start = 10.dp, end = 14.dp, top = 7.dp, bottom = 7.dp)
                .semantics(mergeDescendants = true) {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = words?.let { tr("{partner}: {words}", "partner" to current.from, "words" to it) } ?: tr("{partner} sent you a heart", "partner" to current.from)
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Canvas(Modifier.size(18.dp)) { heart(center, size.width, accent) }
            Text(words ?: current.from, style = Heartpact.type.label, color = Color(0xFF1F2557))
        }
    }
}

internal val CheerRed = Color(0xFFE5484D)

/** How long a cheer stays on the camera screen. */
internal const val CHEER_MS = 2_600L

/**
 * The soft chime for a cheer: the practice levels' star sound, a step higher. Only when the ringer is on (not silent
 * or vibrate) and the voice coach isn't switched off.
 */
internal class CheerSound(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var ready = false
    private val pool = SoundPool.Builder().setMaxStreams(2)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .build().apply { setOnLoadCompleteListener { _, _, status -> ready = status == 0 } }
    private val star = pool.load(context, R.raw.level_star, 1)

    fun play() {
        if (!ready || audio?.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        pool.play(star, 0.6f, 0.6f, 1, 0, 1.26f)
    }

    fun release() = pool.release()
}

/** The cheer chime for the camera screen, released with it; null in previews and tests, where there is nothing to hear. */
@Composable
internal fun rememberCheerSound(): CheerSound? {
    val context = LocalContext.current
    val silent = LocalInspectionMode.current || Build.FINGERPRINT == "robolectric"
    val sound = remember { if (silent) null else runCatching { CheerSound(context) }.getOrNull() }
    DisposableEffect(sound) { onDispose { sound?.release() } }
    return sound
}

/** Which cheers the partner can send, in order, with their button words. */
internal val Cheer.button: String get() = when (this) {
    Cheer.Heart -> tr("Send a heart")
    Cheer.GotThis -> tr("You've got this")
    Cheer.AlmostThere -> tr("Almost there")
}
