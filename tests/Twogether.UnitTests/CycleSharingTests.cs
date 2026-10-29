using Twogether.Application.Features.Cycle;
using Twogether.Core.Cycle;
using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Shared.Localization;

namespace Twogether.UnitTests;

public sealed class CycleSharingTests
{
    private static readonly DateOnly Sep1 = new(2026, 9, 1);

    private static CycleProfile Profile(DateOnly? lastStart, int cycle = 28, int period = 5)
    {
        var profile = CycleProfile.Create(Guid.NewGuid(), DateTime.UtcNow);
        profile.SetCycleSettings(cycle, period, lastStart, DateTime.UtcNow);
        return profile;
    }

    private static List<DateOnly> Days(DateOnly from, int count) => Enumerable.Range(0, count).Select(from.AddDays).ToList();

    [Fact]
    public void A_logged_period_that_is_late_re_anchors_the_forecast()
    {
        // The review's case: 28/5 set up on 1 Sep, real cycles of 31 days. Each logged late period moves the cycle.
        var profile = Profile(Sep1);
        var flow = Days(Sep1, 5).Concat(Days(new DateOnly(2026, 10, 2), 5)).ToList();
        var outlook = CycleSharing.Outlook(profile, flow, new DateOnly(2026, 10, 10), mayShowLate: true)!;
        Assert.Equal(new DateOnly(2026, 10, 2), outlook.LastPeriodStart);
        Assert.Equal(new DateOnly(2026, 10, 2), outlook.Forecast.CycleStart);
        Assert.Equal(9, outlook.Forecast.CycleDay);
        Assert.Equal(new DateOnly(2026, 10, 30), outlook.Forecast.NextPeriodStart);
        Assert.Null(outlook.Forecast.DaysLate);

        // Three days late, then six, then nine: each start is found, not just the twelve-day one.
        foreach (var lateBy in new[] { 3, 6, 9 })
        {
            var start = Sep1.AddDays(28 + lateBy);
            var next = CycleSharing.Outlook(profile, Days(Sep1, 5).Concat(Days(start, 3)).ToList(), start.AddDays(2), mayShowLate: true)!;
            Assert.Equal((start, 3), (next.Forecast.CycleStart, next.Forecast.CycleDay));
        }
    }

    [Fact]
    public void Starts_too_close_together_are_the_same_period_or_mid_cycle_bleeding()
    {
        // Flow the day before the stated start is the real start; flow two days after it is the same period.
        Assert.Equal(Sep1.AddDays(-1), CyclePeriods.LastStart(Sep1, [Sep1.AddDays(-1)], 28, 5, Sep1.AddDays(10)));
        Assert.Equal(Sep1, CyclePeriods.LastStart(Sep1, [Sep1.AddDays(2), Sep1.AddDays(3)], 28, 5, Sep1.AddDays(10)));
        // A light bleed in mid-cycle (day 14) is not a period.
        Assert.Equal(Sep1, CyclePeriods.LastStart(Sep1, [Sep1.AddDays(13)], 28, 5, Sep1.AddDays(20)));
        // Nothing stated, nothing logged: nothing to count from. Logged only: that.
        Assert.Null(CyclePeriods.LastStart(null, [], 28, 5, Sep1));
        Assert.Equal(Sep1, CyclePeriods.LastStart(null, Days(Sep1, 4), 28, 5, Sep1.AddDays(6)));
        // Days after today never count.
        Assert.Equal(Sep1, CyclePeriods.LastStart(Sep1, [Sep1.AddDays(40)], 28, 5, Sep1.AddDays(30)));
    }

    [Fact]
    public void A_period_that_is_due_and_not_logged_is_late_for_someone_who_logs()
    {
        var profile = Profile(Sep1);
        var flow = Days(Sep1, 5);
        var due = CycleSharing.Outlook(profile, flow, new DateOnly(2026, 9, 29), mayShowLate: true)!.Forecast;
        Assert.Equal((0, Sep1, 29), (due.DaysLate, due.CycleStart, due.CycleDay));
        var late = CycleSharing.Outlook(profile, flow, new DateOnly(2026, 10, 2), mayShowLate: true)!.Forecast;
        Assert.Equal((3, Sep1, 32, new DateOnly(2026, 9, 29)), (late.DaysLate, late.CycleStart, late.CycleDay, late.NextPeriodStart));

        // Without logs, or where lateness mustn't show (a private pregnancy), it rolls forward as before.
        var unlogged = CycleSharing.Outlook(profile, [], new DateOnly(2026, 10, 2), mayShowLate: true)!.Forecast;
        Assert.Equal(((int?)null, new DateOnly(2026, 9, 29), 4), (unlogged.DaysLate, unlogged.CycleStart, unlogged.CycleDay));
        Assert.Null(CycleSharing.Outlook(profile, flow, new DateOnly(2026, 10, 2), mayShowLate: false)!.Forecast.DaysLate);
        // More than a whole cycle late: back to estimating the current cycle.
        var lapsed = CyclePrediction.Predict(Sep1, 28, 5, Sep1.AddDays(60), logsFlow: true);
        Assert.Equal(((int?)null, Sep1.AddDays(56)), (lapsed.DaysLate, lapsed.CycleStart));
    }

