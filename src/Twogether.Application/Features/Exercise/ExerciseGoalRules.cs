using System.Globalization;
using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Core.Exercise;
using Twogether.Shared.Results;

namespace Twogether.Application.Features.Exercise;

/// <summary>Request validation for goals. Entity constructors re-check the same limits as programmer errors.</summary>
public static class ExerciseGoalRules
{
    private static readonly string[] TimeFormats = ["HH:mm", "HH:mm:ss", "H:mm", "H:mm:ss"];

    public static Result<TimeOnly> ParseScheduledTime(string? value)
    {
        if (!string.IsNullOrWhiteSpace(value)
            && TimeOnly.TryParseExact(value.Trim(), TimeFormats, CultureInfo.InvariantCulture, DateTimeStyles.None, out var time))
            return new TimeOnly(time.Hour, time.Minute);
        return Error.Validation("exercise.invalid_time", "Scheduled time must look like HH:mm.");
    }

    public static Result Validate(ExerciseType type, string? title, int target, string? timeZoneId, int graceMinutes)
    {
        if (!Enum.IsDefined(type))
            return Error.Validation("exercise.invalid_target", "Unknown exercise.");
        var definition = ExerciseCatalog.Get(type);
        if (target < definition.MinTarget || target > definition.MaxTarget)
            return Error.Validation("exercise.invalid_target", $"{definition.DisplayName} target must be between {definition.MinTarget} and {definition.MaxTarget}.");
        if (!string.IsNullOrWhiteSpace(title) && title.Trim().Length > ExerciseGoal.MaxTitleLength)
            return Error.Validation("exercise.invalid_title", $"Title must be at most {ExerciseGoal.MaxTitleLength} characters.");
        if (string.IsNullOrWhiteSpace(timeZoneId) || timeZoneId.Trim().Length > ExerciseGoal.MaxTimeZoneIdLength || !ExerciseSchedule.IsKnownTimeZone(timeZoneId))
            return Error.Validation("exercise.invalid_time_zone", "Time zone is not recognised.");
        if (graceMinutes is < ExerciseGoal.MinGraceMinutes or > ExerciseGoal.MaxGraceMinutes)
            return Error.Validation("exercise.invalid_grace", $"Grace must be between {ExerciseGoal.MinGraceMinutes} and {ExerciseGoal.MaxGraceMinutes} minutes.");
        return Result.Success();
    }

    /// <summary>Trims the call-off note; empty becomes null. The entity re-checks the length as a programmer error.</summary>
    public static Result<string?> CallOffNote(string? note)
    {
        var clean = ExerciseOccurrence.NormalizeCallOffNote(note);
        if (clean is not null && clean.Length > ExerciseOccurrence.MaxCallOffNoteLength)
            return Error.Validation("exercise.invalid_note", $"The note must be at most {ExerciseOccurrence.MaxCallOffNoteLength} characters.");
        return clean;
    }
}
