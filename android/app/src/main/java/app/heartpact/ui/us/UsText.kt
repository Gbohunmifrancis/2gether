package app.heartpact.ui.us

import app.heartpact.data.DailyQuestion
import app.heartpact.data.NotificationPreferences
import app.heartpact.data.utc
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Pure wording for the Us tab and its screens. */
object UsText {
    fun togetherSince(linkedAtUtc: String?, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): String? {
        val date = linkedAtUtc?.let { runCatching { utc(it).atZone(zone).toLocalDate() }.getOrNull() } ?: return null
        val pattern = if (date.year == today.year) "d MMMM" else "d MMMM yyyy"
        return "together since " + date.format(DateTimeFormatter.ofPattern(pattern))
    }

    fun questionStatus(q: DailyQuestion?, partner: String): String = when {
        q == null -> "One question a day, for both of you"
        q.myAnswer == null && q.partnerAnswered -> "$partner answered · answer to see theirs"
        q.myAnswer == null -> "Not answered yet"
        q.partnerAnswer != null -> "You both answered"
        else -> "Waiting for $partner"
    }

    fun notifications(prefs: NotificationPreferences?): String = when {
        prefs == null -> "What reaches your phone, and when"
        prefs.quietHoursEnabled -> "Quiet ${prefs.quietStart}–${prefs.quietEnd} · dares still ring"
        else -> "Quiet hours off · dares always ring"
    }

    private val categories = mapOf(
        "everyday" to "everyday things", "feelings" to "feelings", "future" to "plans and dreams", "growth" to "growing together",
        "memories" to "memories", "money" to "money", "family" to "family", "fun" to "just for fun", "closeness" to "closeness",
    )

    fun category(key: String) = categories[key] ?: key.replace('-', ' ')

    fun dayLine(q: DailyQuestion): String {
        val day = if (q.streakDays > 1) "Day ${q.streakDays} of asking each other" else "A question for you both"
        return if (q.category.isBlank() || q.category == "retired") day else "$day · ${category(q.category)}"
    }

    fun earlierLine(q: DailyQuestion, today: LocalDate, partner: String): String {
        val date = LocalDate.parse(q.localDate)
        val day = when (date) {
            today.minusDays(1) -> "Yesterday"
            else -> date.format(DateTimeFormatter.ofPattern("EEE d MMM"))
        }
        val who = when {
            q.myAnswer != null && q.partnerAnswered -> "you both answered"
            q.myAnswer != null -> "only you answered"
            q.partnerAnswered -> "only $partner answered"
            else -> "neither of you answered"
        }
        return "$day · $who"
    }
}
