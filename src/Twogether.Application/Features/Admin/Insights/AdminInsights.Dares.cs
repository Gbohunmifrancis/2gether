using Microsoft.EntityFrameworkCore;
using Twogether.Core.Enums;
using Twogether.Core.Exercise;

namespace Twogether.Application.Features.Admin.Insights;

public sealed partial class AdminInsights
{
    /// <summary>How long the history for streak buckets reaches back: enough to tell 30+ from the rest.</summary>
    private const int StreakBucketHistoryDays = 40;

    /// <summary>Medians are taken in memory (the providers share no percentile function), over at most this many of the latest rows.</summary>
    private const int MedianSample = 20_000;

    /// <summary>
    /// Dares due in the window, by exercise: how they ended, how long after the alarm the camera opened, how long on
    /// camera, the camera's confidence, and how many completions the checks flagged (a missed check-in, a moving phone,
    /// leaving the frame, an unverified app; see <see cref="ExerciseCheckCounts.Flags"/>). Early starts are dares opened
    /// before their alarm. Streaks are each active couple's current one: the longer of the two partners'.
    /// </summary>
    public async Task<DareInsightsDto> DaresAsync(int days, CancellationToken ct)
    {
        days = InsightsMath.ClampDays(days);
        var now = clock.UtcNow;
        var fromUtc = InsightsMath.WindowStart(now, days);
        var userIds = measured.UserIds;
        var occurrences = db.ExerciseOccurrences.Where(item => item.ScheduledAtUtc >= fromUtc && userIds.Contains(item.UserId));

        var completed = ExerciseOccurrenceStatus.Completed;
        var byExercise = await occurrences.GroupBy(item => item.ExerciseType).Select(group => new
        {
            group.Key,
            Set = group.Count(),
            Completed = group.Count(item => item.Status == completed),
            Missed = group.Count(item => item.Status == ExerciseOccurrenceStatus.Missed),
            CalledOff = group.Count(item => item.Status == ExerciseOccurrenceStatus.Cancelled),
            Confidence = group.Average(item => item.Status == completed ? (double?)item.Confidence : null),
            Flagged = group.Count(item => item.Status == completed && (item.ChallengesMissed > 0
                || item.PhoneMovedReps >= ExerciseCheckCounts.PhoneMovedThreshold
                || item.OutOfFrameSeconds >= ExerciseCheckCounts.LeftFrameThreshold
                || item.Integrity == ExerciseIntegrity.Failed || item.Integrity == ExerciseIntegrity.Missing)),
        }).ToListAsync(ct);

        // Timings need each started dare's times: only the times, of the latest MedianSample at most.
        var timings = (await occurrences.Where(item => item.StartedAtUtc != null)
                .OrderByDescending(item => item.ScheduledAtUtc).Take(MedianSample)
                .Select(item => new { item.ExerciseType, item.ScheduledAtUtc, item.AlarmStartedAtUtc, item.StartedAtUtc, item.CompletedAtUtc, item.Status })
                .ToListAsync(ct))
            .ToLookup(item => item.ExerciseType);
        var earlyStarts = await occurrences.CountAsync(item => item.AlarmStartedAtUtc < item.ScheduledAtUtc, ct);
        var targets = await occurrences.GroupBy(item => new { item.ExerciseType, item.Target }).Select(group => new { group.Key.ExerciseType, group.Key.Target, Count = group.Count() }).ToListAsync(ct);
        var repsTarget = InsightsMath.WeightedMedian(targets.Where(item => ExerciseCatalog.MeasureOf(item.ExerciseType) == ExerciseMeasure.Reps).Select(item => ((double)item.Target, item.Count)));

        var exercises = byExercise.OrderByDescending(item => item.Set).Select(item =>
        {
            var started = timings[item.Key];
            return new DareExerciseDto(
                InsightsMath.Camel(item.Key.ToString()), item.Set, item.Completed, item.Missed, item.CalledOff,
                InsightsMath.Rate(item.Completed, item.Completed + item.Missed),
                // Time to start counts dares that rang; an early start has no alarm to answer. The start is the latest camera session's.
                InsightsMath.Round(InsightsMath.Median(started.Where(dare => dare.AlarmStartedAtUtc >= dare.ScheduledAtUtc).Select(dare => Math.Max(0, (dare.StartedAtUtc!.Value - dare.AlarmStartedAtUtc).TotalSeconds)))),
                InsightsMath.Round(InsightsMath.Median(started.Where(dare => dare.Status == completed && dare.CompletedAtUtc != null).Select(dare => Math.Max(0, (dare.CompletedAtUtc!.Value - dare.StartedAtUtc!.Value).TotalSeconds)))),
                InsightsMath.Round(item.Confidence, 3),
                item.Flagged);
        }).ToList();

        // Streaks of couples together now; a couple with no dares is on 0.
        var activeCouples = measured.Couples.Where(couple => couple.Status == CoupleStatus.Active && couple.UserBId != null);
        var pairs = await activeCouples.Select(couple => new { couple.UserAId, UserBId = couple.UserBId!.Value }).ToListAsync(ct);
        var members = db.Users.Where(user => activeCouples.Any(couple => couple.UserAId == user.Id || couple.UserBId == user.Id)).Select(user => user.Id);
        var streaks = await StreaksAsync(members, DateOnly.FromDateTime(now).AddDays(-StreakBucketHistoryDays), now, ct);
        var buckets = pairs.Select(pair => InsightsMath.StreakBucket(Math.Max(streaks.GetValueOrDefault(pair.UserAId), streaks.GetValueOrDefault(pair.UserBId))))
            .GroupBy(bucket => bucket).ToDictionary(group => group.Key, group => group.Count());

        return new DareInsightsDto(
            days, exercises, earlyStarts, InsightsMath.Round(repsTarget),
            InsightsMath.StreakBuckets.Select(bucket => new StreakBucketDto(bucket, buckets.GetValueOrDefault(bucket))).ToList());
    }
}
