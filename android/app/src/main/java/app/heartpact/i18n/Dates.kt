package app.heartpact.i18n

import android.text.format.DateFormat
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Date layouts in the phone's language. Code writes the English layout ("d MMMM" for "27 October"); other languages
 * get their own order and words for the same fields ("27 de outubro", "27 octobre"). Made on each call, so a language
 * switch shows straight away.
 */
object Dates {
    fun formatter(englishPattern: String): DateTimeFormatter {
        val locale = Locale.getDefault()
        if (locale.language == "en") return DateTimeFormatter.ofPattern(englishPattern, locale)
        val skeleton = englishPattern.replace(Regex("'[^']*'"), "").filter { it.isLetter() }
        // Not there in plain unit tests (null), where the English layout is used instead.
        val best = runCatching { DateFormat.getBestDateTimePattern(locale, skeleton) }.getOrNull()
        return runCatching { DateTimeFormatter.ofPattern(best ?: englishPattern, locale) }.getOrElse { DateTimeFormatter.ofPattern(englishPattern, locale) }
    }
}
