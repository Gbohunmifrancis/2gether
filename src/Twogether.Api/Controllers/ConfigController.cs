using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Twogether.Api.Common;
using Twogether.Application.Features.Config;

namespace Twogether.Api.Controllers;

/// <summary>What an app needs to know before anyone signs in. Public, so nothing personal ever goes here.</summary>
public sealed class ConfigController(FeatureFlagService flags) : ApiControllerBase
{
    /// <summary>
    /// <c>{ "christmas": bool }</c>. Cacheable for 60 seconds by the app's HTTP client and by this server's response
    /// cache, so a start-up costs the database one read a minute at most, however many apps start. Whether the app may
    /// offer card payment is per person (country), so it comes with the entitlement instead (<c>cardInApp</c>).
    /// </summary>
    [AllowAnonymous]
    [HttpGet]
    [ResponseCache(Duration = 60, Location = ResponseCacheLocation.Any)]
    public async Task<ActionResult<PublicConfigDto>> Get(CancellationToken cancellationToken) => Ok(await flags.GetPublicAsync(cancellationToken));
}
