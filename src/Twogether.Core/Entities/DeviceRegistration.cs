using Twogether.Core.Enums;
using Twogether.Core.Seedwork;
using Twogether.Shared.Guards;

namespace Twogether.Core.Entities;

/// <summary>
/// A push token for one app install. A token belongs to whoever registered it last: when a
/// phone changes hands (log out, log in as someone else) the token moves with it.
/// </summary>
public sealed class DeviceRegistration : BaseEntity
{
    public const int MaxTokenLength = 1024; // FCM and APNs tokens are under 200 chars; this keeps the unique btree index safe
    public const int MaxAppVersionLength = 40;

    private DeviceRegistration() { }

    public Guid UserId { get; private set; }
    public DevicePlatform Platform { get; private set; }
    public PushTokenKind TokenKind { get; private set; }
    public string Token { get; private set; } = string.Empty;
    public string? AppVersion { get; private set; }
    public DateTime LastSeenAtUtc { get; private set; }
    public DateTime? DisabledAtUtc { get; private set; }
    public string? DisabledReason { get; private set; }

    public bool IsActive => DisabledAtUtc is null;

    public static DeviceRegistration Register(Guid userId, DevicePlatform platform, string token, string? appVersion, DateTime utcNow, PushTokenKind tokenKind = PushTokenKind.RegistrationToken)
    {
        var clean = Guard.NotNullOrWhiteSpace(token, nameof(token));
        if (clean.Length > MaxTokenLength) throw new ArgumentException("Token is too long.", nameof(token));
        var device = new DeviceRegistration { Token = clean, CreatedAtUtc = utcNow };
        device.Refresh(userId, platform, appVersion, utcNow, tokenKind);
        return device;
    }

    /// <summary>Re-registration: re-enables the token and moves it to the caller.</summary>
    public void Refresh(Guid userId, DevicePlatform platform, string? appVersion, DateTime utcNow, PushTokenKind tokenKind = PushTokenKind.RegistrationToken)
    {
        var version = string.IsNullOrWhiteSpace(appVersion) ? null : appVersion.Trim();
        if (version?.Length > MaxAppVersionLength) throw new ArgumentException("App version is too long.", nameof(appVersion));
        UserId = userId;
        Platform = platform;
        TokenKind = tokenKind;
        AppVersion = version;
        LastSeenAtUtc = utcNow;
        DisabledAtUtc = null;
        DisabledReason = null;
        UpdatedAtUtc = utcNow;
    }

    public void Disable(string reason, DateTime utcNow)
    {
        if (DisabledAtUtc is not null) return;
        DisabledAtUtc = utcNow;
        DisabledReason = reason.Length > 40 ? reason[..40] : reason;
        UpdatedAtUtc = utcNow;
    }
}
