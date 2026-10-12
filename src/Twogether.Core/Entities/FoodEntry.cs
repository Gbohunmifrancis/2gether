using Twogether.Core.Enums;
using Twogether.Core.Seedwork;
using Twogether.Shared.Guards;

namespace Twogether.Core.Entities;

/// <summary>
/// One logged meal or snack. Calories are what the user accepted, which may be a photo
/// estimate; the low/high range and confidence are kept so the UI can show "about".
/// </summary>
public sealed class FoodEntry : BaseEntity
{
    public const int MaxDescriptionLength = 120;
    public const int MaxCalories = 5000;
    public const double MaxGrams = 1000;

    private FoodEntry() { }

    public Guid CoupleId { get; private set; }
    public Guid UserId { get; private set; }
    public DateOnly LocalDate { get; private set; }
    public MealType MealType { get; private set; }
    public string Description { get; private set; } = string.Empty;
    public int Calories { get; private set; }
    public int? CaloriesLow { get; private set; }
    public int? CaloriesHigh { get; private set; }
    public double? ProteinGrams { get; private set; }
    public double? CarbsGrams { get; private set; }
    public double? FatGrams { get; private set; }
    public FoodEntrySource Source { get; private set; }
    public double? Confidence { get; private set; }
    public Guid? ScanId { get; private set; }
    public DateTime LoggedAtUtc { get; private set; }

    public static FoodEntry Create(Guid coupleId, Guid userId, DateOnly localDate, MealType mealType, string description, int calories,
        double? proteinGrams, double? carbsGrams, double? fatGrams, DateTime utcNow,
        FoodEntrySource source = FoodEntrySource.Manual, int? caloriesLow = null, int? caloriesHigh = null, double? confidence = null, Guid? scanId = null)
    {
        var entry = new FoodEntry
        {
            CoupleId = coupleId,
            UserId = userId,
            Source = source,
            ScanId = scanId,
            LoggedAtUtc = utcNow,
            CreatedAtUtc = utcNow,
            UpdatedAtUtc = utcNow
        };
        entry.Apply(localDate, mealType, description, calories, proteinGrams, carbsGrams, fatGrams, utcNow);
        entry.SetEstimate(caloriesLow, caloriesHigh, confidence);
        return entry;
    }

    public void Update(DateOnly localDate, MealType mealType, string description, int calories, double? proteinGrams, double? carbsGrams, double? fatGrams, DateTime utcNow)
    {
        Apply(localDate, mealType, description, calories, proteinGrams, carbsGrams, fatGrams, utcNow);
        // A hand-edited calorie count is no longer the estimate: drop the range so the UI stops saying "about".
        if (CaloriesLow.HasValue && CaloriesHigh.HasValue && (calories < CaloriesLow || calories > CaloriesHigh))
            SetEstimate(null, null, null);
    }

    private void Apply(DateOnly localDate, MealType mealType, string description, int calories, double? proteinGrams, double? carbsGrams, double? fatGrams, DateTime utcNow)
    {
        var clean = Guard.NotNullOrWhiteSpace(description, nameof(description));
        if (clean.Length > MaxDescriptionLength) throw new ArgumentException("Description is too long.", nameof(description));
        if (calories is < 0 or > MaxCalories) throw new ArgumentOutOfRangeException(nameof(calories), calories, $"Calories must be between 0 and {MaxCalories}.");
        if (!Enum.IsDefined(mealType)) throw new ArgumentOutOfRangeException(nameof(mealType));
        LocalDate = localDate;
        MealType = mealType;
        Description = clean;
        Calories = calories;
        ProteinGrams = Grams(proteinGrams, nameof(proteinGrams));
        CarbsGrams = Grams(carbsGrams, nameof(carbsGrams));
        FatGrams = Grams(fatGrams, nameof(fatGrams));
        UpdatedAtUtc = utcNow;
    }

    private void SetEstimate(int? caloriesLow, int? caloriesHigh, double? confidence)
    {
        if (caloriesLow.HasValue != caloriesHigh.HasValue) throw new ArgumentException("A calorie range needs both ends.");
        if (caloriesLow.HasValue && (caloriesLow < 0 || caloriesHigh < caloriesLow || caloriesHigh > MaxCalories * 2))
            throw new ArgumentException("Calorie range is out of order.");
        if (confidence is < 0 or > 1) throw new ArgumentOutOfRangeException(nameof(confidence));
        CaloriesLow = caloriesLow;
        CaloriesHigh = caloriesHigh;
        Confidence = confidence;
    }

    private static double? Grams(double? value, string name)
    {
        if (value is null) return null;
        if (double.IsNaN(value.Value) || value is < 0 or > MaxGrams) throw new ArgumentOutOfRangeException(name, value, $"Grams must be between 0 and {MaxGrams}.");
        return Math.Round(value.Value, 1);
    }
}
