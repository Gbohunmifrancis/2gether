package app.heartpact.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Follow the phone, or stay in day or night whatever the phone does. */
enum class ThemeMode { System, Light, Dark }

/** The chosen [ThemeMode], kept apart from the session so signing out doesn't reset it. */
class ThemeSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("theme", Context.MODE_PRIVATE)

    var mode by mutableStateOf(ThemeMode.entries.firstOrNull { it.name == prefs.getString(KEY, null) } ?: ThemeMode.System)
        private set

    init { Season.christmas = prefs.getBoolean(CHRISTMAS, false) }

    /** Called with what the server says; remembered so the next start begins festive (or not) straight away. */
    fun setChristmas(on: Boolean) {
        Season.christmas = on
        prefs.edit().putBoolean(CHRISTMAS, on).apply()
    }

    fun set(next: ThemeMode) {
        mode = next
        prefs.edit().putString(KEY, next.name).apply()
    }

    /** [systemNight] is the phone's own setting, used only for [ThemeMode.System]. */
    fun night(systemNight: Boolean) = when (mode) {
        ThemeMode.System -> systemNight
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }

    private companion object { const val KEY = "mode"; const val CHRISTMAS = "christmas" }
}
