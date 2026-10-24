using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.SignalR;
using Microsoft.EntityFrameworkCore;
using Twogether.Api.Common;
using Twogether.Api.Hubs;
using Twogether.Api.Realtime;
using Twogether.Application.Common.Interfaces;
using Twogether.Application.Features.Location;
using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Shared.Localization;

namespace Twogether.Api.Controllers;

/// <summary>
/// Mutual location sharing. Positions are pushed through the couple hub; this controller
/// serves the snapshot, the sharing status both partners can always see, and pause/resume.
/// </summary>
[Authorize]
public sealed class LocationController(IApplicationDbContext db, ICurrentUser currentUser, IDateTimeProvider clock, IHubContext<CoupleHub> hub) : ApiControllerBase
{
    /// <summary>My own point, and my partner's only while we both share. Empty for unlinked users.</summary>
    [HttpGet]
    public async Task<ActionResult<IReadOnlyList<LocationDto>>> List(CancellationToken cancellationToken)
    {
        var context = await LoadAsync(cancellationToken);
        if (context is null) return Ok(Array.Empty<LocationDto>());
        var now = clock.UtcNow;
        var result = new List<LocationDto>();
        if (context.Mine is { } mine && mine.IsVisibleAt(now)) result.Add(ToDto(mine, context.Me, true));
        if (context.Partner is { } partner && LocationSharingRules.CanSee(context.Mine, context.Theirs, now))
            result.Add(ToDto(context.Theirs!, partner, false));
        return Ok(result);
    }

    [HttpGet("status")]
    public async Task<ActionResult<LocationSharingStatusDto>> Status(CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        var context = await LoadAsync(cancellationToken);
        var now = clock.UtcNow;
        if (context is null)
        {
            var name = await db.Users.AsNoTracking().Where(user => user.Id == currentUser.UserId.Value).Select(user => user.DisplayName).SingleOrDefaultAsync(cancellationToken) ?? "You";
            return Ok(new LocationSharingStatusDto(new LocationSharingStateDto(currentUser.UserId.Value, name, LocationSharingStatus.Off, null, null), null, false));
        }
        return Ok(StatusOf(context, now));
    }

    [HttpPost("pause")]
    public async Task<ActionResult<LocationSharingStatusDto>> Pause(PauseLocationRequest request, CancellationToken cancellationToken)
    {
        var context = await LoadAsync(cancellationToken, track: true);
        if (context is null) return BadRequest(new ApiError("location.no_couple", Loc.T("Link your partner before sharing location.")));
        var now = clock.UtcNow;
        var until = LocationSharingRules.PauseUntil(request.Minutes, now);
        if (until.IsFailure) return UnprocessableEntity(new ApiError(until.Error.Code, until.Error.Message));
        if (context.Mine is null || !context.Mine.SharingEnabled) return Conflict(new ApiError("location.not_sharing", Loc.T("You are not sharing your location.")));

        var couldSeeEachOther = LocationSharingRules.CanSee(context.Mine, context.Theirs, now);
        context.Mine.Pause(until.Value, now);
        await db.SaveChangesAsync(cancellationToken);

        // Existing clients drop a person from the map on LocationStopped; send it both ways so
        // neither side keeps a position the mutual rule no longer allows.
        if (couldSeeEachOther && context.Partner is { } partner)
        {
            await hub.Clients.Group(CoupleHub.UserGroupName(partner.Id)).SendAsync(LocationEvents.Stopped, context.Me.Id, cancellationToken);
            await hub.Clients.Group(CoupleHub.UserGroupName(context.Me.Id)).SendAsync(LocationEvents.Stopped, partner.Id, cancellationToken);
        }
        await LocationEvents.SharingChangedAsync(hub, context.Mine.CoupleId, LocationSharingRules.State(context.Me.Id, context.Me.DisplayName, context.Mine, now), cancellationToken);
        return Ok(StatusOf(context, now));
    }

    [HttpPost("resume")]
    public async Task<ActionResult<LocationSharingStatusDto>> Resume(CancellationToken cancellationToken)
    {
        var context = await LoadAsync(cancellationToken, track: true);
        if (context is null) return BadRequest(new ApiError("location.no_couple", Loc.T("Link your partner before sharing location.")));
        if (context.Mine is null || !context.Mine.SharingEnabled) return Conflict(new ApiError("location.not_sharing", Loc.T("You are not sharing your location.")));
        var now = clock.UtcNow;
        context.Mine.Resume(now);
        await db.SaveChangesAsync(cancellationToken);

        if (context.Partner is { } partner && LocationSharingRules.CanSee(context.Mine, context.Theirs, now))
        {
            await hub.Clients.Group(CoupleHub.UserGroupName(partner.Id)).SendAsync(LocationEvents.Updated, ToDto(context.Mine, context.Me, false), cancellationToken);
            await hub.Clients.Group(CoupleHub.UserGroupName(context.Me.Id)).SendAsync(LocationEvents.Updated, ToDto(context.Theirs!, partner, false), cancellationToken);
        }
        await LocationEvents.SharingChangedAsync(hub, context.Mine.CoupleId, LocationSharingRules.State(context.Me.Id, context.Me.DisplayName, context.Mine, now), cancellationToken);
        return Ok(StatusOf(context, now));
    }

    public static LocationDto ToDto(CoupleLocation location, User user, bool isCurrentUser)
        => new(user.Id, user.DisplayName, user.AvatarUrl, user.MapColor, location.Latitude, location.Longitude, location.AccuracyMeters, location.UpdatedAtUtc, isCurrentUser);

    private static LocationSharingStatusDto StatusOf(SharingContext context, DateTime now)
    {
        var me = LocationSharingRules.State(context.Me.Id, context.Me.DisplayName, context.Mine, now);
        var partner = context.Partner is null ? null : LocationSharingRules.State(context.Partner.Id, context.Partner.DisplayName, context.Theirs, now);
        return new LocationSharingStatusDto(me, partner, LocationSharingRules.CanSee(context.Mine, context.Theirs, now));
    }

    private async Task<SharingContext?> LoadAsync(CancellationToken cancellationToken, bool track = false)
    {
        if (!currentUser.CoupleId.HasValue || !currentUser.UserId.HasValue) return null;
        var userId = currentUser.UserId.Value;
        var couple = await db.Couples.AsNoTracking().SingleOrDefaultAsync(item => item.Id == currentUser.CoupleId.Value && item.Status == CoupleStatus.Active, cancellationToken);
        if (couple is null || (couple.UserAId != userId && couple.UserBId != userId)) return null;
        var partnerId = couple.UserAId == userId ? couple.UserBId : couple.UserAId;
        var ids = partnerId is { } id ? new[] { userId, id } : [userId];
        var users = await db.Users.AsNoTracking().Where(user => ids.Contains(user.Id)).ToListAsync(cancellationToken);
        var rowsQuery = db.CoupleLocations.Where(item => item.CoupleId == couple.Id);
        var rows = await (track ? rowsQuery : rowsQuery.AsNoTracking()).ToListAsync(cancellationToken);
        var me = users.Single(user => user.Id == userId);
        var partner = users.SingleOrDefault(user => user.Id == partnerId);
        return new SharingContext(me, partner, rows.SingleOrDefault(row => row.UserId == userId), partner is null ? null : rows.SingleOrDefault(row => row.UserId == partner.Id));
    }

    private sealed record SharingContext(User Me, User? Partner, CoupleLocation? Mine, CoupleLocation? Theirs);
}
