using System.Security.Cryptography;
using System.Text;
using Twogether.Shared.Localization;

namespace Twogether.Application.Features.Auth;

/// <param name="HtmlBody">The branded version (EmailLayout); mail apps that can't show it read <paramref name="TextBody"/>.</param>
public sealed record EmailMessage(string To, string Subject, string TextBody, string? HtmlBody = null);

/// <summary>Outbound email. Only account email goes through it (reset codes); nothing is sent in bulk.</summary>
public interface IEmailSender
{
    bool IsConfigured { get; }
    Task SendAsync(EmailMessage message, CancellationToken cancellationToken);
}

/// <summary>Bound from the "Email" section. Provider "smtp", "log" (development: the message goes to the log) or empty (off).</summary>
public sealed class EmailSettings
{
    public string? Provider { get; init; }
    public string? Host { get; init; }
    public int Port { get; init; } = 587;
    public string? User { get; init; }
    public string? Password { get; init; }
    /// <summary>Resend's API key (Email:Provider=resend). <see cref="From"/> must be on a domain verified in Resend.</summary>
    public string? ApiKey { get; init; }
    public string? From { get; init; }
    public string FromName { get; init; } = "Heartpact";
    /// <summary>STARTTLS on 587 (the default); false means implicit TLS, as on port 465.</summary>
    public bool StartTls { get; init; } = true;
}

/// <summary>What Google vouches for in a verified ID token.</summary>
public sealed record GoogleIdentity(string Subject, string Email, bool EmailVerified, string? Name);

public interface IGoogleTokenVerifier
{
    bool IsConfigured { get; }
    /// <summary>Null when the token is not genuine, expired, or meant for another app.</summary>
    Task<GoogleIdentity?> VerifyAsync(string idToken, CancellationToken cancellationToken);
}

/// <summary>Bound from "Auth:GoogleClientIds": the OAuth client ids whose ID tokens are accepted (the Android app sends the web client id).</summary>
public sealed class GoogleAuthSettings
{
    public IReadOnlyList<string> ClientIds { get; init; } = [];
}

public static class PasswordResetRules
{
    public const int MinPasswordLength = 8;

    /// <summary>Six digits from a cryptographic source.</summary>
    public static string NewCode() => RandomNumberGenerator.GetInt32(0, 1_000_000).ToString("D6");

    /// <summary>Bound to the user, so equal codes for two people never share a hash.</summary>
    public static string Hash(Guid userId, string code)
        => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes($"{userId:N}:{code.Trim()}")));

    public static bool Matches(Guid userId, string code, string storedHash)
        => CryptographicOperations.FixedTimeEquals(Encoding.ASCII.GetBytes(Hash(userId, code)), Encoding.ASCII.GetBytes(storedHash));

    public static EmailMessage Email(string to, string code) => new(
        to,
        Loc.T("Your Heartpact code: {code}", ("code", code)),
        // In the language of the request that asked for it (the app sends Accept-Language).
        Loc.T("Your code to reset your Heartpact password is {code}.\n\nIt works for 15 minutes. If you didn't ask for it, ignore this email: your password stays as it is.", ("code", code)),
        EmailLayout.Code(
            Loc.T("Reset your password"),
            Loc.T("Enter this code in the Heartpact app to choose a new password."),
            code,
            Loc.T("It works for 15 minutes, once."),
            Loc.T("Didn't ask for this? You can ignore this email: your password stays as it is."),
            Loc.T("Your code is {code}. It works for 15 minutes.", ("code", code))));
}
