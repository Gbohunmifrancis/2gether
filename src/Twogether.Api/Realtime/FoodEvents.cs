using Microsoft.AspNetCore.SignalR;
using Twogether.Api.Hubs;
using Twogether.Application.Features.Food;
using Twogether.Core.Enums;

namespace Twogether.Api.Realtime;

public static class FoodEvents
{
    public const string DayUpdated = "FoodDayUpdated";

    /// <summary>Tells the partner a day's totals moved. Nothing is sent when sharing is off.</summary>
    public static Task DayUpdatedAsync(IHubContext<CoupleHub> hub, Guid coupleId, FoodDayUpdatedDto payload, CancellationToken cancellationToken)
        => payload.ShareLevel == FoodShareLevel.None
            ? Task.CompletedTask
            : hub.Clients.Group(CoupleHub.GroupName(coupleId)).SendAsync(DayUpdated, payload, cancellationToken);
}
