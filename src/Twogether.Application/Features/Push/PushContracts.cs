using Twogether.Core.Enums;

namespace Twogether.Application.Features.Push;

public enum PushPriority { Normal, High }

/// <summary>One push to one device token. DataOnly messages are handled by the app itself (the alarm screen).</summary>
public sealed record PushMessage(
    string Token,
    PushTokenKind TokenKind,
    string Title,
    string Body,
    IReadOnlyDictionary<string, string> Data,
    PushPriority Priority,
    string ChannelId,
    TimeSpan TimeToLive,
    bool DataOnly);

public enum PushOutcome
{
    Delivered,
    /// <summary>The token is dead (app uninstalled, token rotated): disable the device.</summary>
    InvalidToken,
    /// <summary>Rate limit, outage or timeout: try again later.</summary>
    RetryLater,
    /// <summary>The provider refused this message or our credentials; retrying will not help.</summary>
    Rejected
}

public sealed record PushResult(PushOutcome Outcome, string? Detail = null);

/// <summary>Outbound port to the push provider. Results are positional: one per message, same order.</summary>
public interface IPushSender
{
    string Name { get; }
    bool IsConfigured { get; }
    Task<IReadOnlyList<PushResult>> SendAsync(IReadOnlyList<PushMessage> messages, CancellationToken cancellationToken);
}

/// <summary>Claims due notifications for this dispatcher instance. Rows claimed by another instance are skipped.</summary>
public interface IPushOutbox
{
    Task<IReadOnlyList<Guid>> ClaimAsync(DateTime utcNow, TimeSpan lease, int batchSize, CancellationToken cancellationToken);
}

public sealed record DeviceDto(Guid Id, DevicePlatform Platform, PushTokenKind TokenKind, string TokenSuffix, string? AppVersion, DateTime LastSeenAtUtc, bool Active);

public sealed record RegisterDeviceRequest(string Token, DevicePlatform Platform, string? AppVersion, PushTokenKind TokenKind = PushTokenKind.RegistrationToken);

public sealed record UnregisterDeviceRequest(string Token);

/// <summary>Bound from the "Push" section.</summary>
public sealed class PushSettings
{
    /// <summary>"fcm", "log" (development: writes each push to the log) or empty (off).</summary>
    public string? Provider { get; init; }
    public string? FcmProjectId { get; init; }
    /// <summary>The service account JSON itself, from a secret environment variable.</summary>
    public string? FcmCredentialsJson { get; init; }
    public int MaxDevicesPerUser { get; init; } = 10;
}
