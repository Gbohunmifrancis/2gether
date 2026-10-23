package app.heartpact.ui.pregnancy

import app.heartpact.data.KickSession
import app.heartpact.data.Pregnancy
import app.heartpact.data.utc
import app.heartpact.i18n.tr
import app.heartpact.i18n.trn
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object PregnancyText {
    private val dayMonth = DateTimeFormatter.ofPattern("d MMMM")

    fun due(p: Pregnancy): String = tr("Due around {date}", "date" to LocalDate.parse(p.dueDate).format(dayMonth))

    fun usRow(p: Pregnancy?, partner: String): String = when {
        p == null -> tr("Pauses cycle predictions")
        p.isMine -> tr("Week {week} · due {date}", "week" to p.week, "date" to LocalDate.parse(p.dueDate).format(dayMonth))
        else -> tr("{partner}, week {week}", "partner" to p.userName.ifBlank { partner }, "week" to p.week)
    }

    fun lastKicks(sessions: List<KickSession>, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): String {
        val last = sessions.filter { it.endedAtUtc != null }.maxByOrNull { it.startedAtUtc } ?: return tr("Count kicks when you feel the baby move")
        val at = utc(last.startedAtUtc).atZone(zone)
        val day = when (at.toLocalDate()) {
            today -> tr("today")
            today.minusDays(1) -> tr("yesterday")
            else -> at.format(DateTimeFormatter.ofPattern("EEE d MMM"))
        }
        val minutes = ((last.durationSeconds ?: 0) + 59) / 60
        return trn(last.count, "{count} kick in {minutes} min · {day} {time}", "{count} kicks in {minutes} min · {day} {time}",
            "minutes" to minutes, "day" to day, "time" to at.format(DateTimeFormatter.ofPattern("HH:mm")))
    }
}
