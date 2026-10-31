using Microsoft.EntityFrameworkCore;
using Twogether.Application.Common.Interfaces;
using Twogether.Application.Features.Config;
using Twogether.Core.Entities;
using Twogether.Core.Enums;

namespace Twogether.Application.Features.Billing;

public interface IEntitlementService
{
    /// <summary>The plan for me and my current partner: Plus if either of us has a subscription that grants access.</summary>
    Task<EntitlementDto> ForUserAsync(Guid userId, CancellationToken cancellationToken);
}

public sealed class EntitlementService(IApplicationDbContext db, IDateTimeProvider clock, BillingSettings settings, BachsSettings? bachs = null) : IEntitlementService
{
    public async Task<EntitlementDto> ForUserAsync(Guid userId, CancellationToken cancellationToken)
    {
        var now = clock.UtcNow;
        var me = await db.Users.AsNoTracking().Where(user => user.Id == userId).Select(user => new { user.CoupleId, user.CountryCode }).SingleOrDefaultAsync(cancellationToken);
        var coupleId = me?.CoupleId;
        var members = new List<Guid> { userId };
        if (coupleId is { } id)
        {
            var couple = await db.Couples.AsNoTracking().SingleOrDefaultAsync(item => item.Id == id && item.Status == CoupleStatus.Active, cancellationToken);
            var partner = couple is null ? null : couple.UserAId == userId ? couple.UserBId : couple.UserAId;
            if (partner is { } other) members.Add(other);
        }

        // Every store counts (Google Play and Bachs card subscriptions alike); the product id list only gates Play purchases.
        var candidates = await db.StoreSubscriptions.AsNoTracking()
            .Where(item => members.Contains(item.UserId) && item.ExpiresAtUtc > now)
            .ToListAsync(cancellationToken);
        var granting = candidates.Where(item => item.GrantsAccess(now)).MaxBy(item => item.ExpiresAtUtc);
        // No paid plan: a complimentary grant on the current couple still gives Plus.
        var complimentaryUntil = granting is not null || coupleId is not { } current ? null
            : await db.PlusGrants.AsNoTracking().Where(item => item.CoupleId == current && item.RevokedAtUtc == null && item.ExpiresAtUtc > now)
                .MaxAsync(item => (DateTime?)item.ExpiresAtUtc, cancellationToken);
        var complimentary = complimentaryUntil is not null;
        var plan = granting is null && !complimentary ? Plan.Free : Plan.Plus;
        var webCheckout = bachs?.IsConfigured == true;
        // The flag is read only when it could matter: card checkout set up and the person in a billing choice region.
        var cardInApp = webCheckout && BachsPlans.CardInAppCountry(me?.CountryCode)
            && FeatureFlagService.IsOn(await db.AppSettings.AsNoTracking().Where(item => item.Key == FeatureFlagService.BachsInAppKey)
                .Select(item => item.Value).FirstOrDefaultAsync(cancellationToken));
        return new EntitlementDto(
            plan,
            settings.Limits(plan),
            granting is null ? null : new SubscriptionSummaryDto(granting.Store, granting.ProductId, granting.Status, granting.ExpiresAtUtc, granting.AutoRenewing, granting.UserId, granting.UserId == userId),
            CanPurchase: granting is null || granting.UserId == userId,
            Complimentary: complimentary,
            ComplimentaryUntilUtc: complimentaryUntil,
            FreeLimits: settings.Free,
            PlusLimits: settings.Plus,
            ProductIds: settings.ProductIds,
            WebCheckout: webCheckout,
            CardInApp: cardInApp);
    }
}
