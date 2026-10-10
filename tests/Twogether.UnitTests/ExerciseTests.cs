using Twogether.Application.Features.Exercise;
using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Core.Exercise;

namespace Twogether.UnitTests;

public sealed class ExerciseTests
{
    private static readonly TimeZoneInfo Lagos = TimeZoneInfo.FindSystemTimeZoneById("Africa/Lagos");
    private static readonly TimeZoneInfo London = TimeZoneInfo.FindSystemTimeZoneById("Europe/London");
    private static readonly TimeZoneInfo NewYork = TimeZoneInfo.FindSystemTimeZoneById("America/New_York");

    private static ExerciseGoal Goal(DateTime utcNow, TimeOnly? time = null, string zone = "Africa/Lagos", ExerciseType type = ExerciseType.Squat, int target = 15, int grace = 30)
        => ExerciseGoal.Create(Guid.NewGuid(), Guid.NewGuid(), Guid.NewGuid(), type, null, target, time ?? new TimeOnly(7, 0), zone, grace, utcNow);

    private static ExerciseOccurrence Ringing(DateTime utcNow, ExerciseType type = ExerciseType.Squat, int target = 15)
    {
        var goal = Goal(utcNow.AddDays(-1), type: type, target: target);
        return ExerciseOccurrence.Open(goal, DateOnly.FromDateTime(utcNow), utcNow, utcNow);
    }

    [Fact]
    public void Next_due_crosses_midnight_in_the_goal_time_zone()
    {
        // 23:30 UTC on 1 Jan is 00:30 on 2 Jan in Lagos (UTC+1); 07:00 Lagos is 06:00 UTC the same day.
        var from = new DateTime(2026, 1, 1, 23, 30, 0, DateTimeKind.Utc);
        var next = ExerciseSchedule.NextDueUtc(new TimeOnly(7, 0), Lagos, from);
        Assert.Equal(new DateTime(2026, 1, 2, 6, 0, 0, DateTimeKind.Utc), next);
        Assert.Equal(DateTimeKind.Utc, next.Kind);
    }

    [Fact]
    public void Spring_forward_gap_advances_to_the_first_valid_minute()
    {
        // London skips 01:00-01:59 on 29 Mar 2026. A 01:30 alarm rings at 02:00 BST = 01:00 UTC.
        var utc = ExerciseSchedule.ToUtc(new DateOnly(2026, 3, 29), new TimeOnly(1, 30), London);
        Assert.Equal(new DateTime(2026, 3, 29, 1, 0, 0, DateTimeKind.Utc), utc);
    }

    [Fact]
    public void Fall_back_overlap_resolves_to_the_earlier_instant_and_never_repeats_a_day()
    {
        // New York repeats 01:00-01:59 on 1 Nov 2026. 01:30 EDT (UTC-4) is 05:30 UTC; the later 01:30 EST would be 06:30 UTC.
        var first = ExerciseSchedule.ToUtc(new DateOnly(2026, 11, 1), new TimeOnly(1, 30), NewYork);
        Assert.Equal(new DateTime(2026, 11, 1, 5, 30, 0, DateTimeKind.Utc), first);

        var next = ExerciseSchedule.NextDueUtc(new TimeOnly(1, 30), NewYork, first.AddMinutes(1));
        Assert.Equal(new DateOnly(2026, 11, 2), ExerciseSchedule.LocalDate(next, NewYork));
    }

    [Fact]
    public void Goal_created_after_today_s_time_first_fires_tomorrow()
    {
        var now = new DateTime(2026, 5, 10, 8, 0, 0, DateTimeKind.Utc); // 09:00 Lagos, past a 07:00 alarm
        var goal = Goal(now);
        Assert.Equal(new DateTime(2026, 5, 11, 6, 0, 0, DateTimeKind.Utc), goal.NextDueAtUtc);
        Assert.Equal("Squats", goal.Title);
        Assert.True(goal.IsActive);
    }

    [Fact]
    public void Goal_rejects_targets_outside_the_catalog_and_moves_the_pointer_forward_only()
    {
        var now = DateTime.UtcNow;
        Assert.Throws<ArgumentOutOfRangeException>(() => Goal(now, target: 0));
        Assert.Throws<ArgumentOutOfRangeException>(() => Goal(now, type: ExerciseType.Plank, target: 601));
        Assert.Throws<ArgumentOutOfRangeException>(() => Goal(now, grace: 1));
        var goal = Goal(now);
        Assert.Throws<ArgumentException>(() => goal.AdvanceNextDue(goal.NextDueAtUtc, now));
        goal.AdvanceNextDue(goal.NextDueAtUtc.AddDays(1), now);
    }

