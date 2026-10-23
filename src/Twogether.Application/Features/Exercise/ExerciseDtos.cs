using Twogether.Core.Enums;
using Twogether.Core.Exercise;

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
    long StateVersion,
    /// <summary>Set once the assigned partner asked to be let off today; only the partner can grant it.</summary>
    DateTime? CallOffRequestedAtUtc = null,
    string? CallOffNote = null,
    /// <summary>What the camera noticed in the session that completed it; all zero before then.</summary>
    ExerciseChecksDto? Checks = null);

/// <summary>
/// Reps that didn't count (form, too fast), check-ins passed and missed, reps counted while the phone moved,
/// seconds out of view, and Play Integrity's verdict. <see cref="Flags"/> are what the partner is told about.
/// </summary>
public sealed record ExerciseChecksDto(
    int FormFaults,
    int FastReps,
    int ChallengesPassed,
    int ChallengesMissed,
    int PhoneMovedReps,
    int OutOfFrameSeconds,
    ExerciseIntegrity Integrity,
    IReadOnlyList<ExerciseFlag> Flags);

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

/// <param name="Checks">What the camera noticed; older apps send none.</param>
/// <param name="IntegrityToken">A Play Integrity token whose request hash is <see cref="PlayIntegrityRules.RequestHash"/> of the count and checks as sent.</param>
public sealed record CompleteExerciseRequest(int AchievedCount, double Confidence, ExerciseChecksRequest? Checks = null, string? IntegrityToken = null);

public sealed record ExerciseChecksRequest(
    int FormFaults = 0,
    int FastReps = 0,
    int ChallengesPassed = 0,
    int ChallengesMissed = 0,
    int PhoneMovedReps = 0,
    int OutOfFrameSeconds = 0)
{
    public ExerciseCheckCounts ToCounts() => new(FormFaults, FastReps, ChallengesPassed, ChallengesMissed, PhoneMovedReps, OutOfFrameSeconds);
}

/// <summary>The assigned partner asks to be let off today. The note is optional, at most 140 characters.</summary>
public sealed record AskCallOffRequest(string? Note);
