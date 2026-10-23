using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using Twogether.Core.Entities;

namespace Twogether.Infrastructure.Persistence.Configurations;

public sealed class ExerciseOccurrenceConfiguration : IEntityTypeConfiguration<ExerciseOccurrence>
{
    public void Configure(EntityTypeBuilder<ExerciseOccurrence> builder)
    {
        builder.ToTable("exercise_occurrences");
        builder.HasKey(occurrence => occurrence.Id);
        builder.Property(occurrence => occurrence.ExerciseType).HasConversion<string>().HasMaxLength(40).IsRequired();
        builder.Property(occurrence => occurrence.Status).HasConversion<string>().HasMaxLength(40).IsRequired();
        builder.Property(occurrence => occurrence.Title).HasMaxLength(ExerciseGoal.MaxTitleLength).IsRequired();
        // Status transitions bump the version; the worker's Miss and the partner's Complete
        // for the same row then collide instead of one silently winning.
        builder.Property(occurrence => occurrence.StateVersion).IsConcurrencyToken();
        builder.Ignore(occurrence => occurrence.IsAlarmActive);
        builder.Ignore(occurrence => occurrence.Checks);
        builder.Property(occurrence => occurrence.Integrity).HasConversion<string>().HasMaxLength(20).IsRequired();
        builder.Property(occurrence => occurrence.CallOffNote).HasMaxLength(ExerciseOccurrence.MaxCallOffNoteLength);
        // One alarm per goal per local day, whatever two worker instances think.
        builder.HasIndex(occurrence => new { occurrence.GoalId, occurrence.LocalDate }).IsUnique();
        builder.HasIndex(occurrence => new { occurrence.CoupleId, occurrence.LocalDate });
        builder.HasIndex(occurrence => new { occurrence.Status, occurrence.DeadlineUtc });
    }
}
