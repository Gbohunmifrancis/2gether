package app.heartpact.ui.us

import app.heartpact.data.DailyQuestion
import app.heartpact.data.NotificationPreferences
import app.heartpact.data.utc
import app.heartpact.i18n.Dates
import app.heartpact.i18n.tr
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Pure wording for the Us tab and its screens. */
object UsText {
    fun togetherSince(linkedAtUtc: String?, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): String? {
        val date = linkedAtUtc?.let { runCatching { utc(it).atZone(zone).toLocalDate() }.getOrNull() } ?: return null
        val pattern = if (date.year == today.year) "d MMMM" else "d MMMM yyyy"
        return tr("together since {date}", "date" to date.format(Dates.formatter(pattern)))
    }

    fun questionStatus(q: DailyQuestion?, partner: String): String = when {
        q == null -> tr("One question a day, for both of you")
        q.myAnswer == null && q.partnerAnswered -> tr("{partner} answered · answer to see theirs", "partner" to partner)
        q.myAnswer == null -> tr("Not answered yet")
        q.partnerAnswer != null -> tr("You both answered")
        else -> tr("Waiting for {partner}", "partner" to partner)
    }

    fun notifications(prefs: NotificationPreferences?): String = when {
        prefs == null -> tr("What reaches your phone, and when")
        prefs.quietHoursEnabled -> tr("Quiet {start}–{end} · dares still ring", "start" to prefs.quietStart, "end" to prefs.quietEnd)
        else -> tr("Quiet hours off · dares always ring")
    }

    private val categories get() = mapOf(
        "everyday" to tr("everyday things"), "feelings" to tr("feelings"), "future" to tr("plans and dreams"), "growth" to tr("growing together"),
        "memories" to tr("memories"), "money" to tr("money"), "family" to tr("family"), "fun" to tr("just for fun"), "closeness" to tr("closeness"),
    )

    fun category(key: String) = categories[key] ?: key.replace('-', ' ')

    fun dayLine(q: DailyQuestion): String {
        val day = if (q.streakDays > 1) tr("Day {day} of asking each other", "day" to q.streakDays) else tr("A question for you both")
        return if (q.category.isBlank() || q.category == "retired") day else "$day · ${category(q.category)}"
    }

    fun earlierLine(q: DailyQuestion, today: LocalDate, partner: String): String {
        val date = LocalDate.parse(q.localDate)
        val day = when (date) {
            today.minusDays(1) -> tr("Yesterday")
            else -> date.format(Dates.formatter("EEE d MMM"))
        }
        val who = when {
            q.myAnswer != null && q.partnerAnswered -> tr("you both answered")
            q.myAnswer != null -> tr("only you answered")
            q.partnerAnswered -> tr("only {partner} answered", "partner" to partner)
            else -> tr("neither of you answered")
        }
        return "$day · $who"
    }
}
