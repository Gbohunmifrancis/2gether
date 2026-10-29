using Twogether.Shared.Results;

namespace Twogether.Application.Features.Food;

/// <summary>Outbound port: turns a meal photo into a calorie and macro estimate.</summary>
public interface IFoodPhotoEstimator
{
    /// <summary>False when no credentials are configured; the controller then answers 503 instead of calling out.</summary>
    bool IsConfigured { get; }
    string ModelName { get; }
    /// <param name="countryCode">Where the person lives (ISO 3166-1 alpha-2), so local dishes are recognised; null when unknown.</param>
    Task<Result<FoodEstimate>> EstimateAsync(FoodPhoto photo, string? hint, string? countryCode, CancellationToken cancellationToken);
}

public sealed record FoodPhoto(string MediaType, byte[] Bytes);

public sealed record FoodEstimateItem(string Name, string Portion, int Calories);

/// <summary>A parsed, clamped estimate. Calories always sit inside [CaloriesLow, CaloriesHigh].</summary>
public sealed record FoodEstimate(
    string Description,
    int Calories,
    int CaloriesLow,
    int CaloriesHigh,
    double ProteinGrams,
    double CarbsGrams,
    double FatGrams,
    double Confidence,
    IReadOnlyList<FoodEstimateItem> Items,
    bool IsFood,
    int InputTokens,
    int OutputTokens);
