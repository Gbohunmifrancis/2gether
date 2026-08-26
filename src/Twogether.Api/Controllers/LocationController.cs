using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using Twogether.Api.Realtime;
using Twogether.Application.Common.Interfaces;
using Twogether.Core.Enums;

namespace Twogether.Api.Controllers;

[Authorize]
public sealed class LocationController(IApplicationDbContext db, ICurrentUser currentUser) : Common.ApiControllerBase
{
    [HttpGet]
    public async Task<ActionResult<IReadOnlyList<LocationDto>>> List(CancellationToken cancellationToken)
    {
        if (!currentUser.CoupleId.HasValue || !currentUser.UserId.HasValue) return Ok(Array.Empty<LocationDto>());
        var linked = await db.Couples.AsNoTracking().AnyAsync(item => item.Id == currentUser.CoupleId.Value && item.Status == CoupleStatus.Active, cancellationToken);
        if (!linked) return Ok(Array.Empty<LocationDto>());
        var locations = await db.CoupleLocations.AsNoTracking()
            .Where(item => item.CoupleId == currentUser.CoupleId.Value && item.SharingEnabled)
            .Join(db.Users.AsNoTracking(), location => location.UserId, user => user.Id, (location, user) => new LocationDto(
                user.Id, user.DisplayName, user.AvatarUrl, user.MapColor, location.Latitude, location.Longitude, location.AccuracyMeters, location.UpdatedAtUtc, user.Id == currentUser.UserId.Value))
            .ToListAsync(cancellationToken);
        return Ok(locations);
    }
}
