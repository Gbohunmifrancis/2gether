using System.Globalization;
using Twogether.Core.Entities;
using Twogether.Core.Exercise;

namespace Twogether.Application.Features.Exercise;

public static class ExerciseMapper
{
    public static ExerciseGoalDto ToDto(ExerciseGoal goal, string assignedUserName) => new(
        goal.Id,
        goal.CoupleId,
        goal.AssignedUserId,
        assignedUserName,
        goal.CreatedByUserId,
        goal.ExerciseType,
        goal.Title,
        goal.Target,
        ExerciseCatalog.MeasureOf(goal.ExerciseType),
        goal.ScheduledTime.ToString("HH:mm", CultureInfo.InvariantCulture),
        goal.TimeZoneId,
        goal.GraceMinutes,
        goal.IsActive,
        goal.NextDueAtUtc,
        goal.CreatedAtUtc);

    public static ExerciseOccurrenceDto ToDto(ExerciseOccurrence occurrence, string userName) => new(
        occurrence.Id,
        occurrence.GoalId,
        occurrence.CoupleId,
        occurrence.UserId,
        userName,
        occurrence.ExerciseType,
        occurrence.Title,
        occurrence.Target,
        ExerciseCatalog.MeasureOf(occurrence.ExerciseType),
        occurrence.LocalDate.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture),
        occurrence.ScheduledAtUtc,
        occurrence.DeadlineUtc,
        occurrence.CompleteByUtc,
        occurrence.Status,
        occurrence.AlarmStartedAtUtc,
        occurrence.StartedAtUtc,
        occurrence.CompletedAtUtc,
        occurrence.AchievedCount,
        occurrence.Confidence,
        occurrence.StateVersion);

    public static ExerciseDefinitionDto ToDto(ExerciseDefinition definition) => new(
        definition.Type,
        definition.DisplayName,
        definition.Measure,
        definition.DefaultTarget,
        definition.MinTarget,
        definition.MaxTarget,
        definition.MinSecondsPerUnit,
        definition.Cue);
}
