using Twogether.Application.Features.Exercise;
using Twogether.Application.Features.Food;
using Twogether.Core.Entities;
using Twogether.Core.Enums;

namespace Twogether.Application.Features.Dashboard;

/// <summary>Pure builders for the dashboard's exercise and food cards, so the rules are testable without a database.</summary>
public static class DashboardSummaries
{
    public static DashboardExerciseDto Exercise(Guid userId, Guid? partnerId, IReadOnlyList<ExerciseGoal> goalsNewestFirst, IReadOnlyList<ExerciseOccurrence> occurrencesNewestFirst, DateTime utcNow)
    {
        (int Streak, int Active, int CompletedToday) For(Guid? id)
        {
            if (id is not { } who) return (0, 0, 0);
            var today = ExerciseStats.TodayFor(who, goalsNewestFirst, occurrencesNewestFirst, utcNow);
            var stats = ExerciseStats.Compute(who, string.Empty, occurrencesNewestFirst, today);
            var mine = occurrencesNewestFirst.Where(item => item.UserId == who).ToList();
            return (stats.CurrentStreakDays,
                mine.Count(item => item.IsAlarmActive),
                mine.Count(item => item.LocalDate == today && item.Status == ExerciseOccurrenceStatus.Completed));
        }

        var me = For(userId);
        var partner = For(partnerId);
        var next = goalsNewestFirst.Where(goal => goal.IsActive).MinBy(goal => goal.NextDueAtUtc);
        return new DashboardExerciseDto(
            me.Streak, partner.Streak,
            me.Active, partner.Active,
            me.CompletedToday, partner.CompletedToday,
            goalsNewestFirst.Count(goal => goal.IsActive),
            next?.NextDueAtUtc, next?.Title, next?.AssignedUserId);
    }

    public static DashboardFoodDto Food(Guid userId, Guid? partnerId, DateOnly localDate, IReadOnlyList<FoodEntry> entriesForDay, IReadOnlyList<NutritionTarget> targets)
    {
        var myTarget = targets.FirstOrDefault(item => item.UserId == userId)?.DailyCalories ?? NutritionTarget.DefaultDailyCalories;
        var mine = entriesForDay.Where(item => item.UserId == userId).Sum(item => item.Calories);
        int? partnerCalories = null;
        int? partnerTarget = null;
        if (partnerId is { } other)
        {
            var theirTarget = targets.FirstOrDefault(item => item.UserId == other);
            if ((theirTarget?.ShareLevel ?? FoodShareLevel.TotalsOnly) != FoodShareLevel.None)
            {
                partnerCalories = entriesForDay.Where(item => item.UserId == other).Sum(item => item.Calories);
                partnerTarget = theirTarget?.DailyCalories ?? NutritionTarget.DefaultDailyCalories;
            }
        }
        return new DashboardFoodDto(FoodMapper.Wire(localDate), mine, myTarget, partnerCalories, partnerTarget);
    }
}
