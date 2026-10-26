using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Http.Features;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using Twogether.Api.Admin;
using Twogether.Api.Common;
using Twogether.Api.Presence;
using Twogether.Application.Common.Interfaces;
using Twogether.Application.Features.Admin;
using Twogether.Application.Features.Admin.Live;
using Twogether.Application.Features.Admin.Ops;
using Twogether.Application.Features.Admin.People;

namespace Twogether.Api.Controllers.Admin;

/// <summary>What is happening right now: the pulse, the live event stream, and the alerts.</summary>
[Authorize(Policy = AdminAuth.Policy)]
[Route("api/admin")]
public sealed class AdminLiveController(
    AdminLiveFeed feed,
    AdminAlerts alerts,
    HubConnectionRegistry connections,
    IServiceScopeFactory scopes,
    IDateTimeProvider clock,
    AdminSettings settings,
    IOptions<JsonOptions> json,
    ILogger<AdminLiveController> logger) : ApiControllerBase
{
    /// <summary>The stream starts a minute back, so nothing between loading the pulse and connecting is lost; the client drops repeats by id.</summary>
    public static readonly TimeSpan StreamLookBack = TimeSpan.FromMinutes(1);

    [HttpGet("analytics/pulse")]
    public async Task<ActionResult<PulseDto>> Pulse(CancellationToken ct)
    {
        // Evaluated first so an alert raised just now is among the recent events.
        await alerts.CurrentAsync(ct);
        return Ok(await feed.PulseAsync(connections.ConnectedUserIds, ct));
    }

    [HttpGet("alerts")]
    public async Task<ActionResult<IReadOnlyList<AlertDto>>> Alerts(CancellationToken ct) => Ok(await alerts.CurrentAsync(ct));

    /// <summary>
    /// Server-Sent Events on an ordinary authenticated GET (read with fetch streaming). Polls the database
    /// every few seconds; ends when the admin token expires, the client goes, or the admin is no longer one.
    /// </summary>
    [HttpGet("analytics/live/events")]
    public async Task Events(CancellationToken ct)
    {
        var now = clock.UtcNow;
        var expires = long.TryParse(User.FindFirst("exp")?.Value, out var exp) ? DateTimeOffset.FromUnixTimeSeconds(exp).UtcDateTime : now + settings.TokenLifetime;
        var issued = long.TryParse(User.FindFirst("nbf")?.Value, out var nbf) ? DateTimeOffset.FromUnixTimeSeconds(nbf).UtcDateTime : now;
        var adminId = Guid.Parse(User.FindFirst(System.Security.Claims.ClaimTypes.NameIdentifier)!.Value);
        if (expires <= now)
        {
            Response.StatusCode = StatusCodes.Status401Unauthorized;
            return;
        }

        Response.StatusCode = StatusCodes.Status200OK;
        Response.ContentType = "text/event-stream";
        Response.Headers.CacheControl = "no-cache, no-store";
        // Proxies (nginx and friends) must pass each event on at once.
        Response.Headers["X-Accel-Buffering"] = "no";
        HttpContext.Features.Get<IHttpResponseBodyFeature>()?.DisableBuffering();

        using var session = CancellationTokenSource.CreateLinkedTokenSource(ct);
        session.CancelAfter(expires - now);
        await LiveStream.RunAsync(
            Response.Body,
            new LiveCursor(now - StreamLookBack),
            async (since, token) =>
            {
                // A scope per poll: the stream can last hours, a DbContext shouldn't.
                await using var scope = scopes.CreateAsyncScope();
                await scope.ServiceProvider.GetRequiredService<AdminAlerts>().CurrentAsync(token);
                return await scope.ServiceProvider.GetRequiredService<AdminLiveFeed>().SinceAsync(since, LiveStream.BatchLimit, token);
            },
            token => StillAdminAsync(adminId, issued, token),
            () => clock.UtcNow,
            json.Value.JsonSerializerOptions,
            LiveStreamTiming.Default,
            exception => logger.LogWarning(exception, "Admin live stream poll failed; retrying."),
            session.Token);
    }

    /// <summary>The same checks as every admin request, repeated while the stream is open.</summary>
    private async Task<bool> StillAdminAsync(Guid adminId, DateTime issuedUtc, CancellationToken ct)
    {
        await using var scope = scopes.CreateAsyncScope();
        var db = scope.ServiceProvider.GetRequiredService<IApplicationDbContext>();
        var current = await db.Users.AsNoTracking().Where(user => user.Id == adminId)
            .Select(user => new { user.Email, user.DisabledAtUtc, user.SessionsValidFromUtc }).SingleOrDefaultAsync(ct);
        return current is not null && current.DisabledAtUtc is null && settings.IsAdmin(current.Email)
               && !(current.SessionsValidFromUtc is { } validFrom && issuedUtc < validFrom);
    }
}

/// <summary>Probes, workers, queues and costs.</summary>
[Authorize(Policy = AdminAuth.Policy)]
[Route("api/admin/system")]
public sealed class AdminHealthController(AdminHealth health) : ApiControllerBase
{
    [HttpGet("health")]
    public async Task<ActionResult<HealthDto>> Health([FromQuery] int days = 30, CancellationToken ct = default) => Ok(await health.GetAsync(days, ct));
}

/// <summary>A person's journey through the app, and marking test and team accounts as internal.</summary>
[Authorize(Policy = AdminAuth.Policy)]
[Route("api/admin/users")]
public sealed class AdminPeopleController(IApplicationDbContext db, IDateTimeProvider clock, AdminAudit audit, AdminJourney journey) : ApiControllerBase
{
    /// <summary>Internal accounts work as usual but are left out of every analytics figure.</summary>
    [HttpPost("{userId:guid}/internal")]
    [RequireStepUp]
    public async Task<IActionResult> Internal(Guid userId, AdminInternalRequest request, CancellationToken ct)
    {
        if (string.IsNullOrWhiteSpace(request.Reason)) return UnprocessableEntity(new ApiError("admin.reason_required", "Say why, for the record."));
        var user = await db.Users.SingleOrDefaultAsync(item => item.Id == userId, ct);
        if (user is null) return NotFound();
        user.MarkInternal(request.Internal, clock.UtcNow);
        audit.Record(User, request.Internal ? "user.marked_internal" : "user.unmarked_internal", "user", userId.ToString(), request.Reason.Trim());
        await db.SaveChangesAsync(ct);
        return NoContent();
    }

    [HttpGet("{userId:guid}/journey")]
    public async Task<ActionResult<JourneyDto>> Journey(Guid userId, [FromQuery] int days = 90, CancellationToken ct = default)
        => await journey.GetAsync(userId, days, ct) is { } result ? Ok(result) : NotFound();
}

public sealed record AdminInternalRequest(bool Internal, string? Reason);
