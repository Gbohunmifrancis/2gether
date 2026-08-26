using Microsoft.EntityFrameworkCore;
using Twogether.Application.Common.Interfaces;
using Twogether.Core.Entities;

namespace Twogether.Infrastructure.Persistence;

public sealed class ApplicationDbContext(DbContextOptions<ApplicationDbContext> options)
    : DbContext(options), IApplicationDbContext
{
    public DbSet<User> Users => Set<User>();
    public DbSet<Couple> Couples => Set<Couple>();
    public DbSet<PartnerInvite> PartnerInvites => Set<PartnerInvite>();
    public DbSet<CycleProfile> CycleProfiles => Set<CycleProfile>();
    public DbSet<CycleDayLog> CycleDayLogs => Set<CycleDayLog>();
    public DbSet<GameSession> GameSessions => Set<GameSession>();
    public DbSet<GameResult> GameResults => Set<GameResult>();
    public DbSet<Message> Messages => Set<Message>();
    public DbSet<Notification> Notifications => Set<Notification>();
    public DbSet<CoupleLocation> CoupleLocations => Set<CoupleLocation>();

    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        modelBuilder.ApplyConfigurationsFromAssembly(typeof(ApplicationDbContext).Assembly);
        base.OnModelCreating(modelBuilder);
    }
}
