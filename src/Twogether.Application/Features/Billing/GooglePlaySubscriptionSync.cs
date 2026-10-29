using Microsoft.EntityFrameworkCore;
using Twogether.Application.Common.Interfaces;
using Twogether.Core.Billing;
using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Shared.Results;
using Twogether.Shared.Localization;

namespace Twogether.Application.Features.Billing;

public sealed record SyncOutcome(StoreSubscription Subscription, bool AcknowledgeFailed);

/// <summary>
/// One path for both the app's "I just bought" call and Google's real-time notifications: fetch
/// the truth from Google, attribute it to a user, store it, acknowledge it. The caller's word is
/// never trusted for the state; it is only checked against the account id Google returns.
/// </summary>
public sealed class GooglePlaySubscriptionSync(IApplicationDbContext db, IGooglePlayBilling billing, IDateTimeProvider clock, BillingSettings settings)
{
    public async Task<Result<SyncOutcome>> SyncAsync(string purchaseToken, Guid? callerUserId, CancellationToken cancellationToken)
    {
        var token = purchaseToken.Trim();
        if (token.Length is 0 or > StoreSubscription.MaxTokenLength) return Error.Validation("billing.invalid_token", Loc.T("The purchase token is missing or too long."));
        var snapshot = await billing.GetAsync(token, cancellationToken);
        if (snapshot.IsFailure) return Result.Failure<SyncOutcome>(snapshot.Error);
        var state = snapshot.Value;
        if (!settings.ProductIds.Contains(state.ProductId, StringComparer.Ordinal))
            return Error.Validation("billing.unknown_product", Loc.T("That product does not unlock Heartpact Plus."));

        Guid? accountUser = Guid.TryParseExact(state.ObfuscatedAccountId, "N", out var parsed) ? parsed : null;
        var existing = await db.StoreSubscriptions.SingleOrDefaultAsync(item => item.PurchaseToken == token, cancellationToken);
        if (callerUserId is { } caller)
        {
            // The app sets the buyer's id at checkout. A token bought on another account, or already
            // claimed by someone else, cannot be redeemed here: that is how shared tokens get abused.
            if (accountUser is null) return Error.Validation("billing.account_missing", Loc.T("This purchase was not made from Heartpact. Update the app and try again."));
            if (accountUser != caller || (existing is not null && existing.UserId != caller))
                return Error.Forbidden("billing.not_your_purchase", Loc.T("This purchase belongs to another account."));
        }

        var owner = existing?.UserId ?? accountUser;
        if (owner is not { } ownerId || !await db.Users.AnyAsync(user => user.Id == ownerId, cancellationToken))
            return Error.NotFound("billing.unknown_purchase", Loc.T("No Heartpact account matches this purchase."));

        var now = clock.UtcNow;
        var subscription = existing;
        if (subscription is null)
        {
            subscription = StoreSubscription.Create(token, ownerId, now);
            db.StoreSubscriptions.Add(subscription);
        }
        subscription.ApplyStoreState(state.ProductId, state.Status, state.ExpiresAtUtc, state.AutoRenewing, state.Acknowledged, state.LinkedPurchaseToken, now);
        await db.SaveChangesAsync(cancellationToken);

        // Google refunds a purchase that is not acknowledged within three days.
        var acknowledgeFailed = false;
        if (!subscription.Acknowledged && subscription.Status is StoreSubscriptionStatus.Active or StoreSubscriptionStatus.InGracePeriod or StoreSubscriptionStatus.Canceled)
        {
            var ack = await billing.AcknowledgeAsync(subscription.ProductId, token, cancellationToken);
            if (ack.IsSuccess)
            {
                subscription.MarkAcknowledged(clock.UtcNow);
                await db.SaveChangesAsync(cancellationToken);
            }
            else acknowledgeFailed = true;
        }
        return new SyncOutcome(subscription, acknowledgeFailed);
    }
}
