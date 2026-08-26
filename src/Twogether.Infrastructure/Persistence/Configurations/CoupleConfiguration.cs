using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using Twogether.Core.Entities;

namespace Twogether.Infrastructure.Persistence.Configurations;

public sealed class CoupleConfiguration : IEntityTypeConfiguration<Couple>
{
    public void Configure(EntityTypeBuilder<Couple> builder)
    {
        builder.ToTable("couples");
        builder.HasKey(couple => couple.Id);
        builder.Property(couple => couple.Status).HasConversion<string>().HasMaxLength(32).IsRequired();
        builder.HasIndex(couple => couple.UserAId);
        builder.HasIndex(couple => couple.UserBId);
    }
}
