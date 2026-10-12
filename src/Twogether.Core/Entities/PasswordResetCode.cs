using Twogether.Core.Seedwork;

namespace Twogether.Core.Entities;

/// <summary>
/// A six-digit code emailed to reset a password. Only its hash is stored; it lasts 15 minutes,
/// allows 5 tries, and works once. Asking for a new code retires the previous one.
/// </summary>
public sealed class PasswordResetCode : BaseEntity
{
    public const int MaxAttempts = 5;
    public static readonly TimeSpan Lifetime = TimeSpan.FromMinutes(15);

    private PasswordResetCode() { }

    public Guid UserId { get; private set; }
    public string CodeHash { get; private set; } = string.Empty;
    public DateTime ExpiresAtUtc { get; private set; }
    public int Attempts { get; private set; }
    public DateTime? UsedAtUtc { get; private set; }

    public static PasswordResetCode Issue(Guid userId, string codeHash, DateTime utcNow) => new()
    {
        UserId = userId,
        CodeHash = codeHash,
        ExpiresAtUtc = utcNow + Lifetime,
        CreatedAtUtc = utcNow,
    };

    public bool IsLive(DateTime utcNow) => UsedAtUtc is null && utcNow < ExpiresAtUtc && Attempts < MaxAttempts;

    /// <summary>Counts the try first, so a wrong guess is spent even if the request is cut off.</summary>
    public void RecordAttempt(DateTime utcNow) { Attempts++; UpdatedAtUtc = utcNow; }

    public void Retire(DateTime utcNow) { UsedAtUtc ??= utcNow; UpdatedAtUtc = utcNow; }
}
