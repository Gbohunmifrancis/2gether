namespace Twogether.Application.Features.Admin.Insights;

// Wire shapes of docs/admin/contract-v2.md, "Insights". Counts, durations, scores and country codes only.

public sealed record FunnelDto(int Days, IReadOnlyList<FunnelStepDto> Steps);
/// <summary>Key: signedUp, invitedOrJoined, linked, firstDareSet, firstDareDone, firstHeart, activeWeek2, plus.</summary>
public sealed record FunnelStepDto(string Key, int Count, double? MedianHoursFromPrevious);

public sealed record RetentionDto(int Weeks, IReadOnlyList<RetentionCohortDto> Cohorts);
/// <summary>WeekStart: the Monday (UTC) the couples linked. Retained[0] is that week, then each following one; null for weeks to come.</summary>
public sealed record RetentionCohortDto(string WeekStart, int Size, IReadOnlyList<double?> Retained);

/// <summary>Each grid is [weekday 0 = Monday … 6][hour 0 … 23], UTC.</summary>
public sealed record RhythmDto(int Days, int[][] Actions, int[][] DaresDone, int[][] Rings);

public sealed record DareInsightsDto(int Days, IReadOnlyList<DareExerciseDto> Exercises, int EarlyStarts, double? MedianRepsTarget, IReadOnlyList<StreakBucketDto> Streaks);
public sealed record DareExerciseDto(
    string Exercise, int Set, int Completed, int Missed, int CalledOff, double? CompletionRate,
    double? MedianSecondsToStart, double? MedianSecondsOnCamera, double? AverageConfidence, int ChecksFailed);
public sealed record StreakBucketDto(string Length, int Couples);

public sealed record GameInsightsDto(int Days, IReadOnlyList<GameTypeDto> Types, IReadOnlyList<SkillDto> Skills, DuelDto Duels);
public sealed record GameTypeDto(string Type, int Started, int Finished, int Abandoned, double? MedianMinutes);
/// <summary>Buckets: ten counts, 0-9 … 90-100.</summary>
public sealed record SkillDto(string Kind, int Rounds, double? AverageScore, int[] Buckets);
/// <summary>Finished duels; ties are level on hearts and on points; the median gap is in hearts.</summary>
public sealed record DuelDto(int Finished, int Ties, double? MedianMargin);

public sealed record GrowthDto(
    int Days, int EndingIn7Days, int EndingIn30Days, int Renewed, int Lapsed, double? RenewalRate,
    IReadOnlyList<GrowthStepDto> Path, double? MedianDaysToPlus, IReadOnlyList<NamedCount> PlusByCountry);
/// <summary>Key: signedUp, linked, hitScanLimit, nearAlbumLimit, plus.</summary>
public sealed record GrowthStepDto(string Key, int Count);

public sealed record WorldDto(IReadOnlyList<CountryDto> Countries, int Unknown);
public sealed record CountryDto(string Code, int People, int Couples, int Plus, int ActiveWeek);

public sealed record TopCoupleDto(Guid CoupleId, IReadOnlyList<string> Names, int Hearts, int DaresSet, int Games, int Streak, bool Plus, DateTime LinkedAtUtc);
