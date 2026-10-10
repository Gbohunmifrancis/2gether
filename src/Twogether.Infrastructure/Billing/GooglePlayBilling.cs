using System.Collections.Concurrent;
using System.Globalization;
using System.Net;
using Google;
using Google.Apis.AndroidPublisher.v3;
using Google.Apis.AndroidPublisher.v3.Data;
using Google.Apis.Auth.OAuth2;
using Google.Apis.Services;
using Microsoft.Extensions.Logging;
using Twogether.Application.Features.Billing;
using Twogether.Core.Enums;
using Twogether.Shared.Results;

namespace Twogether.Infrastructure.Billing;

/// <summary>The Google Play Developer API (subscriptionsv2), authenticated with a service account.</summary>
public sealed class GooglePlayBilling : IGooglePlayBilling, IDisposable
{
    private readonly AndroidPublisherService? _service;
    private readonly string _packageName = string.Empty;
    private readonly ILogger<GooglePlayBilling> _logger;

    public GooglePlayBilling(BillingSettings settings, ILogger<GooglePlayBilling> logger)
    {
        _logger = logger;
        if (string.IsNullOrWhiteSpace(settings.GooglePlayCredentialsJson) || string.IsNullOrWhiteSpace(settings.GooglePlayPackageName)) return;
        var credential = CredentialFactory.FromJson<ServiceAccountCredential>(settings.GooglePlayCredentialsJson)
            .ToGoogleCredential()
            .CreateScoped(AndroidPublisherService.ScopeConstants.Androidpublisher);
        _service = new AndroidPublisherService(new BaseClientService.Initializer { HttpClientInitializer = credential, ApplicationName = "Heartpact" });
        _packageName = settings.GooglePlayPackageName;
    }

    public bool IsConfigured => _service is not null;

    public async Task<Result<PlaySubscriptionSnapshot>> GetAsync(string purchaseToken, CancellationToken cancellationToken)
    {
        if (_service is null) return Unavailable();
        try
        {
            var purchase = await _service.Purchases.Subscriptionsv2.Get(_packageName, purchaseToken).ExecuteAsync(cancellationToken);
            var line = purchase.LineItems?.OrderByDescending(item => item.ExpiryTimeDateTimeOffset).FirstOrDefault();
            if (line?.ProductId is null || line.ExpiryTimeDateTimeOffset is null)
                return Error.Failure("billing.unreadable", "Google returned a subscription without a product or expiry.");
            return new PlaySubscriptionSnapshot(
                line.ProductId,
                PlayStateMapping.Map(purchase.SubscriptionState),
                line.ExpiryTimeDateTimeOffset.Value.UtcDateTime,
                line.AutoRenewingPlan?.AutoRenewEnabled ?? false,
                purchase.AcknowledgementState == "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED",
                purchase.ExternalAccountIdentifiers?.ObfuscatedExternalAccountId,
                purchase.LinkedPurchaseToken);
        }
        catch (GoogleApiException exception) when (exception.HttpStatusCode is HttpStatusCode.NotFound or HttpStatusCode.Gone or HttpStatusCode.BadRequest)
        {
            return Error.NotFound("billing.unknown_purchase", "Google does not know this purchase.");
        }
        catch (GoogleApiException exception)
        {
            _logger.LogWarning(exception, "Google Play lookup failed with {Status}.", exception.HttpStatusCode);
            return new Error("billing.store_unavailable", "Google Play could not be reached. Try again shortly.", ErrorType.Network);
        }
        catch (HttpRequestException exception)
        {
            _logger.LogWarning(exception, "Google Play lookup failed on the network.");
            return new Error("billing.store_unavailable", "Google Play could not be reached. Try again shortly.", ErrorType.Network);
        }
    }

    public async Task<Result> AcknowledgeAsync(string productId, string purchaseToken, CancellationToken cancellationToken)
    {
        if (_service is null) return Unavailable();
        try
        {
            await _service.Purchases.Subscriptions.Acknowledge(new SubscriptionPurchasesAcknowledgeRequest(), _packageName, productId, purchaseToken).ExecuteAsync(cancellationToken);
            return Result.Success();
        }
        catch (Exception exception) when (exception is GoogleApiException or HttpRequestException)
        {
            _logger.LogWarning(exception, "Acknowledging a Google Play purchase failed; the next verify or notification retries it.");
            return new Error("billing.acknowledge_failed", "The purchase could not be acknowledged yet.", ErrorType.Network);
        }
    }

    public void Dispose() => _service?.Dispose();

    private static Error Unavailable() => new("billing.store_unavailable", "Billing is not configured on this server.", ErrorType.Failure);
}

/// <summary>Billing is off: nobody can buy, everyone is on Free.</summary>
public sealed class DisabledGooglePlayBilling : IGooglePlayBilling
{
    public bool IsConfigured => false;
    public Task<Result<PlaySubscriptionSnapshot>> GetAsync(string purchaseToken, CancellationToken cancellationToken)
        => Task.FromResult(Result.Failure<PlaySubscriptionSnapshot>(new Error("billing.store_unavailable", "Billing is not configured on this server.", ErrorType.Failure)));
    public Task<Result> AcknowledgeAsync(string productId, string purchaseToken, CancellationToken cancellationToken)
        => Task.FromResult(Result.Failure(new Error("billing.store_unavailable", "Billing is not configured on this server.", ErrorType.Failure)));
}

/// <summary>
/// Development only, refused in Production at startup. Understands tokens shaped
/// <c>fake.{state}.{daysToExpiry}.{accountId}.{productId}</c>, for example
/// <c>fake.active.30.0f8f...e1.heartpact_plus_yearly</c>, and remembers acknowledgements.
/// </summary>
public sealed class FakeGooglePlayBilling : IGooglePlayBilling
{
    private readonly ConcurrentDictionary<string, bool> _acknowledged = new();

    public bool IsConfigured => true;

    public Task<Result<PlaySubscriptionSnapshot>> GetAsync(string purchaseToken, CancellationToken cancellationToken)
    {
        var parts = purchaseToken.Split('.', 5);
        if (parts.Length != 5 || parts[0] != "fake" || !int.TryParse(parts[2], NumberStyles.AllowLeadingSign, CultureInfo.InvariantCulture, out var days))
            return Task.FromResult(Result.Failure<PlaySubscriptionSnapshot>(Error.NotFound("billing.unknown_purchase", "Google does not know this purchase.")));
        var status = parts[1] switch
        {
            "active" => StoreSubscriptionStatus.Active,
            "canceled" => StoreSubscriptionStatus.Canceled,
            "grace" => StoreSubscriptionStatus.InGracePeriod,
            "onhold" => StoreSubscriptionStatus.OnHold,
            "expired" => StoreSubscriptionStatus.Expired,
            _ => StoreSubscriptionStatus.Pending
        };
        return Task.FromResult(Result.Success(new PlaySubscriptionSnapshot(
            parts[4], status, DateTime.UtcNow.AddDays(days), status == StoreSubscriptionStatus.Active,
            _acknowledged.ContainsKey(purchaseToken), parts[3] == "none" ? null : parts[3], null)));
    }

    public Task<Result> AcknowledgeAsync(string productId, string purchaseToken, CancellationToken cancellationToken)
    {
        _acknowledged[purchaseToken] = true;
        return Task.FromResult(Result.Success());
    }
}
