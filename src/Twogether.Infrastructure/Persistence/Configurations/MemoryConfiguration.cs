using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using Twogether.Core.Entities;
using Twogether.Core.Memories;

namespace Twogether.Infrastructure.Persistence.Configurations;

public sealed class MemoryConfiguration : IEntityTypeConfiguration<Memory>
{
    public void Configure(EntityTypeBuilder<Memory> builder)
    {
        builder.ToTable("memories", table => table.HasCheckConstraint("CK_memories_SizeBytes", "\"SizeBytes\" > 0"));
        builder.HasKey(item => item.Id);
        builder.Property(item => item.ObjectKey).HasMaxLength(200).IsRequired();
        builder.Property(item => item.ContentType).HasMaxLength(40).IsRequired();
        builder.Property(item => item.Caption).HasMaxLength(MemoryRules.MaxCaptionLength);
        builder.Property(item => item.Source).HasConversion<string>().HasMaxLength(20).IsRequired();
        builder.Property(item => item.Status).HasConversion<string>().HasMaxLength(20).IsRequired();
        builder.Property(item => item.ThumbnailContentType).HasMaxLength(20);
        builder.Ignore(item => item.HasThumbnail);
        builder.Ignore(item => item.ThumbnailKey);
        builder.HasIndex(item => item.UploadWindowEndsAtUtc).HasDatabaseName("IX_memories_upload_window").HasFilter("\"UploadWindowEndsAtUtc\" IS NOT NULL");
        builder.HasIndex(item => item.ObjectKey).IsUnique();
        // Offline-first sync: one memory per photo on the uploader's phone, however often a batch is retried.
        builder.HasIndex(item => new { item.UploadedByUserId, item.ClientId }).IsUnique()
            .HasFilter("\"ClientId\" IS NOT NULL").HasDatabaseName("IX_memories_UploadedByUserId_ClientId");
        // The album page: newest first by (SortAtUtc, Id), keyset-paged.
        builder.HasIndex(item => new { item.CoupleId, item.Status, item.SortAtUtc, item.Id });
        // The cleanup worker: abandoned uploads and pending deletes, oldest first.
        builder.HasIndex(item => new { item.Status, item.CreatedAtUtc });
    }
}
