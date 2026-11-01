package app.heartpact.ui.splash

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.core.content.edit
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.snapshotFlow
import app.heartpact.ui.graphics.LocalRiveFile
import app.rive.Fit
import app.rive.Rive
import app.rive.RiveFile
import app.rive.RivePointerInputMode
import app.rive.rememberArtboardResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import app.rive.Result as RiveResult
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.heartpact.R
import app.heartpact.ui.graphics.LogoMood
import app.heartpact.ui.graphics.Mood
import app.heartpact.ui.graphics.Pose
import app.heartpact.ui.graphics.Stride
import app.heartpact.ui.graphics.Who
import app.heartpact.ui.graphics.drawCharacters
import app.heartpact.ui.graphics.drawLogoFace
import app.heartpact.ui.graphics.drawMark
import app.heartpact.ui.theme.Art
import app.heartpact.ui.theme.Heartpact
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * The launch moment: each half walks in from its side of the screen, they meet in the middle,
 * and together they become the mark; then the tick cuts itself in and the wordmark rises. The mark opens its eyes,
 * its heart beats twice, and it blinks. [SplashScene] is a pure function of time so every frame can be checked;
 * [AnimatedSplash] plays it, with its sound (`res/raw/splash_love.ogg`, made by `tools/splash_sound.py` on this timeline).
 */
object SplashTimeline {
    const val WALK_START = 150f
    const val WALK_END = 1450f
    const val MEET_END = 1750f
    /** Arms, legs, faces and the white die-cut edge tuck away; the bare halves stay. */
    const val TUCK_END = 1950f
    /** The bare halves become the mark. */
    const val MORPH_END = 2200f
    const val TICK_END = 2450f
    const val WORD_START = 2300f
    const val WORD_END = 2650f
    /** The mark's eyes open. */
    const val FACE_END = 2650f
    /** Two heartbeats, each a "lub" then a softer "dub". */
    val BEATS = floatArrayOf(2650f, 2950f)
    const val DUB = 190f
    const val BLINK = 3120f
    const val END = 3300f
    /** Strides each half takes on the way in. */
    const val STRIDES = 3.2f
}

/**
 * Plays the splash, then calls [onFinished]. A tap skips it (and stops the sound); with animations off it shows the
 * last frame briefly, silently.
 *
 * It plays in 3D from the app's Rive file (artboard `splash`, built in design/rive-icons on this same timeline) when
 * the file is ready within a moment and its first frame has drawn; the sound and the clock start with that frame so
 * the heartbeats land together. Otherwise, and in tests, it draws [SplashScene] itself.
 */
@Composable
fun AnimatedSplash(onFinished: () -> Unit) {
    val context = LocalContext.current
    val finish by rememberUpdatedState(onFinished)
    var time by remember { mutableFloatStateOf(0f) }
    val animated = remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f }
    val sound = remember { if (animated) SplashSound.load(context) else null }
    var skipped by remember { mutableStateOf(false) }
    val riveFile by rememberUpdatedState(LocalRiveFile.current)
    // null while deciding, then whether the 3D splash is playing.
    var inRive by remember { mutableStateOf<Boolean?>(null) }
    var riveDrawn by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    // Played through, the last shimmer rings on into the next screen; skipped, it stops with the tap.
    DisposableEffect(sound) {
        onDispose {
            sound?.let { player ->
                val playing = runCatching { player.isPlaying }.getOrDefault(false)
                if (skipped || !playing) player.release() else player.setOnCompletionListener(MediaPlayer::release)
            }
        }
    }
    LaunchedEffect(Unit) {
        if (!animated) {
            inRive = false
            time = SplashTimeline.END
            kotlinx.coroutines.delay(500)
        } else {
            inRive = withTimeoutOrNull(RIVE_WAIT_MS) { snapshotFlow { riveFile }.first { it != null } } != null
            if (inRive == true && withTimeoutOrNull(RIVE_WAIT_MS) { snapshotFlow { riveDrawn }.first { it } } == null) inRive = false
            started = true
            sound?.let { SplashSound.play(context, it) }
            val start = withFrameMillis { it }
            while (time < SplashTimeline.END) {
                time = withFrameMillis { (it - start).toFloat() }.coerceAtMost(SplashTimeline.END)
            }
        }
        finish()
    }
    val skip = Modifier.clickable(remember { MutableInteractionSource() }, indication = null) { skipped = true; finish() }
    val file = riveFile
    if (inRive != false && file != null) {
        SplashScene(timeMs = time, modifier = skip, art = { RiveSplashArt(file, playing = started, onDrawn = { riveDrawn = true }) })
    } else {
        // Until it's decided, the scene at 0 ms: the background alone, the same in both.
        SplashScene(timeMs = if (inRive == null) 0f else time, modifier = skip)
    }
}

