using Microsoft.EntityFrameworkCore;
using Twogether.Core.Entities;

namespace Twogether.Application.Common.Interfaces;

public interface IApplicationDbContext
{
    DbSet<User> Users { get; }
    DbSet<Couple> Couples { get; }
    DbSet<PartnerInvite> PartnerInvites { get; }
    DbSet<CycleProfile> CycleProfiles { get; }
    DbSet<CycleDayLog> CycleDayLogs { get; }
    DbSet<GameSession> GameSessions { get; }
    DbSet<GameResult> GameResults { get; }
    DbSet<Message> Messages { get; }
    DbSet<Notification> Notifications { get; }
    DbSet<CoupleLocation> CoupleLocations { get; }
    Task<int> SaveChangesAsync(CancellationToken cancellationToken = default);
}
