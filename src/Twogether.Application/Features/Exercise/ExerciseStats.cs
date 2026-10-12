using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Core.Exercise;

namespace Twogether.Application.Features.Exercise;

public static class ExerciseStats
{
    /// <summary>
    /// A user's "today" for streaks: the local date in the time zone of their most recently
    /// updated active goal, else of the goal behind their latest occurrence, else UTC.
    /// (User.TimeZoneId is never set by the app, so goals are the only reliable signal.)
    /// </summary>
    public static DateOnly TodayFor(Guid userId, IReadOnlyList<ExerciseGoal> goalsNewestFirst, IReadOnlyList<ExerciseOccurrence> occurrencesNewestFirst, DateTime utcNow)
    {
        var zoneId = goalsNewestFirst.FirstOrDefault(goal => goal.IsActive && goal.AssignedUserId == userId)?.TimeZoneId
            ?? occurrencesNewestFirst.Where(item => item.UserId == userId)
                .Select(item => goalsNewestFirst.FirstOrDefault(goal => goal.Id == item.GoalId)?.TimeZoneId)
                .FirstOrDefault(zone => zone is not null);
        return ExerciseSchedule.LocalDate(utcNow, ExerciseSchedule.ResolveTimeZone(zoneId));
    }

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
