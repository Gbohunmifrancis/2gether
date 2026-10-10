using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Core.Exercise;

namespace Twogether.Application.Features.Exercise;

public static class ExerciseStats
{
    /// <summary>Cancelled occurrences count for nothing: not the streak, not the misses, not the rate.</summary>
    public static ExercisePartnerStatsDto Compute(Guid userId, string userName, IReadOnlyList<ExerciseOccurrence> occurrences, DateOnly today)
    {
        var mine = occurrences.Where(item => item.UserId == userId).ToList();
        var completed = mine.Count(item => item.Status == ExerciseOccurrenceStatus.Completed);
        var missed = mine.Count(item => item.Status == ExerciseOccurrenceStatus.Missed);
        var decided = completed + missed;
        var streak = ExerciseStreak.CurrentStreakDays(mine.Select(item => (item.LocalDate, item.Status)), today);
        return new ExercisePartnerStatsDto(userId, userName, streak, completed, missed, decided == 0 ? 0 : (double)completed / decided);
    }
}
