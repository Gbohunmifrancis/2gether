using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Logging;
using Twogether.Application.Common.Interfaces;
using Twogether.Application.Features.Billing;
using Twogether.Core.Entities;
using Twogether.Infrastructure.Persistence;
using Twogether.Shared.Localization;
using Twogether.Shared.Results;

namespace Twogether.Infrastructure.Billing;

public enum BachsWebhookOutcome { Applied, Duplicate, Stale, Ignored }

/// <summary>
/// Bachs webhooks and admin actions on card subscriptions (docs/billing.md, Bachs). Each event is applied inside one
/// transaction, under a lock on its subscription: the event id is checked and recorded, an event older than the stored
/// state changes nothing, and the subscription state, the event record and any Google report it owes are saved
/// together. A failure leaves nothing recorded, so Bachs's redelivery tries again.
/// </summary>
public sealed class BachsSubscriptionSync(
    ApplicationDbContext db,
    IBachsBilling bachs,
    BachsSettings settings,
    IDateTimeProvider clock,
    ILogger<BachsSubscriptionSync> logger)
{
    private readonly List<Notification> notices = [];

    /// <summary>The "Plus is on" notices the last call saved (a subscription's first grant), for the caller to send live.</summary>
    public IReadOnlyList<Notification> Notices => notices;

    /// <summary>A verified webhook body. A failure means "answer 5xx so Bachs retries".</summary>
    public async Task<Result<BachsWebhookOutcome>> HandleAsync(string rawBody, CancellationToken cancellationToken)
    {
        var evt = BachsEvents.ReadEvent(rawBody);
        if (evt is null || evt.Id.Length > BachsWebhookEvent.MaxIdLength)
        {
            logger.LogError("A signed Bachs webhook had no usable id, type or created_at.");
            return Error.Failure("billing.bachs_event_unreadable", "The event could not be read.");
        }
        notices.Clear();
        try
        {
            return await HandleOnceAsync(evt, cancellationToken);
        }
        catch (DbUpdateException)
        {
            notices.Clear();
            // The same event delivered twice at once: the other delivery recorded it. Anything else is retried by Bachs.
            if (await IsRecordedAsync(evt.Id, cancellationToken)) return BachsWebhookOutcome.Duplicate;
            throw;
        }
    }

    private async Task<Result<BachsWebhookOutcome>> HandleOnceAsync(BachsEvent evt, CancellationToken cancellationToken)
    {
        BachsSubscriptionSnapshot? subscription = null;
        BachsInvoiceSnapshot? invoice = null;
        BachsRefundSnapshot? refund = null;
        string? lockOn = null;
        if (BachsEvents.IsSubscription(evt.Type))
        {
            subscription = BachsEvents.ReadSubscription(evt.Data);
            lockOn = subscription?.SubscriptionId;
        }
        else if (evt.Type == BachsEvents.InvoicePaid)
        {
            invoice = BachsEvents.ReadInvoice(evt.Data);
            lockOn = invoice?.SubscriptionId;
        }
        else if (evt.Type == BachsEvents.RefundPaid) refund = BachsEvents.ReadRefund(evt.Data);

        await using var transaction = await db.Database.BeginTransactionAsync(cancellationToken);
        if (lockOn is not null) await db.Database.LockAsync(LockScope.BachsSubscription, DatabaseLocks.KeyFor(lockOn), cancellationToken);
        if (await db.BachsWebhookEvents.AnyAsync(item => item.Id == evt.Id, cancellationToken)) return BachsWebhookOutcome.Duplicate;

        var outcome = BachsWebhookOutcome.Ignored;
        if (BachsEvents.IsSubscription(evt.Type))
        {
            if (subscription is null) logger.LogWarning("Bachs event {EventId} ({Type}) has no readable subscription; recorded and ignored.", evt.Id, evt.Type);
            else outcome = await ApplyAsync(subscription, evt.CreatedAtUtc, evt.Id, cancellationToken);
        }
        else if (invoice is not null)
        {
            var paid = await InvoicePaidAsync(invoice, evt.CreatedAtUtc, cancellationToken);
            if (paid.IsFailure) return Result.Failure<BachsWebhookOutcome>(paid.Error);
            outcome = paid.Value;
        }
        else if (refund is not null) outcome = await RefundPaidAsync(refund, evt.CreatedAtUtc, cancellationToken);
        else logger.LogInformation("Bachs event {EventId} ({Type}) needs nothing from us.", evt.Id, evt.Type);

        db.BachsWebhookEvents.Add(BachsWebhookEvent.Record(evt.Id, evt.Type, clock.UtcNow));
        await db.SaveChangesAsync(cancellationToken);
        await transaction.CommitAsync(cancellationToken);
        return outcome;
    }

    /// <summary>Re-reads one card subscription from Bachs (admin "sync").</summary>
    public async Task<Result<StoreSubscription>> RefreshAsync(StoreSubscription subscription, CancellationToken cancellationToken)
    {
        var fresh = await bachs.GetSubscriptionAsync(subscription.PurchaseToken, cancellationToken);
        if (fresh.IsFailure) return Result.Failure<StoreSubscription>(fresh.Error);
        return await ApplyNowAsync(fresh.Value, subscription.Id, cancellationToken);
    }

    /// <summary>Stops renewal (at period end) or ends it now, then stores what Bachs answered.</summary>
    public async Task<Result<StoreSubscription>> CancelAsync(StoreSubscription subscription, bool atPeriodEnd, CancellationToken cancellationToken)
    {
        var cancelled = await bachs.CancelSubscriptionAsync(subscription.PurchaseToken, atPeriodEnd, cancellationToken);
        if (cancelled.IsFailure)
        {
            // Cancelling one that is already canceled is refused; that is the outcome we wanted.
            if (cancelled.Error.Type != ErrorType.Conflict) return Result.Failure<StoreSubscription>(cancelled.Error);
            var current = await bachs.GetSubscriptionAsync(subscription.PurchaseToken, cancellationToken);
            if (current.IsFailure) return Result.Failure<StoreSubscription>(current.Error);
            var settled = current.Value.Status == "canceled" || (atPeriodEnd && current.Value.CancelAtPeriodEnd);
            if (!settled) return Result.Failure<StoreSubscription>(cancelled.Error);
            return await ApplyNowAsync(current.Value, subscription.Id, cancellationToken);
        }
        return await ApplyNowAsync(cancelled.Value, subscription.Id, cancellationToken);
    }

    private async Task<Result<StoreSubscription>> ApplyNowAsync(BachsSubscriptionSnapshot snapshot, Guid rowId, CancellationToken cancellationToken)
    {
        notices.Clear();
        await using var transaction = await db.Database.BeginTransactionAsync(cancellationToken);
        await db.Database.LockAsync(LockScope.BachsSubscription, DatabaseLocks.KeyFor(snapshot.SubscriptionId), cancellationToken);
        await ApplyAsync(snapshot, clock.UtcNow, eventId: null, cancellationToken);
        await db.SaveChangesAsync(cancellationToken);
        await transaction.CommitAsync(cancellationToken);
        return await db.StoreSubscriptions.AsNoTracking().SingleAsync(item => item.Id == rowId, cancellationToken);
    }

    /// <summary>Stores the subscription as it was at <paramref name="eventAtUtc"/>, unless something newer is stored. Does not save.</summary>
    private async Task<BachsWebhookOutcome> ApplyAsync(BachsSubscriptionSnapshot snapshot, DateTime eventAtUtc, string? eventId, CancellationToken cancellationToken)
    {
        var existing = await db.StoreSubscriptions.SingleOrDefaultAsync(item => item.PurchaseToken == snapshot.SubscriptionId, cancellationToken);
        if (existing is not null && !existing.IsBachs)
        {
            logger.LogError("Bachs event {EventId} names subscription {SubscriptionId}, which is stored as another store's purchase; ignored.", eventId, snapshot.SubscriptionId);
            return BachsWebhookOutcome.Ignored;
        }
        if (existing is not null && existing.IsNewerThan(eventAtUtc))
        {
            logger.LogInformation("Bachs event {EventId} is older than the stored state of subscription {SubscriptionId}; skipped.", eventId, snapshot.SubscriptionId);
            return BachsWebhookOutcome.Stale;
        }
        var productId = settings.OurProductFor(snapshot.ProductId);
        if (productId is null)
        {
            logger.LogWarning("Bachs subscription {SubscriptionId} is for a product that isn't one of our plans; ignored.", snapshot.SubscriptionId);
            return BachsWebhookOutcome.Ignored;
        }
        Guid? owner = existing?.UserId ?? (Guid.TryParse(snapshot.MetadataUserId, out var parsed) ? parsed : null);
        if (owner is not { } ownerId || !await db.Users.AnyAsync(user => user.Id == ownerId, cancellationToken))
        {
            // A deleted account, or a checkout made outside Heartpact: nobody here to give Plus to.
            logger.LogWarning("Bachs subscription {SubscriptionId} belongs to no Heartpact account; ignored.", snapshot.SubscriptionId);
            return BachsWebhookOutcome.Ignored;
        }

        var now = clock.UtcNow;
        var state = BachsStatusMapping.Map(snapshot, eventAtUtc);
        // "Plus is on", once: when the subscription is created granting, or first moves to trialing or active; not for
        // a renewal, and not when the couple already had Plus (the partner pays, a gift). Saved with the state.
        if (PlusStartedNotices.IsFirstGrant(existing?.Status, state.Status, state.ExpiresAtUtc, now)
            && !await PlusStartedNotices.CoupleHasPlusAsync(db, ownerId, existing?.Id, now, cancellationToken))
        {
            var told = await PlusStartedNotices.ForPurchaseAsync(db, ownerId, state.AutoRenewing ? null : state.ExpiresAtUtc, now, cancellationToken);
            foreach (var notice in told) db.Notifications.Add(notice);
            notices.AddRange(told);
        }
        var subscription = existing;
        if (subscription is null)
        {
            subscription = StoreSubscription.CreateBachs(snapshot.SubscriptionId, ownerId, now);
            db.StoreSubscriptions.Add(subscription);
        }
        var wasGranting = existing?.GrantsAccess(now) == true;
        subscription.ApplyBachsState(productId, state.Status, state.ExpiresAtUtc, state.AutoRenewing, snapshot.CustomerId, eventAtUtc, now);
        // The admin live feed and journey read "Plus started" from the new row, as for Google Play.
        if (!wasGranting && subscription.GrantsAccess(now))
            logger.LogInformation("Bachs subscription {SubscriptionId} gives user {UserId} Plus until {ExpiresAtUtc}.", snapshot.SubscriptionId, ownerId, state.ExpiresAtUtc);
        else
            logger.LogInformation("Bachs subscription {SubscriptionId} is now {Status} until {ExpiresAtUtc}.", snapshot.SubscriptionId, state.Status, state.ExpiresAtUtc);

        await StartExternalTransactionAsync(snapshot, ownerId, eventAtUtc, cancellationToken);
        return BachsWebhookOutcome.Applied;
    }

    /// <summary>
    /// An in-app checkout (Google's billing choice) carries our outbox row's id; the first time its subscription shows
    /// up, the initial transaction becomes ready for Google: the price paid now, or 0 for a trial.
    /// </summary>
    private async Task StartExternalTransactionAsync(BachsSubscriptionSnapshot snapshot, Guid ownerId, DateTime eventAtUtc, CancellationToken cancellationToken)
    {
        if (!Guid.TryParse(snapshot.MetadataExternalRef, out var reference)) return;
        var row = await db.GoogleExternalTransactions.SingleOrDefaultAsync(item => item.Id == reference, cancellationToken);
        if (row is null || row.UserId != ownerId || row.Kind != GoogleExternalTransaction.Initial) return;
        if (row.BachsSubscriptionId is not null) return;
        if (await db.GoogleExternalTransactions.AnyAsync(item => item.BachsSubscriptionId == snapshot.SubscriptionId, cancellationToken)) return;
        var trial = snapshot.Status == "trialing";
        var amount = trial ? 0 : snapshot.AmountMicros ?? 0;
        if (!trial && snapshot.AmountMicros is null)
            logger.LogWarning("Bachs subscription {SubscriptionId} has no amount; its Google report says 0.", snapshot.SubscriptionId);
        row.Start(snapshot.SubscriptionId, amount, snapshot.Currency, snapshot.CreatedAt ?? eventAtUtc, clock.UtcNow);
    }

    /// <summary>
    /// A paid renewal of an in-app subscription is reported to Google as a new transaction pointing at the initial one.
    /// The first paid invoice is what the initial transaction already reported (unless that was a free trial start).
    /// A web subscription has no initial transaction and reports nothing.
    /// </summary>
    private async Task<Result<BachsWebhookOutcome>> InvoicePaidAsync(BachsInvoiceSnapshot invoice, DateTime eventAtUtc, CancellationToken cancellationToken)
    {
        if (invoice.SubscriptionId is not { } subscriptionId || invoice.AmountPaidMicros <= 0) return BachsWebhookOutcome.Ignored;
        var initial = await db.GoogleExternalTransactions
            .SingleOrDefaultAsync(item => item.BachsSubscriptionId == subscriptionId && item.Kind == GoogleExternalTransaction.Initial, cancellationToken);
        if (initial is null)
        {
            // Delivered before customer.subscription.created: answer 5xx so this comes back once the subscription is here.
            // Only while an in-app checkout of the last two days is still waiting for its subscription; a web one has none.
            var recent = clock.UtcNow.AddDays(-2);
            if (!await db.StoreSubscriptions.AnyAsync(item => item.PurchaseToken == subscriptionId, cancellationToken)
                && await db.GoogleExternalTransactions.AnyAsync(item => item.BachsSubscriptionId == null && item.Kind == GoogleExternalTransaction.Initial
                    && item.TransactionTimeUtc == null && item.CreatedAtUtc > recent, cancellationToken))
                return Error.Failure("billing.bachs_out_of_order", "The subscription for this invoice hasn't arrived yet.");
            return BachsWebhookOutcome.Ignored;
        }
        if (initial.BachsInvoiceId == invoice.InvoiceId
            || await db.GoogleExternalTransactions.AnyAsync(item => item.BachsInvoiceId == invoice.InvoiceId, cancellationToken))
            return BachsWebhookOutcome.Duplicate;
        var now = clock.UtcNow;
        if (initial.AmountMicros > 0 && initial.BachsInvoiceId is null)
        {
            initial.CoverInvoice(invoice.InvoiceId, invoice.ChargeId, now);
            return BachsWebhookOutcome.Applied;
        }
        db.GoogleExternalTransactions.Add(GoogleExternalTransaction.ForRenewal(initial, invoice.InvoiceId, invoice.ChargeId, invoice.AmountPaidMicros, invoice.Currency, eventAtUtc, now));
        logger.LogInformation("Renewal {InvoiceId} of in-app subscription {SubscriptionId} queued for Google.", invoice.InvoiceId, subscriptionId);
        return BachsWebhookOutcome.Applied;
    }

    /// <summary>A refund of a payment Google was told about is reported to Google too, matched by its charge.</summary>
    private async Task<BachsWebhookOutcome> RefundPaidAsync(BachsRefundSnapshot refund, DateTime eventAtUtc, CancellationToken cancellationToken)
    {
        if (refund.ChargeId is null) return BachsWebhookOutcome.Ignored;
        var row = await db.GoogleExternalTransactions.FirstOrDefaultAsync(item => item.BachsChargeId == refund.ChargeId, cancellationToken);
        if (row is null) return BachsWebhookOutcome.Ignored;
        row.RequestRefund(refund.RefundId, refund.RefundedMicros ?? row.AmountMicros, eventAtUtc, clock.UtcNow);
        logger.LogInformation("Refund {RefundId} of a reported in-app payment queued for Google.", refund.RefundId);
        return BachsWebhookOutcome.Applied;
    }

    private async Task<bool> IsRecordedAsync(string eventId, CancellationToken cancellationToken)
    {
        db.ChangeTracker.Clear();
        return await db.BachsWebhookEvents.AsNoTracking().AnyAsync(item => item.Id == eventId, cancellationToken);
    }

    /// <summary>
    /// Before an account is erased: a card subscription that would renew is cancelled now, since nobody is left to use
    /// it or to cancel it later. One already set to end at its period end charges nothing more and is left to run out.
    /// Without Bachs set up nothing can be done here; that is logged for the operator.
    /// </summary>
    public async Task<Result> CancelForErasureAsync(Guid userId, CancellationToken cancellationToken)
    {
        var live = await db.StoreSubscriptions.AsNoTracking()
            .Where(item => item.UserId == userId && item.Store == StoreSubscription.Bachs && item.AutoRenewing && item.Status != Core.Enums.StoreSubscriptionStatus.Expired)
            .ToListAsync(cancellationToken);
        foreach (var subscription in live)
        {
            if (!bachs.IsConfigured)
            {
                logger.LogError("Account {UserId} is being deleted with Bachs subscription {SubscriptionId} still live, and Bachs isn't set up here: cancel it in the Bachs dashboard.", userId, subscription.PurchaseToken);
                continue;
            }
            var cancelled = await CancelAsync(subscription, atPeriodEnd: false, cancellationToken);
            if (cancelled.IsFailure && cancelled.Error.Type != ErrorType.NotFound)
            {
                logger.LogWarning("Couldn't cancel Bachs subscription {SubscriptionId} before deleting account {UserId}: {Code}.", subscription.PurchaseToken, userId, cancelled.Error.Code);
                return Error.Failure("account.subscription_cancel_failed", Loc.T("Your card subscription couldn't be cancelled just now, so nothing was deleted. Try again in a few minutes."));
            }
        }
        return Result.Success();
    }
}
