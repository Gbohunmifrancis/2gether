using System.Security.Claims;
using Twogether.Application.Common.Interfaces;

namespace Twogether.Api.Auth;

public sealed class CurrentUser(IHttpContextAccessor accessor) : ICurrentUser
{
    private ClaimsPrincipal? Principal => accessor.HttpContext?.User;

    public Guid? UserId => ParseClaim(ClaimTypes.NameIdentifier) ?? ParseClaim("sub");
    public Guid? CoupleId => ParseClaim("couple_id");
    public bool IsAuthenticated => Principal?.Identity?.IsAuthenticated == true;

    private Guid? ParseClaim(string type)
        => Guid.TryParse(Principal?.FindFirstValue(type), out var value) ? value : null;
}