/** How long the splash waits for the Rive file to load, and then for its first frame, before drawing itself. */
private const val RIVE_WAIT_MS = 450L

/**
 * The Rive splash, placed where [drawScene] draws its heart: the artboard's heart is 182 of its 400 units wide, and
 * the drawn one 100 units of 1.27 dp, centred at 47% of the height.
 */
@Composable
private fun BoxScope.RiveSplashArt(file: RiveFile, playing: Boolean, onDrawn: () -> Unit) {
    val artboard = rememberArtboardResult(file, "splash")
    if (artboard !is RiveResult.Success) return
    BoxWithConstraints(Modifier.matchParentSize()) {
        val side = 127.dp * 400f / 182f
        Rive(
            file,
            Modifier.size(side).align(Alignment.Center).offset(y = maxHeight * -0.03f),
            playing = playing,
            artboard = artboard.value,
            fit = Fit.Contain(),
            pointerInputMode = RivePointerInputMode.PassThrough,
            onBitmapAvailable = { onDrawn() },
        )
    }
}

/** The splash at [timeMs]: the background, the scene ([art], or [drawScene] by default) and the wordmark. */
@Composable
fun SplashScene(timeMs: Float, modifier: Modifier = Modifier, art: (@Composable BoxScope.() -> Unit)? = null) {
    val density = LocalDensity.current
    val wordProgress = ease(progress(timeMs, SplashTimeline.WORD_START, SplashTimeline.WORD_END))
    Box(
        modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(Color(0xFF2C3474), Art.SplashIndigo, Color(0xFF1A1F4D)), radius = with(density) { 520.dp.toPx() }))
            .semantics { contentDescription = "Heartpact" },
    ) {
        if (art != null) art() else Canvas(Modifier.fillMaxSize().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
            drawScene(timeMs, unit = 1.27.dp.toPx())
        }
        Text(
            "heartpact",
            style = Heartpact.type.wordmark.copy(fontSize = 24.sp),
            color = Color.White.copy(alpha = 0.92f * wordProgress),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 48.dp)
                .graphicsLayer { translationY = (1f - wordProgress) * 10.dp.toPx() },
        )
    }
}

/**
 * How loud the splash plays: in full the very first time it's heard on this phone, then at a quarter of that, so a
 * daily open is a soft hello rather than a fanfare.
 */
fun splashVolume(heardBefore: Boolean): Float = if (heardBefore) 0.25f else 1f

/**
 * The splash's sound: only when the phone's ringer is on (not silent or vibrate; that is its "sound off"), on the
 * system sounds volume, at [splashVolume].
 */
private object SplashSound {
    private const val PREFS = "splash"
    private const val HEARD = "heard"

    fun play(context: Context, player: MediaPlayer) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val volume = splashVolume(prefs.getBoolean(HEARD, false))
        runCatching {
            player.setVolume(volume, volume)
            player.start()
        }.onSuccess { prefs.edit { putBoolean(HEARD, true) } }
    }

    fun load(context: Context): MediaPlayer? {
        val audio = context.getSystemService(AudioManager::class.java) ?: return null
        if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return null
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        // A missing or broken player only costs the sound.
        return runCatching { MediaPlayer.create(context, R.raw.splash_love, attributes, audio.generateAudioSessionId()) }.getOrNull()
    }
}

