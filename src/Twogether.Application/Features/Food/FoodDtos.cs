using Twogether.Core.Enums;

namespace Twogether.Application.Features.Food;

public sealed record NutritionTargetDto(Guid UserId, int DailyCalories, double? ProteinGrams, double? CarbsGrams, double? FatGrams, FoodShareLevel ShareLevel);

public sealed record FoodEntryDto(
    Guid Id,
    Guid UserId,
    string LocalDate,
    MealType MealType,
    string Description,
    int Calories,
    int? CaloriesLow,
    int? CaloriesHigh,
    double? ProteinGrams,
    double? CarbsGrams,
    double? FatGrams,
    FoodEntrySource Source,
    double? Confidence,
    Guid? ScanId,
    DateTime LoggedAtUtc);

public sealed record FoodTotalsDto(int Calories, double ProteinGrams, double CarbsGrams, double FatGrams, int EntryCount);

/// <summary>The partner's side of the day, filtered by their share level.</summary>
public sealed record FoodPartnerDayDto(
    Guid UserId,
    string UserName,
    FoodShareLevel ShareLevel,
    int? DailyCalories,
    FoodTotalsDto? Totals,
    IReadOnlyList<FoodEntryDto> Entries);

public sealed record FoodDayDto(
    string LocalDate,
    NutritionTargetDto Targets,
    FoodTotalsDto Totals,
    int RemainingCalories,
    IReadOnlyList<FoodEntryDto> Entries,
    FoodPartnerDayDto? Partner,
    int ScansUsedToday,
    int ScanLimitPerDay);

public sealed record FoodHistoryDayDto(Guid UserId, string LocalDate, int Calories, double ProteinGrams, double CarbsGrams, double FatGrams, int DailyCalories);

public sealed record FoodHistoryDto(int Days, IReadOnlyList<FoodHistoryDayDto> Items);

public sealed record FoodScanItemDto(string Name, string Portion, int Calories);

public sealed record FoodScanResultDto(
    Guid ScanId,
    string Description,
    int Calories,
    int CaloriesLow,
    int CaloriesHigh,
    double ProteinGrams,
    double CarbsGrams,
    double FatGrams,
    double Confidence,
    IReadOnlyList<FoodScanItemDto> Items,
    string Model,
    int ScansRemainingToday);

/// <summary>Sent to the partner when a day's totals change, when sharing allows it.</summary>
public sealed record FoodDayUpdatedDto(Guid UserId, string LocalDate, FoodTotalsDto Totals, FoodShareLevel ShareLevel);

public sealed record UpsertNutritionTargetRequest(int DailyCalories, double? ProteinGrams, double? CarbsGrams, double? FatGrams, FoodShareLevel ShareLevel);

public sealed record CreateFoodEntryRequest(string LocalDate, MealType MealType, string Description, int Calories, double? ProteinGrams, double? CarbsGrams, double? FatGrams, Guid? ScanId);

public sealed record UpdateFoodEntryRequest(string LocalDate, MealType MealType, string Description, int Calories, double? ProteinGrams, double? CarbsGrams, double? FatGrams);

/// <summary>Portion is a free hint such as "small", "large" or "half the plate"; it goes into the prompt, nothing else.</summary>
public sealed record FoodScanRequest(string ImageBase64, string MediaType, string? Portion, string? Hint);
