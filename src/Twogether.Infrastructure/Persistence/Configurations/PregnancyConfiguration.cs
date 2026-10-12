using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using Twogether.Core.Entities;

namespace Twogether.Infrastructure.Persistence.Configurations;

public sealed class PregnancyConfiguration : IEntityTypeConfiguration<Pregnancy>
{
    public void Configure(EntityTypeBuilder<Pregnancy> builder)
    {
        builder.ToTable("pregnancies");
        builder.HasKey(item => item.Id);
        builder.Property(item => item.Status).HasConversion<string>().HasMaxLength(20).IsRequired();
        // At most one active pregnancy per person, whatever two requests race to create.
        builder.HasIndex(item => item.UserId).IsUnique().HasFilter("\"Status\" = 'Active'");
        builder.HasIndex(item => new { item.CoupleId, item.Status });
    }
}

public sealed class PregnancyAppointmentConfiguration : IEntityTypeConfiguration<PregnancyAppointment>
{
    public void Configure(EntityTypeBuilder<PregnancyAppointment> builder)
    {
        builder.ToTable("pregnancy_appointments");
        builder.HasKey(item => item.Id);
        builder.Property(item => item.Title).HasMaxLength(PregnancyAppointment.MaxTitleLength).IsRequired();
        builder.Property(item => item.Location).HasMaxLength(PregnancyAppointment.MaxLocationLength);
        builder.Property(item => item.Notes).HasMaxLength(PregnancyAppointment.MaxNotesLength);
        // Deleting a pregnancy deletes everything recorded under it, in the database itself.
        builder.HasOne<Pregnancy>().WithMany().HasForeignKey(item => item.PregnancyId).OnDelete(DeleteBehavior.Cascade);
        builder.HasIndex(item => new { item.PregnancyId, item.AtUtc });
    }
}

public sealed class KickSessionConfiguration : IEntityTypeConfiguration<KickSession>
{
    public void Configure(EntityTypeBuilder<KickSession> builder)
    {
        builder.ToTable("kick_sessions", table => table.HasCheckConstraint("CK_kick_sessions_Count", "\"Count\" BETWEEN 0 AND 200"));
        builder.HasKey(item => item.Id);
        builder.Ignore(item => item.IsOpen);
        builder.HasOne<Pregnancy>().WithMany().HasForeignKey(item => item.PregnancyId).OnDelete(DeleteBehavior.Cascade);
        builder.HasIndex(item => new { item.PregnancyId, item.StartedAtUtc });
    }
}
