using System.Security.Cryptography;
using System.Text;
using Microsoft.EntityFrameworkCore;
using Twogether.Application.Common.Interfaces;
using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Shared.Localization;
using Twogether.Shared.Results;

namespace Twogether.Application.Features.Billing;

/// <param name="Plan">"monthly" or "yearly".</param>
/// <param name="From">"web" (the site, the default) or "app" (card payment inside the Android app).</param>
/// <param name="ExternalTransactionToken">
/// From the app only: Play Billing's UserChoiceDetails token after the person picked card on Google's choice screen.
/// </param>
public sealed record BachsCheckoutStart(string? Plan, string? From = null, string? ExternalTransactionToken = null);

public sealed record BachsCheckoutDto(string CheckoutUrl);
public sealed record BachsPortalDto(string Url);

/// <summary>
/// Starts card checkout and opens the billing portal. Access never comes from here: only the signed webhook grants
/// Plus. The price is whatever the configured product costs in Bachs; the client only names a plan.
/// </summary>
public sealed class BachsCheckoutService(
    IApplicationDbContext db,
    IBachsBilling bachs,
    IEntitlementService entitlements,
    BachsSettings settings,
    IDateTimeProvider clock)
{
    public const string Unavailable = "billing.bachs_unavailable";
    public const string UnknownPlan = "billing.unknown_plan";
    public const string InvalidFrom = "billing.invalid_from";
    /// <summary>Checkouts with the same body within this window share an Idempotency-Key, so a double tap opens one checkout.</summary>
    public static readonly TimeSpan IdempotencyWindow = TimeSpan.FromMinutes(30);

    public async Task<Result<BachsCheckoutDto>> StartAsync(Guid userId, BachsCheckoutStart request, CancellationToken cancellationToken)
    {
        if (!settings.IsConfigured || !bachs.IsConfigured) return NotSetUp();
        var productId = settings.BachsProductFor(request.Plan);
        if (productId is null) return Error.Validation(UnknownPlan, Loc.T("Choose the monthly or the yearly plan."));
        var from = string.IsNullOrWhiteSpace(request.From) ? "web" : request.From.Trim().ToLowerInvariant();
        var token = string.IsNullOrWhiteSpace(request.ExternalTransactionToken) ? null : request.ExternalTransactionToken.Trim();
        // An in-app card checkout only ever starts from Google's choice screen, whose token Google needs reported:
        // without it the sale couldn't be reported, so it isn't started.
        if (from is not ("web" or "app") || (token is not null && from != "app") || (from == "app" && token is null))
            return Error.Validation(InvalidFrom, Loc.T("This checkout can't be started from here."));
        if (token is { Length: > GoogleExternalTransaction.MaxTokenLength })
            return Error.Validation(InvalidFrom, Loc.T("This checkout can't be started from here."));

        var user = await db.Users.AsNoTracking().Where(item => item.Id == userId)
            .Select(item => new { item.Email, item.DisplayName, item.CountryCode }).SingleOrDefaultAsync(cancellationToken);
        if (user is null) return Error.NotFound("billing.unknown_user", Loc.T("No Heartpact account matches this purchase."));
        var entitlement = await entitlements.ForUserAsync(userId, cancellationToken);
        if (entitlement.Plan == Plan.Plus)
            return Error.Conflict("billing.already_plus", Loc.T("You already have Heartpact Plus."));
        // Inside the app, card payment exists only where Google's billing choice allows it and the switch is on.
        if (from == "app" && !entitlement.CardInApp)
            return Error.Forbidden("billing.card_in_app_unavailable", Loc.T("Card payment isn't available in the app here. Use Google Play instead."));

        var customerId = await CustomerIdAsync(userId, cancellationToken);
        var now = clock.UtcNow;
        GoogleExternalTransaction? external = null;
        if (token is not null)
        {
            // Saved before Bachs is called, so the token Google needs is never lost to a crash in between.
            external = GoogleExternalTransaction.ForCheckout(userId, token, user.CountryCode!, now);
            db.GoogleExternalTransactions.Add(external);
            await db.SaveChangesAsync(cancellationToken);
        }

        var checkout = new BachsCheckoutRequest(
            productId,
            userId,
            customerId,
            customerId is null ? user.Email : null,
            customerId is null ? user.DisplayName : null,
            WithFrom(settings.SuccessUrl, from),
            WithFrom(settings.CancelUrl, from),
            external?.Id);
        var created = await bachs.CreateCheckoutAsync(checkout, IdempotencyKey(checkout, now), cancellationToken);
        if (created.IsFailure) return Result.Failure<BachsCheckoutDto>(created.Error);
        return new BachsCheckoutDto(created.Value.CheckoutUrl);
    }

    public async Task<Result<BachsPortalDto>> OpenPortalAsync(Guid userId, CancellationToken cancellationToken)
    {
        if (!settings.IsConfigured || !bachs.IsConfigured) return NotSetUp();
        var customerId = await CustomerIdAsync(userId, cancellationToken);
        if (customerId is null) return Error.NotFound("billing.no_bachs_customer", Loc.T("You haven't paid by card yet, so there is nothing to manage here."));
        var portal = await bachs.CreatePortalSessionAsync(customerId, cancellationToken);
        return portal.IsSuccess ? new BachsPortalDto(portal.Value) : Result.Failure<BachsPortalDto>(portal.Error);
    }

    /// <summary>The person's own Bachs customer, from their newest card subscription; never a partner's.</summary>
    private Task<string?> CustomerIdAsync(Guid userId, CancellationToken cancellationToken)
        => db.StoreSubscriptions.AsNoTracking()
            .Where(item => item.UserId == userId && item.Store == StoreSubscription.Bachs && item.ExternalCustomerId != null)
            .OrderByDescending(item => item.UpdatedAtUtc).Select(item => item.ExternalCustomerId).FirstOrDefaultAsync(cancellationToken);

    /// <summary>Deterministic per request body and half hour: a retry or a double tap gets the same checkout back.</summary>
    public static string IdempotencyKey(BachsCheckoutRequest request, DateTime utcNow)
    {
        var window = utcNow.Ticks / IdempotencyWindow.Ticks;
        var text = string.Join('|', request.ProductId, request.UserId.ToString("N"), request.CustomerId, request.Email, request.Name,
            request.SuccessUrl, request.CancelUrl, request.ExternalRef?.ToString("N"), window.ToString(System.Globalization.CultureInfo.InvariantCulture));
        return "hp-checkout-" + Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(text)))[..40];
    }

    private static string WithFrom(string url, string from)
        => from == "app" ? url + (url.Contains('?') ? "&" : "?") + "from=app" : url;

    private static Error NotSetUp() => Error.Failure(Unavailable, Loc.T("Card payments are not set up on this server."));
}
