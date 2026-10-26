using Microsoft.EntityFrameworkCore;
using Twogether.Core.Enums;

namespace Twogether.Application.Features.Admin.Insights;

public sealed partial class AdminInsights
{
    /// <summary>
    /// Couples by the week they linked (Monday, UTC), and the share with either partner active (<see cref="ActivityLog"/>)
    /// in each week after. Week 0 is the week they linked, which is itself an action, so it is always 1. The current
    /// week counts as far as it has gone; weeks that haven't started are null. A couple that ended counts as inactive
    /// from then on.
    /// </summary>
    public async Task<RetentionDto> RetentionAsync(int weeks, CancellationToken ct)
    {
        weeks = InsightsMath.ClampWeeks(weeks);
        var now = clock.UtcNow;
        var today = DateOnly.FromDateTime(now);
        var firstWeek = InsightsMath.WeekStart(today).AddDays(-7 * (weeks - 1));
        var firstWeekUtc = InsightsMath.Utc(firstWeek);
        var cohortQuery = measured.Couples.Where(couple => couple.UserBId != null && couple.LinkedAtUtc >= firstWeekUtc);
        var couples = await cohortQuery.Select(couple => new { couple.UserAId, UserBId = couple.UserBId!.Value, LinkedAtUtc = couple.LinkedAtUtc!.Value, couple.DissolvedAtUtc }).ToListAsync(ct);
        var members = db.Users.Where(user => cohortQuery.Any(couple => couple.UserAId == user.Id || couple.UserBId == user.Id)).Select(user => user.Id);
        var active = couples.Count == 0 ? new HashSet<(Guid, DateOnly)>() : await Activity.ActiveDaysAsync(firstWeek, today, ct, members);

        var cohorts = new List<RetentionCohortDto>();
        for (var index = 0; index < weeks; index++)
        {
            var weekStart = firstWeek.AddDays(7 * index);
            var cohort = couples.Where(couple => InsightsMath.WeekStart(DateOnly.FromDateTime(couple.LinkedAtUtc)) == weekStart).ToList();
            var retained = new double?[weeks];
            for (var week = 0; week < weeks; week++)
            {
                var from = weekStart.AddDays(7 * week);
                if (from > today || cohort.Count == 0) continue;
                if (week == 0)
                {
                    retained[0] = 1;
                    continue;
                }
                var kept = cohort.Count(couple =>
                {
                    // Nothing counts after the couple ended.
                    var until = couple.DissolvedAtUtc is { } ended ? DateOnly.FromDateTime(ended) : DateOnly.MaxValue;
                    for (var day = from; day < from.AddDays(7) && day <= until; day = day.AddDays(1))
                        if (active.Contains((couple.UserAId, day)) || active.Contains((couple.UserBId, day))) return true;
                    return false;
                });
                retained[week] = InsightsMath.Rate(kept, cohort.Count);
            }
            cohorts.Add(new RetentionCohortDto(weekStart.ToString("yyyy-MM-dd"), cohort.Count, retained));
        }
        return new RetentionDto(weeks, cohorts);
    }

    /// <summary>
    /// When things happen, by weekday and hour (UTC): every timed action of <see cref="ActivityLog"/>, dares completed,
    /// and alarms that rang (not dares opened early, which never rang). Grouped in the database.
    /// </summary>
    public async Task<RhythmDto> RhythmAsync(int days, CancellationToken ct)
    {
        days = InsightsMath.ClampDays(days);
        var now = clock.UtcNow;
        var fromUtc = InsightsMath.WindowStart(now, days);
        var actions = ActivityLog.EmptyWeek();
        foreach (var source in Activity.PersonStamps(fromUtc, now.AddSeconds(1)).Concat(Activity.CoupleStamps(fromUtc, now.AddSeconds(1))))
            await ActivityLog.AddByHourAsync(actions, source, ct);

        var userIds = measured.UserIds;
        var daresDone = ActivityLog.EmptyWeek();
        await ActivityLog.AddByHourAsync(daresDone, db.ExerciseOccurrences
            .Where(item => item.Status == ExerciseOccurrenceStatus.Completed && item.CompletedAtUtc >= fromUtc && userIds.Contains(item.UserId))
            .Select(item => new ActivityStamp { ActorId = item.UserId, AtUtc = item.CompletedAtUtc!.Value }), ct);
        var rings = ActivityLog.EmptyWeek();
        await ActivityLog.AddByHourAsync(rings, db.ExerciseOccurrences
            .Where(item => item.AlarmStartedAtUtc >= fromUtc && item.AlarmStartedAtUtc >= item.ScheduledAtUtc && userIds.Contains(item.UserId))
            .Select(item => new ActivityStamp { ActorId = item.UserId, AtUtc = item.AlarmStartedAtUtc }), ct);
        return new RhythmDto(days, actions, daresDone, rings);
    }
}
