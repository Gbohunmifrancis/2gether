using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Twogether.Core.Enums;
using Twogether.Shared.Results;

namespace Twogether.Application.Features.Billing;

/// <summary>
/// Bound from the "Bachs" section (bachs.io: card subscriptions on the web, and in the app where Google's billing choice
/// allows it). Prices live in the Bachs dashboard, on the two products; nothing here or from a client sets an amount.
/// </summary>
public sealed class BachsSettings
{
    public const string SandboxUrl = "https://sandbox-api.bachs.io";
    public const string ProductionUrl = "https://api.bachs.io";

    /// <summary>sk_sandbox_... or sk_live_...; the key decides the environment. Never logged.</summary>
    public string? ApiKey { get; init; }
    /// <summary>The webhook endpoint's signing secret (whsec_...). Never logged.</summary>
    public string? WebhookSecret { get; init; }
    public string? MonthlyProductId { get; init; }
    public string? YearlyProductId { get; init; }
    /// <summary>Optional override of the API's address (a local stand-in); otherwise chosen from the key.</summary>
    public string? ApiUrl { get; init; }
    public string SuccessUrl { get; init; } = "https://heartpact.me/plus/thanks/";
    public string CancelUrl { get; init; } = "https://heartpact.me/plus/";

    /// <summary>Checkout, the portal and webhooks all need the key, the secret and both products.</summary>
    public bool IsConfigured => !string.IsNullOrWhiteSpace(ApiKey) && !string.IsNullOrWhiteSpace(WebhookSecret)
        && !string.IsNullOrWhiteSpace(MonthlyProductId) && !string.IsNullOrWhiteSpace(YearlyProductId);

    public string BaseUrl => !string.IsNullOrWhiteSpace(ApiUrl) ? ApiUrl.Trim().TrimEnd('/')
        : ApiKey?.Trim().StartsWith("sk_live_", StringComparison.Ordinal) == true ? ProductionUrl : SandboxUrl;

    /// <summary>The Bachs product for a plan the app or site asks for; null for anything else.</summary>
    public string? BachsProductFor(string? plan) => plan?.Trim().ToLowerInvariant() switch
    {
        "monthly" => MonthlyProductId?.Trim(),
        "yearly" => YearlyProductId?.Trim(),
        _ => null
    };

    /// <summary>Our product id for a Bachs product, the same ids Google Play uses, so limits and revenue read alike.</summary>
    public string? OurProductFor(string? bachsProductId)
    {
        if (string.IsNullOrWhiteSpace(bachsProductId)) return null;
        if (string.Equals(bachsProductId, MonthlyProductId?.Trim(), StringComparison.Ordinal)) return BachsPlans.MonthlyProductId;
        if (string.Equals(bachsProductId, YearlyProductId?.Trim(), StringComparison.Ordinal)) return BachsPlans.YearlyProductId;
        return null;
    }
}

public static class BachsPlans
{
    public const string MonthlyProductId = "heartpact_plus_monthly";
    public const string YearlyProductId = "heartpact_plus_yearly";

    /// <summary>
    /// Where card payments may be offered inside the Android app under Google Play's billing choice: the United States,
    /// the United Kingdom, the 27 EU states, Iceland, Liechtenstein and Norway.
    /// </summary>
    public static readonly IReadOnlySet<string> CardInAppCountries = new HashSet<string>(StringComparer.OrdinalIgnoreCase)
    {
        "US", "GB",
        "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR", "HU", "IE", "IT", "LV", "LT", "LU", "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE",
        "IS", "LI", "NO",
    };

    public static bool CardInAppCountry(string? countryCode) => countryCode is { Length: 2 } && CardInAppCountries.Contains(countryCode);
}

/// <summary>What our checkout sends to Bachs. The product comes from <see cref="BachsSettings"/>, never from a client.</summary>
public sealed record BachsCheckoutRequest(
    string ProductId,
    Guid UserId,
    string? CustomerId,
    string? Email,
    string? Name,
    string SuccessUrl,
    string CancelUrl,
    Guid? ExternalRef = null);

