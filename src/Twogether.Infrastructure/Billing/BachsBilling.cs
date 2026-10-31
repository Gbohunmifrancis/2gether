using System.Net;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Microsoft.Extensions.Logging;
using Twogether.Application.Features.Billing;
using Twogether.Shared.Localization;
using Twogether.Shared.Results;

namespace Twogether.Infrastructure.Billing;

/// <summary>
/// The Bachs API over plain HTTPS (there is no .NET SDK). The key decides sandbox or production. Logs carry the status
/// and Bachs's short error code only: never the key, a portal URL, or a provider error body.
/// </summary>
public sealed class BachsBilling : IBachsBilling, IDisposable
{
    private readonly BachsSettings settings;
    private readonly ILogger<BachsBilling> logger;
    private readonly HttpClient http;

    public BachsBilling(BachsSettings settings, ILogger<BachsBilling> logger, HttpMessageHandler? handler = null)
    {
        this.settings = settings;
        this.logger = logger;
        http = handler is null ? new HttpClient() : new HttpClient(handler, disposeHandler: false);
        http.BaseAddress = new Uri(settings.BaseUrl + "/");
        // Bachs counts a webhook handler slower than 10 s as failed; our own calls get a little more.
        http.Timeout = TimeSpan.FromSeconds(15);
    }

    public bool IsConfigured => settings.IsConfigured;

    public async Task<Result<BachsCheckout>> CreateCheckoutAsync(BachsCheckoutRequest request, string idempotencyKey, CancellationToken cancellationToken)
    {
        if (!IsConfigured) return Unavailable();
        var customer = request.CustomerId is { } id
            ? new JsonObject { ["customer_id"] = id }
            : new JsonObject { ["email"] = request.Email, ["name"] = request.Name };
        var metadata = new JsonObject { ["user_id"] = request.UserId.ToString() };
        // Our outbox row's id, never Google's token: Bachs copies metadata onto the subscription and its webhooks.
        if (request.ExternalRef is { } reference) metadata["external_ref"] = reference.ToString();
        var body = new JsonObject
        {
            ["product_cart"] = new JsonArray(new JsonObject { ["product_id"] = request.ProductId, ["quantity"] = 1 }),
            ["customer"] = customer,
            ["billing_currency"] = "USD",
            ["metadata"] = metadata,
            ["success_url"] = request.SuccessUrl,
            ["cancel_url"] = request.CancelUrl,
        };
        var response = await SendAsync(HttpMethod.Post, "v1/checkout-sessions", body, idempotencyKey, "checkout", cancellationToken);
        if (response.IsFailure) return Result.Failure<BachsCheckout>(response.Error);
        var root = response.Value;
        var checkoutId = Read(root, "checkout_id") ?? Read(root, "id");
        var url = Read(root, "checkout_url");
        if (checkoutId is null || !Uri.TryCreate(url, UriKind.Absolute, out var parsed) || parsed.Scheme != Uri.UriSchemeHttps)
        {
            logger.LogWarning("Bachs answered a checkout without a usable checkout_url.");
            return Failed();
        }
        logger.LogInformation("Bachs checkout {CheckoutId} created for user {UserId}.", checkoutId, request.UserId);
        return new BachsCheckout(checkoutId, url!);
    }

    public async Task<Result<string>> CreatePortalSessionAsync(string customerId, CancellationToken cancellationToken)
    {
        if (!IsConfigured) return Unavailable();
        // A new session per click: each is its own operation, so each gets its own key.
        var response = await SendAsync(HttpMethod.Post, $"v1/customers/{Uri.EscapeDataString(customerId)}/portal-sessions", null, "hp-portal-" + Guid.NewGuid().ToString("N"), "portal", cancellationToken);
        if (response.IsFailure) return Result.Failure<string>(response.Error);
        var url = Read(response.Value, "url");
        if (!Uri.TryCreate(url, UriKind.Absolute, out var parsed) || parsed.Scheme != Uri.UriSchemeHttps)
        {
            logger.LogWarning("Bachs answered a portal session without a usable url.");
            return Failed();
        }
        return url!;
    }

    public async Task<Result<BachsSubscriptionSnapshot>> GetSubscriptionAsync(string subscriptionId, CancellationToken cancellationToken)
    {
        if (!IsConfigured) return Unavailable();
        var response = await SendAsync(HttpMethod.Get, $"v1/subscriptions/{Uri.EscapeDataString(subscriptionId)}", null, null, "subscription lookup", cancellationToken);
        return response.IsFailure ? Result.Failure<BachsSubscriptionSnapshot>(response.Error) : Subscription(response.Value);
    }

