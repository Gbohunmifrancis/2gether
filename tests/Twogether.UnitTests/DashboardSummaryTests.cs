using Twogether.Application.Features.Dashboard;
using Twogether.Core.Entities;
using Twogether.Core.Enums;

namespace Twogether.UnitTests;

public sealed class DashboardSummaryTests
{
    private static readonly DateTime Now = new(2026, 10, 10, 12, 0, 0, DateTimeKind.Utc);

    [Fact]
    public void Exercise_summary_counts_streaks_alarms_and_the_next_alarm_per_partner()
    {
        var coupleId = Guid.NewGuid();
        var ada = Guid.NewGuid();
        var femi = Guid.NewGuid();
        var adaGoal = ExerciseGoal.Create(coupleId, ada, femi, ExerciseType.Squat, null, 15, new TimeOnly(18, 0), "UTC", 30, Now);
        var femiGoal = ExerciseGoal.Create(coupleId, femi, ada, ExerciseType.PushUp, null, 10, new TimeOnly(11, 0), "UTC", 30, Now);
        var occurrences = new List<ExerciseOccurrence>();
        for (var day = 1; day <= 3; day++)
        {
            var at = Now.Date.AddDays(-day).AddHours(18);
            var done = ExerciseOccurrence.Open(adaGoal, DateOnly.FromDateTime(at), at, at);
            done.Start(at); done.Complete(15, 0.9, at.AddSeconds(20));
            occurrences.Add(done);
        }
        var ringing = ExerciseOccurrence.Open(femiGoal, DateOnly.FromDateTime(Now), Now.AddMinutes(-5), Now.AddMinutes(-5));
        occurrences.Add(ringing);
        var goalsNewestFirst = new List<ExerciseGoal> { femiGoal, adaGoal };

        var summary = DashboardSummaries.Exercise(ada, femi, goalsNewestFirst, occurrences.OrderByDescending(item => item.ScheduledAtUtc).ToList(), Now);

        Assert.Equal(3, summary.MyStreakDays);
        Assert.Equal(0, summary.PartnerStreakDays);
        Assert.Equal((0, 1), (summary.MyActiveAlarms, summary.PartnerActiveAlarms));
        Assert.Equal(2, summary.ActiveGoals);
        // Both goals were saved at 12:00. Femi's 11:00 has passed, so it first fires tomorrow; Ada's 18:00 today is next.
        Assert.Equal(Now.Date.AddDays(1).AddHours(11), femiGoal.NextDueAtUtc);
        Assert.Equal(adaGoal.NextDueAtUtc, summary.NextAlarmAtUtc);
        Assert.Equal(ada, summary.NextAlarmUserId);
    }

    [Fact]
    public void Food_summary_hides_partner_calories_when_they_share_nothing()
    {
        var coupleId = Guid.NewGuid();
        var ada = Guid.NewGuid();
        var femi = Guid.NewGuid();
        var today = DateOnly.FromDateTime(Now);
        var entries = new List<FoodEntry>
        {
            FoodEntry.Create(coupleId, ada, today, MealType.Lunch, "Jollof", 640, null, null, null, Now),
            FoodEntry.Create(coupleId, femi, today, MealType.Lunch, "Eba", 820, null, null, null, Now)
        };
        var femiTarget = NutritionTarget.Create(femi, coupleId, Now);
        femiTarget.Update(2300, null, null, null, FoodShareLevel.TotalsOnly, Now);

        var shared = DashboardSummaries.Food(ada, femi, today, entries, [femiTarget]);
        Assert.Equal((640, NutritionTarget.DefaultDailyCalories, 820, 2300), (shared.CaloriesToday, shared.DailyCalories, shared.PartnerCaloriesToday!.Value, shared.PartnerDailyCalories!.Value));

        femiTarget.Update(2300, null, null, null, FoodShareLevel.None, Now);
        var hidden = DashboardSummaries.Food(ada, femi, today, entries, [femiTarget]);
        Assert.Null(hidden.PartnerCaloriesToday);
        Assert.Null(hidden.PartnerDailyCalories);
        Assert.Equal("2026-10-10", hidden.LocalDate);
    }
}
