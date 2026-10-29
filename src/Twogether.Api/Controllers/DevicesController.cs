using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using Npgsql;
using Twogether.Api.Common;
using Twogether.Application.Common.Interfaces;
using Twogether.Application.Features.Push;
using Twogether.Core.Entities;
using Twogether.Shared.Localization;

namespace Twogether.Api.Controllers;

/// <summary>Push token registration. A user only ever sees and manages their own devices.</summary>
[Authorize]
public sealed class DevicesController(IApplicationDbContext db, ICurrentUser currentUser, IDateTimeProvider clock, PushSettings settings, ILogger<DevicesController> logger) : ApiControllerBase
{
    [HttpGet]
    public async Task<ActionResult<IReadOnlyList<DeviceDto>>> List(CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        var devices = await db.DeviceRegistrations.AsNoTracking()
            .Where(item => item.UserId == currentUser.UserId.Value)
            .OrderByDescending(item => item.LastSeenAtUtc)
            .ToListAsync(cancellationToken);
        return Ok(devices.Select(ToDto).ToList());
    }

    /// <summary>Call on every app start and whenever the token rotates. Safe to repeat.</summary>
    [HttpPut]
    public async Task<ActionResult<DeviceDto>> Register(RegisterDeviceRequest request, CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        if (string.IsNullOrWhiteSpace(request.Token) || request.Token.Trim().Length > DeviceRegistration.MaxTokenLength)
            return UnprocessableEntity(new ApiError("devices.invalid_token", Loc.T("The push token is missing or too long.")));
        if (!Enum.IsDefined(request.Platform) || !Enum.IsDefined(request.TokenKind) || request.AppVersion?.Trim().Length > DeviceRegistration.MaxAppVersionLength)
            return UnprocessableEntity(new ApiError("devices.invalid_device", Loc.T("Unknown platform, token kind or app version.")));

        for (var attempt = 0; ; attempt++)
        {
            try
            {
                var device = await UpsertAsync(request, cancellationToken);
                return Ok(ToDto(device));
            }
            catch (DbUpdateException exception) when (attempt == 0 && exception.InnerException is PostgresException { SqlState: "23505" })
            {
                // The same new token registered twice at once: the other request inserted it, so update it instead.
                foreach (var entry in db.DeviceRegistrations.Local.ToList()) ((DbContext)db).Entry(entry).State = EntityState.Detached;
                logger.LogInformation("Concurrent registration of one push token; retrying as an update.");
            }
        }
    }

    /// <summary>Call on sign-out. Answers 204 whether or not the token was ours, so tokens cannot be probed.</summary>
    [HttpPost("unregister")]
    public async Task<IActionResult> Unregister(UnregisterDeviceRequest request, CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        if (string.IsNullOrWhiteSpace(request.Token)) return NoContent();
        var token = request.Token.Trim();
        var device = await db.DeviceRegistrations.SingleOrDefaultAsync(item => item.Token == token && item.UserId == currentUser.UserId.Value, cancellationToken);
        if (device is not null)
        {
            device.Disable("signed_out", clock.UtcNow);
            await db.SaveChangesAsync(cancellationToken);
        }
        return NoContent();
    }

    private async Task<DeviceRegistration> UpsertAsync(RegisterDeviceRequest request, CancellationToken cancellationToken)
    {
        var userId = currentUser.UserId!.Value;
        var token = request.Token.Trim();
        var now = clock.UtcNow;
        var device = await db.DeviceRegistrations.SingleOrDefaultAsync(item => item.Token == token, cancellationToken);
        if (device is null)
        {
            device = DeviceRegistration.Register(userId, request.Platform, token, request.AppVersion, now, request.TokenKind);
            db.DeviceRegistrations.Add(device);
        }
        else device.Refresh(userId, request.Platform, request.AppVersion, now, request.TokenKind);

        // Keep the newest few installs per user; old phones stop receiving pushes.
        var others = await db.DeviceRegistrations
            .Where(item => item.UserId == userId && item.DisabledAtUtc == null && item.Token != token)
            .OrderByDescending(item => item.LastSeenAtUtc)
            .ToListAsync(cancellationToken);
        foreach (var stale in others.Skip(Math.Max(0, settings.MaxDevicesPerUser - 1))) stale.Disable("replaced", now);
        await db.SaveChangesAsync(cancellationToken);
        return device;
    }

    private static DeviceDto ToDto(DeviceRegistration item)
        => new(item.Id, item.Platform, item.TokenKind, PushPolicy.TokenSuffix(item.Token), item.AppVersion, item.LastSeenAtUtc, item.IsActive);
}
