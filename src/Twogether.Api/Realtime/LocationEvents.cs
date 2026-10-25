using Microsoft.AspNetCore.SignalR;
using Twogether.Api.Controllers;
using Twogether.Api.Hubs;
using Twogether.Application.Features.Location;
using Twogether.Core.Entities;

namespace Twogether.Api.Realtime;

public static class LocationEvents
{
    /// <summary>A position the receiver is allowed to see. Same name the existing frontend handles.</summary>
    public const string Updated = "LocationUpdated";
    /// <summary>Drop this user id from the map. Same name the existing frontend handles.</summary>
    public const string Stopped = "LocationStopped";
    /// <summary>Sharing state without coordinates; drives the "sharing / paused / off" indicator.</summary>
    public const string SharingChanged = "LocationSharingChanged";

    public static Task SharingChangedAsync(IHubContext<CoupleHub> hub, Guid coupleId, LocationSharingStateDto state, CancellationToken cancellationToken)
        => SharingChangedAsync(hub.Clients, coupleId, state, cancellationToken);

    public static Task SharingChangedAsync(IHubClients<IClientProxy> clients, Guid coupleId, LocationSharingStateDto state, CancellationToken cancellationToken)
        => clients.Group(CoupleHub.GroupName(coupleId)).SendAsync(SharingChanged, state, cancellationToken);

    /// <summary>Visible to each other again (a start, a resume, the end of a pause): each partner's phones get the other's point.</summary>
    public static async Task ExchangeAsync(IHubClients<IClientProxy> clients, CoupleLocation mine, User me, CoupleLocation theirs, User partner, CancellationToken cancellationToken)
    {
        await clients.Group(CoupleHub.UserGroupName(partner.Id)).SendAsync(Updated, LocationController.ToDto(mine, me, false), cancellationToken);
        await clients.Group(CoupleHub.UserGroupName(me.Id)).SendAsync(Updated, LocationController.ToDto(theirs, partner, false), cancellationToken);
    }

    /// <summary>
    /// The two can no longer see each other: every phone in the couple drops this person, this
    /// person's phones drop the partner, and the indicator updates.
    /// </summary>
    public static async Task HiddenAsync(IHubClients<IClientProxy> clients, Guid coupleId, Guid userId, Guid? partnerId, LocationSharingStateDto state, CancellationToken cancellationToken)
    {
        await clients.Group(CoupleHub.GroupName(coupleId)).SendAsync(Stopped, userId, cancellationToken);
        if (partnerId is { } partner) await clients.Group(CoupleHub.UserGroupName(userId)).SendAsync(Stopped, partner, cancellationToken);
        await SharingChangedAsync(clients, coupleId, state, cancellationToken);
    }
}
