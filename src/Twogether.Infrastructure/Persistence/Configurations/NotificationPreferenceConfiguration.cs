using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using Twogether.Core.Entities;

namespace Twogether.Infrastructure.Persistence.Configurations;

public sealed class NotificationPreferenceConfiguration : IEntityTypeConfiguration<NotificationPreference>
{
    public void Configure(EntityTypeBuilder<NotificationPreference> builder)
    {
        builder.ToTable("notification_preferences", table =>
            table.HasCheckConstraint("CK_notification_preferences_quiet_window", "\"QuietStartLocal\" <> \"QuietEndLocal\""));
        builder.HasKey(item => item.Id);
        builder.HasIndex(item => item.UserId).IsUnique();
    }
}
