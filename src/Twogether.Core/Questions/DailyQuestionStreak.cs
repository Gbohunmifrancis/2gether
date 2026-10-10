namespace Twogether.Core.Questions;

public static class DailyQuestionStreak
{
    /// <summary>
    /// Consecutive days on which both partners answered, ending today, or yesterday while
    /// today's question is still open.
    /// </summary>
    public static int CurrentStreakDays(IEnumerable<DateOnly> daysBothAnswered, DateOnly today)
    {
        var days = daysBothAnswered.ToHashSet();
        var cursor = days.Contains(today) ? today : today.AddDays(-1);
        var streak = 0;
        while (days.Contains(cursor))
        {
            streak++;
            cursor = cursor.AddDays(-1);
        }
        return streak;
    }
}
