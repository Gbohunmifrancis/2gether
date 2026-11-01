using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using Twogether.Core.Entities;

namespace Twogether.Infrastructure.Persistence.Configurations;

public sealed class AiRequestConfiguration : IEntityTypeConfiguration<AiRequest>
{
    public void Configure(EntityTypeBuilder<AiRequest> builder)
    {
        builder.ToTable("ai_requests");
        builder.HasKey(item => item.Id);
        builder.Property(item => item.Feature).HasConversion<string>().HasMaxLength(20).IsRequired();
        builder.Property(item => item.Model).HasMaxLength(80).IsRequired();
        builder.Property(item => item.ErrorCode).HasMaxLength(60);
        // The daily cap: this person's requests for this feature since midnight.
        builder.HasIndex(item => new { item.UserId, item.Feature, item.CreatedAtUtc });
        // The admin's cost view: every request in a window.
        builder.HasIndex(item => item.CreatedAtUtc);
    }
}

public sealed class AiCoachNoteConfiguration : IEntityTypeConfiguration<AiCoachNote>
{
    public void Configure(EntityTypeBuilder<AiCoachNote> builder)
    {
        builder.ToTable("ai_coach_notes");
        builder.HasKey(item => item.Id);
        builder.Property(item => item.Language).HasMaxLength(8).IsRequired();
        builder.Property(item => item.State).HasConversion<string>().HasMaxLength(20).IsRequired();
        builder.Property(item => item.Text).HasMaxLength(AiCoachNote.MaxTextLength);
        // One note per finished dare: the second request to arrive finds the first one's claim.
        builder.HasIndex(item => item.OccurrenceId).IsUnique();
        builder.HasIndex(item => item.UserId);
        builder.HasOne<ExerciseOccurrence>().WithMany().HasForeignKey(item => item.OccurrenceId).OnDelete(DeleteBehavior.Cascade);
    }
}

public sealed class WeeklyRecapConfiguration : IEntityTypeConfiguration<WeeklyRecap>
{
    public void Configure(EntityTypeBuilder<WeeklyRecap> builder)
    {
        builder.ToTable("weekly_recaps");
        builder.HasKey(item => item.Id);
        builder.Property(item => item.TimeZoneId).HasMaxLength(100).IsRequired();
        builder.Property(item => item.State).HasConversion<string>().HasMaxLength(20).IsRequired();
        builder.Property(item => item.StatsJson).HasColumnType("jsonb").HasDefaultValueSql("'{}'::jsonb").IsRequired();
        builder.Property(item => item.NotesJson).HasColumnType("jsonb").HasDefaultValueSql("'[]'::jsonb").IsRequired();
        // Once per couple per week, whichever instance gets there first.
        builder.HasIndex(item => new { item.CoupleId, item.WeekEnding }).IsUnique();
        builder.HasOne<Couple>().WithMany().HasForeignKey(item => item.CoupleId).OnDelete(DeleteBehavior.Cascade);
    }
}
