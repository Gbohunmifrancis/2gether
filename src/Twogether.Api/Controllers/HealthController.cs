using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using Twogether.Api.Common;
using Twogether.Infrastructure.Persistence;

namespace Twogether.Api.Controllers;

public sealed class HealthController(ApplicationDbContext db, ILogger<HealthController> logger) : ApiControllerBase
{
    /// <summary>Liveness: the process answers. Never touches the database, so a slow database does not get it restarted.</summary>
    [AllowAnonymous]
    [HttpGet]
    public IActionResult Get() => Ok(new { status = "ok", service = "twogether-api", version = AppVersion.Current });

    /// <summary>
    /// Readiness: point the platform's deploy health check here. 503 while the database is out of
    /// reach, so a new deploy is not given traffic it cannot serve. Says nothing about why.
    /// </summary>
    [AllowAnonymous]
    [HttpGet("ready")]
    public async Task<IActionResult> Ready(CancellationToken cancellationToken)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(TimeSpan.FromSeconds(3));
        try
        {
            if (await db.Database.CanConnectAsync(timeout.Token)) return Ok(new { status = "ready" });
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            logger.LogWarning("Readiness check: the database did not answer within 3 seconds.");
        }
        return StatusCode(StatusCodes.Status503ServiceUnavailable, new { status = "unavailable" });
    }
}
