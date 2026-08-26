using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using Twogether.Core.Entities;

namespace Twogether.Infrastructure.Persistence.Configurations;

public sealed class CycleProfileConfiguration : IEntityTypeConfiguration<CycleProfile>
{
    public void Configure(EntityTypeBuilder<CycleProfile> builder)
    {
        builder.ToTable("cycle_profiles");
        builder.HasKey(profile => profile.Id);
        builder.HasIndex(profile => profile.UserId).IsUnique();
        builder.Property(profile => profile.ShareLevelWithPartner).HasConversion<string>().HasMaxLength(32).IsRequired();
        builder.Property(profile => profile.LastPeriodStartDate);
    }
}

public sealed class CycleDayLogConfiguration : IEntityTypeConfiguration<CycleDayLog>
{
    public void Configure(EntityTypeBuilder<CycleDayLog> builder)
    {
        builder.ToTable("cycle_day_logs");
        builder.HasKey(log => log.Id);
        builder.HasIndex(log => new { log.UserId, log.LogDate }).IsUnique();
        builder.Property(log => log.FlowLevel).HasConversion<string>().HasMaxLength(32).IsRequired();
        builder.Property(log => log.Mood).HasConversion<string>().HasMaxLength(32);
        builder.Property(log => log.SymptomsJson).HasColumnType("jsonb").IsRequired();
        builder.Property(log => log.Notes).HasMaxLength(4000);
    }
}