    [Fact]
    public void Occurrence_state_machine_resets_on_start_and_bumps_version_only_on_transitions()
    {
        var now = DateTime.UtcNow;
        var occurrence = Ringing(now);
        Assert.Equal(ExerciseOccurrenceStatus.Alarming, occurrence.Status);
        Assert.Equal(now.AddMinutes(30), occurrence.DeadlineUtc);
        Assert.Throws<InvalidOperationException>(() => occurrence.Complete(15, 0.9, now));
        Assert.Throws<InvalidOperationException>(() => occurrence.RecordProgress(3, 0.9, now));

        occurrence.Start(now.AddMinutes(1));
        Assert.Equal(ExerciseOccurrenceStatus.InProgress, occurrence.Status);
        Assert.Equal(1, occurrence.StateVersion);
        Assert.Equal(now.AddMinutes(30), occurrence.CompleteByUtc); // deadline is later than start + 10 min

        occurrence.RecordProgress(5, 0.8, now.AddMinutes(2));
        occurrence.RecordProgress(3, 0.7, now.AddMinutes(3)); // never lowers the count
        Assert.Equal(5, occurrence.AchievedCount);
        Assert.Equal(1, occurrence.StateVersion);

        occurrence.Start(now.AddMinutes(25));
        Assert.Equal(0, occurrence.AchievedCount);
        Assert.Equal(2, occurrence.StateVersion);
        Assert.Equal(now.AddMinutes(35), occurrence.CompleteByUtc); // start + 10 min is now later than the deadline

        occurrence.Complete(15, 0.9, now.AddMinutes(26));
        Assert.Equal(ExerciseOccurrenceStatus.Completed, occurrence.Status);
        Assert.Equal(3, occurrence.StateVersion);
        Assert.Throws<InvalidOperationException>(() => occurrence.Miss(now));
        Assert.Throws<InvalidOperationException>(() => occurrence.Cancel(now));
    }

    [Fact]
    public void Occurrence_can_be_missed_or_cancelled_only_while_the_alarm_is_active()
    {
        var now = DateTime.UtcNow;
        var missed = Ringing(now);
        missed.Miss(now.AddMinutes(31));
        Assert.Equal(ExerciseOccurrenceStatus.Missed, missed.Status);
        Assert.False(missed.IsAlarmActive);
        Assert.Equal(1, missed.StateVersion);

        var cancelled = Ringing(now);
        cancelled.Start(now);
        cancelled.Cancel(now.AddMinutes(1));
        Assert.Equal(ExerciseOccurrenceStatus.Cancelled, cancelled.Status);
        Assert.Equal(2, cancelled.StateVersion);
        Assert.Throws<InvalidOperationException>(() => cancelled.Start(now));
    }

    [Fact]
    public void Verification_rejects_implausible_completions()
    {
        var started = DateTime.UtcNow;
        Assert.Equal("exercise.not_started", ExerciseVerification.Validate(ExerciseType.Squat, 15, 15, 0.9, null, started).Error.Code);
        Assert.Equal("exercise.target_not_met", ExerciseVerification.Validate(ExerciseType.Squat, 15, 14, 0.9, started, started.AddSeconds(30)).Error.Code);
        Assert.Equal("exercise.low_confidence", ExerciseVerification.Validate(ExerciseType.Squat, 15, 15, 0.2, started, started.AddSeconds(30)).Error.Code);
        // 15 squats need at least 12 s.
        Assert.Equal("exercise.too_fast", ExerciseVerification.Validate(ExerciseType.Squat, 15, 15, 0.9, started, started.AddSeconds(11)).Error.Code);
        Assert.True(ExerciseVerification.Validate(ExerciseType.Squat, 15, 15, 0.9, started, started.AddSeconds(12)).IsSuccess);
        // A 30 s plank needs 27 s of wall time.
        Assert.Equal("exercise.too_fast", ExerciseVerification.Validate(ExerciseType.Plank, 30, 30, 0.9, started, started.AddSeconds(26)).Error.Code);
        Assert.True(ExerciseVerification.Validate(ExerciseType.Plank, 30, 31, 0.5, started, started.AddSeconds(27)).IsSuccess);
    }

