using System.Text.RegularExpressions;
using Twogether.Core.Seedwork;

namespace Twogether.Core.Entities;

/// <summary>
/// One payment taken outside Google Play (a Bachs card subscription started from the app after Google's billing choice
/// screen) that must be reported to Google within 24 hours: the first transaction of the subscription, then each paid
/// renewal. An outbox row: written with the webhook's state change, sent by a worker, kept as the record of what was
/// reported. A refund of a reported payment is reported from the same row.
/// </summary>
public sealed partial class GoogleExternalTransaction : BaseEntity
{
    public const string Initial = "initial";
    public const string Renewal = "renewal";
    public const int MaxTokenLength = 2048;
    public const int MaxIdLength = 63;
    public const int MaxBachsIdLength = 100;
    public const int MaxErrorLength = 200;

    private GoogleExternalTransaction() { }

    public Guid UserId { get; private set; }
    /// <summary><see cref="Initial"/> or <see cref="Renewal"/>.</summary>
    public string Kind { get; private set; } = Initial;
    /// <summary>The token from Play Billing's UserChoiceDetails. Only the initial report needs it; cleared once sent.</summary>
    public string? Token { get; private set; }
    /// <summary>Our id for this transaction at Google: 1 to 63 of [a-zA-Z0-9_-], nothing personal.</summary>
    public string ExternalTransactionId { get; private set; } = string.Empty;
    /// <summary>For a renewal: the initial transaction's id. Null on the initial one.</summary>
    public string? InitialExternalTransactionId { get; private set; }
    public string? BachsSubscriptionId { get; private set; }
    /// <summary>The paid invoice this transaction stands for; on the initial row, the first invoice once it is paid.</summary>
    public string? BachsInvoiceId { get; private set; }
    /// <summary>The charge that paid it, to match a Bachs refund to what was reported.</summary>
    public string? BachsChargeId { get; private set; }
    /// <summary>Pre-tax amount in micros (1 USD = 1,000,000). A trial start is 0.</summary>
    public long AmountMicros { get; private set; }
    public string Currency { get; private set; } = "USD";
    /// <summary>The buyer's country (ISO 3166-1 alpha-2), Google's userTaxAddress.</summary>
    public string RegionCode { get; private set; } = string.Empty;
    /// <summary>When the payment (or the trial) started. Null until the checkout completes: nothing to report before.</summary>
    public DateTime? TransactionTimeUtc { get; private set; }
    public DateTime? ReportedAtUtc { get; private set; }
    public int Attempts { get; private set; }
    public DateTime? NextAttemptAtUtc { get; private set; }
    /// <summary>The last failure, short and without secrets.</summary>
    public string? LastError { get; private set; }
    public long? RefundAmountMicros { get; private set; }
    public string? RefundId { get; private set; }
    public DateTime? RefundTimeUtc { get; private set; }
    public DateTime? RefundReportedAtUtc { get; private set; }

    /// <summary>Waiting for Google: ready (the payment happened) and not reported yet.</summary>
    public bool IsDue => TransactionTimeUtc is not null && ReportedAtUtc is null;
    public bool IsRefundDue => ReportedAtUtc is not null && RefundTimeUtc is not null && RefundReportedAtUtc is null;

    [GeneratedRegex("^[A-Za-z0-9_-]{1,63}$")]
    private static partial Regex ValidId();

    /// <summary>Made at checkout, before Bachs is called, so the token is never lost. Ready once the subscription exists.</summary>
    public static GoogleExternalTransaction ForCheckout(Guid userId, string token, string regionCode, DateTime utcNow)
    {
        if (userId == Guid.Empty) throw new ArgumentException("A buyer is required.", nameof(userId));
        var trimmed = token?.Trim() ?? string.Empty;
        if (trimmed.Length is 0 or > MaxTokenLength) throw new ArgumentException("The external transaction token is missing or too long.", nameof(token));
        if (string.IsNullOrWhiteSpace(regionCode) || regionCode.Trim().Length != 2) throw new ArgumentException("A two-letter region code is required.", nameof(regionCode));
        var row = new GoogleExternalTransaction { UserId = userId, Kind = Initial, Token = trimmed, RegionCode = regionCode.Trim().ToUpperInvariant(), CreatedAtUtc = utcNow, UpdatedAtUtc = utcNow };
        row.ExternalTransactionId = "hp-" + row.Id.ToString("N");
        return row;
    }