    public async Task<Result<BachsSubscriptionSnapshot>> CancelSubscriptionAsync(string subscriptionId, bool atPeriodEnd, CancellationToken cancellationToken)
    {
        if (!IsConfigured) return Unavailable();
        // Sent explicitly both ways: without it Bachs cancels at once.
        var body = new JsonObject { ["cancel_at_period_end"] = atPeriodEnd };
        var response = await SendAsync(HttpMethod.Delete, $"v1/subscriptions/{Uri.EscapeDataString(subscriptionId)}", body, null, "cancel", cancellationToken);
        return response.IsFailure ? Result.Failure<BachsSubscriptionSnapshot>(response.Error) : Subscription(response.Value);
    }

    private Result<BachsSubscriptionSnapshot> Subscription(JsonElement root)
    {
        var snapshot = BachsEvents.ReadSubscription(root);
        if (snapshot is not null) return snapshot;
        logger.LogWarning("Bachs answered a subscription without an id or status.");
        return Failed();
    }

    private async Task<Result<JsonElement>> SendAsync(HttpMethod method, string path, JsonObject? body, string? idempotencyKey, string what, CancellationToken cancellationToken)
    {
        using var request = new HttpRequestMessage(method, path);
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", settings.ApiKey!.Trim());
        request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));
        if (idempotencyKey is not null) request.Headers.TryAddWithoutValidation("Idempotency-Key", idempotencyKey);
        if (body is not null) request.Content = new StringContent(body.ToJsonString(), Encoding.UTF8, "application/json");
        try
        {
            using var response = await http.SendAsync(request, cancellationToken);
            var text = await response.Content.ReadAsStringAsync(cancellationToken);
            if (!response.IsSuccessStatusCode)
            {
                // Only Bachs's short error code: the body can echo customer details.
                logger.LogWarning("Bachs {What} failed with {Status} ({ErrorCode}).", what, (int)response.StatusCode, ErrorCode(text));
                return response.StatusCode switch
                {
                    HttpStatusCode.NotFound => Error.NotFound("billing.bachs_not_found", Loc.T("Card payments couldn't find this subscription.")),
                    HttpStatusCode.BadRequest or HttpStatusCode.Conflict or HttpStatusCode.UnprocessableEntity
                        => Error.Conflict("billing.bachs_refused", Loc.T("Card payments refused this request.")),
                    _ => Failed()
                };
            }
            using var document = JsonDocument.Parse(string.IsNullOrWhiteSpace(text) ? "{}" : text);
            return document.RootElement.Clone();
        }
        catch (Exception exception) when (exception is HttpRequestException or TaskCanceledException or JsonException && !cancellationToken.IsCancellationRequested)
        {
            logger.LogWarning("Bachs {What} failed: {Error}.", what, exception.GetType().Name);
            return Failed();
        }
    }

    private static string? ErrorCode(string body)
    {
        try
        {
            using var document = JsonDocument.Parse(body);
            return Read(document.RootElement, "error_code") is { Length: <= 64 } code ? code : null;
        }
        catch (JsonException)
        {
            return null;
        }
    }

    private static string? Read(JsonElement element, string name)
        => element.ValueKind == JsonValueKind.Object && element.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String ? value.GetString() : null;

    private static Error Failed() => new("billing.bachs_failed", Loc.T("Card payments couldn't be reached. Try again shortly."), ErrorType.Network);
    private static Error Unavailable() => Error.Failure(BachsCheckoutService.Unavailable, Loc.T("Card payments are not set up on this server."));

    public void Dispose() => http.Dispose();
}

/// <summary>Bachs is not set up: nothing can be bought by card, and the entitlement says so.</summary>
public sealed class DisabledBachsBilling : IBachsBilling
{
    public bool IsConfigured => false;
    public Task<Result<BachsCheckout>> CreateCheckoutAsync(BachsCheckoutRequest request, string idempotencyKey, CancellationToken cancellationToken) => Off<BachsCheckout>();
    public Task<Result<string>> CreatePortalSessionAsync(string customerId, CancellationToken cancellationToken) => Off<string>();
    public Task<Result<BachsSubscriptionSnapshot>> GetSubscriptionAsync(string subscriptionId, CancellationToken cancellationToken) => Off<BachsSubscriptionSnapshot>();
    public Task<Result<BachsSubscriptionSnapshot>> CancelSubscriptionAsync(string subscriptionId, bool atPeriodEnd, CancellationToken cancellationToken) => Off<BachsSubscriptionSnapshot>();
    private static Task<Result<T>> Off<T>() => Task.FromResult(Result.Failure<T>(Error.Failure(BachsCheckoutService.Unavailable, Loc.T("Card payments are not set up on this server."))));
}
