using Twogether.Core.Seedwork;

namespace Twogether.Core.Entities;

/// <summary>
/// The latest known position of one partner. Only the last point is stored; there is no
/// location history. Sharing is mutual: a partner sees this row only while they share too.
/// </summary>
public sealed class CoupleLocation : BaseEntity
{
    private CoupleLocation() { }

    public Guid CoupleId { get; private set; }
    public Guid UserId { get; private set; }
    public double Latitude { get; private set; }
    public double Longitude { get; private set; }
    public double AccuracyMeters { get; private set; }
    public bool SharingEnabled { get; private set; }
    /// <summary>While in the future, the partner sees "paused" and no position. Cleared by Resume, Update or Stop.</summary>
    public DateTime? PausedUntilUtc { get; private set; }

    public static CoupleLocation Create(Guid coupleId, Guid userId, double latitude, double longitude, double accuracyMeters, DateTime utcNow) => new()
    {
        CoupleId = coupleId,
        UserId = userId,
        Latitude = latitude,
        Longitude = longitude,
        AccuracyMeters = accuracyMeters,
        SharingEnabled = true,
        CreatedAtUtc = utcNow,
        UpdatedAtUtc = utcNow
    };

    public bool IsPausedAt(DateTime utcNow) => SharingEnabled && PausedUntilUtc > utcNow;

    /// <summary>Sharing and not inside a pause. Both partners must be visible for either to see the other.</summary>
    public bool IsVisibleAt(DateTime utcNow) => SharingEnabled && !IsPausedAt(utcNow);

    /// <summary>A new fix. Not allowed during a pause: the caller must drop the point instead of storing it.</summary>
    public void Update(double latitude, double longitude, double accuracyMeters, DateTime utcNow)
    {
        if (IsPausedAt(utcNow)) throw new InvalidOperationException("Location sharing is paused.");
        Latitude = latitude;
        Longitude = longitude;
        AccuracyMeters = accuracyMeters;
        SharingEnabled = true;
        PausedUntilUtc = null;
        UpdatedAtUtc = utcNow;
    }

    public void Pause(DateTime untilUtc, DateTime utcNow)
    {
        if (!SharingEnabled) throw new InvalidOperationException("Only an active share can be paused.");
        if (untilUtc <= utcNow) throw new ArgumentException("A pause must end in the future.", nameof(untilUtc));
        PausedUntilUtc = untilUtc;
        UpdatedAtUtc = utcNow;
    }

    public void Resume(DateTime utcNow)
    {
        PausedUntilUtc = null;
        UpdatedAtUtc = utcNow;
    }

    public void StopSharing(DateTime utcNow)
    {
        SharingEnabled = false;
        PausedUntilUtc = null;
        UpdatedAtUtc = utcNow;
    }
}
