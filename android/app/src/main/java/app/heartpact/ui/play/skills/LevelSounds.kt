package app.heartpact.ui.play.skills

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import app.heartpact.R

/**
 * The practice levels' sounds (res/raw/level_*.ogg, made by android/tools/game_sounds.py): a level cleared, a star
 * landing (played a step higher for each star), a level not cleared, and the heart closing and opening between levels.
 * Soft, short, on the system sounds volume, and only when the ringer is on (not silent or vibrate).
 */
internal class LevelSounds(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    /** Sounds decode in the background: one asked for before it's ready plays as soon as it is. */
    private val ready = mutableSetOf<Int>()
    private var waiting: Triple<Int, Float, Float>? = null
    private val pool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
        .apply {
            setOnLoadCompleteListener { _, id, status ->
                if (status != 0) return@setOnLoadCompleteListener
                ready += id
                waiting?.takeIf { it.first == id }?.let { (sound, rate, volume) -> waiting = null; play(sound, rate, volume) }
            }
        }
    private val up = pool.load(context, R.raw.level_up, 1)
    private val star = pool.load(context, R.raw.level_star, 1)
    private val tryAgain = pool.load(context, R.raw.level_try, 1)
    private val whoosh = pool.load(context, R.raw.level_whoosh, 1)

    private fun play(id: Int, rate: Float = 1f, volume: Float = 1f) {
        if (audio?.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        if (id !in ready) { waiting = Triple(id, rate, volume); return }
        pool.play(id, volume, volume, 1, 0, rate)
    }

    /** A level cleared (or a game won): softer than the rest, and once, however many screens ask at the same moment. */
    fun cleared() {
        if (ClearedSound.gate.allow(SystemClock.elapsedRealtime())) play(up, volume = ClearedSound.VOLUME)
    }

    /** The [n]th star (0, 1, 2), each a whole tone higher. */
    fun star(n: Int) = play(star, floatArrayOf(1f, 1.122f, 1.26f)[n.coerceIn(0, 2)])

    fun notYet() = play(tryAgain)

    fun whoosh() = play(whoosh)

    fun release() = pool.release()
}

/** The level sounds for a screen, released with it; null in previews and on the JVM, where there is nothing to hear. */
@Composable
internal fun rememberLevelSounds(): LevelSounds? {
    val context = LocalContext.current
    val silent = LocalInspectionMode.current || Build.FINGERPRINT == "robolectric"
    val sounds = remember { if (silent) null else runCatching { LevelSounds(context) }.getOrNull() }
    DisposableEffect(sounds) { onDispose { sounds?.release() } }
    return sounds
}

/**
 * The sound of a level cleared or a game won (level_up, and Snakes and ladders' chime at 100). It plays at 40% of the
 * others, which was too loud next to the rest, and through one gate for the whole app, so two callers (a card and a
 * celebration, a replayed move) never stack it.
 */
object ClearedSound {
    const val VOLUME = 0.4f
    /** Longer than the sound itself (about 1.3 s), so a second call while it rings is dropped. */
    const val GAP_MS = 2_000L
    val gate = SoundGate(GAP_MS)
}

/** Lets a sound through once per [gapMs]: a call while the last one is still ringing is dropped. Pure, so it is tested. */
class SoundGate(private val gapMs: Long) {
    private var last: Long? = null

    @Synchronized
    fun allow(nowMs: Long): Boolean {
        val before = last
        if (before != null && nowMs - before in 0 until gapMs) return false
        last = nowMs
        return true
    }
}
