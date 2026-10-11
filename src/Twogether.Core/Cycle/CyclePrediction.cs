namespace Twogether.Core.Cycle;

/// <summary>Where today sits in the current cycle, and what comes next. Estimates, never medical advice.</summary>
public sealed record CycleForecast(
    DateOnly CycleStart,
    int CycleDay,
    DateOnly NextPeriodStart,
    DateOnly NextPeriodEnd,
    DateOnly FertileWindowStart,
    DateOnly FertileWindowEnd);

/// <summary>
/// Calendar estimate from the last period start and the person's averages. The luteal phase (ovulation
/// to the next period) is about 14 days whatever the cycle length; the fertile window is the five
/// days before ovulation and the day of it. An old last-period date rolls forward whole cycles, so the
/// forecast is always about the cycle that contains today.
/// </summary>
public static class CyclePrediction
{
    public const int LutealPhaseDays = 14;

    public static CycleForecast Predict(DateOnly lastPeriodStart, int cycleLengthDays, int periodLengthDays, DateOnly today)
    {
        ArgumentOutOfRangeException.ThrowIfLessThan(cycleLengthDays, 1);
        ArgumentOutOfRangeException.ThrowIfLessThan(periodLengthDays, 1);
        var start = lastPeriodStart;
        if (today > start) start = start.AddDays((today.DayNumber - start.DayNumber) / cycleLengthDays * cycleLengthDays);
        var next = start.AddDays(cycleLengthDays);
        var ovulation = next.AddDays(-LutealPhaseDays);
        return new CycleForecast(
            start,
            Math.Max(1, today.DayNumber - start.DayNumber + 1),
            next,
            next.AddDays(periodLengthDays - 1),
            ovulation.AddDays(-5),
            ovulation);
    }
}
