using System.Security.Claims;
using Microsoft.AspNetCore.SignalR;
using Microsoft.EntityFrameworkCore;
using Twogether.Api.Auth;
using Twogether.Application.Common.Interfaces;

namespace Twogether.Api.Hubs;

/// <summary>
/// A hub connection keeps the claims and groups it had when it connected, which can be days
/// old. If the account is gone or its couple has changed since (breakup, deletion, new partner),
/// the connection is closed before the call runs. The client reconnects and gets the current
/// couple's groups; nothing is ever done against the old couple.
/// </summary>
public sealed class CoupleMembershipHubFilter(ILogger<CoupleMembershipHubFilter> logger) : IHubFilter
{
    public async ValueTask<object?> InvokeMethodAsync(HubInvocationContext invocationContext, Func<HubInvocationContext, ValueTask<object?>> next)
    {
        var context = invocationContext.Context;
        if (ClaimGuid(context.User, ClaimTypes.NameIdentifier) is { } userId)
        {
            var db = invocationContext.ServiceProvider.GetRequiredService<IApplicationDbContext>();
            var stored = await db.Users.AsNoTracking()
                .Where(user => user.Id == userId)
                .Select(user => new { user.CoupleId })
                .SingleOrDefaultAsync(context.ConnectionAborted);
            if (stored is null || stored.CoupleId != ClaimGuid(context.User, JwtTokenService.CoupleClaim))
            {
                logger.LogInformation("Closing hub connection {ConnectionId}: the account or its couple changed since it connected.", context.ConnectionId);
                context.Abort();
                throw new HubException("Your couple changed. Reconnect to continue.");
            }
        }
        return await next(invocationContext);
    }

    private static Guid? ClaimGuid(ClaimsPrincipal? user, string type)
        => Guid.TryParse(user?.FindFirst(type)?.Value, out var value) ? value : null;
}
