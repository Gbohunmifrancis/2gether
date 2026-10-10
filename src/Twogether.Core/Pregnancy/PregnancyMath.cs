namespace Twogether.Core.Pregnancy;

public sealed record PregnancyProgress(int Week, int Day, int Trimester, int DaysToGo, double Fraction);

/// <summary>
/// Gestational age from the due date, counted from the first day of the last period as
/// clinics do: the due date is 280 days (40 weeks) after it. Informational only.
/// </summary>
public static class PregnancyMath
{
    public const int FullTermDays = 280;
    /// <summary>Counting stops here: past 43 weeks the numbers mean nothing useful.</summary>
    public const int MaxTrackedDays = 301;

    public static DateOnly DueDateFromLastPeriod(DateOnly lastPeriodStart) => lastPeriodStart.AddDays(FullTermDays);

    public static PregnancyProgress Progress(DateOnly dueDate, DateOnly today)
    {
        var lastPeriodStart = dueDate.AddDays(-FullTermDays);
        var elapsed = Math.Clamp(today.DayNumber - lastPeriodStart.DayNumber, 0, MaxTrackedDays);
        var week = elapsed / 7;
        var trimester = week < 14 ? 1 : week < 28 ? 2 : 3;
        var daysToGo = Math.Max(0, dueDate.DayNumber - today.DayNumber);
        var fraction = Math.Round(Math.Min(1.0, elapsed / (double)FullTermDays), 4);
        return new PregnancyProgress(week, elapsed % 7, trimester, daysToGo, fraction);
    }
}
