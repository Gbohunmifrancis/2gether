using Twogether.Core.Enums;

namespace Twogether.Core.Exercise;

public static class ExerciseStreak
{
    /// <summary>
    /// Consecutive good days ending today (or yesterday when today has no result yet). A day is
    /// good when it has at least one completed occurrence and no missed one. Some days are neutral:
    /// they neither count nor end the streak. Those are a day with only cancelled occurrences (the
    /// partner let you off) and a day a dare was due but has no occurrence at all, which is a window
    /// the server slept through (<paramref name="wasDue"/> says which days a dare was set for).
    /// Downtime never counts against anyone. A day with nothing due ends the streak.
    /// </summary>
    public static int CurrentStreakDays(IEnumerable<(DateOnly LocalDate, ExerciseOccurrenceStatus Status)> occurrences, DateOnly today, Func<DateOnly, bool>? wasDue = null)
    {
        var byDay = new Dictionary<DateOnly, (bool Completed, bool Missed)>();
        var cancelled = new HashSet<DateOnly>();
        DateOnly? earliest = null;
        foreach (var (date, status) in occurrences)
        {
            if (earliest is null || date < earliest) earliest = date;
            if (status == ExerciseOccurrenceStatus.Cancelled) cancelled.Add(date);
            if (status is not (ExerciseOccurrenceStatus.Completed or ExerciseOccurrenceStatus.Missed)) continue;
            var day = byDay.GetValueOrDefault(date);
            byDay[date] = (day.Completed || status == ExerciseOccurrenceStatus.Completed, day.Missed || status == ExerciseOccurrenceStatus.Missed);
        }
        if (earliest is null) return 0;

        var cursor = byDay.ContainsKey(today) ? today : today.AddDays(-1);
        var streak = 0;
        // Nothing older than the first occurrence can be good, so neutral days never walk further back than that.
        while (cursor >= earliest)
        {
            if (byDay.TryGetValue(cursor, out var day))
            {
                if (!day.Completed || day.Missed) break;
                streak++;
            }
            else if (!cancelled.Contains(cursor) && wasDue?.Invoke(cursor) != true) break;
            cursor = cursor.AddDays(-1);
        }
        return streak;
    }
}
