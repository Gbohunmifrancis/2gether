using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.SignalR;
using Microsoft.EntityFrameworkCore;
using Twogether.Api.Common;
using Twogether.Api.Hubs;
using Twogether.Api.Realtime;
using Twogether.Application.Common.Interfaces;
using Twogether.Application.Features.Location;
using Twogether.Application.Features.Notifications;
using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Shared.Localization;

namespace Twogether.Api.Controllers;

/// <summary>
/// Mutual location sharing. Positions are pushed through the couple hub; this controller
/// serves the snapshot, the sharing status both partners can always see, start and stop,
/// the sharing phone's heartbeat, and pause/resume.
/// </summary>
[Authorize]
public sealed class LocationController(IApplicationDbContext db, ICurrentUser currentUser, IDateTimeProvider clock, IHubContext<CoupleHub> hub, LocationSharingStopper stopper) : ApiControllerBase
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
        if (context is null) return Ok(await UnlinkedStatusAsync(cancellationToken));
        return Ok(StatusOf(context, clock.UtcNow));
    }

    /// <summary>
    /// Starts sharing (the person tapped Share), or confirms a share that is already running.
    /// Starting is always this explicit call: a fix on a stopped share is dropped. Ends a pause.
    /// </summary>
    [HttpPost("start")]
    public async Task<ActionResult<LocationSharingStatusDto>> Start(CancellationToken cancellationToken)
    {
        var context = await LoadAsync(cancellationToken, track: true);
        if (context is null) return BadRequest(new ApiError("location.no_couple", Loc.T("Link your partner before sharing location.")));
        var now = clock.UtcNow;
        var mine = context.Mine;
        var wasSharing = LocationSharingRules.StatusOf(mine, now) == LocationSharingStatus.Sharing;
        // Ending a pause or confirming a running share is not a new start: only a share that was off tells the partner.
        var wasOff = mine is null || !mine.IsLiveAt(now);
        if (mine is null)
        {
            mine = CoupleLocation.Start(currentUser.CoupleId!.Value, context.Me.Id, now);
            db.CoupleLocations.Add(mine);
            context = context with { Mine = mine };
        }
        else mine.StartSharing(now);
        var notice = wasOff ? await SharedNoticeAsync(context, mine.CoupleId, now, cancellationToken) : null;
        if (notice is not null) db.Notifications.Add(notice);
        await db.SaveChangesAsync(cancellationToken);
        if (!wasSharing) await AnnounceAsync(context, now, cancellationToken);
        if (notice is not null) await ExerciseEvents.NotificationAsync(hub, notice, cancellationToken);
        return Ok(StatusOf(context, now));
    }

    /// <summary>
    /// The sharing phone is still there, moving or not; it calls this every few minutes. Renews
    /// the lease, ends a pause whose time is up (so both phones see each other again without
    /// waiting for a fix), and answers with the status: a phone told "off" stops sharing.
    /// </summary>
    [HttpPost("heartbeat")]
    public async Task<ActionResult<LocationSharingStatusDto>> Heartbeat(CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        var context = await LoadAsync(cancellationToken, track: true);
        if (context is null) return Ok(await UnlinkedStatusAsync(cancellationToken));
        var now = clock.UtcNow;
        if (context.Mine is not { SharingEnabled: true } mine) return Ok(StatusOf(context, now));
        var wasLive = mine.IsLiveAt(now);
        var pauseEnded = mine.PausedUntilUtc is { } until && until <= now;
        if (pauseEnded) mine.Resume(now);
        mine.Heartbeat(now);
        await db.SaveChangesAsync(cancellationToken);
        if (!wasLive || pauseEnded) await AnnounceAsync(context, now, cancellationToken);
        return Ok(StatusOf(context, now));
    }

    /// <summary>Stops sharing and wipes my point. Idempotent, so the app retries it until it lands.</summary>
    [HttpPost("stop")]
    public async Task<IActionResult> Stop(CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        await stopper.StopAsync(currentUser.UserId.Value, cancellationToken);
        return NoContent();
    }

    [HttpPost("pause")]
    public async Task<ActionResult<LocationSharingStatusDto>> Pause(PauseLocationRequest request, CancellationToken cancellationToken)
    {
        var context = await LoadAsync(cancellationToken, track: true);
        if (context is null) return BadRequest(new ApiError("location.no_couple", Loc.T("Link your partner before sharing location.")));
        var now = clock.UtcNow;
        var until = LocationSharingRules.PauseUntil(request.Minutes, now);
        if (until.IsFailure) return UnprocessableEntity(new ApiError(until.Error.Code, until.Error.Message));
        if (context.Mine is null || !context.Mine.IsLiveAt(now)) return Conflict(new ApiError("location.not_sharing", Loc.T("You are not sharing your location.")));

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
        await LocationEvents.SharingChangedAsync(hub, context.Mine.CoupleId, LocationSharingRules.State(context.Me.Id, context.Me.DisplayName, context.Mine, now, withLastFix: false), cancellationToken);
        return Ok(StatusOf(context, now));
    }

    [HttpPost("resume")]
    public async Task<ActionResult<LocationSharingStatusDto>> Resume(CancellationToken cancellationToken)
    {
        var context = await LoadAsync(cancellationToken, track: true);
        if (context is null) return BadRequest(new ApiError("location.no_couple", Loc.T("Link your partner before sharing location.")));
        var now = clock.UtcNow;
        if (context.Mine is null || !context.Mine.IsLiveAt(now)) return Conflict(new ApiError("location.not_sharing", Loc.T("You are not sharing your location.")));
        context.Mine.Resume(now);
        await db.SaveChangesAsync(cancellationToken);
        await AnnounceAsync(context, now, cancellationToken);
        return Ok(StatusOf(context, now));
    }

    /// <summary>A point as the API serves it: its time is when the phone took the fix, not when the row last changed.</summary>
    public static LocationDto ToDto(CoupleLocation location, User user, bool isCurrentUser)
        => new(user.Id, user.DisplayName, user.AvatarUrl, user.MapColor, location.Latitude!.Value, location.Longitude!.Value, location.AccuracyMeters ?? 0, location.FixAtUtc!.Value, isCurrentUser);

    /// <summary>My indicator changed and we may see each other again: tell the couple, and swap points if so.</summary>
    private async Task AnnounceAsync(SharingContext context, DateTime now, CancellationToken cancellationToken)
    {
        var mutual = LocationSharingRules.CanSee(context.Mine, context.Theirs, now);
        // The indicator first: the app shows points only while its own share reads "sharing".
        await LocationEvents.SharingChangedAsync(hub, context.Mine!.CoupleId, LocationSharingRules.State(context.Me.Id, context.Me.DisplayName, context.Mine, now, withLastFix: mutual), cancellationToken);
        if (mutual && context.Partner is { } partner)
            await LocationEvents.ExchangeAsync(hub.Clients, context.Mine, context.Me, context.Theirs!, partner, cancellationToken);
    }

    /// <summary>How long a partner isn't told again that the same person started sharing.</summary>
    public static readonly TimeSpan SharedNoticeQuiet = TimeSpan.FromHours(12);

    /// <summary>
    /// "Maya is sharing their location", to the partner, so they can share theirs too: sharing is mutual, so this is an
    /// invitation, never a report. Not again within 12 hours (stop and start again, a lapsed phone), read back from the
    /// notifications. Written in the partner's language. Not added.
    /// </summary>
    private async Task<Notification?> SharedNoticeAsync(SharingContext context, Guid coupleId, DateTime now, CancellationToken cancellationToken)
    {
        if (context.Partner is not { } partner) return null;
        var since = now - SharedNoticeQuiet;
        if (await db.Notifications.AsNoTracking().AnyAsync(item => item.CoupleId == coupleId && item.UserId == partner.Id
                && item.Type == NotificationType.LocationShared && item.CreatedAtUtc >= since, cancellationToken))
            return null;
        using var _ = Loc.Use(await db.LanguageOfAsync(partner.Id, cancellationToken));
        return Notification.Create(partner.Id, coupleId, NotificationType.LocationShared,
            Loc.T("{name} is sharing their location", ("name", context.Me.DisplayName)),
            Loc.T("Open the map to see each other. Share yours too, and you can both pause any time."),
            System.Text.Json.JsonSerializer.Serialize(new { userId = context.Me.Id }), now);
    }

    private async Task<LocationSharingStatusDto> UnlinkedStatusAsync(CancellationToken cancellationToken)
    {
        var userId = currentUser.UserId!.Value;
        var name = await db.Users.AsNoTracking().Where(user => user.Id == userId).Select(user => user.DisplayName).SingleOrDefaultAsync(cancellationToken) ?? "You";
        return new LocationSharingStatusDto(new LocationSharingStateDto(userId, name, LocationSharingStatus.Off, null, null), null, false);
    }

    private static LocationSharingStatusDto StatusOf(SharingContext context, DateTime now)
        => LocationSharingRules.Status(context.Me.Id, context.Me.DisplayName, context.Mine, context.Partner?.Id, context.Partner?.DisplayName, context.Theirs, now);

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
