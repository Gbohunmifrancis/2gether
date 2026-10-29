namespace Twogether.Core.Cycle;

/// <summary>
/// Where today sits in the current cycle, and what comes next. Estimates, never medical advice.
/// DaysLate is set while the expected period has not been logged: 0 on the day it is due, then 1, 2...
/// </summary>
public sealed record CycleForecast(
    DateOnly CycleStart,
    int CycleDay,
    DateOnly NextPeriodStart,
    DateOnly NextPeriodEnd,
    DateOnly FertileWindowStart,
    DateOnly FertileWindowEnd,
    int? DaysLate = null);

/// <summary>
/// Calendar estimate from the last period start and the person's averages. The luteal phase (ovulation
/// to the next period) is about 14 days whatever the cycle length; the fertile window is the five
/// days before ovulation and the day of it, never before the cycle starts.
/// For someone who logs their flow, a due period that hasn't been logged makes the cycle late rather
/// than starting a new one, for up to a whole cycle. Otherwise, and after that, an old last-period date
/// rolls forward whole cycles, so the forecast is about the cycle that contains today.
/// </summary>
public static class CyclePrediction
{
    public const int LutealPhaseDays = 14;

    public static CycleForecast Predict(DateOnly lastPeriodStart, int cycleLengthDays, int periodLengthDays, DateOnly today, bool logsFlow = false)
    {
        ArgumentOutOfRangeException.ThrowIfLessThan(cycleLengthDays, 1);
        ArgumentOutOfRangeException.ThrowIfLessThan(periodLengthDays, 1);
        var start = lastPeriodStart;
        var elapsed = today.DayNumber - start.DayNumber;
        int? late = null;
        if (logsFlow && elapsed >= cycleLengthDays && elapsed < 2 * cycleLengthDays) late = elapsed - cycleLengthDays;
        else if (elapsed > 0) start = start.AddDays(elapsed / cycleLengthDays * cycleLengthDays);
        var next = start.AddDays(cycleLengthDays);
        var ovulation = next.AddDays(-LutealPhaseDays);
        var fertileStart = ovulation.AddDays(-5);
        return new CycleForecast(
            start,
            Math.Max(1, today.DayNumber - start.DayNumber + 1),
            next,
            next.AddDays(periodLengthDays - 1),
            fertileStart < start ? start : fertileStart,
            ovulation < start ? start : ovulation,
            late);
    }
}

/// <summary>When the last period really started, from the days with flow logged.</summary>
public static class CyclePeriods
{
    /// <summary>Flow after this many days without any starts a new period (spotting doesn't count as flow).</summary>
    public const int DryDays = 5;

    /// <summary>
    /// The latest period start. A flow day after at least <see cref="DryDays"/> days without flow starts a period;
    /// the profile's own date is a start too, and the fallback when nothing is logged. A start too soon after the one
    /// before (less than half a cycle, or within the period and five days) is the same period or bleeding mid-cycle,
    /// so the earlier start stands.
    /// </summary>
    public static DateOnly? LastStart(DateOnly? profileStart, IEnumerable<DateOnly> flowDays, int cycleLengthDays, int periodLengthDays, DateOnly today)
    {
        var minimumGap = Math.Max(periodLengthDays + 5, cycleLengthDays / 2);
        var candidates = new List<DateOnly>();
        DateOnly? previous = null;
        foreach (var day in flowDays.Where(day => day <= today).Distinct().Order())
        {
            if (previous is null || day.DayNumber - previous.Value.DayNumber > DryDays) candidates.Add(day);
            previous = day;
        }
        if (profileStart is { } stated) candidates.Add(stated);
        DateOnly? anchor = null;
        foreach (var candidate in candidates.Order())
            if (anchor is null || candidate.DayNumber - anchor.Value.DayNumber >= minimumGap) anchor = candidate;
        return anchor;
    }
}
