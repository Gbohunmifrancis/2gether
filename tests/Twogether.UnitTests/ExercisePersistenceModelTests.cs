using Microsoft.EntityFrameworkCore;
using Twogether.Core.Entities;
using Twogether.Infrastructure.Persistence;

namespace Twogether.UnitTests;

/// <summary>
/// Builds the EF model against the Npgsql provider without a database. Catches mapping
/// mistakes (unmapped computed properties, missing keys, wrong index names) at test time
/// instead of on the first request after a deploy.
/// </summary>
public sealed class ExercisePersistenceModelTests
{
    private static ApplicationDbContext CreateContext()
        => new(new DbContextOptionsBuilder<ApplicationDbContext>().UseNpgsql("Host=localhost;Database=model-only").Options);

    [Fact]
    public void Exercise_goal_maps_to_its_table_with_row_version_and_indexes()
    {
        using var context = CreateContext();
        var entity = context.Model.FindEntityType(typeof(ExerciseGoal))!;

        Assert.Equal("exercise_goals", entity.GetTableName());
        Assert.NotNull(entity.FindProperty("xmin"));
        Assert.True(entity.FindProperty("xmin")!.IsConcurrencyToken);
        Assert.Equal("time", entity.FindProperty(nameof(ExerciseGoal.ScheduledTime))!.GetColumnType());
        var indexes = entity.GetIndexes().Select(index => index.GetDatabaseName()).ToList();
        Assert.Contains("IX_exercise_goals_CoupleId_IsActive", indexes);
        Assert.Contains("IX_exercise_goals_IsActive_NextDueAtUtc", indexes);
    }

    [Fact]
    public void Exercise_occurrence_maps_status_as_string_and_one_alarm_per_goal_per_day()
    {
        using var context = CreateContext();
        var entity = context.Model.FindEntityType(typeof(ExerciseOccurrence))!;

        Assert.Equal("exercise_occurrences", entity.GetTableName());
        Assert.Null(entity.FindProperty(nameof(ExerciseOccurrence.IsAlarmActive)));
        Assert.True(entity.FindProperty(nameof(ExerciseOccurrence.StateVersion))!.IsConcurrencyToken);
        Assert.Equal(typeof(string), entity.FindProperty(nameof(ExerciseOccurrence.Status))!.GetProviderClrType());
        var unique = entity.GetIndexes().Single(index => index.IsUnique);
        Assert.Equal("IX_exercise_occurrences_GoalId_LocalDate", unique.GetDatabaseName());
        Assert.Equal(140, entity.FindProperty(nameof(ExerciseOccurrence.CallOffNote))!.GetMaxLength());
        Assert.True(entity.FindProperty(nameof(ExerciseOccurrence.CallOffNote))!.IsNullable);
        Assert.True(entity.FindProperty(nameof(ExerciseOccurrence.CallOffRequestedAtUtc))!.IsNullable);
    }
}
