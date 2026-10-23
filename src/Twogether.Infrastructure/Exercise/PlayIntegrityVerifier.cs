using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using Google.Apis.Auth.OAuth2;
using Microsoft.Extensions.Logging;
using Twogether.Application.Common.Interfaces;
using Twogether.Application.Features.Exercise;
using Twogether.Core.Enums;

namespace Twogether.Infrastructure.Exercise;

/// <summary>
/// Decodes a Play Integrity token with Google (playintegrity.googleapis.com) using a service account,
/// then applies <see cref="PlayIntegrityRules.Evaluate"/>. Google being down or slow never blocks a
/// completion: that is <see cref="ExerciseIntegrity.NotChecked"/>.
/// </summary>
public sealed class PlayIntegrityVerifier : IPlayIntegrityVerifier
{
    private const string Scope = "https://www.googleapis.com/auth/playintegrity";
    private readonly GoogleCredential? _credential;
    private readonly string _packageName = string.Empty;
    private readonly HttpClient _http;
    private readonly IDateTimeProvider _clock;
    private readonly ILogger<PlayIntegrityVerifier> _logger;

    public PlayIntegrityVerifier(PlayIntegritySettings settings, HttpClient http, IDateTimeProvider clock, ILogger<PlayIntegrityVerifier> logger)
    {
        _http = http;
        _clock = clock;
        _logger = logger;
        if (string.IsNullOrWhiteSpace(settings.CredentialsJson) || string.IsNullOrWhiteSpace(settings.PackageName)) return;
        _credential = CredentialFactory.FromJson<ServiceAccountCredential>(settings.CredentialsJson).ToGoogleCredential().CreateScoped(Scope);
        _packageName = settings.PackageName.Trim();
    }

    public bool IsConfigured => _credential is not null;

    public async Task<ExerciseIntegrity> VerifyAsync(string? token, string requestHash, CancellationToken cancellationToken)
    {
        if (_credential is null) return ExerciseIntegrity.NotChecked;
        if (string.IsNullOrWhiteSpace(token)) return ExerciseIntegrity.Missing;
        try
        {
            var access = await ((ITokenAccess)_credential).GetAccessTokenForRequestAsync(cancellationToken: cancellationToken);
            using var request = new HttpRequestMessage(HttpMethod.Post, $"https://playintegrity.googleapis.com/v1/{Uri.EscapeDataString(_packageName)}:decodeIntegrityToken")
            {
                Content = JsonContent.Create(new { integrityToken = token.Trim() })
            };
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", access);
            using var response = await _http.SendAsync(request, cancellationToken);
            // Google rejects a malformed or foreign token with 400: that is a failed check, not an outage.
            if (response.StatusCode == System.Net.HttpStatusCode.BadRequest) return ExerciseIntegrity.Failed;
            if (!response.IsSuccessStatusCode)
            {
                _logger.LogWarning("Play Integrity decode failed with {Status}.", (int)response.StatusCode);
                return ExerciseIntegrity.NotChecked;
            }
            using var json = await JsonDocument.ParseAsync(await response.Content.ReadAsStreamAsync(cancellationToken), cancellationToken: cancellationToken);
            return PlayIntegrityRules.Evaluate(Read(json.RootElement), _packageName, requestHash, _clock.UtcNow);
        }
        catch (Exception exception) when (exception is HttpRequestException or TaskCanceledException or JsonException or Google.Apis.Auth.OAuth2.Responses.TokenResponseException && !cancellationToken.IsCancellationRequested)
        {
            _logger.LogWarning(exception, "Play Integrity couldn't be reached.");
            return ExerciseIntegrity.NotChecked;
        }
    }

    /// <summary>The fields we use from Google's tokenPayloadExternal; anything missing reads as null or empty.</summary>
    public static IntegrityPayload Read(JsonElement root)
    {
        var payload = root.TryGetProperty("tokenPayloadExternal", out var inner) ? inner : root;
        static JsonElement? Child(JsonElement element, string name) => element.ValueKind == JsonValueKind.Object && element.TryGetProperty(name, out var value) ? value : null;
        static string? Text(JsonElement? element, string name) => element is { } e && Child(e, name) is { ValueKind: JsonValueKind.String } value ? value.GetString() : null;
        var request = Child(payload, "requestDetails");
        var app = Child(payload, "appIntegrity");
        var device = Child(payload, "deviceIntegrity");
        // Google sends timestampMillis as a string (int64 in JSON); accept a number too.
        long? millis = request is { } r && Child(r, "timestampMillis") is { } t
            ? t.ValueKind == JsonValueKind.String && long.TryParse(t.GetString(), out var parsed) ? parsed : t.ValueKind == JsonValueKind.Number ? t.GetInt64() : null
            : null;
        var verdicts = device is { } d && Child(d, "deviceRecognitionVerdict") is { ValueKind: JsonValueKind.Array } list
            ? list.EnumerateArray().Where(item => item.ValueKind == JsonValueKind.String).Select(item => item.GetString()!).ToList()
            : [];
        return new IntegrityPayload(Text(request, "requestPackageName"), Text(request, "requestHash"), millis, Text(app, "appRecognitionVerdict"), verdicts);
    }
}

public sealed class DisabledPlayIntegrityVerifier : IPlayIntegrityVerifier
{
    public bool IsConfigured => false;
    public Task<ExerciseIntegrity> VerifyAsync(string? token, string requestHash, CancellationToken cancellationToken) => Task.FromResult(ExerciseIntegrity.NotChecked);
}

/// <summary>
/// Development and CI only, refused in Production: "fake.pass" passes, any other token fails, no token is missing.
/// </summary>
public sealed class FakePlayIntegrityVerifier : IPlayIntegrityVerifier
{
    public const string PassToken = "fake.pass";
    public bool IsConfigured => true;

    public Task<ExerciseIntegrity> VerifyAsync(string? token, string requestHash, CancellationToken cancellationToken) => Task.FromResult(
        string.IsNullOrWhiteSpace(token) ? ExerciseIntegrity.Missing : token == PassToken ? ExerciseIntegrity.Passed : ExerciseIntegrity.Failed);
}