    /// <summary>A paid renewal of a subscription whose initial transaction is <paramref name="initial"/>.</summary>
    public static GoogleExternalTransaction ForRenewal(GoogleExternalTransaction initial, string invoiceId, string? chargeId, long amountMicros, string? currency, DateTime paidAtUtc, DateTime utcNow)
    {
        if (initial.Kind != Initial) throw new ArgumentException("A renewal follows an initial transaction.", nameof(initial));
        var id = "hp-" + invoiceId.Trim();
        if (!ValidId().IsMatch(id)) id = "hp-" + Guid.NewGuid().ToString("N");
        return new GoogleExternalTransaction
        {
            UserId = initial.UserId,
            Kind = Renewal,
            ExternalTransactionId = id,
            InitialExternalTransactionId = initial.ExternalTransactionId,
            BachsSubscriptionId = initial.BachsSubscriptionId,
            BachsInvoiceId = Clip(invoiceId),
            BachsChargeId = Clip(chargeId),
            AmountMicros = Math.Max(0, amountMicros),
            Currency = NormalizeCurrency(currency),
            RegionCode = initial.RegionCode,
            TransactionTimeUtc = DateTime.SpecifyKind(paidAtUtc, DateTimeKind.Utc),
            CreatedAtUtc = utcNow,
            UpdatedAtUtc = utcNow,
        };
    }

    /// <summary>The checkout became a subscription: the initial transaction is ready to report.</summary>
    public void Start(string subscriptionId, long amountMicros, string? currency, DateTime startedAtUtc, DateTime utcNow)
    {
        if (Kind != Initial || BachsSubscriptionId is not null) throw new InvalidOperationException("Only an initial transaction not yet linked can start.");
        BachsSubscriptionId = Clip(subscriptionId);
        AmountMicros = Math.Max(0, amountMicros);
        Currency = NormalizeCurrency(currency);
        TransactionTimeUtc = DateTime.SpecifyKind(startedAtUtc, DateTimeKind.Utc);
        NextAttemptAtUtc = null;
        UpdatedAtUtc = utcNow;
    }

    /// <summary>The first paid invoice, whose amount the initial transaction already carries.</summary>
    public void CoverInvoice(string invoiceId, string? chargeId, DateTime utcNow)
    {
        BachsInvoiceId = Clip(invoiceId);
        BachsChargeId ??= Clip(chargeId);
        UpdatedAtUtc = utcNow;
    }

    public void MarkReported(DateTime utcNow)
    {
        ReportedAtUtc = utcNow;
        Token = null;
        LastError = null;
        NextAttemptAtUtc = null;
        UpdatedAtUtc = utcNow;
    }

    public void MarkFailed(string error, DateTime utcNow)
    {
        Attempts++;
        LastError = error.Length > MaxErrorLength ? error[..MaxErrorLength] : error;
        // 1, 2, 4 ... minutes, at most an hour: well inside Google's 24 hours, without hammering it.
        NextAttemptAtUtc = utcNow + TimeSpan.FromMinutes(Math.Min(60, Math.Pow(2, Math.Min(Attempts - 1, 6))));
        UpdatedAtUtc = utcNow;
    }

    /// <summary>Bachs refunded the payment this row reported (fully when the amounts match).</summary>
    public void RequestRefund(string? refundId, long amountMicros, DateTime refundedAtUtc, DateTime utcNow)
    {
        if (RefundTimeUtc is not null) return;
        RefundId = Clip(refundId);
        RefundAmountMicros = Math.Max(0, amountMicros);
        RefundTimeUtc = DateTime.SpecifyKind(refundedAtUtc, DateTimeKind.Utc);
        NextAttemptAtUtc = null;
        UpdatedAtUtc = utcNow;
    }

    public void MarkRefundReported(DateTime utcNow)
    {
        RefundReportedAtUtc = utcNow;
        LastError = null;
        NextAttemptAtUtc = null;
        UpdatedAtUtc = utcNow;
    }

    private static string? Clip(string? value)
    {
        var trimmed = value?.Trim();
        return string.IsNullOrEmpty(trimmed) ? null : trimmed.Length > MaxBachsIdLength ? trimmed[..MaxBachsIdLength] : trimmed;
    }

    private static string NormalizeCurrency(string? currency)
        => currency?.Trim() is { Length: 3 } code ? code.ToUpperInvariant() : "USD";
}
