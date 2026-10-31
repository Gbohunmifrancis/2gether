using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using Twogether.Core.Entities;

namespace Twogether.Infrastructure.Persistence.Configurations;

public sealed class GoogleExternalTransactionConfiguration : IEntityTypeConfiguration<GoogleExternalTransaction>
{
    public void Configure(EntityTypeBuilder<GoogleExternalTransaction> builder)
    {
        builder.ToTable("google_external_transactions");
        builder.HasKey(item => item.Id);
        builder.Property(item => item.Kind).HasMaxLength(20).IsRequired();
        builder.Property(item => item.Token).HasMaxLength(GoogleExternalTransaction.MaxTokenLength);
        builder.Property(item => item.ExternalTransactionId).HasMaxLength(GoogleExternalTransaction.MaxIdLength).IsRequired();
        builder.Property(item => item.InitialExternalTransactionId).HasMaxLength(GoogleExternalTransaction.MaxIdLength);
        builder.Property(item => item.BachsSubscriptionId).HasMaxLength(GoogleExternalTransaction.MaxBachsIdLength);
        builder.Property(item => item.BachsInvoiceId).HasMaxLength(GoogleExternalTransaction.MaxBachsIdLength);
        builder.Property(item => item.BachsChargeId).HasMaxLength(GoogleExternalTransaction.MaxBachsIdLength);
        builder.Property(item => item.Currency).HasMaxLength(3).IsRequired();
        builder.Property(item => item.RegionCode).HasMaxLength(2).IsRequired();
        builder.Property(item => item.LastError).HasMaxLength(GoogleExternalTransaction.MaxErrorLength);
        builder.Property(item => item.RefundId).HasMaxLength(GoogleExternalTransaction.MaxBachsIdLength);
        // Google refuses a second transaction with the same id; so do we.
        builder.HasIndex(item => item.ExternalTransactionId).IsUnique();
        builder.HasIndex(item => item.BachsSubscriptionId);
        builder.HasIndex(item => item.ReportedAtUtc);
    }
}
