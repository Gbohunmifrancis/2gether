using Google;
using Google.Apis.AndroidPublisher.v3;
using Google.Apis.AndroidPublisher.v3.Data;
using Google.Apis.Auth.OAuth2;
using Google.Apis.Auth.OAuth2.Responses;
using Google.Apis.Services;
using Microsoft.Extensions.Logging;
using Twogether.Application.Features.Admin.Ops;
using Twogether.Application.Features.Billing;

namespace Twogether.Infrastructure.Billing;

/// <summary>
/// Play Console's subscriptions for the app (monetization.subscriptions.list with archived ones, and
/// basePlans.offers.list), for the admin billing check. Uses the billing service account whatever
/// <c>Billing:Provider</c> says, so the check can explain a server that isn't selling yet. Each Google call gets
/// <see cref="BillingCheckService.CallTimeout"/>. Failures are kept to the HTTP status and Google's one-word reason;
/// the key, tokens and Google's messages are never logged or returned.
/// </summary>
public sealed class GooglePlayCatalogReader : IPlayCatalogReader, IDisposable
{
    private const int MaxSubscriptions = 500;
    private readonly string packageName = string.Empty;
    private readonly Lazy<AndroidPublisherService>? service;
    private readonly ILogger<GooglePlayCatalogReader> logger;

    public GooglePlayCatalogReader(BillingSettings settings, ILogger<GooglePlayCatalogReader> logger)
    {
        this.logger = logger;
        if (string.IsNullOrWhiteSpace(settings.GooglePlayCredentialsJson) || string.IsNullOrWhiteSpace(settings.GooglePlayPackageName)) return;
        packageName = settings.GooglePlayPackageName.Trim();
        var json = settings.GooglePlayCredentialsJson;
        // Built on first use, so a broken key is reported by the check instead of stopping the API at start.
        service = new Lazy<AndroidPublisherService>(() =>
        {
            var credential = CredentialFactory.FromJson<ServiceAccountCredential>(json)
                .ToGoogleCredential()
                .CreateScoped(AndroidPublisherService.ScopeConstants.Androidpublisher);
            return new AndroidPublisherService(new BaseClientService.Initializer { HttpClientInitializer = credential, ApplicationName = "Heartpact" });
        });
    }

    public bool IsConfigured => service is not null;

    public async Task<PlayCatalogRead> ReadAsync(IReadOnlyCollection<string> offersFor, CancellationToken cancellationToken)
    {
        if (service is null) return new PlayCatalogRead([], new StoreReadError("google.unconfigured"));
        AndroidPublisherService publisher;
        try
        {
            publisher = service.Value;
        }
        catch (Exception exception) when (exception is not OperationCanceledException)
        {
            // The type only: the message can quote the key file.
            logger.LogWarning("The Google Play service account key could not be read ({Error}).", exception.GetType().Name);
            return new PlayCatalogRead([], new StoreReadError("google.credentials_invalid"));
        }

        var subscriptions = new List<Subscription>();
        string? pageToken = null;
        do
        {
            var request = publisher.Monetization.Subscriptions.List(packageName);
            request.ShowArchived = true;
            request.PageSize = 100;
            request.PageToken = pageToken;
            var (page, error) = await CallAsync("subscriptions", request.ExecuteAsync, cancellationToken);
            if (error is not null) return new PlayCatalogRead([], error);
            // An app with no subscriptions can answer with an empty body.
            if (page is null) break;
            subscriptions.AddRange((page.Subscriptions ?? []).Where(item => item is not null));
            pageToken = page.NextPageToken;
        }
        while (!string.IsNullOrEmpty(pageToken) && subscriptions.Count < MaxSubscriptions);

        var wanted = new HashSet<string>(offersFor, StringComparer.Ordinal);
        var result = new List<PlaySubscriptionInfo>();
        foreach (var subscription in subscriptions.Where(item => !string.IsNullOrEmpty(item.ProductId)))
        {
            var offers = new List<PlayOfferInfo>();
            StoreReadError? offersError = null;
            if (wanted.Contains(subscription.ProductId) && subscription.BasePlans is { Count: > 0 })
                (offers, offersError) = await OffersAsync(publisher, subscription.ProductId, cancellationToken);
            result.Add(new PlaySubscriptionInfo(
                subscription.ProductId,
                subscription.Archived == true,
                (subscription.BasePlans ?? []).Where(plan => plan is not null).Select(BasePlan).ToList(),
                offers,
                offersError));
        }
        return new PlayCatalogRead(result);
    }

