package app.heartpact.ui.cycle

import app.heartpact.data.CycleDayLog
import app.heartpact.data.CyclePrediction
import app.heartpact.data.CycleShare
import app.heartpact.data.FlowLevel
import java.time.LocalDate
import java.time.format.DateTimeFormatter

enum class DayKind { Period, Fertile, Plain, Predicted }

data class StripDay(val date: LocalDate, val kind: DayKind, val today: Boolean)

/** Pure: the cycle strip, the wording, and when a logged flow starts a new period. */
object CyclePlan {
    /** Every day of the cycle containing today, then the predicted next period. */
    fun strip(prediction: CyclePrediction, logs: List<CycleDayLog>, periodLength: Int, today: LocalDate): List<StripDay> {
        val start = prediction.cycleStart?.let(LocalDate::parse) ?: return emptyList()
        val next = prediction.nextPeriodStart?.let(LocalDate::parse) ?: return emptyList()
        val fertileFrom = prediction.fertileWindowStart?.let(LocalDate::parse)
        val fertileTo = prediction.fertileWindowEnd?.let(LocalDate::parse)
        val flowDays = logs.filter { it.flowLevel != FlowLevel.None }.map { LocalDate.parse(it.logDate) }.toSet()
        // Logged flow wins; with nothing logged this cycle, the usual period length is assumed.
        val anyLogged = flowDays.any { !it.isBefore(start) && it.isBefore(next) }
        val days = generateSequence(start) { it.plusDays(1) }.takeWhile { it.isBefore(next) }.map { date ->
            val kind = when {
                (if (anyLogged) date in flowDays else date.isBefore(start.plusDays(periodLength.toLong()))) -> DayKind.Period
                fertileFrom != null && fertileTo != null && !date.isBefore(fertileFrom) && !date.isAfter(fertileTo) -> DayKind.Fertile
                else -> DayKind.Plain
            }
            StripDay(date, kind, date == today)
        }.toList()
        val predicted = (0 until periodLength).map { StripDay(next.plusDays(it.toLong()), DayKind.Predicted, false) }
        return days + predicted
    }

    private val dayMonth = DateTimeFormatter.ofPattern("d MMMM")

    /** "27–29 October": the predicted start, give or take a day. */
    fun likely(next: LocalDate): String {
        val from = next.minusDays(1)
        val to = next.plusDays(1)
        return if (from.month == to.month) "${from.dayOfMonth}–${to.format(dayMonth)}" else "${from.format(dayMonth)}–${to.format(dayMonth)}"
    }

    fun summary(prediction: CyclePrediction, cycleLength: Int): String? {
        val next = prediction.nextPeriodStart?.let(LocalDate::parse) ?: return null
        return "Of about $cycleLength. Next period likely ${likely(next)}."
    }

    fun shareLine(level: CycleShare, partner: String): String = when (level) {
        CycleShare.None -> "$partner sees nothing of your cycle."
        CycleShare.PredictionsOnly -> "$partner sees your next period and fertile window. Not your logs or notes."
        CycleShare.FullDetail -> "$partner sees your predictions and what you log, including notes."
    }

    fun usRow(owner: Boolean, cycleDay: Int?, share: CycleShare?, partner: String, partnerDay: Int?): String = when {
        owner && cycleDay != null -> "Day $cycleDay · " + when (share) {
            CycleShare.None -> "private"
            CycleShare.FullDetail -> "$partner sees everything"
            else -> "$partner sees predictions"
        }
        owner -> "Set up your cycle"
        partnerDay != null -> "Day $partnerDay of $partner's cycle"
        else -> "Track your cycle, privately"
    }

    /**
     * Flow logged after a stretch without it, well past the last period, starts a new cycle: the
     * profile's last period start moves to that day so predictions follow.
     */
    fun startsNewPeriod(flow: FlowLevel, date: LocalDate, cycleStart: LocalDate?, periodLength: Int, logs: List<CycleDayLog>): Boolean {
        if (flow == FlowLevel.None || flow == FlowLevel.Spotting) return false
        if (cycleStart == null) return true
        if (!date.isAfter(cycleStart.plusDays(periodLength.toLong() + 5))) return false
        val yesterday = logs.firstOrNull { LocalDate.parse(it.logDate) == date.minusDays(1) }
        return yesterday == null || yesterday.flowLevel == FlowLevel.None || yesterday.flowLevel == FlowLevel.Spotting
    }

    val symptoms = listOf("Cramps", "Tired", "Headache", "Bloating", "Tender", "Acne", "Cravings", "Back pain")
}
