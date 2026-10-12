package app.heartpact.ui.pregnancy

import app.heartpact.data.KickSession
import app.heartpact.data.Pregnancy
import app.heartpact.data.utc
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object PregnancyText {
    private val dayMonth = DateTimeFormatter.ofPattern("d MMMM")

    fun due(p: Pregnancy): String = "Due around " + LocalDate.parse(p.dueDate).format(dayMonth)

    fun usRow(p: Pregnancy?, partner: String): String = when {
        p == null -> "Pauses cycle predictions"
        p.isMine -> "Week ${p.week} · due ${LocalDate.parse(p.dueDate).format(dayMonth)}"
        else -> "${p.userName.ifBlank { partner }}, week ${p.week}"
    }

    fun lastKicks(sessions: List<KickSession>, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): String {
        val last = sessions.filter { it.endedAtUtc != null }.maxByOrNull { it.startedAtUtc } ?: return "Count kicks when you feel the baby move"
        val at = utc(last.startedAtUtc).atZone(zone)
        val day = when (at.toLocalDate()) {
            today -> "today"
            today.minusDays(1) -> "yesterday"
            else -> at.format(DateTimeFormatter.ofPattern("EEE d MMM"))
        }
        val minutes = ((last.durationSeconds ?: 0) + 59) / 60
        val kicks = if (last.count == 1) "1 kick" else "${last.count} kicks"
        return "$kicks in $minutes min · $day ${at.format(DateTimeFormatter.ofPattern("HH:mm"))}"
    }
}