private fun DrawScope.drawScene(t: Float, unit: Float) {
    // Heart units: the body spans x 0..100, y about 6..100; centre it a little above the middle.
    val origin = Offset(size.width / 2 - 50f * unit, size.height * 0.47f - 53f * unit)
    val walk = progress(t, SplashTimeline.WALK_START, SplashTimeline.WALK_END)
    val arrive = 1f - (1f - walk).pow(2.2f)
    // Start just past the screen edge on each side.
    val startGap = (size.width / 2 + 60f * unit) / unit
    val gap = startGap * (1f - arrive)
    val meet = progress(t, SplashTimeline.WALK_END, SplashTimeline.MEET_END)
    val tuck = progress(t, SplashTimeline.MEET_END, SplashTimeline.TUCK_END)
    val morph = progress(t, SplashTimeline.TUCK_END, SplashTimeline.MORPH_END)
    val hop = if (meet in 0f..1f && t >= SplashTimeline.WALK_END) -6f * sin(PI.toFloat() * meet) else 0f

    val walking = t < SplashTimeline.WALK_END
    val stride = if (walking) Stride(phase = walk * 2f * PI.toFloat() * SplashTimeline.STRIDES, amplitude = 1f - smoothstep(0.8f, 1f, walk)) else null
    val pose = if (walking) Pose.Walk else Pose.Hug
    val mood = if (walking) Mood.Smile else Mood.Cheer
    val bodyAlpha = 1f - morph
    val featureAlpha = 1f - ease(tuck)

    withTransform({ translate(origin.x, origin.y); scale(unit, unit, pivot = Offset.Zero) }) {
        if (bodyAlpha > 0f) {
            withTransform({ translate(-gap, hop) }) { drawCharacters(Who.Left, pose, mood, sticker = true, stride = stride, alpha = bodyAlpha, featureAlpha = featureAlpha, keylineAlpha = featureAlpha) }
            withTransform({ translate(gap, hop) }) { drawCharacters(Who.Right, pose, mood, sticker = true, stride = stride, alpha = bodyAlpha, featureAlpha = featureAlpha, keylineAlpha = featureAlpha) }
        }
        if (morph > 0f) {
            val beat = 1f + heartbeat(t)
            withTransform({ scale(beat, beat, pivot = Offset(50f, 64f)) }) {
                drawMark(Art.MarkNightLeft, Art.MarkNightRight, tickProgress = ease(progress(t, SplashTimeline.MORPH_END, SplashTimeline.TICK_END)), alpha = ease(morph))
                val face = ease(progress(t, SplashTimeline.TICK_END, SplashTimeline.FACE_END))
                if (face > 0f) drawLogoFace(t, LogoMood.Happy, night = true, alpha = face, open = face * blink(t))
            }
        }
    }
}

/** How much bigger the mark is at [t]: a quick swell for each "lub", a smaller one for each "dub". */
private fun heartbeat(t: Float) = SplashTimeline.BEATS.sumOf { at ->
    (0.07f * pulse(t, at, 160f) + 0.045f * pulse(t, at + SplashTimeline.DUB, 150f)).toDouble()
}.toFloat()

private fun pulse(t: Float, start: Float, length: Float) = if (t in start..start + length) sin(PI.toFloat() * (t - start) / length) else 0f

/** 1 open, 0 shut: one blink near the end. */
private fun blink(t: Float) = 1f - pulse(t, SplashTimeline.BLINK, 150f)

private fun progress(t: Float, start: Float, end: Float) = ((t - start) / (end - start)).coerceIn(0f, 1f)
private fun ease(p: Float) = 1f - (1f - p).pow(3)
private fun smoothstep(a: Float, b: Float, x: Float): Float {
    val p = ((x - a) / (b - a)).coerceIn(0f, 1f)
    return p * p * (3 - 2 * p)
}