public sealed record BachsCheckout(string CheckoutId, string CheckoutUrl);

/// <summary>A Bachs subscription, read leniently: the docs name some fields two ways.</summary>
public sealed record BachsSubscriptionSnapshot(
    string SubscriptionId,
    string? CustomerId,
    string? ProductId,
    string Status,
    DateTime? CurrentPeriodEnd,
    DateTime? TrialEnd,
    bool CancelAtPeriodEnd,
    DateTime? CanceledAt,
    string? MetadataUserId,
    string? MetadataExternalRef,
    long? AmountMicros,
    string? Currency,
    DateTime? CreatedAt);

/// <summary>A paid (or failed) invoice from an invoice.* event.</summary>
public sealed record BachsInvoiceSnapshot(string InvoiceId, string? SubscriptionId, string? ChargeId, long AmountPaidMicros, string? Currency, string? Status);

/// <summary>A refund from a refund.* event.</summary>
public sealed record BachsRefundSnapshot(string RefundId, string? ChargeId, string? Status, long? RefundedMicros);

public sealed record BachsEvent(string Id, string Type, DateTime CreatedAtUtc, JsonElement Data);

/// <summary>Outbound port to the Bachs API.</summary>
public interface IBachsBilling
{
    bool IsConfigured { get; }
    Task<Result<BachsCheckout>> CreateCheckoutAsync(BachsCheckoutRequest request, string idempotencyKey, CancellationToken cancellationToken);
    /// <summary>A short-lived portal link for the customer. The URL signs them in: never log or store it.</summary>
    Task<Result<string>> CreatePortalSessionAsync(string customerId, CancellationToken cancellationToken);
    Task<Result<BachsSubscriptionSnapshot>> GetSubscriptionAsync(string subscriptionId, CancellationToken cancellationToken);
    /// <summary>At period end: access lasts until it, then the subscription ends. Otherwise now, with no refund.</summary>
    Task<Result<BachsSubscriptionSnapshot>> CancelSubscriptionAsync(string subscriptionId, bool atPeriodEnd, CancellationToken cancellationToken);
}

public static class BachsEvents
{
    public const string SubscriptionCreated = "customer.subscription.created";
    public const string SubscriptionUpdated = "customer.subscription.updated";
    public const string SubscriptionDeleted = "customer.subscription.deleted";
    public const string InvoicePaid = "invoice.paid";
    public const string InvoicePaymentFailed = "invoice.payment_failed";
    public const string RefundPaid = "refund.paid";

    public static bool IsSubscription(string type) => type is SubscriptionCreated or SubscriptionUpdated or SubscriptionDeleted;

    /// <summary>The envelope, or null when it has no id, type or time.</summary>
    public static BachsEvent? ReadEvent(string rawBody)
    {
        try
        {
            using var document = JsonDocument.Parse(rawBody);
            var root = document.RootElement;
            if (root.ValueKind != JsonValueKind.Object) return null;
            var id = Text(root, "id");
            var type = Text(root, "type");
            var created = Time(root, "created_at");
            if (id is null || type is null || created is null) return null;
            var data = root.TryGetProperty("data", out var element) ? element.Clone() : default;
            return new BachsEvent(id, type, created.Value, data);
        }
        catch (JsonException)
        {
            return null;
        }
    }