    [Fact]
    public void A_short_cycle_never_puts_the_fertile_window_before_the_cycle()
    {
        var forecast = CyclePrediction.Predict(Sep1, 16, 4, Sep1.AddDays(1));
        Assert.Equal(Sep1, forecast.FertileWindowStart);
        Assert.Equal(Sep1.AddDays(2), forecast.FertileWindowEnd);
    }

    [Fact]
    public void Dates_are_bounded_and_enums_must_be_real()
    {
        var now = new DateTime(2026, 10, 25, 12, 0, 0, DateTimeKind.Utc);
        var today = new DateOnly(2026, 10, 25);
        Assert.True(CycleRules.ValidateLog(today.AddDays(1), FlowLevel.Light, null, null, null, now).IsSuccess); // already tomorrow somewhere
        Assert.Equal("cycle.invalid_date", CycleRules.ValidateLog(today.AddDays(2), FlowLevel.Light, null, null, null, now).Error.Code);
        Assert.Equal("cycle.invalid_date", CycleRules.ValidateLog(new DateOnly(2099, 1, 1), FlowLevel.Light, null, null, null, now).Error.Code);
        Assert.Equal("cycle.invalid_date", CycleRules.ValidateLog(new DateOnly(2025, 1, 1), FlowLevel.Light, null, null, null, now).Error.Code);
        Assert.Equal("cycle.invalid_log", CycleRules.ValidateLog(today, (FlowLevel)9, null, null, null, now).Error.Code);
        Assert.Equal("cycle.invalid_log", CycleRules.ValidateLog(today, FlowLevel.None, (Mood)9, null, null, now).Error.Code);
        Assert.Equal("cycle.invalid_notes", CycleRules.ValidateLog(today, FlowLevel.None, null, null, new string('x', 2001), now).Error.Code);
        Assert.Equal("cycle.invalid_symptoms", CycleRules.ValidateLog(today, FlowLevel.None, null, [" "], null, now).Error.Code);

        Assert.True(CycleRules.ValidateProfile(28, 5, new DateOnly(2026, 10, 1), ShareLevel.None, now).IsSuccess);
        Assert.True(CycleRules.ValidateProfile(28, 5, null, ShareLevel.FullDetail, now).IsSuccess);
        Assert.Equal("cycle.invalid_profile", CycleRules.ValidateProfile(28, 5, null, (ShareLevel)7, now).Error.Code);
        Assert.Equal("cycle.invalid_date", CycleRules.ValidateProfile(28, 5, today.AddDays(9), ShareLevel.None, now).Error.Code);
        Assert.Equal("cycle.invalid_profile", CycleRules.ValidateProfile(14, 5, null, ShareLevel.None, now).Error.Code);
    }

    [Fact]
    public void A_new_partner_sees_logs_only_from_the_day_they_linked()
    {
        var couple = Couple.Create(Guid.NewGuid(), new DateTime(2026, 9, 1, 8, 0, 0, DateTimeKind.Utc));
        Assert.Equal(new DateOnly(2026, 9, 1), CycleSharing.SharedLogsSince(couple));
        couple.LinkPartner(Guid.NewGuid(), new DateTime(2026, 10, 3, 22, 0, 0, DateTimeKind.Utc));
        Assert.Equal(new DateOnly(2026, 10, 3), CycleSharing.SharedLogsSince(couple));
    }

    [Fact]
    public void A_new_profile_shares_nothing()
        => Assert.Equal(ShareLevel.None, CycleProfile.Create(Guid.NewGuid(), DateTime.UtcNow).ShareLevelWithPartner);

    [Fact]
    public void The_home_card_has_no_number_without_data()
    {
        using var _ = Loc.Use("en");
        var today = new DateOnly(2026, 10, 10);
        Assert.Equal(("No cycle tracked yet", (int?)null), CycleSharing.Summary(null, pregnant: false, today));
        Assert.Equal(("Cycle predictions paused", (int?)null), CycleSharing.Summary(CyclePrediction.Predict(Sep1, 28, 5, today), pregnant: true, today));
        // An old start rolls forward, never "expected today" for ever.
        Assert.Equal(("11 days until period", (int?)11), CycleSharing.Summary(CyclePrediction.Predict(new DateOnly(2026, 7, 1), 28, 5, today), false, today));
        var lateDay = new DateOnly(2026, 10, 2);
        Assert.Equal(("Period late by 3 days", (int?)0), CycleSharing.Summary(CyclePrediction.Predict(Sep1, 28, 5, lateDay, logsFlow: true), false, lateDay));
    }
}
