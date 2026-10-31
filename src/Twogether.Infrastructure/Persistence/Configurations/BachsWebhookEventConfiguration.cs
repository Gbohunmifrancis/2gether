using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using Twogether.Core.Entities;

namespace Twogether.Infrastructure.Persistence.Configurations;

public sealed class BachsWebhookEventConfiguration : IEntityTypeConfiguration<BachsWebhookEvent>
{
    public void Configure(EntityTypeBuilder<BachsWebhookEvent> builder)
    {
        builder.ToTable("bachs_webhook_events");
        // The event id is the key: a second delivery of the same event cannot be recorded twice.
        builder.HasKey(item => item.Id);
        builder.Property(item => item.Id).HasMaxLength(BachsWebhookEvent.MaxIdLength);
        builder.Property(item => item.Type).HasMaxLength(BachsWebhookEvent.MaxTypeLength).IsRequired();
    }
}
