package app.heartpact.ui.camera

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.util.Log
import java.io.Closeable
import java.util.Locale

/**
 * Says the coach's lines out loud with the phone's own text-to-speech, on the device. It speaks in the app's language and
 * stays quiet when the phone has no voice for it (the lines are on screen too), rather than reading French with an
 * English voice. A new line cuts off the one before: the latest count or instruction is the one that matters.
 */
class VoiceCoach(context: Context, private val locale: Locale) : Closeable {
    @Volatile private var ready = false
    @Volatile var muted = false
        set(value) { field = value; if (value) runCatching { tts.stop() } }

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status != TextToSpeech.SUCCESS) { Log.w(TAG, "Text-to-speech didn't start: $status"); return@TextToSpeech }
        val result = runCatching { tts.setLanguage(locale) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        ready = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        if (!ready) Log.w(TAG, "No voice for ${locale.toLanguageTag()} on this phone; the coach shows its lines instead")
        runCatching {
            tts.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            tts.setSpeechRate(1.05f)
        }
    }

    fun say(text: String) {
        if (!ready || muted || text.isBlank()) return
        runCatching { tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "coach") }
    }

    override fun close() {
        runCatching { tts.stop(); tts.shutdown() }
    }

    private companion object { const val TAG = "VoiceCoach" }
}
