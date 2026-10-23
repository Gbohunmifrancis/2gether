using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using Twogether.Core.Entities;
using Twogether.Core.Enums;

namespace Twogether.Infrastructure.Persistence.Configurations;

public sealed class UserConfiguration : IEntityTypeConfiguration<User>
{
    public void Configure(EntityTypeBuilder<User> builder)
    {
        builder.ToTable("users");
        builder.HasKey(user => user.Id);
        builder.Property(user => user.Email).HasMaxLength(254).IsRequired();
        builder.Property(user => user.PasswordHash).HasMaxLength(200);
        builder.Property(user => user.GoogleId).HasMaxLength(128);
        builder.Property(user => user.DisplayName).HasMaxLength(100).IsRequired();
        builder.Property(user => user.AvatarUrl).HasMaxLength(500);
        builder.Property(user => user.DisabledReason).HasMaxLength(500);
        builder.Property(user => user.MapColor).HasMaxLength(20).HasDefaultValue("#f45c91").IsRequired();
        builder.Property(user => user.CycleOwner).HasDefaultValue(false).IsRequired();
        // No database default: Female is the enum's zero, so EF would leave it out of inserts and the default would replace it.
        builder.Property(user => user.Gender).HasConversion<string>().HasMaxLength(32).IsRequired();
        builder.Property(user => user.CountryCode).HasMaxLength(2);
        builder.Property(user => user.TimeZoneId).HasMaxLength(100).IsRequired();
        builder.HasIndex(user => user.Email).IsUnique();
        builder.HasIndex(user => user.GoogleId).IsUnique().HasFilter("\"GoogleId\" IS NOT NULL");
    }
}
