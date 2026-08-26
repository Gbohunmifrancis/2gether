using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using Twogether.Core.Entities;

namespace Twogether.Infrastructure.Persistence.Configurations;

public sealed class PartnerInviteConfiguration : IEntityTypeConfiguration<PartnerInvite>
{
    public void Configure(EntityTypeBuilder<PartnerInvite> builder)
    {
        builder.ToTable("partner_invites");
        builder.HasKey(invite => invite.Id);
        builder.Property(invite => invite.CodeHash).HasMaxLength(64).IsRequired();
        builder.Property(invite => invite.LinkTokenHash).HasMaxLength(64).IsRequired();
        builder.Property(invite => invite.Status).HasConversion<string>().HasMaxLength(32).IsRequired();
        builder.HasIndex(invite => invite.CodeHash).IsUnique();
        builder.HasIndex(invite => invite.LinkTokenHash).IsUnique();
        builder.HasIndex(invite => new { invite.InviterUserId, invite.Status });
    }
}
