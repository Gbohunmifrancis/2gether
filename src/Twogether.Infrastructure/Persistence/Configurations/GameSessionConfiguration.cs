using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using Twogether.Core.Entities;

namespace Twogether.Infrastructure.Persistence.Configurations;

public sealed class GameSessionConfiguration : IEntityTypeConfiguration<GameSession>
{
    public void Configure(EntityTypeBuilder<GameSession> builder)
    {
        builder.ToTable("game_sessions");
        builder.HasKey(session => session.Id);
        builder.Property(session => session.GameType).HasConversion<string>().HasMaxLength(40).IsRequired();
        builder.Property(session => session.Status).HasConversion<string>().HasMaxLength(40).IsRequired();
        builder.Property(session => session.StateJson).HasColumnType("jsonb").IsRequired();
        builder.Property(session => session.SetupJson).HasColumnType("jsonb").HasDefaultValueSql("'{}'::jsonb").IsRequired();
        // Two partners (or the timeout worker) can advance a round at the
        // same time. Make the version part of the update predicate so the
        // second writer receives a recoverable concurrency conflict instead
        // of silently overwriting the first writer's submission.
        builder.Property(session => session.StateVersion).IsConcurrencyToken();
        builder.HasIndex(session => new { session.CoupleId, session.Status });
        builder.HasIndex(session => session.InvitedByUserId);
    }
}
