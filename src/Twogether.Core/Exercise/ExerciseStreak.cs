using Twogether.Core.Enums;

namespace Twogether.Core.Exercise;

public static class ExerciseStreak
{
    /// <summary>
    /// Consecutive good days ending today (or yesterday when today has no result yet). A day is
    /// good when it has at least one completed occurrence and no missed one. Cancelled
    /// occurrences do not count either way, and a day with nothing scheduled ends the streak.
    /// </summary>
    public static int CurrentStreakDays(IEnumerable<(DateOnly LocalDate, ExerciseOccurrenceStatus Status)> occurrences, DateOnly today)
    {
        var byDay = new Dictionary<DateOnly, (bool Completed, bool Missed)>();
        foreach (var (date, status) in occurrences)
        {
            if (status is not (ExerciseOccurrenceStatus.Completed or ExerciseOccurrenceStatus.Missed)) continue;
            var day = byDay.GetValueOrDefault(date);
            byDay[date] = (day.Completed || status == ExerciseOccurrenceStatus.Completed, day.Missed || status == ExerciseOccurrenceStatus.Missed);
        }

        var cursor = byDay.ContainsKey(today) ? today : today.AddDays(-1);
        var streak = 0;
        while (byDay.TryGetValue(cursor, out var day) && day.Completed && !day.Missed)
        {
            streak++;
            cursor = cursor.AddDays(-1);
        }
        return streak;
    }
}