    /// <summary>A subscription object (an event's data, or an API response); null without an id or status.</summary>
    public static BachsSubscriptionSnapshot? ReadSubscription(JsonElement data)
    {
        if (data.ValueKind != JsonValueKind.Object) return null;
        var id = Text(data, "subscription_id") ?? Text(data, "id");
        var status = Text(data, "status");
        if (id is null || status is null) return null;
        var customer = data.TryGetProperty("customer", out var customerElement) && customerElement.ValueKind == JsonValueKind.Object
            ? Text(customerElement, "customer_id") ?? Text(customerElement, "id")
            : null;
        customer ??= Text(data, "customer_id");
        var product = Text(data, "product_id")
            ?? (data.TryGetProperty("product", out var productElement) && productElement.ValueKind == JsonValueKind.Object ? Text(productElement, "id") ?? Text(productElement, "product_id") : null);
        string? userId = null, externalRef = null;
        if (data.TryGetProperty("metadata", out var metadata) && metadata.ValueKind == JsonValueKind.Object)
        {
            userId = Text(metadata, "user_id");
            externalRef = Text(metadata, "external_ref");
        }
        var cancelAtPeriodEnd = data.TryGetProperty("cancel_at_period_end", out var cancel) && cancel.ValueKind == JsonValueKind.True;
        return new BachsSubscriptionSnapshot(id, customer, product, status.ToLowerInvariant(), Time(data, "current_period_end"), Time(data, "trial_end"),
            cancelAtPeriodEnd, Time(data, "canceled_at"), userId, externalRef, Micros(Text(data, "amount")), Text(data, "currency"), Time(data, "created_at"));
    }

    public static BachsInvoiceSnapshot? ReadInvoice(JsonElement data)
    {
        if (data.ValueKind != JsonValueKind.Object) return null;
        var id = Text(data, "invoice_id") ?? Text(data, "id");
        if (id is null) return null;
        var subscription = data.TryGetProperty("subscription", out var sub) && sub.ValueKind == JsonValueKind.Object
            ? Text(sub, "subscription_id") ?? Text(sub, "id")
            : Text(data, "subscription_id");
        var charge = data.TryGetProperty("charge", out var chargeElement) && chargeElement.ValueKind == JsonValueKind.Object
            ? Text(chargeElement, "id") ?? Text(chargeElement, "charge_id") ?? Text(chargeElement, "payment_id")
            : Text(data, "charge_id");
        return new BachsInvoiceSnapshot(id, subscription, charge, Micros(Text(data, "amount_paid") ?? Text(data, "total")) ?? 0, Text(data, "currency"), Text(data, "status"));
    }

    public static BachsRefundSnapshot? ReadRefund(JsonElement data)
    {
        if (data.ValueKind != JsonValueKind.Object) return null;
        var id = Text(data, "refund_id") ?? Text(data, "id");
        if (id is null) return null;
        return new BachsRefundSnapshot(id, Text(data, "charge_id"), Text(data, "status"), Micros(Text(data, "refunded_amount") ?? Text(data, "requested_amount")));
    }

    /// <summary>"7.99" as 7,990,000 micros. Money is always a decimal string; anything else reads as missing.</summary>
    public static long? Micros(string? amount)
        => decimal.TryParse(amount, NumberStyles.AllowDecimalPoint, CultureInfo.InvariantCulture, out var value) && value >= 0
            ? (long)Math.Round(value * 1_000_000m, MidpointRounding.AwayFromZero)
            : null;

    private static string? Text(JsonElement element, string name)
        => element.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String && !string.IsNullOrWhiteSpace(value.GetString()) ? value.GetString()!.Trim()
            : element.TryGetProperty(name, out value) && value.ValueKind == JsonValueKind.Number ? value.GetRawText()
            : null;

    private static DateTime? Time(JsonElement element, string name)
        => Text(element, name) is { } text && DateTimeOffset.TryParse(text, CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal, out var at)
            ? at.UtcDateTime
            : null;
}

/// <summary>Bachs statuses in our vocabulary, chosen so <c>EntitlementRules.Grants</c> needs no Bachs knowledge.</summary>
public static class BachsStatusMapping
{
    public sealed record State(StoreSubscriptionStatus Status, DateTime ExpiresAtUtc, bool AutoRenewing);

