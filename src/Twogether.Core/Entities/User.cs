using Twogether.Core.Enums;
using Twogether.Core.Seedwork;
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
    public string MapColor { get; private set; } = "#f45c91";
    public bool CycleOwner { get; private set; }
    public Gender Gender { get; private set; } = Gender.PreferNotToSay;
    public DateOnly DateOfBirth { get; private set; }
    public bool EmailVerified { get; private set; }
    public Guid? CoupleId { get; private set; }
    public string TimeZoneId { get; private set; } = "UTC";

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

    public void SetCouple(Guid? coupleId, DateTime utcNow)
    {
        CoupleId = coupleId;
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
}