    [Fact]
    public void Streak_ignores_cancelled_days_and_ends_on_an_empty_day()
    {
        var today = new DateOnly(2026, 6, 10);
        var days = new List<(DateOnly, ExerciseOccurrenceStatus)>
        {
            (today, ExerciseOccurrenceStatus.Alarming),               // not decided yet: start from yesterday
            (today.AddDays(-1), ExerciseOccurrenceStatus.Completed),
            (today.AddDays(-2), ExerciseOccurrenceStatus.Completed),
            (today.AddDays(-2), ExerciseOccurrenceStatus.Cancelled),  // ignored
            (today.AddDays(-3), ExerciseOccurrenceStatus.Completed),
            // day -4 has nothing scheduled: the streak ends here
            (today.AddDays(-5), ExerciseOccurrenceStatus.Completed)
        };
        Assert.Equal(3, ExerciseStreak.CurrentStreakDays(days, today));

        days.Add((today, ExerciseOccurrenceStatus.Completed));
        Assert.Equal(4, ExerciseStreak.CurrentStreakDays(days, today));

        days.Add((today.AddDays(-1), ExerciseOccurrenceStatus.Missed)); // a miss spoils an otherwise good day
        Assert.Equal(1, ExerciseStreak.CurrentStreakDays(days, today));
    }

    [Fact]
    public void Stats_completion_rate_ignores_cancelled_occurrences()
    {
        var now = DateTime.UtcNow;
        var userId = Guid.NewGuid();
        var goal = ExerciseGoal.Create(Guid.NewGuid(), userId, userId, ExerciseType.PushUp, "Morning push", 10, new TimeOnly(6, 30), "UTC", 30, now.AddDays(-10));
        var occurrences = new List<ExerciseOccurrence>();
        for (var day = 1; day <= 4; day++)
        {
            var at = now.AddDays(-day);
            var occurrence = ExerciseOccurrence.Open(goal, DateOnly.FromDateTime(at), at, at);
            if (day == 4) occurrence.Cancel(at);
            else if (day == 3) occurrence.Miss(at.AddMinutes(31));
            else { occurrence.Start(at); occurrence.Complete(10, 0.9, at.AddSeconds(10)); }
            occurrences.Add(occurrence);
        }

        var stats = ExerciseStats.Compute(userId, "Ada", occurrences, DateOnly.FromDateTime(now));
        Assert.Equal(2, stats.CompletedCount);
        Assert.Equal(1, stats.MissedCount);
        Assert.Equal(2d / 3d, stats.CompletionRate, 6);
        Assert.Equal(2, stats.CurrentStreakDays);
        Assert.Equal("Morning push", goal.Title);
    }

    [Fact]
    public void Goal_rules_validate_requests_before_entities_see_them()
    {
        Assert.True(ExerciseGoalRules.ParseScheduledTime("07:05").IsSuccess);
        Assert.Equal(new TimeOnly(7, 5), ExerciseGoalRules.ParseScheduledTime("7:05:30").Value);
        Assert.Equal("exercise.invalid_time", ExerciseGoalRules.ParseScheduledTime("7pm").Error.Code);
        Assert.Equal("exercise.invalid_time_zone", ExerciseGoalRules.Validate(ExerciseType.Squat, null, 15, "Mars/Olympus", 30).Error.Code);
        Assert.Equal("exercise.invalid_target", ExerciseGoalRules.Validate(ExerciseType.Squat, null, 500, "UTC", 30).Error.Code);
        Assert.Equal("exercise.invalid_grace", ExerciseGoalRules.Validate(ExerciseType.Squat, null, 15, "UTC", 2).Error.Code);
        Assert.Equal("exercise.invalid_title", ExerciseGoalRules.Validate(ExerciseType.Squat, new string('x', 81), 15, "UTC", 30).Error.Code);
        Assert.True(ExerciseGoalRules.Validate(ExerciseType.HighKnees, "Knees!", 30, "Africa/Lagos", 30).IsSuccess);
    }

    [Fact]
    public void Mapper_emits_wire_formats_for_time_and_date()
    {
        var now = new DateTime(2026, 2, 3, 4, 5, 0, DateTimeKind.Utc);
        var goal = Goal(now, new TimeOnly(18, 45));
        var dto = ExerciseMapper.ToDto(goal, "Femi");
        Assert.Equal("18:45", dto.ScheduledTime);
        Assert.Equal(ExerciseMeasure.Reps, dto.Measure);
        var occurrence = ExerciseOccurrence.Open(goal, new DateOnly(2026, 2, 3), goal.NextDueAtUtc, now);
        Assert.Equal("2026-02-03", ExerciseMapper.ToDto(occurrence, "Femi").LocalDate);
        Assert.Equal(7, ExerciseCatalog.All.Count);
    }
}
