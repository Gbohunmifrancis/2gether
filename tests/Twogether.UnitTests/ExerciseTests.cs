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
    public void Call_off_can_be_asked_once_while_active_and_never_bumps_the_version()
    {
        var now = DateTime.UtcNow;
        var ringing = Ringing(now);
        ringing.RequestCallOff("  Knee is sore today  ", now.AddMinutes(1));
        Assert.Equal(now.AddMinutes(1), ringing.CallOffRequestedAtUtc);
        Assert.Equal("Knee is sore today", ringing.CallOffNote);
        Assert.Equal(now.AddMinutes(1), ringing.UpdatedAtUtc);
        Assert.Equal(0, ringing.StateVersion);
        Assert.Equal(ExerciseOccurrenceStatus.Alarming, ringing.Status);
        Assert.Throws<InvalidOperationException>(() => ringing.RequestCallOff(null, now.AddMinutes(2)));

        var started = Ringing(now);
        started.Start(now);
        started.RequestCallOff("   ", now.AddMinutes(1));
        Assert.Null(started.CallOffNote);
        Assert.NotNull(started.CallOffRequestedAtUtc);
        Assert.Equal(1, started.StateVersion);
        started.Cancel(now.AddMinutes(2)); // the partner grants it

        var done = Ringing(now);
        done.Miss(now.AddMinutes(31));
        Assert.Throws<InvalidOperationException>(() => done.RequestCallOff(null, now.AddMinutes(32)));
        Assert.Null(done.CallOffRequestedAtUtc);
    }

    [Fact]
    public void Call_off_note_is_trimmed_and_limited_to_140_characters()
    {
        var now = DateTime.UtcNow;
        Assert.Null(ExerciseGoalRules.CallOffNote(null).Value);
        Assert.Null(ExerciseGoalRules.CallOffNote(" \t ").Value);
        Assert.Equal(new string('x', 140), ExerciseGoalRules.CallOffNote("  " + new string('x', 140) + "  ").Value);
        Assert.Equal("exercise.invalid_note", ExerciseGoalRules.CallOffNote(new string('x', 141)).Error.Code);

        var occurrence = Ringing(now);
        Assert.Throws<ArgumentException>(() => occurrence.RequestCallOff(new string('x', 141), now));
        Assert.Null(occurrence.CallOffRequestedAtUtc);
        occurrence.RequestCallOff(" " + new string('x', 140) + " ", now);
        Assert.Equal(140, occurrence.CallOffNote!.Length);

        var dto = ExerciseMapper.ToDto(occurrence, "Maya");
        Assert.Equal(now, dto.CallOffRequestedAtUtc);
        Assert.Equal(occurrence.CallOffNote, dto.CallOffNote);
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
    public void Settling_before_it_rings_moves_the_pointer_to_the_next_day_across_spring_forward()
    {
        // London, 07:00 daily. 28 Mar 2026 is still GMT (07:00 UTC); 29 Mar is BST, so 07:00 local is 06:00 UTC.
        var created = new DateTime(2026, 3, 28, 5, 0, 0, DateTimeKind.Utc);
        var goal = Goal(created, zone: "Europe/London");
        var due = new DateTime(2026, 3, 28, 7, 0, 0, DateTimeKind.Utc);
        Assert.Equal(due, goal.NextDueAtUtc);

        var settledAt = new DateTime(2026, 3, 28, 6, 15, 0, DateTimeKind.Utc);
        Assert.True(goal.SettleOccurrence(due, settledAt));
        Assert.Equal(new DateTime(2026, 3, 29, 6, 0, 0, DateTimeKind.Utc), goal.NextDueAtUtc);
        Assert.Equal(new DateOnly(2026, 3, 29), ExerciseSchedule.LocalDate(goal.NextDueAtUtc, London));
        Assert.Equal(new TimeOnly(7, 0), TimeOnly.FromDateTime(TimeZoneInfo.ConvertTimeFromUtc(goal.NextDueAtUtc, London)));
        Assert.Equal(settledAt, goal.UpdatedAtUtc);

        // Settling the same occurrence again (or the worker's pass later) never skips another day.
        Assert.False(goal.SettleOccurrence(due, settledAt.AddMinutes(1)));
        Assert.Equal(new DateTime(2026, 3, 29, 6, 0, 0, DateTimeKind.Utc), goal.NextDueAtUtc);
    }

    [Fact]
    public void Settling_before_it_rings_lands_on_the_next_local_time_across_fall_back()
    {
        // 24 Oct 2026 is BST (07:00 local = 06:00 UTC); 25 Oct is GMT (07:00 local = 07:00 UTC).
        var goal = Goal(new DateTime(2026, 10, 24, 5, 0, 0, DateTimeKind.Utc), zone: "Europe/London");
        var due = new DateTime(2026, 10, 24, 6, 0, 0, DateTimeKind.Utc);
        Assert.Equal(due, goal.NextDueAtUtc);
        Assert.True(goal.SettleOccurrence(due, due.AddMinutes(-20)));
        Assert.Equal(new DateTime(2026, 10, 25, 7, 0, 0, DateTimeKind.Utc), goal.NextDueAtUtc);
    }

    [Fact]
    public void Settling_after_it_rang_leaves_the_pointer_alone()
    {
        // The worker opened today's alarm and already moved the pointer to tomorrow.
        var created = new DateTime(2026, 5, 10, 5, 0, 0, DateTimeKind.Utc); // 06:00 Lagos, before 07:00
        var goal = Goal(created);
        var due = goal.NextDueAtUtc;
        var tomorrow = ExerciseSchedule.NextDueUtc(goal.ScheduledTime, Lagos, due.AddMinutes(1));
        goal.AdvanceNextDue(tomorrow, due);

        Assert.False(goal.SettleOccurrence(due, due.AddMinutes(5)));
        Assert.Equal(tomorrow, goal.NextDueAtUtc);
        Assert.Equal(due, goal.UpdatedAtUtc);
    }

    [Fact]
    public void Settling_advances_when_the_pointer_is_before_the_occurrence()
    {
        var goal = Goal(new DateTime(2026, 5, 10, 5, 0, 0, DateTimeKind.Utc));
        var pointer = goal.NextDueAtUtc;           // 10 May 06:00 UTC
        var occurrenceAt = pointer.AddDays(1);     // 11 May 06:00 UTC
        Assert.True(goal.SettleOccurrence(occurrenceAt, pointer));
        Assert.Equal(occurrenceAt.AddDays(1), goal.NextDueAtUtc);
    }

    [Fact]
    public void Today_s_dare_is_early_only_before_it_rings_on_the_same_local_date()
    {
        var due = new DateTime(2026, 5, 10, 18, 0, 0, DateTimeKind.Utc);
        Assert.True(ExerciseSchedule.IsLaterToday(due, TimeZoneInfo.Utc, due.AddHours(-17)));
        Assert.True(ExerciseSchedule.IsLaterToday(due, TimeZoneInfo.Utc, due.AddSeconds(-1)));
        Assert.False(ExerciseSchedule.IsLaterToday(due, TimeZoneInfo.Utc, due));             // it rings now
        Assert.False(ExerciseSchedule.IsLaterToday(due, TimeZoneInfo.Utc, due.AddMinutes(1)));
        Assert.False(ExerciseSchedule.IsLaterToday(due, TimeZoneInfo.Utc, due.AddDays(-1)));  // that is tomorrow's

        // "Today" is the goal's zone: 22:30 UTC on 9 May is already 10 May in Lagos (UTC+1),
        // so a 07:00 Lagos alarm (06:00 UTC on 10 May) is later today there, but not in UTC.
        var lagosDue = new DateTime(2026, 5, 10, 6, 0, 0, DateTimeKind.Utc);
        var lateEvening = new DateTime(2026, 5, 9, 23, 30, 0, DateTimeKind.Utc);
        Assert.True(ExerciseSchedule.IsLaterToday(lagosDue, Lagos, lateEvening));
        Assert.False(ExerciseSchedule.IsLaterToday(lagosDue, TimeZoneInfo.Utc, lateEvening));
    }

    [Fact]
    public void Opening_early_keeps_the_real_due_time_and_starts_at_once()
    {
        var created = new DateTime(2026, 5, 10, 5, 0, 0, DateTimeKind.Utc);
        var goal = Goal(created, new TimeOnly(19, 0), grace: 30); // 19:00 Lagos = 18:00 UTC
        var due = goal.NextDueAtUtc;
        var now = new DateTime(2026, 5, 10, 9, 0, 0, DateTimeKind.Utc);
        Assert.True(goal.IsDueLaterToday(now));

        var occurrence = ExerciseOccurrence.OpenEarly(goal, now);
        Assert.Equal(ExerciseOccurrenceStatus.InProgress, occurrence.Status);
        Assert.Equal(new DateOnly(2026, 5, 10), occurrence.LocalDate);
        Assert.Equal(due, occurrence.ScheduledAtUtc);
        Assert.Equal(due.AddMinutes(30), occurrence.DeadlineUtc);
        Assert.Equal(due.AddMinutes(30), occurrence.CompleteByUtc); // the deadline is later than now + 10 min
        Assert.Equal(now, occurrence.StartedAtUtc);
        Assert.Equal(1, occurrence.StateVersion);
        Assert.Equal(due, goal.NextDueAtUtc); // the alarm still rings if they give up

        // With 5 minutes' grace, starting 2 minutes before the alarm makes the allowance the later bound.
        var tight = Goal(created, new TimeOnly(19, 0), grace: 5);
        var close = due.AddMinutes(-2);
        var early = ExerciseOccurrence.OpenEarly(tight, close);
        Assert.Equal(due.AddMinutes(5), early.DeadlineUtc);
        Assert.Equal(close + ExerciseVerification.CompletionAllowance, early.CompleteByUtc);
    }

    [Fact]
    public void Opening_early_is_refused_once_it_rang_or_for_an_inactive_goal()
    {
        var created = new DateTime(2026, 5, 10, 5, 0, 0, DateTimeKind.Utc);
        var goal = Goal(created, new TimeOnly(19, 0));
        Assert.Throws<InvalidOperationException>(() => ExerciseOccurrence.OpenEarly(goal, goal.NextDueAtUtc));
        Assert.Throws<InvalidOperationException>(() => ExerciseOccurrence.OpenEarly(goal, goal.NextDueAtUtc.AddDays(-1)));

        goal.Deactivate(created);
        Assert.False(goal.IsDueLaterToday(created.AddHours(1)));
        Assert.Throws<InvalidOperationException>(() => ExerciseOccurrence.OpenEarly(goal, created.AddHours(1)));
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
        Assert.Equal(13, ExerciseCatalog.All.Count);
    }

    [Fact]
    public void Restarting_the_camera_never_keeps_a_dare_open_past_deadline_plus_the_allowance()
    {
        var now = new DateTime(2026, 6, 1, 7, 0, 0, DateTimeKind.Utc);
        var occurrence = Ringing(now); // deadline 07:30, hard end 07:40
        Assert.Equal(now.AddMinutes(40), occurrence.LatestCompleteByUtc);

        occurrence.Start(now.AddMinutes(29));
        Assert.Equal(now.AddMinutes(39), occurrence.CompleteByUtc);
        // "Leave for now", then start again every few minutes: the end stays at 07:40.
        occurrence.Start(now.AddMinutes(36));
        Assert.Equal(now.AddMinutes(40), occurrence.CompleteByUtc);
        occurrence.Start(now.AddMinutes(39));
        Assert.Equal(now.AddMinutes(40), occurrence.CompleteByUtc);
        Assert.True(occurrence.IsOpenAt(now.AddMinutes(39).AddSeconds(59)));
        Assert.False(occurrence.IsOpenAt(now.AddMinutes(40)));
        Assert.Throws<InvalidOperationException>(() => occurrence.Start(now.AddMinutes(40)));
    }

    [Fact]
    public void The_window_is_open_while_ringing_before_the_deadline_and_in_progress_before_complete_by()
    {
        var now = new DateTime(2026, 6, 1, 7, 0, 0, DateTimeKind.Utc);
        var ringing = Ringing(now);
        Assert.True(ringing.IsOpenAt(now.AddMinutes(29)));
        Assert.False(ringing.IsOpenAt(now.AddMinutes(30))); // start is refused once the deadline passes while ringing

        var started = Ringing(now);
        started.Start(now.AddMinutes(5)); // complete by the deadline, 07:30
        Assert.True(started.IsOpenAt(now.AddMinutes(29)));
        Assert.False(started.IsOpenAt(now.AddMinutes(30))); // complete is refused here even before the worker marks it missed

        started.Complete(15, 0.9, now.AddMinutes(10));
        Assert.False(started.IsOpenAt(now.AddMinutes(11)));
    }

    [Fact]
    public void Confidence_must_reach_what_the_phone_sends_for_visible_joints()
    {
        var started = DateTime.UtcNow;
        var done = started.AddSeconds(30);
        // The phone drops frames under 0.5 visibility, so 0.35 can only come from a made-up completion.
        Assert.Equal("exercise.low_confidence", ExerciseVerification.Validate(ExerciseType.Squat, 15, 15, 0.35, started, done).Error.Code);
        Assert.Equal("exercise.low_confidence", ExerciseVerification.Validate(ExerciseType.Squat, 15, 15, 0.49, started, done).Error.Code);
        Assert.True(ExerciseVerification.Validate(ExerciseType.Squat, 15, 15, 0.5, started, done).IsSuccess);
        // A mean of floats that are each at least 0.5 can round a hair under it.
        Assert.True(ExerciseVerification.Validate(ExerciseType.Squat, 15, 15, 0.49999999, started, done).IsSuccess);
    }

    [Fact]
    public void Streak_treats_a_called_off_day_as_neutral()
    {
        var today = new DateOnly(2026, 6, 10);
        var days = new List<(DateOnly, ExerciseOccurrenceStatus)>
        {
            (today, ExerciseOccurrenceStatus.Completed),
            (today.AddDays(-1), ExerciseOccurrenceStatus.Cancelled), // the partner let you off: neither counts nor breaks
            (today.AddDays(-2), ExerciseOccurrenceStatus.Completed),
            (today.AddDays(-3), ExerciseOccurrenceStatus.Cancelled),
            (today.AddDays(-3), ExerciseOccurrenceStatus.Cancelled),
            (today.AddDays(-4), ExerciseOccurrenceStatus.Completed),
        };
        Assert.Equal(3, ExerciseStreak.CurrentStreakDays(days, today));

        // A called-off day today: the streak runs on from yesterday's.
        Assert.Equal(1, ExerciseStreak.CurrentStreakDays([(today, ExerciseOccurrenceStatus.Cancelled), (today.AddDays(-1), ExerciseOccurrenceStatus.Completed)], today));
        Assert.Equal(0, ExerciseStreak.CurrentStreakDays([(today, ExerciseOccurrenceStatus.Cancelled)], today));
    }

    [Fact]
    public void Streak_skips_a_window_the_server_slept_through_but_ends_on_a_day_with_nothing_due()
    {
        var today = new DateOnly(2026, 6, 10);
        var days = new List<(DateOnly, ExerciseOccurrenceStatus)>
        {
            (today, ExerciseOccurrenceStatus.Completed),
            // day -1: the dare was due but the server was down through its window, so there is no occurrence
            (today.AddDays(-2), ExerciseOccurrenceStatus.Completed),
            // day -3: no dare existed yet
            (today.AddDays(-4), ExerciseOccurrenceStatus.Completed),
        };
        bool Due(DateOnly date) => date >= today.AddDays(-2);
        Assert.Equal(2, ExerciseStreak.CurrentStreakDays(days, today, Due));
        Assert.Equal(1, ExerciseStreak.CurrentStreakDays(days, today)); // without the goals, an empty day still ends it
        // A miss still ends it, neutral days or not.
        days.Add((today.AddDays(-2), ExerciseOccurrenceStatus.Missed));
        Assert.Equal(1, ExerciseStreak.CurrentStreakDays(days, today, Due));
    }

    [Fact]
    public void Stats_take_downtime_days_from_the_user_s_goals()
    {
        var created = new DateTime(2026, 6, 1, 5, 0, 0, DateTimeKind.Utc);
        var goal = Goal(created); // 07:00 Lagos daily, set on 1 June
        var user = goal.AssignedUserId;
        ExerciseOccurrence Done(int day)
        {
            var occurrence = ExerciseOccurrence.Open(goal, new DateOnly(2026, 6, day), new DateTime(2026, 6, day, 6, 0, 0, DateTimeKind.Utc), created);
            occurrence.Start(new DateTime(2026, 6, day, 6, 1, 0, DateTimeKind.Utc));
            occurrence.Complete(15, 0.9, new DateTime(2026, 6, day, 6, 5, 0, DateTimeKind.Utc));
            return occurrence;
        }
        var occurrences = new List<ExerciseOccurrence> { Done(5), Done(3), Done(2) }; // 4 June skipped while the server was down
        var today = new DateOnly(2026, 6, 5);
        Assert.Equal(3, ExerciseStats.Compute(user, "Ada", occurrences, today, [goal]).CurrentStreakDays);
        Assert.Equal(1, ExerciseStats.Compute(user, "Ada", occurrences, today).CurrentStreakDays);
        // Someone else's goal says nothing about this user's days.
        Assert.Equal(1, ExerciseStats.Compute(user, "Ada", occurrences, today, [Goal(created)]).CurrentStreakDays);
    }

    [Fact]
    public void A_dare_set_by_the_partner_uses_the_assignee_s_saved_zone()
    {
        var setter = Guid.NewGuid();
        var assignee = Guid.NewGuid();
        Assert.Equal("America/New_York", ExerciseGoalRules.ZoneFor("Europe/London", setter, assignee, "America/New_York"));
        // Your own dare follows your phone.
        Assert.Equal("Europe/London", ExerciseGoalRules.ZoneFor(" Europe/London ", assignee, assignee, "America/New_York"));
        // No zone saved yet ("UTC" is every account's default), or one the server doesn't know: the setter's zone.
        Assert.Equal("Europe/London", ExerciseGoalRules.ZoneFor("Europe/London", setter, assignee, "UTC"));
        Assert.Equal("Europe/London", ExerciseGoalRules.ZoneFor("Europe/London", setter, assignee, null));
        Assert.Equal("Europe/London", ExerciseGoalRules.ZoneFor("Europe/London", setter, assignee, "Mars/Olympus_Mons"));
    }

    [Fact]
    public void A_due_goal_opens_rings_an_early_start_or_skips_downtime()
    {
        var created = new DateTime(2026, 6, 1, 5, 0, 0, DateTimeKind.Utc);
        var goal = Goal(created, new TimeOnly(19, 0), grace: 30); // due 18:00 UTC
        var due = goal.NextDueAtUtc;
        Assert.Equal(DueAction.Open, ExerciseAlarmRules.Decide(null, due, goal.GraceMinutes, due));
        Assert.Equal(DueAction.SkipDowntime, ExerciseAlarmRules.Decide(null, due, goal.GraceMinutes, due.AddMinutes(31)));

        // Started early and left: still pushed at the due time, so a phone without exact alarms rings on time.
        var early = ExerciseOccurrence.OpenEarly(goal, due.AddHours(-3));
        Assert.Equal(DueAction.RingStartedEarly, ExerciseAlarmRules.Decide(early, due, goal.GraceMinutes, due.AddSeconds(1)));
        Assert.Equal(DueAction.None, ExerciseAlarmRules.Decide(early, due, goal.GraceMinutes, due.AddMinutes(31)));

        // Already done early, or already opened by another instance: nothing more.
        early.Complete(15, 0.9, due.AddHours(-2));
        Assert.Equal(DueAction.None, ExerciseAlarmRules.Decide(early, due, goal.GraceMinutes, due));
        Assert.Equal(DueAction.None, ExerciseAlarmRules.Decide(ExerciseOccurrence.Open(goal, goal.NextDueLocalDate(), due, due), due, goal.GraceMinutes, due));
    }
}
