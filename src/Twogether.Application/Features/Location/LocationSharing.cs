using Twogether.Core.Entities;
using Twogether.Core.Enums;
using Twogether.Shared.Results;
using Twogether.Shared.Localization;

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
            ? Error.Validation("location.invalid_pause", Loc.T("A pause lasts between {minutes} minutes and {hours} hours.", ("minutes", MinPauseMinutes), ("hours", MaxPauseMinutes / 60)))
            : utcNow.AddMinutes(minutes);

    /// <summary>A share whose phone has gone quiet past the lease reads as off, for both partners.</summary>
    public static LocationSharingStatus StatusOf(CoupleLocation? row, DateTime utcNow)
        => row is null || !row.IsLiveAt(utcNow) ? LocationSharingStatus.Off
            : row.IsPausedAt(utcNow) ? LocationSharingStatus.Paused
            : LocationSharingStatus.Sharing;

    /// <summary>The mutual rule: a viewer sees the target only while both are sharing and neither is paused.</summary>
    public static bool CanSee(CoupleLocation? viewer, CoupleLocation? target, DateTime utcNow)
        => viewer is not null && target is not null && viewer.IsVisibleAt(utcNow) && target.IsVisibleAt(utcNow);

    /// <summary>
    /// The indicator for one partner. The time of the last fix is shown only when
    /// <paramref name="withLastFix"/>: your own, or your partner's while you see each other, so
    /// polling the status never tells a partner who isn't sharing when you move.
    /// </summary>
    public static LocationSharingStateDto State(Guid userId, string displayName, CoupleLocation? row, DateTime utcNow, bool withLastFix)
    {
        var status = StatusOf(row, utcNow);
        return new LocationSharingStateDto(
            userId,
            displayName,
            status,
            status == LocationSharingStatus.Paused ? row!.PausedUntilUtc : null,
            withLastFix && status != LocationSharingStatus.Off ? row!.FixAtUtc : null);
    }

    /// <summary>Both partners' indicators. Mine always carries my last fix; my partner's only while mutual.</summary>
    public static LocationSharingStatusDto Status(Guid myId, string myName, CoupleLocation? mine, Guid? partnerId, string? partnerName, CoupleLocation? theirs, DateTime utcNow)
    {
        var mutual = CanSee(mine, theirs, utcNow);
        var me = State(myId, myName, mine, utcNow, withLastFix: true);
        var partner = partnerId is { } id ? State(id, partnerName ?? string.Empty, theirs, utcNow, withLastFix: mutual) : null;
        return new LocationSharingStatusDto(me, partner, mutual);
    }

    /// <summary>The time a fix was taken, from the phone's clock in Unix milliseconds; null when it is not a usable time.</summary>
    public static DateTime? FixTime(long unixMilliseconds)
        => unixMilliseconds is > 0 and < 253402300799999 ? DateTimeOffset.FromUnixTimeMilliseconds(unixMilliseconds).UtcDateTime : null;

    /// <summary>Coordinates a fix may carry. NaN and infinities fail every range check, so they are refused explicitly.</summary>
    public static bool IsValidFix(double latitude, double longitude, double accuracyMeters)
        => double.IsFinite(latitude) && double.IsFinite(longitude) && double.IsFinite(accuracyMeters)
            && latitude is >= -90 and <= 90 && longitude is >= -180 and <= 180 && accuracyMeters is >= 0 and <= 100000;
}
