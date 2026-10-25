using Microsoft.EntityFrameworkCore;
using Twogether.Application.Common.Interfaces;

namespace Twogether.Application.Features.Location;

/// <summary>
/// How long location rows live. Only the latest point is ever stored; these remove it once it
/// no longer serves anyone: when the couple ends, and when nothing has touched it for a week.
/// </summary>
public static class LocationRetention
{
    public static readonly TimeSpan Unused = TimeSpan.FromDays(7);

    /// <summary>A breakup: both partners' rows go, coordinates and all.</summary>
    public static Task<int> DeleteCoupleAsync(IApplicationDbContext db, Guid coupleId, CancellationToken cancellationToken)
        => db.CoupleLocations.Where(item => item.CoupleId == coupleId).ExecuteDeleteAsync(cancellationToken);

    /// <summary>Rows not updated for <see cref="Unused"/>: a share stopped long ago, or a phone that went quiet.</summary>
    public static Task<int> DeleteUnusedAsync(IApplicationDbContext db, DateTime utcNow, CancellationToken cancellationToken)
    {
        var before = utcNow - Unused;
        return db.CoupleLocations.Where(item => item.UpdatedAtUtc < before).ExecuteDeleteAsync(cancellationToken);
    }
}
