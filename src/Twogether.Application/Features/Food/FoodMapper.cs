using System.Globalization;
using Twogether.Core.Entities;

namespace Twogether.Application.Features.Food;

public static class FoodMapper
{
    public static string Wire(DateOnly date) => date.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);

    public static NutritionTargetDto ToDto(NutritionTarget target)
        => new(target.UserId, target.DailyCalories, target.ProteinGrams, target.CarbsGrams, target.FatGrams, target.ShareLevel);

    /// <summary>What a user who never saved targets gets: defaults, nothing persisted yet.</summary>
    public static NutritionTargetDto DefaultTargets(Guid userId)
        => new(userId, NutritionTarget.DefaultDailyCalories, null, null, null, Core.Enums.FoodShareLevel.TotalsOnly);

    public static FoodEntryDto ToDto(FoodEntry entry) => new(
        entry.Id,
        entry.UserId,
        Wire(entry.LocalDate),
        entry.MealType,
        entry.Description,
        entry.Calories,
        entry.CaloriesLow,
        entry.CaloriesHigh,
        entry.ProteinGrams,
        entry.CarbsGrams,
        entry.FatGrams,
        entry.Source,
        entry.Confidence,
        entry.ScanId,
        entry.LoggedAtUtc);

    public static FoodTotalsDto Totals(IEnumerable<FoodEntry> entries)
    {
        var list = entries as IReadOnlyCollection<FoodEntry> ?? entries.ToList();
        return new FoodTotalsDto(
            list.Sum(entry => entry.Calories),
            Math.Round(list.Sum(entry => entry.ProteinGrams ?? 0), 1),
            Math.Round(list.Sum(entry => entry.CarbsGrams ?? 0), 1),
            Math.Round(list.Sum(entry => entry.FatGrams ?? 0), 1),
            list.Count);
    }

    public static FoodScanResultDto ToDto(Guid scanId, FoodEstimate estimate, string model, int scansRemainingToday) => new(
        scanId,
        estimate.Description,
        estimate.Calories,
        estimate.CaloriesLow,
        estimate.CaloriesHigh,
        estimate.ProteinGrams,
        estimate.CarbsGrams,
        estimate.FatGrams,
        estimate.Confidence,
        estimate.Items.Select(item => new FoodScanItemDto(item.Name, item.Portion, item.Calories)).ToList(),
        model,
        scansRemainingToday);
}
