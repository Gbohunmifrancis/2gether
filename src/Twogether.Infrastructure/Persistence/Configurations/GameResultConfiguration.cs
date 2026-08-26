using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using Twogether.Core.Entities;

namespace Twogether.Infrastructure.Persistence.Configurations;

public sealed class GameResultConfiguration : IEntityTypeConfiguration<GameResult>
{
    public void Configure(EntityTypeBuilder<GameResult> builder)
    {
        builder.ToTable("game_results");
        builder.HasKey(result => result.Id);
        builder.Property(result => result.GameType).HasConversion<string>().HasMaxLength(40).IsRequired();
        builder.Property(result => result.Outcome).HasConversion<string>().HasMaxLength(40).IsRequired();
        builder.Property(result => result.ScoresJson).HasColumnType("jsonb").HasDefaultValueSql("'{}'::jsonb").IsRequired();
        // One result per session: whichever path reaches the terminal transition first wins
        // the race, and the loser's insert fails loudly instead of duplicating history.
        builder.HasIndex(result => result.GameSessionId).IsUnique();
        builder.HasIndex(result => new { result.CoupleId, result.EndedAtUtc });
    }
}
