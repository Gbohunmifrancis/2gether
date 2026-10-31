package app.heartpact.ui.play.skills

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
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
    private val pool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private val up = pool.load(context, R.raw.level_up, 1)
    private val star = pool.load(context, R.raw.level_star, 1)
    private val tryAgain = pool.load(context, R.raw.level_try, 1)
    private val whoosh = pool.load(context, R.raw.level_whoosh, 1)

    private fun play(id: Int, rate: Float = 1f) {
        if (audio?.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        pool.play(id, 1f, 1f, 1, 0, rate)
    }

    fun cleared() = play(up)

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
