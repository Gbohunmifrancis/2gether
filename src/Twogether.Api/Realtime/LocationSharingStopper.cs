using Microsoft.AspNetCore.SignalR;
using Microsoft.EntityFrameworkCore;
using Twogether.Api.Hubs;
using Twogether.Application.Common.Interfaces;
using Twogether.Application.Features.Location;
using Twogether.Core.Enums;

namespace Twogether.Api.Realtime;

/// <summary>
/// Ends a person's sharing, wipes their point and tells both partners. Shared by the app's
/// Stop (REST, retried until it lands), the hub's StopLocationSharing and a password reset.
/// Idempotent: stopping what is already stopped changes nothing and sends nothing.
/// </summary>
public sealed class LocationSharingStopper(IApplicationDbContext db, IHubContext<CoupleHub> hub, IDateTimeProvider clock)
{
    public async Task StopAsync(Guid userId, CancellationToken cancellationToken)
    {
        var rows = await db.CoupleLocations.Where(item => item.UserId == userId && (item.SharingEnabled || item.Latitude != null)).ToListAsync(cancellationToken);
        if (rows.Count == 0) return;
        var now = clock.UtcNow;
        foreach (var row in rows) row.StopSharing(now);
        await db.SaveChangesAsync(cancellationToken);

        var name = await db.Users.AsNoTracking().Where(item => item.Id == userId).Select(item => item.DisplayName).SingleOrDefaultAsync(cancellationToken) ?? string.Empty;
        foreach (var row in rows)
        {
            var couple = await db.Couples.AsNoTracking().SingleOrDefaultAsync(item => item.Id == row.CoupleId && item.Status == CoupleStatus.Active, cancellationToken);
            if (couple is null) continue;
            var partnerId = couple.UserAId == userId ? couple.UserBId : couple.UserAId;
            await LocationEvents.HiddenAsync(hub.Clients, row.CoupleId, userId, partnerId, LocationSharingRules.State(userId, name, row, now, withLastFix: false), cancellationToken);
        }
    }
}
