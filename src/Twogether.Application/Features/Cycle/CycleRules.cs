using Twogether.Core.Enums;
using Twogether.Shared.Localization;
using Twogether.Shared.Results;

namespace Twogether.Application.Features.Cycle;

public static class CycleRules
{
    public const int MaxNotesLength = 2000;
    public const int MaxSymptoms = 30;
    public const int MaxSymptomLength = 50;
    /// <summary>A last period or a logged day older than this is a typo, and would not change any prediction.</summary>
    public const int MaxDaysBack = 366;

    /// <summary>The latest calendar day anywhere on Earth (UTC+14), as AgeRules counts: today for everyone, and never later.</summary>
    public static DateOnly LatestDay(DateTime utcNow) => DateOnly.FromDateTime(utcNow.AddHours(14));

    public static bool IsPlausibleDay(DateOnly day, DateTime utcNow)
    {
        var latest = LatestDay(utcNow);
        return day <= latest && day >= latest.AddDays(-MaxDaysBack);
    }

    public static Result ValidateProfile(int cycleLengthDays, int periodLengthDays, DateOnly? lastPeriodStart, ShareLevel shareLevel, DateTime utcNow)
    {
        if (cycleLengthDays is < 15 or > 60 || periodLengthDays is < 1 or > 14)
            return Error.Validation("cycle.invalid_profile", Loc.T("Cycle lengths are outside the supported range."));
        // JSON enums also accept numbers, so 7 would otherwise be stored and read as some level.
        if (!Enum.IsDefined(shareLevel))
            return Error.Validation("cycle.invalid_profile", Loc.T("Choose what your partner sees: nothing, predictions or everything."));
        if (lastPeriodStart is { } start && !IsPlausibleDay(start, utcNow))
            return Error.Validation("cycle.invalid_date", Loc.T("The last period can't start in the future or more than a year ago."));
        return Result.Success();
    }

    public static Result ValidateLog(DateOnly date, FlowLevel flowLevel, Mood? mood, IReadOnlyList<string>? symptoms, string? notes, DateTime utcNow)
    {
        if (!IsPlausibleDay(date, utcNow))
            return Error.Validation("cycle.invalid_date", Loc.T("You can log today or a day in the past year."));
        if (!Enum.IsDefined(flowLevel) || mood is { } chosen && !Enum.IsDefined(chosen))
            return Error.Validation("cycle.invalid_log", Loc.T("Choose a flow and a mood from the list."));
        if (notes?.Length > MaxNotesLength)
            return Error.Validation("cycle.invalid_notes", Loc.T("Notes are at most {max} characters.", ("max", MaxNotesLength)));
        if (symptoms is { } list && (list.Count > MaxSymptoms || list.Any(symptom => string.IsNullOrWhiteSpace(symptom) || symptom.Length > MaxSymptomLength)))
            return Error.Validation("cycle.invalid_symptoms", Loc.T("Up to {symptoms} symptoms of at most {characters} characters each.", ("symptoms", MaxSymptoms), ("characters", MaxSymptomLength)));
        return Result.Success();
    }
}
