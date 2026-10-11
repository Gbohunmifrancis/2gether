using Microsoft.EntityFrameworkCore;
using Twogether.Application.Common.Interfaces;
using Twogether.Core.Entities;
using Twogether.Core.Enums;

namespace Twogether.Application.Features.Billing;

public interface IEntitlementService
{
    /// <summary>The plan for me and my current partner: Plus if either of us has a subscription that grants access.</summary>
    Task<EntitlementDto> ForUserAsync(Guid userId, CancellationToken cancellationToken);
}

public sealed class EntitlementService(IApplicationDbContext db, IDateTimeProvider clock, BillingSettings settings) : IEntitlementService
{
    public async Task<EntitlementDto> ForUserAsync(Guid userId, CancellationToken cancellationToken)
    {
        var now = clock.UtcNow;
        var coupleId = await db.Users.AsNoTracking().Where(user => user.Id == userId).Select(user => user.CoupleId).SingleOrDefaultAsync(cancellationToken);
        var members = new List<Guid> { userId };
        if (coupleId is { } id)
        {
            var couple = await db.Couples.AsNoTracking().SingleOrDefaultAsync(item => item.Id == id && item.Status == CoupleStatus.Active, cancellationToken);
            var partner = couple is null ? null : couple.UserAId == userId ? couple.UserBId : couple.UserAId;
            if (partner is { } other) members.Add(other);
        }

        var candidates = await db.StoreSubscriptions.AsNoTracking()
            .Where(item => members.Contains(item.UserId) && item.ExpiresAtUtc > now)
            .ToListAsync(cancellationToken);
        var granting = candidates.Where(item => item.GrantsAccess(now)).MaxBy(item => item.ExpiresAtUtc);
        // No paid plan: a complimentary grant on the current couple still gives Plus.
        var complimentary = granting is null && coupleId is { } current
            && await db.PlusGrants.AsNoTracking().AnyAsync(item => item.CoupleId == current && item.RevokedAtUtc == null && item.ExpiresAtUtc > now, cancellationToken);
        var plan = granting is null && !complimentary ? Plan.Free : Plan.Plus;
        return new EntitlementDto(
            plan,
            settings.Limits(plan),
            granting is null ? null : new SubscriptionSummaryDto(granting.Store, granting.ProductId, granting.Status, granting.ExpiresAtUtc, granting.AutoRenewing, granting.UserId, granting.UserId == userId),
            CanPurchase: granting is null || granting.UserId == userId,
            Complimentary: complimentary);
    }
}
