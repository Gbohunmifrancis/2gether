using Twogether.Core.Seedwork;

namespace Twogether.Core.Entities;

/// <summary>
/// The second factor of an admin: an authenticator-app (TOTP) secret. Created pending on the first
/// password-checked sign-in, enrolled once the first code checks out. Who may be an admin is decided by
/// configuration (Admin:Emails), not by this row.
/// </summary>
public sealed class AdminAccount : BaseEntity
{
    private AdminAccount() { }

    public Guid UserId { get; private set; }
    /// <summary>The authenticator secret as stored: encrypted (see AdminSecretProtector), never plain text once rewritten.</summary>
    public string TotpSecret { get; private set; } = string.Empty;
    public DateTime? EnrolledAtUtc { get; private set; }
    /// <summary>The last accepted time step: a code is never accepted twice.</summary>
    public long LastUsedStep { get; private set; }

    public bool Enrolled => EnrolledAtUtc is not null;

    public static AdminAccount Pending(Guid userId, string secret, DateTime utcNow) => new() { UserId = userId, TotpSecret = secret, CreatedAtUtc = utcNow, UpdatedAtUtc = utcNow };

    public void Accept(long step, DateTime utcNow)
    {
        LastUsedStep = step;
        EnrolledAtUtc ??= utcNow;
        UpdatedAtUtc = utcNow;
    }

    /// <summary>The same secret stored again, under the current key. Enrollment is unchanged.</summary>
    public void ReplaceStoredSecret(string stored, DateTime utcNow)
    {
        TotpSecret = stored;
        UpdatedAtUtc = utcNow;
    }

    /// <summary>Lost phone: the next sign-in enrolls a new authenticator.</summary>
    public void Reset(string secret, DateTime utcNow)
    {
        TotpSecret = secret;
        EnrolledAtUtc = null;
        LastUsedStep = 0;
        UpdatedAtUtc = utcNow;
    }
}

/// <summary>One admin action, kept for accountability. Never holds user content, only what was done to what.</summary>
public sealed class AdminAuditEvent : BaseEntity
{
    public const int MaxDetailsLength = 4000;

    private AdminAuditEvent() { }

    public Guid? AdminUserId { get; private set; }
    public string AdminEmail { get; private set; } = string.Empty;
    public string Action { get; private set; } = string.Empty;
    public string? TargetType { get; private set; }
    public string? TargetId { get; private set; }
    public string? Details { get; private set; }
    public string? IpAddress { get; private set; }
    public bool Succeeded { get; private set; }

    public static AdminAuditEvent Record(Guid? adminUserId, string adminEmail, string action, string? targetType, string? targetId, string? details, string? ipAddress, bool succeeded, DateTime utcNow) => new()
    {
        AdminUserId = adminUserId,
        AdminEmail = adminEmail.Length > 320 ? adminEmail[..320] : adminEmail,
        Action = action,
        TargetType = targetType,
        TargetId = targetId,
        Details = details is { Length: > MaxDetailsLength } ? details[..MaxDetailsLength] : details,
        IpAddress = ipAddress,
        Succeeded = succeeded,
        CreatedAtUtc = utcNow,
        UpdatedAtUtc = utcNow,
    };
}

/// <summary>Complimentary Plus for a couple (support, apology, testing), with an end date and a reason.</summary>
public sealed class PlusGrant : BaseEntity
{
    public const int MaxReasonLength = 500;

    private PlusGrant() { }

    public Guid CoupleId { get; private set; }
    public DateTime ExpiresAtUtc { get; private set; }
    public string Reason { get; private set; } = string.Empty;
    public Guid GrantedByUserId { get; private set; }
    public DateTime? RevokedAtUtc { get; private set; }

    public bool IsActive(DateTime utcNow) => RevokedAtUtc is null && utcNow < ExpiresAtUtc;

    public static PlusGrant Create(Guid coupleId, DateTime expiresAtUtc, string reason, Guid grantedBy, DateTime utcNow) => new()
    {
        CoupleId = coupleId,
        ExpiresAtUtc = expiresAtUtc,
        Reason = reason.Length > MaxReasonLength ? reason[..MaxReasonLength] : reason,
        GrantedByUserId = grantedBy,
        CreatedAtUtc = utcNow,
        UpdatedAtUtc = utcNow,
    };

    public void Revoke(DateTime utcNow) { RevokedAtUtc ??= utcNow; UpdatedAtUtc = utcNow; }
}

/// <summary>That a user used the app on a day (their own calendar day in UTC): one row per user and day, for active-user charts.</summary>
public sealed class UserActivityDay
{
    public Guid UserId { get; set; }
    public DateOnly Day { get; set; }
}

/// <summary>
/// That an account was deleted, and when: nothing else, so the admin live feed can say "an account was
/// deleted" after the person and everything of theirs is gone. Internal (test) accounts are flagged so
/// analytics can leave them out.
/// </summary>
public sealed class AccountDeletion : BaseEntity
{
    private AccountDeletion() { }

    public bool WasInternal { get; private set; }

    public static AccountDeletion Record(bool wasInternal, DateTime utcNow) => new() { WasInternal = wasInternal, CreatedAtUtc = utcNow, UpdatedAtUtc = utcNow };
}
