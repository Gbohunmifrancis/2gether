using Twogether.Core.Enums;
using Twogether.Core.Seedwork;

namespace Twogether.Core.Entities;

/// <summary>Per-user daily targets and how much of the log the partner may see.</summary>
public sealed class NutritionTarget : BaseEntity
{
    public const int MinDailyCalories = 800;
    public const int MaxDailyCalories = 6000;
    public const int DefaultDailyCalories = 2000;
    public const double MaxGrams = 1000;

    private NutritionTarget() { }

    public Guid UserId { get; private set; }
    public Guid CoupleId { get; private set; }
    public int DailyCalories { get; private set; } = DefaultDailyCalories;
    public double? ProteinGrams { get; private set; }
    public double? CarbsGrams { get; private set; }
    public double? FatGrams { get; private set; }
    public FoodShareLevel ShareLevel { get; private set; } = FoodShareLevel.TotalsOnly;

    public static NutritionTarget Create(Guid userId, Guid coupleId, DateTime utcNow) => new()
    {
        UserId = userId,
        CoupleId = coupleId,
        CreatedAtUtc = utcNow,
        UpdatedAtUtc = utcNow
    };

    public void Update(int dailyCalories, double? proteinGrams, double? carbsGrams, double? fatGrams, FoodShareLevel shareLevel, DateTime utcNow)
    {
        if (dailyCalories is < MinDailyCalories or > MaxDailyCalories)
            throw new ArgumentOutOfRangeException(nameof(dailyCalories), dailyCalories, $"Daily calories must be between {MinDailyCalories} and {MaxDailyCalories}.");
        if (!Enum.IsDefined(shareLevel)) throw new ArgumentOutOfRangeException(nameof(shareLevel));
        DailyCalories = dailyCalories;
        ProteinGrams = Grams(proteinGrams, nameof(proteinGrams));
        CarbsGrams = Grams(carbsGrams, nameof(carbsGrams));
        FatGrams = Grams(fatGrams, nameof(fatGrams));
        ShareLevel = shareLevel;
        UpdatedAtUtc = utcNow;
    }

    private static double? Grams(double? value, string name)
    {
        if (value is null) return null;
        if (double.IsNaN(value.Value) || value is < 0 or > MaxGrams) throw new ArgumentOutOfRangeException(name, value, $"Grams must be between 0 and {MaxGrams}.");
        return Math.Round(value.Value, 1);
    }
}
