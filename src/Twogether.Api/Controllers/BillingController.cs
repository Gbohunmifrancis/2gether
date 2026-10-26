using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using Npgsql;
using Twogether.Api.Common;
using Twogether.Application.Common.Interfaces;
using Twogether.Application.Features.Billing;
using Twogether.Shared.Results;
using Twogether.Shared.Localization;

namespace Twogether.Api.Controllers;

/// <summary>
/// One Plus subscription covers the couple. The app reports purchases; Google confirms them and
/// keeps us current through real-time notifications. Nothing here trusts the app's word on state.
/// </summary>
public sealed class BillingController(
    ICurrentUser currentUser,
    IEntitlementService entitlements,
    GooglePlaySubscriptionSync sync,
    IGooglePlayBilling store,
    BillingSettings settings,
    ILogger<BillingController> logger,
    Twogether.Application.Features.Admin.Ops.OpsSignals? signals = null) : ApiControllerBase
{
    [Authorize]
    [HttpGet("entitlement")]
    public async Task<ActionResult<EntitlementDto>> Entitlement(CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        return Ok(await entitlements.ForUserAsync(currentUser.UserId.Value, cancellationToken));
    }

    /// <summary>
    /// Call after a purchase and on every app start for unacknowledged purchases. The app must set
    /// the obfuscated account id to the user id (no dashes) at checkout.
    /// </summary>
    [Authorize]
    [HttpPost("google/verify")]
    public async Task<ActionResult<EntitlementDto>> VerifyGoogle(VerifyGooglePurchaseRequest request, CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        if (!store.IsConfigured) return StatusCode(StatusCodes.Status503ServiceUnavailable, new ApiError("billing.unavailable", Loc.T("Purchases are not set up on this server.")));
        if (string.IsNullOrWhiteSpace(request.PurchaseToken)) return UnprocessableEntity(new ApiError("billing.invalid_token", Loc.T("The purchase token is missing.")));
        if (!settings.ProductIds.Contains(request.ProductId, StringComparer.Ordinal))
            return UnprocessableEntity(new ApiError("billing.unknown_product", Loc.T("That product does not unlock Heartpact Plus.")));

        Result<SyncOutcome> result;
        try { result = await sync.SyncAsync(request.PurchaseToken, currentUser.UserId.Value, cancellationToken); }
        catch (DbUpdateException exception) when (exception.InnerException is PostgresException { SqlState: "23505" })
        {
            // The same purchase verified twice at once; the other call stored it. Report what is there now.
            logger.LogInformation("Concurrent verification of one purchase token for user {UserId}.", currentUser.UserId);
            return Ok(await entitlements.ForUserAsync(currentUser.UserId.Value, cancellationToken));
        }
        if (result.IsFailure)
        {
            logger.LogWarning("Purchase verification for user {UserId} failed: {Code}.", currentUser.UserId, result.Error.Code);
            // For the admin payment alert: failures that aren't the buyer's own doing.
            if (result.Error.Type is not (ErrorType.Validation or ErrorType.Forbidden)) signals?.VerifyFailed();
            return Problem(result.Error);
        }
        if (result.Value.AcknowledgeFailed)
            logger.LogWarning("Purchase {SubscriptionId} is not acknowledged yet; the next verify or notification retries.", result.Value.Subscription.Id);
        logger.LogInformation("Purchase {SubscriptionId} verified for user {UserId}: {Status} until {ExpiresAtUtc}.",
            result.Value.Subscription.Id, currentUser.UserId, result.Value.Subscription.Status, result.Value.Subscription.ExpiresAtUtc);
        return Ok(await entitlements.ForUserAsync(currentUser.UserId.Value, cancellationToken));
    }

    /// <summary>
    /// Google Play real-time developer notifications, delivered by a Pub/Sub push subscription whose
    /// URL carries the secret. The payload only names a token; the state is always re-fetched from
    /// Google, so a forged call can at most cause a refresh. 503 makes Pub/Sub retry later.
    /// </summary>
    [AllowAnonymous]
    [HttpPost("google/rtdn")]
    public async Task<IActionResult> GoogleNotification([FromQuery] string? secret, [FromBody] JsonElement body, CancellationToken cancellationToken)
    {
        if (string.IsNullOrEmpty(settings.RtdnSecret) || secret is null
            || !CryptographicOperations.FixedTimeEquals(Encoding.UTF8.GetBytes(secret), Encoding.UTF8.GetBytes(settings.RtdnSecret)))
            return Unauthorized();
        if (!store.IsConfigured) return StatusCode(StatusCodes.Status503ServiceUnavailable);

        var token = PlayNotificationParser.ReadPurchaseToken(body, settings.GooglePlayPackageName, out var kind);
        if (token is null)
        {
            logger.LogInformation("Play notification ignored: {Kind}.", kind);
            return NoContent();
        }
        try
        {
            var result = await sync.SyncAsync(token, callerUserId: null, cancellationToken);
            if (result.IsFailure && result.Error.Type == ErrorType.Network) return StatusCode(StatusCodes.Status503ServiceUnavailable);
            if (result.IsFailure) logger.LogWarning("Play notification for an unusable purchase: {Code}.", result.Error.Code);
            else logger.LogInformation("Play notification {Kind} applied to subscription {SubscriptionId}: {Status}.", kind, result.Value.Subscription.Id, result.Value.Subscription.Status);
        }
        catch (DbUpdateException exception) when (exception.InnerException is PostgresException { SqlState: "23505" })
        {
            logger.LogInformation("Play notification raced a verify for the same purchase; the stored row wins.");
        }
        return NoContent();
    }

    private ActionResult Problem(Error error) => error.Type switch
    {
        ErrorType.Validation => UnprocessableEntity(new ApiError(error.Code, error.Message)),
        ErrorType.NotFound => NotFound(new ApiError(error.Code, error.Message)),
        ErrorType.Forbidden => StatusCode(StatusCodes.Status403Forbidden, new ApiError(error.Code, error.Message)),
        ErrorType.Network => StatusCode(StatusCodes.Status502BadGateway, new ApiError(error.Code, error.Message)),
        _ => StatusCode(StatusCodes.Status503ServiceUnavailable, new ApiError(error.Code, error.Message))
    };
}
