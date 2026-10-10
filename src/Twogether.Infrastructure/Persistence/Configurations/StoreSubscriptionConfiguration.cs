using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using Twogether.Core.Entities;

namespace Twogether.Infrastructure.Persistence.Configurations;

public sealed class StoreSubscriptionConfiguration : IEntityTypeConfiguration<StoreSubscription>
{
    public void Configure(EntityTypeBuilder<StoreSubscription> builder)
    {
        builder.ToTable("store_subscriptions");
        builder.HasKey(item => item.Id);
        builder.Property(item => item.Store).HasMaxLength(20).IsRequired();
        builder.Property(item => item.PurchaseToken).HasMaxLength(StoreSubscription.MaxTokenLength).IsRequired();
        builder.Property(item => item.ProductId).HasMaxLength(100).IsRequired();
        builder.Property(item => item.Status).HasConversion<string>().HasMaxLength(30).IsRequired();
        builder.Property(item => item.LinkedPurchaseToken).HasMaxLength(StoreSubscription.MaxTokenLength);
        // One purchase, one owner: a shared token can never be claimed twice.
        builder.HasIndex(item => item.PurchaseToken).IsUnique();
        builder.HasIndex(item => new { item.UserId, item.ExpiresAtUtc });
    }
}
