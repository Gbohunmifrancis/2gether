using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using Twogether.Core.Entities;

namespace Twogether.Infrastructure.Persistence.Configurations;

public sealed class ExerciseGoalConfiguration : IEntityTypeConfiguration<ExerciseGoal>
{
    public void Configure(EntityTypeBuilder<ExerciseGoal> builder)
    {
        builder.ToTable("exercise_goals");
        builder.HasKey(goal => goal.Id);
        builder.Property(goal => goal.ExerciseType).HasConversion<string>().HasMaxLength(40).IsRequired();
        builder.Property(goal => goal.Title).HasMaxLength(ExerciseGoal.MaxTitleLength).IsRequired();
        builder.Property(goal => goal.TimeZoneId).HasMaxLength(ExerciseGoal.MaxTimeZoneIdLength).IsRequired();
        builder.Property(goal => goal.ScheduledTime).HasColumnType("time");
        // The alarm worker advances NextDueAtUtc while a partner may be editing the same goal.
        // Postgres' row version makes the second writer fail instead of overwriting the first.
        builder.Property<uint>("xmin").IsRowVersion();
        builder.HasIndex(goal => new { goal.CoupleId, goal.IsActive });
        builder.HasIndex(goal => new { goal.IsActive, goal.NextDueAtUtc });
    }
}
