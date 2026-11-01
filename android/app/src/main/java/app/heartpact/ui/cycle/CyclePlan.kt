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

/** What a day is on the dial and the calendar. Everything but a logged (or owner-set) period is a prediction. */
enum class CycleMark { Period, PredictedPeriod, Fertile, Ovulation, None }

/**
 * One day on the cycle dial: day [day] of the cycle (1 is the period's first day), drawn as an arc from
 * [startDegrees] for [sweepDegrees] (0° is 3 o'clock and angles grow clockwise, as Compose draws arcs).
 */
data class RingDay(val day: Int, val date: LocalDate, val mark: CycleMark, val today: Boolean, val startDegrees: Float, val sweepDegrees: Float) {
    val midDegrees: Float get() = startDegrees + sweepDegrees / 2f
}

/** The words in the middle of the dial: the big line, the line under it, and a small note for a fertile or ovulation day. */
data class DialText(val big: String, val line: String?, val note: String? = null, val period: Boolean = false)

/** One square of the month calendar; null squares pad the first and last weeks. */
data class CalendarDay(val date: LocalDate, val mark: CycleMark, val today: Boolean, val inMonth: Boolean = true)

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

    /** Ovulation is taken as the last day of the fertile window (docs/cycle.md: the five days before it and the day itself). */
    fun ovulation(prediction: CyclePrediction): LocalDate? = prediction.fertileWindowEnd?.let(LocalDate::parse)

    private fun mark(kind: DayKind, date: LocalDate, ovulation: LocalDate?): CycleMark = when (kind) {
        DayKind.Period -> CycleMark.Period
        DayKind.Predicted -> CycleMark.PredictedPeriod
        DayKind.Fertile -> if (date == ovulation) CycleMark.Ovulation else CycleMark.Fertile
        DayKind.Plain -> CycleMark.None
    }

    /**
     * The dial: every day of the cycle containing today, from day 1 at the top, clockwise (counter-clockwise when the
     * screen reads right to left, [clockwise] false). A late period runs the dial on to today. Each day gets an equal
     * share of the circle less a small [gapDegrees] between days, so the segments and gaps make exactly 360°.
     */
    fun ring(prediction: CyclePrediction, logs: List<CycleDayLog>, periodLength: Int, today: LocalDate, clockwise: Boolean = true, gapDegrees: Float = 1.6f): List<RingDay> {
        val next = prediction.nextPeriodStart?.let(LocalDate::parse) ?: return emptyList()
        val late = prediction.daysLate != null
        val days = strip(prediction, logs, periodLength, today).filter { late || it.date.isBefore(next) }
        if (days.isEmpty()) return emptyList()
        val ovulation = ovulation(prediction)
        val step = 360f / days.size
        val gap = minOf(gapDegrees, step * 0.3f)
        return days.mapIndexed { i, d ->
            val start = if (clockwise) -90f + i * step + gap / 2f else -90f - (i + 1) * step + gap / 2f
            RingDay(i + 1, d.date, mark(d.kind, d.date, ovulation), d.today, start, step - gap)
        }
    }

    /**
     * Every day the forecast marks, for the calendar: the cycle containing today and the predicted next period (as
     * the dial and strip draw them), and earlier periods from the logs the reader may see.
     */
    fun marks(prediction: CyclePrediction, logs: List<CycleDayLog>, periodLength: Int, today: LocalDate): Map<LocalDate, CycleMark> {
        val ovulation = ovulation(prediction)
        val strip = strip(prediction, logs, periodLength, today)
        val first = strip.firstOrNull()?.date
        val marks = LinkedHashMap<LocalDate, CycleMark>()
        logs.filter { it.flowLevel != FlowLevel.None }.map { LocalDate.parse(it.logDate) }
            .filter { first == null || it.isBefore(first) }
            .forEach { marks[it] = CycleMark.Period }
        strip.forEach { d -> mark(d.kind, d.date, ovulation).takeIf { it != CycleMark.None }?.let { marks[d.date] = it } }
        return marks
    }

    /**
     * A month as whole weeks of seven, starting on [firstDay]. The first and last weeks are filled from the months
     * either side ([CalendarDay.inMonth] false, drawn faintly), so a period that began on the 30th still shows its
     * first day in the next month's first week.
     */
    fun month(month: java.time.YearMonth, marks: Map<LocalDate, CycleMark>, today: LocalDate, firstDay: java.time.DayOfWeek = java.time.DayOfWeek.MONDAY): List<List<CalendarDay>> {
        val first = month.atDay(1)
        val lead = (first.dayOfWeek.value - firstDay.value + 7) % 7
        val weeks = (lead + month.lengthOfMonth() + 6) / 7
        return (0 until weeks * 7).map { i ->
            val date = first.plusDays((i - lead).toLong())
            CalendarDay(date, marks[date] ?: CycleMark.None, date == today, java.time.YearMonth.from(date) == month)
        }.chunked(7)
    }

    /** The months the calendar can step through: from the earliest mark (or today) to the last predicted day. */
    fun months(marks: Map<LocalDate, CycleMark>, today: LocalDate): ClosedRange<java.time.YearMonth> {
        val now = java.time.YearMonth.from(today)
        val first = marks.keys.minOrNull()?.let(java.time.YearMonth::from)?.let { minOf(it, now) } ?: now
        val last = marks.keys.maxOrNull()?.let(java.time.YearMonth::from)?.let { maxOf(it, now) } ?: now
        return first..last
    }

    /**
     * The middle of the dial. "Day 12" over "Period in 16 days" most of the time; "Period day 2" during a period;
     * "Period may be late" when the server says so; a note on a predicted fertile or ovulation day.
     */
    fun dial(prediction: CyclePrediction, ring: List<RingDay>, periodLength: Int, today: LocalDate): DialText? {
        val next = prediction.nextPeriodStart?.let(LocalDate::parse) ?: return null
        val now = ring.firstOrNull { it.today }
        // The number is the knob's day, counted on the phone's own today, like the ring and the calendar. The server's
        // cycleDay is for the date the forecast was asked on: a screen left open past midnight, or a server that read
        // the date as the day before or after, would put the middle a day away from the knob and the calendar.
        val cycleDay = now?.day ?: prediction.cycleDay ?: return null
        val note = when (now?.mark) {
            CycleMark.Ovulation -> tr("Ovulation, predicted")
            CycleMark.Fertile -> tr("Fertile window, predicted")
            else -> null
        }
        prediction.daysLate?.let { late ->
            return DialText(tr("Day {day}", "day" to cycleDay), if (late == 0) tr("Period due today") else tr("Period may be late"),
                if (late == 0) null else trn(late, "{count} day past the predicted start", "{count} days past the predicted start"))
        }
        if (now?.mark == CycleMark.Period) {
            return DialText(tr("Period day {day}", "day" to cycleDay), trn(periodLength, "Usually about {count} day", "Usually about {count} days"), period = true)
        }
        val until = java.time.temporal.ChronoUnit.DAYS.between(today, next).toInt()
        val line = when {
            now?.mark == CycleMark.PredictedPeriod -> tr("Period expected around now")
            until <= 0 -> tr("Period due today")
            else -> trn(until, "Period in {count} day", "Period in {count} days")
        }
        return DialText(tr("Day {day}", "day" to cycleDay), line, note)
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
