using Twogether.Core.Enums;

namespace Twogether.Core.Billing;

public static class EntitlementRules
{
    /// <summary>
    /// Paid access: active, cancelled-but-paid-up, or in Google's grace period, and not past
    /// expiry. On hold, paused, pending and expired give nothing.
    /// </summary>
    public static bool Grants(StoreSubscriptionStatus status, DateTime expiresAtUtc, DateTime utcNow)
        => status is StoreSubscriptionStatus.Active or StoreSubscriptionStatus.Canceled or StoreSubscriptionStatus.InGracePeriod
           && expiresAtUtc > utcNow;

    /// <summary>The obfuscated account id the app sets at checkout: the buyer's user id, no dashes.</summary>
    public static string AccountId(Guid userId) => userId.ToString("N");
}
