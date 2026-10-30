using Twogether.Core.Enums;
using Twogether.Core.Seedwork;
using Twogether.Core.Users;
using Twogether.Shared.Guards;

namespace Twogether.Core.Entities;

public sealed class User : BaseEntity
{
    private User() { }

    public string Email { get; private set; } = string.Empty;
    public string? PasswordHash { get; private set; }
    public string? GoogleId { get; private set; }
    public string DisplayName { get; private set; } = string.Empty;
    public string? AvatarUrl { get; private set; }
    /// <summary>The profile picture as uploaded (JPEG, PNG or WebP, at most <see cref="Twogether.Core.Users.AvatarImage.MaxBytes"/>). Null when there is none.</summary>
    public byte[]? AvatarBytes { get; private set; }
    /// <summary>Goes up on every change of the picture, including removing it, so it can serve as a cache key and ETag. 0 until the first upload.</summary>
    public long AvatarVersion { get; private set; }
    public string MapColor { get; private set; } = "#f45c91";
    public bool CycleOwner { get; private set; }
    public Gender Gender { get; private set; } = Gender.PreferNotToSay;
    public DateOnly DateOfBirth { get; private set; }
    public bool EmailVerified { get; private set; }
    public Guid? CoupleId { get; private set; }
    public string TimeZoneId { get; private set; } = "UTC";
    /// <summary>ISO 3166-1 alpha-2, chosen at sign-up; picks regional content. Null for accounts made before it was asked.</summary>
    public string? CountryCode { get; private set; }
    /// <summary>The app's language (en, fr, pt, es, ar, hi), from its requests: notifications and emails are written in it.</summary>
    public string Language { get; private set; } = "en";
    /// <summary>Tokens issued before this are rejected: set when the password is reset, to end every other session.</summary>
    public DateTime? SessionsValidFromUtc { get; private set; }
    /// <summary>Set by an admin: the account can't sign in and its sessions end.</summary>
    public DateTime? DisabledAtUtc { get; private set; }
    public string? DisabledReason { get; private set; }
    /// <summary>Last authenticated request, at most every 15 minutes; for active-user analytics.</summary>
    public DateTime? LastActiveAtUtc { get; private set; }
    /// <summary>Set by an admin for test and team accounts: they work as usual but are left out of analytics.</summary>
    public bool IsInternal { get; private set; }

    public static User Create(string email, string displayName, DateOnly dateOfBirth, DateTime utcNow, string? passwordHash = null, string? googleId = null, bool cycleOwner = false, Gender gender = Gender.PreferNotToSay)
    {
        return new User
        {
            Email = Guard.NotNullOrWhiteSpace(email).ToLowerInvariant(),
            DisplayName = Guard.NotNullOrWhiteSpace(displayName),
            DateOfBirth = dateOfBirth,
            PasswordHash = passwordHash,
            GoogleId = googleId,
            CycleOwner = cycleOwner,
            Gender = gender,
            EmailVerified = googleId is not null,
            // The language the app signed up in (Accept-Language); later requests keep it current.
            Language = Twogether.Shared.Localization.Loc.Language,
            CreatedAtUtc = utcNow,
            UpdatedAtUtc = utcNow
        };
    }

    public void SetGender(Gender gender, DateTime utcNow)
    {
        Gender = gender;
        UpdatedAtUtc = utcNow;
    }

    public void UpdateProfile(string displayName, string? avatarUrl, string mapColor, DateTime utcNow)
    {
        DisplayName = Guard.NotNullOrWhiteSpace(displayName);
        AvatarUrl = string.IsNullOrWhiteSpace(avatarUrl) ? null : avatarUrl.Trim();
        MapColor = string.IsNullOrWhiteSpace(mapColor) ? "#f45c91" : mapColor.Trim();
        UpdatedAtUtc = utcNow;
    }

    /// <summary>
    /// What clients see: 0 when there is no picture, otherwise the version to put in the picture's address. Changed only
    /// by the database in one statement (AvatarService), so two uploads at once never share a version. Queries that
    /// shouldn't load the picture spell this out: <c>user.AvatarBytes == null ? 0L : user.AvatarVersion</c>.
    /// </summary>
    public long PublicAvatarVersion => AvatarBytes is null ? 0 : AvatarVersion;

    public void SetCouple(Guid? coupleId, DateTime utcNow)
    {
        CoupleId = coupleId;
        UpdatedAtUtc = utcNow;
    }

    public void SetCountry(string countryCode, DateTime utcNow)
    {
        CountryCode = ContentRegions.Normalize(countryCode) ?? throw new ArgumentException($"'{countryCode}' is not an ISO 3166-1 alpha-2 country code.", nameof(countryCode));
        UpdatedAtUtc = utcNow;
    }

    public void SetLanguage(string language, DateTime utcNow)
    {
        Language = Twogether.Shared.Localization.Loc.Normalize(language) ?? "en";
        UpdatedAtUtc = utcNow;
    }

    public void SetTimeZone(string timeZoneId, DateTime utcNow)
    {
        TimeZoneId = Guard.NotNullOrWhiteSpace(timeZoneId);
        UpdatedAtUtc = utcNow;
    }

    public void ClaimCycleOwnership(DateTime utcNow)
    {
        CycleOwner = true;
        UpdatedAtUtc = utcNow;
    }

    /// <summary>A new password after proving the inbox: the email is verified, and every older session ends.</summary>
    public void ResetPassword(string passwordHash, DateTime utcNow)
    {
        PasswordHash = passwordHash;
        EmailVerified = true;
        // JWT times have whole seconds: the token issued with the reset carries this same second.
        SessionsValidFromUtc = new DateTime(utcNow.Ticks - utcNow.Ticks % TimeSpan.TicksPerSecond, DateTimeKind.Utc);
        UpdatedAtUtc = utcNow;
    }

    /// <summary>Google vouched for this email: sign-in with Google now finds this account.</summary>
    public void LinkGoogle(string googleId, DateTime utcNow)
    {
        GoogleId = googleId;
        EmailVerified = true;
        UpdatedAtUtc = utcNow;
    }

    public void MarkInternal(bool internalAccount, DateTime utcNow)
    {
        IsInternal = internalAccount;
        UpdatedAtUtc = utcNow;
    }

    public void Disable(string reason, DateTime utcNow)
    {
        DisabledAtUtc = utcNow;
        DisabledReason = reason.Length > 500 ? reason[..500] : reason;
        EndSessions(utcNow);
    }

    public void Enable(DateTime utcNow)
    {
        DisabledAtUtc = null;
        DisabledReason = null;
        UpdatedAtUtc = utcNow;
    }

    /// <summary>Every token issued before now stops working (same whole-second rule as a password reset).</summary>
    public void EndSessions(DateTime utcNow)
    {
        SessionsValidFromUtc = new DateTime(utcNow.Ticks - utcNow.Ticks % TimeSpan.TicksPerSecond, DateTimeKind.Utc).AddSeconds(1);
        UpdatedAtUtc = utcNow;
    }
}
