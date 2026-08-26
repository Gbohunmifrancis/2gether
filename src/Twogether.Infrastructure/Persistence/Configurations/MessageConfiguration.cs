using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using Twogether.Core.Entities;

namespace Twogether.Infrastructure.Persistence.Configurations;

public sealed class MessageConfiguration : IEntityTypeConfiguration<Message>
{
    public void Configure(EntityTypeBuilder<Message> builder)
    {
        builder.ToTable("messages");
        builder.HasKey(message => message.Id);
        builder.Property(message => message.Body).HasMaxLength(4000).IsRequired();
        builder.Property(message => message.IsPrivate).HasDefaultValue(false).IsRequired();
        builder.HasIndex(message => new { message.CoupleId, message.CreatedAtUtc });
    }
}
