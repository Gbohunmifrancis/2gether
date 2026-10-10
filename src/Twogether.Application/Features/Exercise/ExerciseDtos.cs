using Twogether.Core.Enums;

namespace Twogether.Application.Features.Exercise;

public sealed record ExerciseGoalDto(
    Guid Id,
    Guid CoupleId,
    Guid AssignedUserId,
    string AssignedUserName,
    Guid CreatedByUserId,
    ExerciseType ExerciseType,
    string Title,
    int Target,
    ExerciseMeasure Measure,
    /// <summary>"HH:mm" local wall clock.</summary>
    string ScheduledTime,
    string TimeZoneId,
    int GraceMinutes,
    bool IsActive,
    DateTime NextDueAtUtc,
    DateTime CreatedAtUtc);

public sealed record ExerciseOccurrenceDto(
    Guid Id,
    Guid GoalId,
    Guid CoupleId,
    Guid UserId,
    string UserName,
    ExerciseType ExerciseType,
    string Title,
    int Target,
    ExerciseMeasure Measure,
    /// <summary>"yyyy-MM-dd" in the goal's time zone.</summary>
    string LocalDate,
    DateTime ScheduledAtUtc,
    DateTime DeadlineUtc,
    DateTime? CompleteByUtc,
    ExerciseOccurrenceStatus Status,
    DateTime AlarmStartedAtUtc,
    DateTime? StartedAtUtc,
    DateTime? CompletedAtUtc,
    int AchievedCount,
    double Confidence,
    long StateVersion);

public sealed record ExerciseTodayDto(
    DateTime GeneratedAtUtc,
    IReadOnlyList<ExerciseGoalDto> Goals,
    IReadOnlyList<ExerciseOccurrenceDto> Occurrences,
    DateTime? NextDueAtUtc,
    Guid? NextDueGoalId);

public sealed record ExercisePartnerStatsDto(
    Guid UserId,
    string UserName,
    int CurrentStreakDays,
    int CompletedCount,
    int MissedCount,
    double CompletionRate);

public sealed record ExerciseHistoryDto(
    int Days,
    IReadOnlyList<ExerciseOccurrenceDto> Occurrences,
    IReadOnlyList<ExercisePartnerStatsDto> Stats);

public sealed record ExerciseProgressDto(Guid OccurrenceId, Guid UserId, int AchievedCount, int Target, double Confidence);

public sealed record ExerciseDefinitionDto(
    ExerciseType Type,
    string DisplayName,
    ExerciseMeasure Measure,
    int DefaultTarget,
    int MinTarget,
    int MaxTarget,
    double MinSecondsPerUnit,
    string Cue);

public sealed record CreateExerciseGoalRequest(
    Guid AssignedUserId,
    ExerciseType ExerciseType,
    string? Title,
    int Target,
    string ScheduledTime,
    string TimeZoneId,
    int GraceMinutes = 30);

public sealed record UpdateExerciseGoalRequest(
    Guid AssignedUserId,
    ExerciseType ExerciseType,
    string? Title,
    int Target,
    string ScheduledTime,
    string TimeZoneId,
    int GraceMinutes,
    bool IsActive);

public sealed record CompleteExerciseRequest(int AchievedCount, double Confidence);