    private async Task<(List<PlayOfferInfo>, StoreReadError?)> OffersAsync(AndroidPublisherService publisher, string productId, CancellationToken cancellationToken)
    {
        var offers = new List<PlayOfferInfo>();
        string? pageToken = null;
        do
        {
            // "-" reads the offers of every base plan of the subscription in one list.
            var request = publisher.Monetization.Subscriptions.BasePlans.Offers.List(packageName, productId, "-");
            request.PageSize = 100;
            request.PageToken = pageToken;
            var (page, error) = await CallAsync("offers", request.ExecuteAsync, cancellationToken);
            if (error is not null) return (offers, error);
            if (page is null) break;
            offers.AddRange((page.SubscriptionOffers ?? []).Where(offer => offer is not null).Select(offer => new PlayOfferInfo(offer.BasePlanId ?? "?", offer.OfferId ?? "?", offer.State ?? "STATE_UNSPECIFIED")));
            pageToken = page.NextPageToken;
        }
        while (!string.IsNullOrEmpty(pageToken) && offers.Count < MaxSubscriptions);
        return (offers, null);
    }

    private static PlayBasePlanInfo BasePlan(BasePlan plan)
    {
        var (type, period) = plan switch
        {
            { AutoRenewingBasePlanType: { } renewing } => ("autoRenewing", renewing.BillingPeriodDuration),
            { PrepaidBasePlanType: { } prepaid } => ("prepaid", prepaid.BillingPeriodDuration),
            { InstallmentsBasePlanType: not null } => ("installments", (string?)null),
            _ => ("unknown", null),
        };
        var regions = (plan.RegionalConfigs ?? [])
            .Where(config => config is not null && !string.IsNullOrEmpty(config.RegionCode))
            .Select(config => new PlayRegionInfo(
                config.RegionCode.ToUpperInvariant(),
                config.NewSubscriberAvailability == true,
                config.Price is { } price ? BillingCheckHints.Price(price.CurrencyCode, price.Units, price.Nanos) : null))
            .ToList();
        return new PlayBasePlanInfo(plan.BasePlanId ?? "?", plan.State ?? "STATE_UNSPECIFIED", type, period, regions);
    }

    private async Task<(T?, StoreReadError?)> CallAsync<T>(string what, Func<CancellationToken, Task<T>> call, CancellationToken cancellationToken)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(BillingCheckService.CallTimeout);
        try
        {
            return (await call(timeout.Token), null);
        }
        catch (GoogleApiException exception)
        {
            var status = (int)exception.HttpStatusCode;
            var reason = Word(exception.Error?.Errors?.FirstOrDefault()?.Reason);
            if (exception.Error?.ErrorResponseContent?.Contains("SERVICE_DISABLED", StringComparison.Ordinal) == true) reason = "SERVICE_DISABLED";
            logger.LogWarning("Billing check: Google Play {What} answered {Status} ({Reason}).", what, status, reason);
            return (default, new StoreReadError($"google.http_{status}", status, reason));
        }
        catch (TokenResponseException exception)
        {
            var reason = Word(exception.Error?.Error);
            logger.LogWarning("Billing check: Google refused the service account's token ({Reason}).", reason);
            return (default, new StoreReadError("google.token_refused", exception.StatusCode is { } code ? (int)code : null, reason));
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            logger.LogWarning("Billing check: Google Play {What} timed out.", what);
            return (default, new StoreReadError("google.timeout"));
        }
        catch (HttpRequestException exception)
        {
            logger.LogWarning("Billing check: Google Play {What} failed on the network ({Error}).", what, exception.GetType().Name);
            return (default, new StoreReadError("google.unreachable"));
        }
    }

    /// <summary>Google's reason if it is one short word ("permissionDenied", "invalid_grant"), otherwise nothing.</summary>
    private static string? Word(string? value)
        => value is { Length: > 0 and <= 40 } && value.All(c => char.IsAsciiLetterOrDigit(c) || c == '_') ? value : null;

    public void Dispose()
    {
        if (service is { IsValueCreated: true }) service.Value.Dispose();
    }
}
