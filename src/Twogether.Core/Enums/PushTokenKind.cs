namespace Twogether.Core.Enums;

/// <summary>What the app registered. FCM is moving targeting from registration tokens to installation ids.</summary>
public enum PushTokenKind
{
    RegistrationToken,
    InstallationId
}
