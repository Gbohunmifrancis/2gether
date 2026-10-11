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
        builder.HasIndex(item => item.ObjectKey).IsUnique();
        // The album page: newest first by (SortAtUtc, Id), keyset-paged.
        builder.HasIndex(item => new { item.CoupleId, item.Status, item.SortAtUtc, item.Id });
        // The cleanup worker: abandoned uploads and pending deletes, oldest first.
        builder.HasIndex(item => new { item.Status, item.CreatedAtUtc });
    }
}
