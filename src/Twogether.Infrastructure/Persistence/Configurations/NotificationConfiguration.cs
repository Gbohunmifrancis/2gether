using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using Twogether.Core.Entities;

namespace Twogether.Infrastructure.Persistence.Configurations;

public sealed class NotificationConfiguration : IEntityTypeConfiguration<Notification>
{
    public void Configure(EntityTypeBuilder<Notification> builder)
    {
        builder.ToTable("notifications");
        builder.HasKey(item => item.Id);
        builder.Property(item => item.Type).HasConversion<string>().HasMaxLength(40).IsRequired();
        builder.Property(item => item.Title).HasMaxLength(180).IsRequired();
        builder.Property(item => item.Body).HasMaxLength(1000).IsRequired();
        builder.Property(item => item.DataJson).HasColumnType("jsonb");
        builder.HasIndex(item => new { item.UserId, item.CreatedAtUtc });
    }
}
