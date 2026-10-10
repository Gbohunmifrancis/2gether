using FirebaseAdmin;
using FirebaseAdmin.Messaging;
using Google.Apis.Auth.OAuth2;
using Microsoft.Extensions.Logging;
using Twogether.Application.Features.Push;

namespace Twogether.Infrastructure.Push;

/// <summary>Firebase Cloud Messaging over the official Admin SDK (HTTP v1).</summary>
public sealed class FcmPushSender : IPushSender
{
    private const int MaxPerCall = 500; // SendEachAsync limit
    private readonly FirebaseMessaging? _messaging;
    private readonly ILogger<FcmPushSender> _logger;

    public FcmPushSender(PushSettings settings, ILogger<FcmPushSender> logger)
    {
        _logger = logger;
        if (string.IsNullOrWhiteSpace(settings.FcmCredentialsJson) || string.IsNullOrWhiteSpace(settings.FcmProjectId)) return;
        var credential = CredentialFactory.FromJson<ServiceAccountCredential>(settings.FcmCredentialsJson).ToGoogleCredential();
        var app = FirebaseApp.GetInstance("heartpact-push")
            ?? FirebaseApp.Create(new AppOptions { Credential = credential, ProjectId = settings.FcmProjectId }, "heartpact-push");
        _messaging = FirebaseMessaging.GetMessaging(app);
    }

    public string Name => "fcm";
    public bool IsConfigured => _messaging is not null;

    public async Task<IReadOnlyList<PushResult>> SendAsync(IReadOnlyList<PushMessage> messages, CancellationToken cancellationToken)
    {
        if (_messaging is null) throw new InvalidOperationException("FCM is not configured.");
        var results = new List<PushResult>(messages.Count);
        foreach (var chunk in messages.Chunk(MaxPerCall))
        {
            try
            {
                var response = await _messaging.SendEachAsync(chunk.Select(ToFcm), cancellationToken);
                results.AddRange(response.Responses.Select(item => item.IsSuccess
                    ? new PushResult(PushOutcome.Delivered)
                    : new PushResult(FcmErrorMapping.Map(item.Exception?.MessagingErrorCode), item.Exception?.MessagingErrorCode?.ToString())));
            }
            catch (Exception exception) when (exception is not OperationCanceledException)
            {
                // The whole call failed (credentials, network): nothing was sent, try the chunk again later.
                _logger.LogError(exception, "FCM batch of {Count} failed; will retry.", chunk.Length);
                results.AddRange(chunk.Select(_ => new PushResult(PushOutcome.RetryLater, "batch_failed")));
            }
        }
        return results;
    }

    private static Message ToFcm(PushMessage message) => new()
    {
#pragma warning disable CS0618 // Registration tokens are deprecated in favour of installation ids but still accepted; both are supported.
        Token = message.TokenKind == Core.Enums.PushTokenKind.RegistrationToken ? message.Token : null,
#pragma warning restore CS0618
        Fid = message.TokenKind == Core.Enums.PushTokenKind.InstallationId ? message.Token : null,
        Data = message.Data,
        // Data-only messages wake the app so it can open the full-screen alarm itself.
        Notification = message.DataOnly ? null : new Notification { Title = message.Title, Body = message.Body },
        Android = new AndroidConfig
        {
            Priority = message.Priority == PushPriority.High ? Priority.High : Priority.Normal,
            TimeToLive = message.TimeToLive,
            Notification = message.DataOnly ? null : new AndroidNotification { ChannelId = message.ChannelId }
        }
    };
}

public static class FcmErrorMapping
{
    /// <summary>
    /// Only errors that prove the token is dead disable a device. INVALID_ARGUMENT can mean our
    /// message was malformed, so it fails the message instead of punishing the device.
    /// </summary>
    public static PushOutcome Map(MessagingErrorCode? code) => code switch
    {
        MessagingErrorCode.Unregistered or MessagingErrorCode.SenderIdMismatch => PushOutcome.InvalidToken,
        MessagingErrorCode.QuotaExceeded or MessagingErrorCode.Unavailable or MessagingErrorCode.Internal => PushOutcome.RetryLater,
        MessagingErrorCode.InvalidArgument or MessagingErrorCode.ThirdPartyAuthError => PushOutcome.Rejected,
        _ => PushOutcome.RetryLater
    };
}
