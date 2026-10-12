using Microsoft.AspNetCore.SignalR;
using Twogether.Api.Hubs;
using Twogether.Application.Features.Location;

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
        => hub.Clients.Group(CoupleHub.GroupName(coupleId)).SendAsync(SharingChanged, state, cancellationToken);
}
