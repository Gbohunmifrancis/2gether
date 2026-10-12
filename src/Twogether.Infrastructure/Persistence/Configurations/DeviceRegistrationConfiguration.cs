using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using Twogether.Core.Entities;

namespace Twogether.Infrastructure.Persistence.Configurations;

public sealed class DeviceRegistrationConfiguration : IEntityTypeConfiguration<DeviceRegistration>
{
    public void Configure(EntityTypeBuilder<DeviceRegistration> builder)
    {
        builder.ToTable("device_registrations");
        builder.HasKey(item => item.Id);
        builder.Property(item => item.Token).HasMaxLength(DeviceRegistration.MaxTokenLength).IsRequired();
        builder.Property(item => item.Platform).HasConversion<string>().HasMaxLength(20).IsRequired();
        builder.Property(item => item.TokenKind).HasConversion<string>().HasMaxLength(30).IsRequired();
        builder.Property(item => item.AppVersion).HasMaxLength(DeviceRegistration.MaxAppVersionLength);
        builder.Property(item => item.DisabledReason).HasMaxLength(40);
        builder.Ignore(item => item.IsActive);
        // A token identifies one install; re-registering moves it rather than duplicating it.
        builder.HasIndex(item => item.Token).IsUnique();
        builder.HasIndex(item => new { item.UserId, item.DisabledAtUtc });
    }
}
