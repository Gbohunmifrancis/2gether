using Twogether.Core.Enums;
using Twogether.Shared.Results;

namespace Twogether.Application.Features.Billing;

public sealed record PlanLimits(int FoodScansPerDay, long MemoriesBytes, int QuestionHistoryDays);

/// <summary>Bound from the "Billing" section.</summary>
public sealed class BillingSettings
{
    /// <summary>"googlePlay", "fake" (development only; refused in Production) or empty (everyone is on Free).</summary>
    public string? Provider { get; init; }
    public string? GooglePlayPackageName { get; init; }
    public string? GooglePlayCredentialsJson { get; init; }
    /// <summary>Products that grant Plus. Anything else is refused, so a cheap test product cannot unlock the app.</summary>
    public IReadOnlyList<string> ProductIds { get; init; } = [];
    /// <summary>Secret in the Pub/Sub push URL for real-time notifications.</summary>
    public string? RtdnSecret { get; init; }
    public PlanLimits Free { get; init; } = new(3, 500L * 1024 * 1024, 7);
    public PlanLimits Plus { get; init; } = new(20, 2L * 1024 * 1024 * 1024, 90);

    public PlanLimits Limits(Plan plan) => plan == Plan.Plus ? Plus : Free;
}

/// <summary>What Google says about one purchase token, already mapped to our vocabulary.</summary>
public sealed record PlaySubscriptionSnapshot(
    string ProductId,
    StoreSubscriptionStatus Status,
    DateTime ExpiresAtUtc,
    bool AutoRenewing,
    bool Acknowledged,
    string? ObfuscatedAccountId,
    string? LinkedPurchaseToken);

/// <summary>Outbound port to the Google Play Developer API.</summary>
public interface IGooglePlayBilling
{
    bool IsConfigured { get; }
    Task<Result<PlaySubscriptionSnapshot>> GetAsync(string purchaseToken, CancellationToken cancellationToken);
    Task<Result> AcknowledgeAsync(string productId, string purchaseToken, CancellationToken cancellationToken);
    /// <summary>Stops renewal; access lasts until the paid period ends.</summary>
    Task<Result> CancelAsync(string productId, string purchaseToken, CancellationToken cancellationToken);
    /// <summary>Refunds the latest payment; the subscription carries on.</summary>
    Task<Result> RefundAsync(string productId, string purchaseToken, CancellationToken cancellationToken);
    /// <summary>Refunds the latest payment and ends access now.</summary>
    Task<Result> RevokeAsync(string productId, string purchaseToken, CancellationToken cancellationToken);
}

public sealed record SubscriptionSummaryDto(string Store, string ProductId, StoreSubscriptionStatus Status, DateTime ExpiresAtUtc, bool AutoRenewing, Guid PurchasedByUserId, bool PurchasedByMe);

/// <summary>
/// CanPurchase is false when my partner already pays: one plan covers both of us. Complimentary: Plus
/// comes from a grant by Heartpact (support), not a purchase, until ComplimentaryUntilUtc. FreeLimits,
/// PlusLimits and ProductIds let the app show the comparison and the plans without hard-coding them.
/// </summary>
public sealed record EntitlementDto(
    Plan Plan,
    PlanLimits Limits,
    SubscriptionSummaryDto? Subscription,
    bool CanPurchase,
    bool Complimentary = false,
    DateTime? ComplimentaryUntilUtc = null,
    PlanLimits? FreeLimits = null,
    PlanLimits? PlusLimits = null,
    IReadOnlyList<string>? ProductIds = null);

public sealed record VerifyGooglePurchaseRequest(string ProductId, string PurchaseToken);

public static class PlayStateMapping
{
    public static StoreSubscriptionStatus Map(string? state) => state switch
    {
        "SUBSCRIPTION_STATE_ACTIVE" => StoreSubscriptionStatus.Active,
        "SUBSCRIPTION_STATE_CANCELED" => StoreSubscriptionStatus.Canceled,
        "SUBSCRIPTION_STATE_IN_GRACE_PERIOD" => StoreSubscriptionStatus.InGracePeriod,
        "SUBSCRIPTION_STATE_ON_HOLD" => StoreSubscriptionStatus.OnHold,
        "SUBSCRIPTION_STATE_PAUSED" => StoreSubscriptionStatus.Paused,
        "SUBSCRIPTION_STATE_EXPIRED" => StoreSubscriptionStatus.Expired,
        "SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED" => StoreSubscriptionStatus.PendingPurchaseCanceled,
        // Unknown or pending: grant nothing until Google says otherwise.
        _ => StoreSubscriptionStatus.Pending
    };
}
