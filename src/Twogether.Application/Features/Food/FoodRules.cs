using System.Globalization;
using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Shared.Results;

namespace Twogether.Application.Features.Food;

public static class FoodRules
{
    public static readonly IReadOnlySet<string> ImageMediaTypes = new HashSet<string>(StringComparer.OrdinalIgnoreCase) { "image/jpeg", "image/png", "image/webp", "image/gif" };
    public const int MaxHintLength = 200;

    public static Result<DateOnly> ParseLocalDate(string? value)
        => !string.IsNullOrWhiteSpace(value) && DateOnly.TryParseExact(value.Trim(), "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out var date)
            ? date
            : Error.Validation("food.invalid_date", "Date must look like yyyy-MM-dd.");

    public static Result ValidateEntry(MealType mealType, string? description, int calories, double? proteinGrams, double? carbsGrams, double? fatGrams)
    {
        if (!Enum.IsDefined(mealType)) return Error.Validation("food.invalid_meal", "Unknown meal type.");
        if (string.IsNullOrWhiteSpace(description)) return Error.Validation("food.invalid_description", "Say what you ate.");
        if (description.Trim().Length > FoodEntry.MaxDescriptionLength) return Error.Validation("food.invalid_description", $"Description must be at most {FoodEntry.MaxDescriptionLength} characters.");
        if (calories is < 0 or > FoodEntry.MaxCalories) return Error.Validation("food.invalid_calories", $"Calories must be between 0 and {FoodEntry.MaxCalories}.");
        foreach (var grams in new[] { proteinGrams, carbsGrams, fatGrams })
            if (grams is not null && (double.IsNaN(grams.Value) || grams is < 0 or > FoodEntry.MaxGrams))
                return Error.Validation("food.invalid_macros", $"Macros must be between 0 and {FoodEntry.MaxGrams} g.");
        return Result.Success();
    }

    public static Result ValidateTarget(int dailyCalories, double? proteinGrams, double? carbsGrams, double? fatGrams, FoodShareLevel shareLevel)
    {
        if (dailyCalories is < NutritionTarget.MinDailyCalories or > NutritionTarget.MaxDailyCalories)
            return Error.Validation("food.invalid_target", $"Daily calories must be between {NutritionTarget.MinDailyCalories} and {NutritionTarget.MaxDailyCalories}.");
        foreach (var grams in new[] { proteinGrams, carbsGrams, fatGrams })
            if (grams is not null && (double.IsNaN(grams.Value) || grams is < 0 or > NutritionTarget.MaxGrams))
                return Error.Validation("food.invalid_macros", $"Macro targets must be between 0 and {NutritionTarget.MaxGrams} g.");
        if (!Enum.IsDefined(shareLevel)) return Error.Validation("food.invalid_share_level", "Unknown share level.");
        return Result.Success();
    }

    /// <summary>Decodes and bounds the uploaded image without ever logging it.</summary>
    public static Result<FoodPhoto> ValidateImage(string? imageBase64, string? mediaType, int maxBytes)
    {
        if (string.IsNullOrWhiteSpace(mediaType) || !ImageMediaTypes.Contains(mediaType.Trim()))
            return Error.Validation("food.image_type", "Send a JPEG, PNG, WebP or GIF image.");
        if (string.IsNullOrWhiteSpace(imageBase64))
            return Error.Validation("food.image_invalid", "The image is empty.");
        var payload = imageBase64.Trim();
        var comma = payload.IndexOf(',');
        if (payload.StartsWith("data:", StringComparison.OrdinalIgnoreCase) && comma > 0) payload = payload[(comma + 1)..];
        // Base64 inflates by 4/3; reject before decoding so a huge body never allocates twice.
        if (payload.Length > (long)maxBytes * 4 / 3 + 4)
            return Error.Validation("food.image_too_large", $"The image must be under {maxBytes / (1024 * 1024)} MB.");
        byte[] bytes;
        try { bytes = Convert.FromBase64String(payload); }
        catch (FormatException) { return Error.Validation("food.image_invalid", "The image is not valid base64."); }
        if (bytes.Length == 0) return Error.Validation("food.image_invalid", "The image is empty.");
        if (bytes.Length > maxBytes) return Error.Validation("food.image_too_large", $"The image must be under {maxBytes / (1024 * 1024)} MB.");
        return new FoodPhoto(mediaType.Trim().ToLowerInvariant(), bytes);
    }

    public static string? CleanHint(string? portion, string? hint)
    {
        var parts = new[] { portion, hint }
            .Where(part => !string.IsNullOrWhiteSpace(part))
            .Select(part => part!.Trim())
            .Select(part => part.Length > MaxHintLength ? part[..MaxHintLength] : part)
            .ToList();
        return parts.Count == 0 ? null : string.Join(". ", parts);
    }
}
