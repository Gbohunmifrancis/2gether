using System.Net;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Google.Apis.Auth.OAuth2;
using Microsoft.Extensions.Logging;
using Twogether.Application.Features.Billing;
using Twogether.Shared.Results;

namespace Twogether.Infrastructure.Billing;

/// <summary>
/// Google Play's externaltransactions API (billing choice reporting), with the same service account and package as
/// Play billing. Errors are kept to the HTTP status and Google's short status word, never a body.
/// </summary>
public sealed class GoogleExternalTransactionReporter : IGoogleExternalTransactionReporter
{
    private const string Scope = "https://www.googleapis.com/auth/androidpublisher";
    private const string BaseUrl = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/";
    private readonly Func<CancellationToken, Task<string>>? accessToken;
    private readonly string packageName = string.Empty;
    private readonly HttpClient http;
    private readonly ILogger<GoogleExternalTransactionReporter> logger;

    public GoogleExternalTransactionReporter(BillingSettings settings, HttpClient http, ILogger<GoogleExternalTransactionReporter> logger)
        : this(settings.GooglePlayPackageName, CredentialFor(settings), http, logger)
    {
    }

    /// <summary>For tests: any token source and any HTTP handler.</summary>
    public GoogleExternalTransactionReporter(string? packageName, Func<CancellationToken, Task<string>>? accessToken, HttpClient http, ILogger<GoogleExternalTransactionReporter> logger)
    {
        this.http = http;
        this.logger = logger;
        if (string.IsNullOrWhiteSpace(packageName) || accessToken is null) return;
        this.packageName = packageName.Trim();
        this.accessToken = accessToken;
    }

    private static Func<CancellationToken, Task<string>>? CredentialFor(BillingSettings settings)
    {
        if (string.IsNullOrWhiteSpace(settings.GooglePlayCredentialsJson) || string.IsNullOrWhiteSpace(settings.GooglePlayPackageName)) return null;
        var credential = CredentialFactory.FromJson<ServiceAccountCredential>(settings.GooglePlayCredentialsJson).ToGoogleCredential().CreateScoped(Scope);
        return cancellationToken => ((ITokenAccess)credential).GetAccessTokenForRequestAsync(cancellationToken: cancellationToken);
    }

    public bool IsConfigured => accessToken is not null;

    public Task<Result> CreateAsync(string externalTransactionId, JsonObject body, CancellationToken cancellationToken)
        => SendAsync($"{BaseUrl}{Uri.EscapeDataString(packageName)}/externalTransactions?externalTransactionId={Uri.EscapeDataString(externalTransactionId)}", body, "report", cancellationToken);

    public Task<Result> RefundAsync(string externalTransactionId, JsonObject body, CancellationToken cancellationToken)
        => SendAsync($"{BaseUrl}{Uri.EscapeDataString(packageName)}/externalTransactions/{Uri.EscapeDataString(externalTransactionId)}:refund", body, "refund", cancellationToken);

    private async Task<Result> SendAsync(string url, JsonObject body, string what, CancellationToken cancellationToken)
    {
        if (accessToken is null) return Error.Failure("google.external_unconfigured", "Google Play credentials are not set.");
        try
        {
            var token = await accessToken(cancellationToken);
            using var request = new HttpRequestMessage(HttpMethod.Post, url) { Content = new StringContent(body.ToJsonString(), Encoding.UTF8, "application/json") };
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
            using var response = await http.SendAsync(request, cancellationToken);
            if (response.IsSuccessStatusCode) return Result.Success();
            var status = GoogleStatus(await response.Content.ReadAsStringAsync(cancellationToken));
            // Sent before, and Google kept it: a retry after a lost answer. That is reported.
            if (response.StatusCode == HttpStatusCode.Conflict || status == "ALREADY_EXISTS") return Result.Success();
            logger.LogWarning("Google external transaction {What} failed with {Status} ({GoogleStatus}).", what, (int)response.StatusCode, status);
            return new Error($"google.http_{(int)response.StatusCode}" + (status is null ? string.Empty : "_" + status.ToLowerInvariant()), "Google refused the report.", ErrorType.Network);
        }
        catch (Exception exception) when (exception is HttpRequestException or TaskCanceledException or Google.Apis.Auth.OAuth2.Responses.TokenResponseException && !cancellationToken.IsCancellationRequested)
        {
            logger.LogWarning("Google external transaction {What} failed: {Error}.", what, exception.GetType().Name);
            return new Error("google.unreachable_" + exception.GetType().Name.ToLowerInvariant(), "Google couldn't be reached.", ErrorType.Network);
        }
    }

    /// <summary>Google's error.status ("ALREADY_EXISTS", "INVALID_ARGUMENT"), if it is a short word.</summary>
    private static string? GoogleStatus(string body)
    {
        try
        {
            using var document = JsonDocument.Parse(body);
            return document.RootElement.TryGetProperty("error", out var error) && error.ValueKind == JsonValueKind.Object
                && error.TryGetProperty("status", out var status) && status.ValueKind == JsonValueKind.String
                && status.GetString() is { Length: <= 40 } text && text.All(c => char.IsAsciiLetterUpper(c) || c == '_')
                    ? text
                    : null;
        }
        catch (JsonException)
        {
            return null;
        }
    }
}

/// <summary>No Google credentials: reports wait in the outbox until they are set.</summary>
public sealed class DisabledGoogleExternalTransactionReporter : IGoogleExternalTransactionReporter
{
    public bool IsConfigured => false;
    public Task<Result> CreateAsync(string externalTransactionId, JsonObject body, CancellationToken cancellationToken) => Off();
    public Task<Result> RefundAsync(string externalTransactionId, JsonObject body, CancellationToken cancellationToken) => Off();
    private static Task<Result> Off() => Task.FromResult(Result.Failure(Error.Failure("google.external_unconfigured", "Google Play credentials are not set.")));
}
