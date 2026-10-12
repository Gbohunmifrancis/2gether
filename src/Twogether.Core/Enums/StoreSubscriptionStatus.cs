namespace Twogether.Core.Enums;

/// <summary>Mirrors Google Play's subscription states.</summary>
public enum StoreSubscriptionStatus
{
    Pending,
    Active,
    /// <summary>Auto-renew is off; still paid for until expiry.</summary>
    Canceled,
    /// <summary>Payment failed, Google is retrying; access continues during the grace period.</summary>
    InGracePeriod,
    /// <summary>Payment failed after grace; no access until fixed.</summary>
    OnHold,
    Paused,
    Expired,
    /// <summary>A pending purchase that was never paid.</summary>
    PendingPurchaseCanceled
}
