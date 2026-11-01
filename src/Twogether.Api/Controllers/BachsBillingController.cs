using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.SignalR;
using Twogether.Api.Common;
using Twogether.Api.Hubs;
using Twogether.Api.Realtime;
using Twogether.Application.Common.Interfaces;
using Twogether.Application.Common.Security;
using Twogether.Application.Features.Billing;
using Twogether.Infrastructure.Billing;
using Twogether.Shared.Localization;
using Twogether.Shared.Results;

namespace Twogether.Api.Controllers;

/// <summary>
/// Card subscriptions through Bachs (docs/billing.md, Bachs): checkout on the web, or in the app where Google's billing
/// choice allows it, the billing portal, and Bachs's webhooks. Plus comes only from a verified webhook.
/// </summary>
[Route("api/billing/bachs")]
public sealed class BachsBillingController(
    ICurrentUser currentUser,
    BachsCheckoutService checkout,
    BachsSubscriptionSync sync,
    BachsSettings settings,
    IDateTimeProvider clock,
    AttemptLimiter limiter,
    ILogger<BachsBillingController> logger,
    IHubContext<CoupleHub>? hub = null) : ApiControllerBase
{
    /// <summary>Bachs's events are a few kilobytes; anything near this is not one of them.</summary>
    public const int MaxWebhookBytes = 256 * 1024;

    /// <summary>
    /// <c>{ "plan": "monthly"|"yearly", "from": "web"|"app", "externalTransactionToken"?: "..." }</c> to
    /// <c>{ "checkoutUrl": "..." }</c>. Open the URL; Plus arrives by webhook once the payment (or the trial) starts.
    /// </summary>
    [Authorize]
    [HttpPost("checkout")]
    public async Task<IActionResult> Checkout(BachsCheckoutStart request, CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        var userId = currentUser.UserId.Value;
        if (Blocked(limiter, (AttemptRules.BachsCheckoutPerUser, userId.ToString())) is { } wait)
            return TooManyAttempts(wait, Loc.T("Too many checkouts started. Wait a few minutes and try again."));
        limiter.RecordFailure(AttemptRules.BachsCheckoutPerUser, userId.ToString());
        var result = await checkout.StartAsync(userId, request, cancellationToken);
        if (result.IsFailure)
        {
            logger.LogInformation("Card checkout for user {UserId} refused: {Code}.", userId, result.Error.Code);
            return Answer(result.Error);
        }
        return Ok(result.Value);
    }

    /// <summary><c>{ "url": "..." }</c>: the caller's own billing portal (cancel, change card, invoices). Open it at once.</summary>
    [Authorize]
    [HttpPost("portal")]
    public async Task<IActionResult> Portal(CancellationToken cancellationToken)
    {
        if (!currentUser.UserId.HasValue) return Unauthorized();
        var userId = currentUser.UserId.Value;
        if (Blocked(limiter, (AttemptRules.BachsPortalPerUser, userId.ToString())) is { } wait)
            return TooManyAttempts(wait, Loc.T("Too many tries. Wait a few minutes and try again."));
        limiter.RecordFailure(AttemptRules.BachsPortalPerUser, userId.ToString());
        var result = await checkout.OpenPortalAsync(userId, cancellationToken);
        return result.IsSuccess ? Ok(result.Value) : Answer(result.Error);
    }

    /// <summary>
    /// Bachs's webhook. The signature is checked on the raw bytes before anything is parsed: 400 only for a bad one.
    /// 2xx once handled (or already handled); 5xx when handling failed, so Bachs delivers it again.
    /// </summary>
    [AllowAnonymous]
    [HttpPost("webhook")]
    [RequestSizeLimit(MaxWebhookBytes)]
    public async Task<IActionResult> Webhook(CancellationToken cancellationToken)
    {
        if (!settings.IsConfigured) return StatusCode(StatusCodes.Status503ServiceUnavailable);
        if (Blocked(limiter, (AttemptRules.BachsWebhookPerIp, ClientIp)) is { } wait) return TooManyAttempts(wait, "Too many bad signatures.");

        var body = await ReadBodyAsync(cancellationToken);
        var signature = Request.Headers[BachsSignature.Header].ToString();
        if (body is null || !BachsSignature.Verify(signature, body, settings.WebhookSecret!, clock.UtcNow))
        {
            limiter.RecordFailure(AttemptRules.BachsWebhookPerIp, ClientIp);
            logger.LogWarning("Bachs webhook with a missing or wrong signature refused.");
            return BadRequest(new ApiError("billing.bad_signature", "The signature doesn't match."));
        }

        var result = await sync.HandleAsync(System.Text.Encoding.UTF8.GetString(body), cancellationToken);
        if (result.IsFailure)
        {
            logger.LogWarning("Bachs webhook not handled ({Code}); Bachs will deliver it again.", result.Error.Code);
            return StatusCode(StatusCodes.Status500InternalServerError);
        }
        // A first grant's "Plus is on" is saved (and pushed) already; this shows it in an open app too.
        if (hub is not null)
            foreach (var notice in sync.Notices) await ExerciseEvents.NotificationAsync(hub, notice, cancellationToken);
        return Ok();
    }

    private async Task<byte[]?> ReadBodyAsync(CancellationToken cancellationToken)
    {
        using var buffer = new MemoryStream();
        var chunk = new byte[16 * 1024];
        int read;
        while ((read = await Request.Body.ReadAsync(chunk, cancellationToken)) > 0)
        {
            if (buffer.Length + read > MaxWebhookBytes) return null;
            buffer.Write(chunk, 0, read);
        }
        return buffer.ToArray();
    }

    private ObjectResult Answer(Error error) => error.Code switch
    {
        BachsCheckoutService.Unavailable => StatusCode(StatusCodes.Status503ServiceUnavailable, new ApiError(error.Code, error.Message)),
        BachsCheckoutService.UnknownPlan or BachsCheckoutService.InvalidFrom => BadRequest(new ApiError(error.Code, error.Message)),
        // Whatever Bachs itself answered (refused, not found, unreachable) is a gateway problem, not the caller's.
        "billing.bachs_refused" or "billing.bachs_not_found" or "billing.bachs_failed" => StatusCode(StatusCodes.Status502BadGateway, new ApiError(error.Code, error.Message)),
        _ => error.Type switch
        {
            ErrorType.Conflict => Conflict(new ApiError(error.Code, error.Message)),
            ErrorType.NotFound => NotFound(new ApiError(error.Code, error.Message)),
            ErrorType.Forbidden => StatusCode(StatusCodes.Status403Forbidden, new ApiError(error.Code, error.Message)),
            ErrorType.Validation => BadRequest(new ApiError(error.Code, error.Message)),
            _ => StatusCode(StatusCodes.Status502BadGateway, new ApiError(error.Code, error.Message)),
        }
    };
}
