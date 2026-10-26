package app.heartpact.ui.cycle

import app.heartpact.data.CycleDayLog
import app.heartpact.data.CyclePrediction
import app.heartpact.data.CycleShare
import app.heartpact.data.FlowLevel
import app.heartpact.i18n.Dates
import app.heartpact.i18n.tr
import app.heartpact.i18n.trn
import java.time.LocalDate
import java.time.format.DateTimeFormatter

enum class DayKind { Period, Fertile, Plain, Predicted }

data class StripDay(val date: LocalDate, val kind: DayKind, val today: Boolean)

/** Pure: the cycle strip, the wording, and when a logged flow starts a new period. */
object CyclePlan {
    /**
     * Every day of the cycle containing today, then the predicted next period. A late period runs the strip on to
     * today with nothing drawn where it was due, since it hasn't come.
     */
    fun strip(prediction: CyclePrediction, logs: List<CycleDayLog>, periodLength: Int, today: LocalDate): List<StripDay> {
        val start = prediction.cycleStart?.let(LocalDate::parse) ?: return emptyList()
        val next = prediction.nextPeriodStart?.let(LocalDate::parse) ?: return emptyList()
        val late = prediction.daysLate != null
        val end = if (late && !today.isBefore(next)) today.plusDays(1) else next
        val fertileFrom = prediction.fertileWindowStart?.let(LocalDate::parse)
        val fertileTo = prediction.fertileWindowEnd?.let(LocalDate::parse)
        val flowDays = logs.filter { it.flowLevel != FlowLevel.None }.map { LocalDate.parse(it.logDate) }.toSet()
        // Logged flow wins. With nothing logged this cycle the usual period length is assumed: drawn as a period when
        // the cycle starts on the real last start, but as a prediction when cycles were rolled forward from it.
        val anyLogged = flowDays.any { !it.isBefore(start) && it.isBefore(next) }
        val assumed = if (prediction.lastPeriodStart == null || prediction.lastPeriodStart == prediction.cycleStart) DayKind.Period else DayKind.Predicted
        val days = generateSequence(start) { it.plusDays(1) }.takeWhile { it.isBefore(end) }.map { date ->
            val kind = when {
                anyLogged && date in flowDays -> DayKind.Period
                !anyLogged && date.isBefore(start.plusDays(periodLength.toLong())) -> assumed
                fertileFrom != null && fertileTo != null && !date.isBefore(fertileFrom) && !date.isAfter(fertileTo) -> DayKind.Fertile
                else -> DayKind.Plain
            }
            StripDay(date, kind, date == today)
        }.toList()
        val predicted = if (late) emptyList() else (0 until periodLength).map { StripDay(next.plusDays(it.toLong()), DayKind.Predicted, false) }
        return days + predicted
    }

    private val dayMonth get() = Dates.formatter("d MMMM")

    /** "27–29 October": the predicted start, give or take a day. */
    fun likely(next: LocalDate): String {
        val from = next.minusDays(1)
        val to = next.plusDays(1)
        return if (from.month == to.month) "${from.dayOfMonth}–${to.format(dayMonth)}" else "${from.format(dayMonth)}–${to.format(dayMonth)}"
    }

    fun summary(prediction: CyclePrediction, cycleLength: Int): String? {
        val next = prediction.nextPeriodStart?.let(LocalDate::parse) ?: return null
        prediction.daysLate?.let { late ->
            return if (late == 0) tr("Period due today.") else trn(late, "Period late by {count} day.", "Period late by {count} days.")
        }
        return trn(cycleLength, "Of about {count} day. Next period likely {dates}.", "Of about {count} days. Next period likely {dates}.", "dates" to likely(next))
    }

    /**
     * The same forecast seen from the partner's side. A late period is named as the owner's (never "her" or "your")
     * and says only how late it is; otherwise the wording is the owner's.
     */
    fun partnerSummary(prediction: CyclePrediction, cycleLength: Int, partner: String): String? {
        prediction.nextPeriodStart ?: return null
        prediction.daysLate?.let { late ->
            return if (late == 0) tr("{partner}'s period is due today.", "partner" to partner)
            else trn(late, "{partner}'s period is {count} day late.", "{partner}'s period is {count} days late.", "partner" to partner)
        }
        return summary(prediction, cycleLength)
    }

    fun shareLine(level: CycleShare, partner: String): String = when (level) {
        CycleShare.None -> tr("{partner} sees nothing of your cycle.", "partner" to partner)
        CycleShare.PredictionsOnly -> tr("{partner} sees your next period and fertile window. Not your logs or notes.", "partner" to partner)
        CycleShare.FullDetail -> tr("{partner} sees your predictions and what you log, including notes.", "partner" to partner)
    }

    /** The cycle row on Us. A partner whose view is late sees that instead of a day count that keeps climbing. */
    fun usRow(owner: Boolean, cycleDay: Int?, share: CycleShare?, partner: String, partnerDay: Int?, partnerDaysLate: Int? = null): String = when {
        owner && cycleDay != null -> when (share) {
            CycleShare.None -> tr("Day {day} · private", "day" to cycleDay)
            CycleShare.FullDetail -> tr("Day {day} · {partner} sees everything", "day" to cycleDay, "partner" to partner)
            else -> tr("Day {day} · {partner} sees predictions", "day" to cycleDay, "partner" to partner)
        }
        owner -> tr("Set up your cycle")
        partnerDay != null && partnerDaysLate == 0 -> tr("{partner}'s period is due today", "partner" to partner)
        partnerDay != null && partnerDaysLate != null -> trn(partnerDaysLate, "{partner}'s period is {count} day late", "{partner}'s period is {count} days late", "partner" to partner)
        partnerDay != null -> tr("Day {day} of {partner}'s cycle", "day" to partnerDay, "partner" to partner)
        else -> tr("Track your cycle, privately")
    }

    /** How far back a day can be logged: a period noted a few days late still lands on the right day. */
    const val LOG_DAYS_BACK = 60L

    fun canLog(day: LocalDate, today: LocalDate): Boolean = !day.isAfter(today) && !day.isBefore(today.minusDays(LOG_DAYS_BACK))

    /** The heading over the day being logged. */
    fun dayTitle(day: LocalDate, today: LocalDate): String = when (day) {
        today -> tr("Today")
        today.minusDays(1) -> tr("Yesterday")
        else -> day.format(Dates.formatter("EEEE d MMMM"))
    }

    /** The last period start to show when settings are edited: the one the forecast counts from, which logged flow may have moved. */
    fun lastStartForEditing(prediction: CyclePrediction, profileStart: String?): String? = prediction.lastPeriodStart ?: profileStart

    /** The symptoms on offer. These English names are what's saved and sent; [symptomLabel] is what people see. */
    val symptoms = listOf("Cramps", "Tired", "Headache", "Bloating", "Tender", "Acne", "Cravings", "Back pain")

    /** A saved symptom in the reader's language; one this app doesn't know is shown as saved. */
    fun symptomLabel(symptom: String): String = when (symptom) {
        "Cramps" -> tr("Cramps")
        "Tired" -> tr("Tired")
        "Headache" -> tr("Headache")
        "Bloating" -> tr("Bloating")
        "Tender" -> tr("Tender")
        "Acne" -> tr("Acne")
        "Cravings" -> tr("Cravings")
        "Back pain" -> tr("Back pain")
        else -> symptom
    }
}
