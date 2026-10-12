using Twogether.Core.Billing;
using Twogether.Core.Enums;
using Twogether.Core.Seedwork;
using Twogether.Shared.Guards;

namespace Twogether.Core.Entities;

/// <summary>
/// A store subscription, owned by the person who paid. It covers whichever couple they are in,
/// so a breakup needs no special handling: the plan simply leaves with its buyer.
/// </summary>
public sealed class StoreSubscription : BaseEntity
{
    public const string GooglePlay = "googlePlay";
    public const int MaxTokenLength = 1024;

    private StoreSubscription() { }

    public string Store { get; private set; } = GooglePlay;
    public string PurchaseToken { get; private set; } = string.Empty;
    public string ProductId { get; private set; } = string.Empty;
    public Guid UserId { get; private set; }
    public StoreSubscriptionStatus Status { get; private set; }
    public DateTime ExpiresAtUtc { get; private set; }
    public bool AutoRenewing { get; private set; }
    public bool Acknowledged { get; private set; }
    /// <summary>The token this one replaced (upgrade, downgrade, resubscribe).</summary>
    public string? LinkedPurchaseToken { get; private set; }
    public DateTime LastVerifiedAtUtc { get; private set; }

    public bool GrantsAccess(DateTime utcNow) => EntitlementRules.Grants(Status, ExpiresAtUtc, utcNow);

    public static StoreSubscription Create(string purchaseToken, Guid userId, DateTime utcNow)
    {
        var token = Guard.NotNullOrWhiteSpace(purchaseToken, nameof(purchaseToken));
        if (token.Length > MaxTokenLength) throw new ArgumentException("Purchase token is too long.", nameof(purchaseToken));
        if (userId == Guid.Empty) throw new ArgumentException("A buyer is required.", nameof(userId));
        return new StoreSubscription { PurchaseToken = token, UserId = userId, CreatedAtUtc = utcNow, UpdatedAtUtc = utcNow };
    }

    /// <summary>Overwrites everything with what the store just said. The store is the source of truth.</summary>
    public void ApplyStoreState(string productId, StoreSubscriptionStatus status, DateTime expiresAtUtc, bool autoRenewing, bool acknowledged, string? linkedPurchaseToken, DateTime utcNow)
    {
        ProductId = Guard.NotNullOrWhiteSpace(productId, nameof(productId));
        Status = status;
        ExpiresAtUtc = DateTime.SpecifyKind(expiresAtUtc, DateTimeKind.Utc);
        AutoRenewing = autoRenewing;
        Acknowledged = acknowledged;
        LinkedPurchaseToken = string.IsNullOrWhiteSpace(linkedPurchaseToken) ? null : linkedPurchaseToken;
        LastVerifiedAtUtc = utcNow;
        UpdatedAtUtc = utcNow;
    }

    public void MarkAcknowledged(DateTime utcNow)
    {
        Acknowledged = true;
        UpdatedAtUtc = utcNow;
    }
}
