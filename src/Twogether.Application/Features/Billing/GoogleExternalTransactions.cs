using System.Globalization;
using System.Text.Json.Nodes;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Logging;
using Twogether.Application.Common.Interfaces;
using Twogether.Core.Entities;
using Twogether.Shared.Results;

namespace Twogether.Application.Features.Billing;

/// <summary>
/// Outbound port to Google Play's externaltransactions API: reports a payment taken outside Play under the billing
/// choice programme. A transaction Google already has (409 for the same id) counts as reported.
/// </summary>
public interface IGoogleExternalTransactionReporter
{
    bool IsConfigured { get; }
    Task<Result> CreateAsync(string externalTransactionId, JsonObject body, CancellationToken cancellationToken);
    Task<Result> RefundAsync(string externalTransactionId, JsonObject body, CancellationToken cancellationToken);
}

/// <summary>Google's request bodies, built from an outbox row. Pure, so the shapes are tested on their own.</summary>
public static class GoogleExternalTransactionBodies
{
    public static JsonObject Create(GoogleExternalTransaction row)
    {
        var recurring = new JsonObject();
        if (row.Kind == GoogleExternalTransaction.Initial) recurring["externalTransactionToken"] = row.Token;
        else recurring["initialExternalTransactionId"] = row.InitialExternalTransactionId;
        recurring["externalSubscription"] = new JsonObject { ["subscriptionType"] = "RECURRING" };
        return new JsonObject
        {
            ["originalPreTaxAmount"] = Money(row.AmountMicros, row.Currency),
            // Bachs does not add tax to these prices.
            ["originalTaxAmount"] = Money(0, row.Currency),
            ["transactionTime"] = Rfc3339(row.TransactionTimeUtc!.Value),
            ["recurringTransaction"] = recurring,
            ["userTaxAddress"] = new JsonObject { ["regionCode"] = row.RegionCode },
        };
    }

    public static JsonObject Refund(GoogleExternalTransaction row)
    {
        var body = new JsonObject { ["refundTime"] = Rfc3339(row.RefundTimeUtc!.Value) };
        if (row.RefundAmountMicros is { } amount && amount < row.AmountMicros)
            body["partialRefund"] = new JsonObject
            {
                ["refundId"] = SafeId(row.RefundId) ?? "refund-" + row.ExternalTransactionId,
                ["refundPreTaxAmount"] = Money(amount, row.Currency),
            };
        else body["fullRefund"] = new JsonObject();
        return body;
    }

    private static JsonObject Money(long micros, string currency)
        => new() { ["priceMicros"] = micros.ToString(CultureInfo.InvariantCulture), ["currency"] = currency };

    private static string Rfc3339(DateTime utc) => DateTime.SpecifyKind(utc, DateTimeKind.Utc).ToString("yyyy-MM-dd'T'HH:mm:ss'Z'", CultureInfo.InvariantCulture);

    private static string? SafeId(string? id)
        => id is { Length: > 0 and <= 63 } && id.All(c => char.IsAsciiLetterOrDigit(c) || c is '_' or '-') ? id : null;
}

/// <summary>
/// Sends the outbox (docs/billing.md, Billing choice): every ready, unreported transaction, then refunds of reported ones.
/// A renewal waits for its initial transaction. Failures back off and stay in the outbox; nothing is ever dropped.
/// </summary>
public sealed class GoogleExternalTransactionSender(
    IApplicationDbContext db,
    IGoogleExternalTransactionReporter google,
    IDateTimeProvider clock,
    ILogger<GoogleExternalTransactionSender> logger)
{
    public const int BatchSize = 50;
    /// <summary>Google wants a report within 24 hours; past this, someone should look.</summary>
    public static readonly TimeSpan Overdue = TimeSpan.FromHours(12);

    public sealed record Outcome(int Reported, int Failed, int Overdue);

    public async Task<Outcome> RunAsync(CancellationToken cancellationToken)
    {
        var now = clock.UtcNow;
        var overdue = await db.GoogleExternalTransactions.AsNoTracking()
            .CountAsync(item => item.TransactionTimeUtc != null && item.ReportedAtUtc == null && item.TransactionTimeUtc < now - Overdue, cancellationToken);
        if (overdue > 0)
            logger.LogError("{Count} Google external transactions are still unreported more than 12 hours after the payment; Google requires them within 24.", overdue);
        // Without credentials the rows wait: they are sent once the service account is set.
        if (!google.IsConfigured) return new Outcome(0, 0, overdue);

        var reported = 0;
        var failed = 0;
        var due = await db.GoogleExternalTransactions
            .Where(item => item.TransactionTimeUtc != null && item.ReportedAtUtc == null && (item.NextAttemptAtUtc == null || item.NextAttemptAtUtc <= now))
            .OrderBy(item => item.TransactionTimeUtc).Take(BatchSize).ToListAsync(cancellationToken);
        foreach (var row in due)
        {
            if (row.Kind == GoogleExternalTransaction.Renewal
                && !await db.GoogleExternalTransactions.AnyAsync(item => item.ExternalTransactionId == row.InitialExternalTransactionId && item.ReportedAtUtc != null, cancellationToken))
                continue;
            var result = await google.CreateAsync(row.ExternalTransactionId, GoogleExternalTransactionBodies.Create(row), cancellationToken);
            if (result.IsSuccess) { row.MarkReported(clock.UtcNow); reported++; }
            else { row.MarkFailed(result.Error.Code, clock.UtcNow); failed++; }
            await db.SaveChangesAsync(cancellationToken);
        }

        var refunds = await db.GoogleExternalTransactions
            .Where(item => item.ReportedAtUtc != null && item.RefundTimeUtc != null && item.RefundReportedAtUtc == null && (item.NextAttemptAtUtc == null || item.NextAttemptAtUtc <= now))
            .OrderBy(item => item.RefundTimeUtc).Take(BatchSize).ToListAsync(cancellationToken);
        foreach (var row in refunds)
        {
            var result = await google.RefundAsync(row.ExternalTransactionId, GoogleExternalTransactionBodies.Refund(row), cancellationToken);
            if (result.IsSuccess) { row.MarkRefundReported(clock.UtcNow); reported++; }
            else { row.MarkFailed(result.Error.Code, clock.UtcNow); failed++; }
            await db.SaveChangesAsync(cancellationToken);
        }
        if (failed > 0) logger.LogWarning("Reporting {Failed} Google external transactions failed; they are retried with backoff.", failed);
        return new Outcome(reported, failed, overdue);
    }
}
