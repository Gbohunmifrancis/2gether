using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using Twogether.Core.Entities;

namespace Twogether.Infrastructure.Persistence.Configurations;

public sealed class CoupleLocationConfiguration : IEntityTypeConfiguration<CoupleLocation>
{
    public void Configure(EntityTypeBuilder<CoupleLocation> builder)
    {
        builder.ToTable("couple_locations");
        builder.HasKey(item => item.Id);
        builder.HasIndex(item => new { item.CoupleId, item.UserId }).IsUnique();
        // The sweep deletes rows nobody has touched for a week.
        builder.HasIndex(item => item.UpdatedAtUtc);
    }
}
