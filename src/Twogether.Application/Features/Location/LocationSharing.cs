using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Shared.Results;

namespace Twogether.Application.Features.Location;

/// <summary>What each partner can see of the other's sharing, without coordinates.</summary>
public sealed record LocationSharingStateDto(Guid UserId, string DisplayName, LocationSharingStatus Status, DateTime? PausedUntilUtc, DateTime? LastUpdatedAtUtc);

public sealed record LocationSharingStatusDto(LocationSharingStateDto Me, LocationSharingStateDto? Partner, bool Mutual);

public sealed record PauseLocationRequest(int Minutes);

public static class LocationSharingRules
{
    public const int MinPauseMinutes = 15;
    public const int MaxPauseMinutes = 24 * 60;

    public static Result<DateTime> PauseUntil(int minutes, DateTime utcNow)
        => minutes is < MinPauseMinutes or > MaxPauseMinutes
            ? Error.Validation("location.invalid_pause", $"A pause lasts between {MinPauseMinutes} minutes and {MaxPauseMinutes / 60} hours.")
            : utcNow.AddMinutes(minutes);

    public static LocationSharingStatus StatusOf(CoupleLocation? row, DateTime utcNow)
        => row is null || !row.SharingEnabled ? LocationSharingStatus.Off
            : row.IsPausedAt(utcNow) ? LocationSharingStatus.Paused
            : LocationSharingStatus.Sharing;

    /// <summary>The mutual rule: a viewer sees the target only while both are sharing and neither is paused.</summary>
    public static bool CanSee(CoupleLocation? viewer, CoupleLocation? target, DateTime utcNow)
        => viewer is not null && target is not null && viewer.IsVisibleAt(utcNow) && target.IsVisibleAt(utcNow);

    public static LocationSharingStateDto State(Guid userId, string displayName, CoupleLocation? row, DateTime utcNow)
    {
        var status = StatusOf(row, utcNow);
        return new LocationSharingStateDto(
            userId,
            displayName,
            status,
            status == LocationSharingStatus.Paused ? row!.PausedUntilUtc : null,
            status == LocationSharingStatus.Off ? null : row?.UpdatedAtUtc);
    }
}
