package app.heartpact.alarm

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The planned alarms and the ones silenced on this phone, in plain SharedPreferences so the boot
 * and alarm receivers can read them synchronously, without the network.
 */
class AlarmStore(context: Context) {
    private val prefs = context.getSharedPreferences("alarms", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(AlarmSlot.serializer())

    var slots: List<AlarmSlot>
        get() = prefs.getString(SLOTS, null)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: emptyList()
        set(value) = prefs.edit { putString(SLOTS, json.encodeToString(serializer, value)) }

    val silenced: Set<String> get() = prefs.getStringSet(SILENCED, emptySet()) ?: emptySet()

    /** Silencing is local: the dare stays open on the server until its deadline. */
    fun silence(key: String) {
        val keep = (silenced + key).toList().takeLast(50).toSet()
        prefs.edit { putStringSet(SILENCED, keep) }
    }

    fun clear() = prefs.edit { clear() }

    private companion object {
        const val SLOTS = "slots"
        const val SILENCED = "silenced"
    }
}