    /// <summary>
    /// trialing and active are Active (Canceled once renewal is off: still paid up), past_due is a grace period while
    /// Bachs retries, unpaid is on hold, canceled is over. Access lasts to the end of the period (or the trial, when
    /// later). Anything unknown grants nothing.
    /// </summary>
    public static State Map(BachsSubscriptionSnapshot subscription, DateTime eventAtUtc)
    {
        var canceled = subscription.Status == "canceled";
        var status = subscription.Status switch
        {
            "trialing" or "active" => subscription.CancelAtPeriodEnd ? StoreSubscriptionStatus.Canceled : StoreSubscriptionStatus.Active,
            "past_due" => StoreSubscriptionStatus.InGracePeriod,
            "unpaid" => StoreSubscriptionStatus.OnHold,
            "canceled" => StoreSubscriptionStatus.Expired,
            _ => StoreSubscriptionStatus.Pending
        };
        DateTime expires;
        if (canceled) expires = subscription.CanceledAt ?? eventAtUtc;
        else
        {
            var end = subscription.CurrentPeriodEnd;
            if (subscription.Status == "trialing" && subscription.TrialEnd is { } trialEnd && (end is null || trialEnd > end)) end = trialEnd;
            // No period end: nothing to grant until Bachs says how long.
            expires = end ?? eventAtUtc;
        }
        return new State(status, DateTime.SpecifyKind(expires, DateTimeKind.Utc), !subscription.CancelAtPeriodEnd && !canceled);
    }
}

/// <summary>The X-Bachs-Signature-V2 check: "t={unix},v1={hex}[,v1={hex}...]", HMAC-SHA256 of "{t}.{raw body}".</summary>
public static class BachsSignature
{
    public const string Header = "X-Bachs-Signature-V2";
    public static readonly TimeSpan Tolerance = TimeSpan.FromSeconds(300);

    /// <summary>
    /// True when the timestamp is within five minutes of now and any v1 value (there is one per valid secret while a
    /// secret is being rotated) matches, compared in constant time. Run it on the raw bytes, before any parsing.
    /// </summary>
    public static bool Verify(string? header, ReadOnlySpan<byte> rawBody, string secret, DateTime utcNow)
    {
        if (string.IsNullOrWhiteSpace(header) || string.IsNullOrEmpty(secret)) return false;
        long? timestamp = null;
        var candidates = new List<string>();
        foreach (var part in header.Split(',', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries))
        {
            var equals = part.IndexOf('=');
            if (equals <= 0) continue;
            var key = part[..equals];
            var value = part[(equals + 1)..];
            if (key == "t" && timestamp is null && long.TryParse(value, NumberStyles.None, CultureInfo.InvariantCulture, out var seconds)) timestamp = seconds;
            else if (key == "v1") candidates.Add(value);
        }
        if (timestamp is not { } t || candidates.Count == 0) return false;
        var sent = DateTimeOffset.FromUnixTimeSeconds(Math.Clamp(t, 0, 253402300799)).UtcDateTime;
        if ((utcNow - sent).Duration() > Tolerance) return false;

        var prefix = Encoding.UTF8.GetBytes(t.ToString(CultureInfo.InvariantCulture) + ".");
        var signed = new byte[prefix.Length + rawBody.Length];
        prefix.CopyTo(signed, 0);
        rawBody.CopyTo(signed.AsSpan(prefix.Length));
        var expected = HMACSHA256.HashData(Encoding.UTF8.GetBytes(secret), signed);
        var matched = false;
        foreach (var candidate in candidates)
        {
            if (candidate.Length != expected.Length * 2) continue;
            byte[] given;
            try { given = Convert.FromHexString(candidate); }
            catch (FormatException) { continue; }
            // No early exit: every candidate costs the same.
            matched |= CryptographicOperations.FixedTimeEquals(given, expected);
        }
        return matched;
    }

    /// <summary>The header Bachs would send, for tests and local tools.</summary>
    public static string Sign(string secret, ReadOnlySpan<byte> rawBody, DateTime utcNow)
    {
        var t = new DateTimeOffset(DateTime.SpecifyKind(utcNow, DateTimeKind.Utc)).ToUnixTimeSeconds().ToString(CultureInfo.InvariantCulture);
        var prefix = Encoding.UTF8.GetBytes(t + ".");
        var signed = new byte[prefix.Length + rawBody.Length];
        prefix.CopyTo(signed, 0);
        rawBody.CopyTo(signed.AsSpan(prefix.Length));
        return $"t={t},v1={Convert.ToHexStringLower(HMACSHA256.HashData(Encoding.UTF8.GetBytes(secret), signed))}";
    }
}
