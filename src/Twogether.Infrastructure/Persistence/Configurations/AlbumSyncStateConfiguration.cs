using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using Twogether.Core.Entities;
using Twogether.Core.Memories;

namespace Twogether.Infrastructure.Persistence.Configurations;

public sealed class AlbumSyncStateConfiguration : IEntityTypeConfiguration<AlbumSyncState>
{
    public void Configure(EntityTypeBuilder<AlbumSyncState> builder)
    {
        builder.ToTable("album_sync_states", table =>
        {
            table.HasCheckConstraint("CK_album_sync_states_PendingCount", $"\"PendingCount\" BETWEEN 0 AND {AlbumSyncRules.MaxPendingCount}");
            table.HasCheckConstraint("CK_album_sync_states_PendingBytes", "\"PendingBytes\" >= 0");
        });
        builder.HasKey(item => item.UserId);
        builder.Property(item => item.UserId).ValueGeneratedNever();
        // One row per person, gone with them.
        builder.HasOne<User>().WithOne().HasForeignKey<AlbumSyncState>(item => item.UserId).OnDelete(DeleteBehavior.Cascade);
        // The reminder worker only looks at people with something waiting.
        builder.HasIndex(item => item.PendingSinceUtc).HasFilter("\"PendingCount\" > 0").HasDatabaseName("IX_album_sync_states_pending");
    }
}
