package app.heartpact.ui.games

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
import app.heartpact.ui.play.skills.ClearedSound

/**
 * Snakes and ladders' sounds (res/raw/snakes_*.ogg, made by android/tools/snakes_sounds.py): the die rattling, a tick
 * per hop (a step higher each square of a move), a ladder's arpeggio up, a snake's slide down, a whoosh for a boost or
 * a swap, and a chime for 100. Soft, short, on the system sounds volume, and only when the ringer is on, like the
 * level sounds (ui/play/skills/LevelSounds.kt).
 */
internal class SnakesSounds(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val pool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private val dice = pool.load(context, R.raw.snakes_dice, 1)
    private val step = pool.load(context, R.raw.snakes_step, 1)
    private val ladder = pool.load(context, R.raw.snakes_ladder, 1)
    private val slide = pool.load(context, R.raw.snakes_slide, 1)
    private val boost = pool.load(context, R.raw.snakes_boost, 1)
    private val win = pool.load(context, R.raw.snakes_win, 1)

    private fun play(id: Int, rate: Float = 1f, volume: Float = 1f) {
        if (audio?.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        pool.play(id, volume, volume, 1, 0, rate.coerceIn(0.5f, 2f))
    }

    fun dice() = play(dice)

    /** The [n]th hop of a move, each a little higher. */
    fun step(n: Int) = play(step, 1f + 0.045f * n.coerceIn(0, 10), 0.8f)

    fun ladder() = play(ladder)

    /** A snake; a heartbreak plays it lower and shorter-sounding. */
    fun slide(low: Boolean = false) = play(slide, if (low) 0.8f else 1f)

    fun boost() = play(boost)

    /** 100: the game's won. As soft as a level cleared, and once, through the same gate (ClearedSound). */
    fun win() {
        if (ClearedSound.gate.allow(SystemClock.elapsedRealtime())) play(win, volume = ClearedSound.VOLUME)
    }

    fun release() = pool.release()
}

/** The sounds for a screen, released with it; null in previews and on the JVM, where there is nothing to hear. */
@Composable
internal fun rememberSnakesSounds(): SnakesSounds? {
    val context = LocalContext.current
    val silent = LocalInspectionMode.current || Build.FINGERPRINT == "robolectric"
    val sounds = remember { if (silent) null else runCatching { SnakesSounds(context) }.getOrNull() }
    DisposableEffect(sounds) { onDispose { sounds?.release() } }
    return sounds
}
