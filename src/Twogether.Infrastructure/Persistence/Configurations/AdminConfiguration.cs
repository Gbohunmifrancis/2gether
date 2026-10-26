using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using Twogether.Core.Entities;

namespace Twogether.Infrastructure.Persistence.Configurations;

public sealed class AdminAccountConfiguration : IEntityTypeConfiguration<AdminAccount>
{
    public void Configure(EntityTypeBuilder<AdminAccount> builder)
    {
        builder.ToTable("admin_accounts");
        builder.HasKey(item => item.Id);
        builder.Property(item => item.TotpSecret).HasMaxLength(128).IsRequired();
        builder.Ignore(item => item.Enrolled);
        builder.HasOne<User>().WithMany().HasForeignKey(item => item.UserId).OnDelete(DeleteBehavior.Cascade);
        builder.HasIndex(item => item.UserId).IsUnique();
    }
}

public sealed class AdminAuditEventConfiguration : IEntityTypeConfiguration<AdminAuditEvent>
{
    public void Configure(EntityTypeBuilder<AdminAuditEvent> builder)
    {
        builder.ToTable("admin_audit_events");
        builder.HasKey(item => item.Id);
        // No foreign key to users: the trail outlives the accounts it mentions.
        builder.Property(item => item.AdminEmail).HasMaxLength(320).IsRequired();
        builder.Property(item => item.Action).HasMaxLength(60).IsRequired();
        builder.Property(item => item.TargetType).HasMaxLength(40);
        builder.Property(item => item.TargetId).HasMaxLength(80);
        builder.Property(item => item.Details).HasMaxLength(AdminAuditEvent.MaxDetailsLength);
        builder.Property(item => item.IpAddress).HasMaxLength(64);
        builder.HasIndex(item => item.CreatedAtUtc);
        builder.HasIndex(item => new { item.TargetType, item.TargetId });
    }
}

public sealed class PlusGrantConfiguration : IEntityTypeConfiguration<PlusGrant>
{
    public void Configure(EntityTypeBuilder<PlusGrant> builder)
    {
        builder.ToTable("plus_grants");
        builder.HasKey(item => item.Id);
        builder.Property(item => item.Reason).HasMaxLength(PlusGrant.MaxReasonLength).IsRequired();
        builder.HasOne<Couple>().WithMany().HasForeignKey(item => item.CoupleId).OnDelete(DeleteBehavior.Cascade);
        builder.HasIndex(item => new { item.CoupleId, item.ExpiresAtUtc });
    }
}

public sealed class UserActivityDayConfiguration : IEntityTypeConfiguration<UserActivityDay>
{
    public void Configure(EntityTypeBuilder<UserActivityDay> builder)
    {
        builder.ToTable("user_activity_days");
        builder.HasKey(item => new { item.UserId, item.Day });
        builder.HasOne<User>().WithMany().HasForeignKey(item => item.UserId).OnDelete(DeleteBehavior.Cascade);
        builder.HasIndex(item => item.Day);
    }
}

public sealed class AccountDeletionConfiguration : IEntityTypeConfiguration<AccountDeletion>
{
    public void Configure(EntityTypeBuilder<AccountDeletion> builder)
    {
        builder.ToTable("account_deletions");
        builder.HasKey(item => item.Id);
        builder.HasIndex(item => item.CreatedAtUtc);
    }
}
