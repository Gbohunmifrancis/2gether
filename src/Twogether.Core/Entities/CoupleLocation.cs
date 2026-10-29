using Twogether.Core.Seedwork;

namespace Twogether.Core.Entities;

/// <summary>
/// The latest known position of one partner. Only the last point is stored; there is no
/// location history. Sharing is mutual: a partner sees this row only while they share too.
/// </summary>
public sealed class CoupleLocation : BaseEntity
{
    /// <summary>
    /// A share with no fix or heartbeat for this long counts as off: the phone that was sending
    /// was switched off, killed or uninstalled, and must not leave a frozen point "sharing".
    /// </summary>
    public static readonly TimeSpan Lease = TimeSpan.FromMinutes(10);

    private CoupleLocation() { }

    public Guid CoupleId { get; private set; }
    public Guid UserId { get; private set; }
    /// <summary>Null until the first fix after starting, and wiped when sharing stops.</summary>
    public double? Latitude { get; private set; }
    public double? Longitude { get; private set; }
    public double? AccuracyMeters { get; private set; }
    /// <summary>When the phone took the fix (never later than the server's clock). Pause, resume and stop leave it alone.</summary>
    public DateTime? FixAtUtc { get; private set; }
    /// <summary>The last fix or heartbeat from the sharing phone; the lease runs from here.</summary>
    public DateTime LastSeenAtUtc { get; private set; }
    public bool SharingEnabled { get; private set; }
    /// <summary>While in the future, the partner sees "paused" and no position. Cleared by Resume, a fix after it ends, Start or Stop.</summary>
    public DateTime? PausedUntilUtc { get; private set; }

    /// <summary>Starting is explicit: the person tapped Share. A fix never starts a share on its own.</summary>
    public static CoupleLocation Start(Guid coupleId, Guid userId, DateTime utcNow) => new()
    {
        CoupleId = coupleId,
        UserId = userId,
        SharingEnabled = true,
        LastSeenAtUtc = utcNow,
        CreatedAtUtc = utcNow,
        UpdatedAtUtc = utcNow
    };

    public bool HasFix => Latitude is not null && Longitude is not null && FixAtUtc is not null;

    /// <summary>Sharing, and the phone has been heard from within the lease.</summary>
    public bool IsLiveAt(DateTime utcNow) => SharingEnabled && LastSeenAtUtc > utcNow - Lease;

    public bool IsPausedAt(DateTime utcNow) => IsLiveAt(utcNow) && PausedUntilUtc > utcNow;

    /// <summary>Live, not inside a pause, and with a point. Both partners must be visible for either to see the other.</summary>
    public bool IsVisibleAt(DateTime utcNow) => IsLiveAt(utcNow) && !IsPausedAt(utcNow) && HasFix;

    /// <summary>
    /// Starts sharing again, or confirms a share that is still running (idempotent). Clears a pause.
    /// A share that had lapsed drops its old point so it never reappears as current.
    /// </summary>
    public void StartSharing(DateTime utcNow)
    {
        if (!IsLiveAt(utcNow)) ClearFix();
        SharingEnabled = true;
        PausedUntilUtc = null;
        LastSeenAtUtc = utcNow;
        UpdatedAtUtc = utcNow;
    }

    /// <summary>
    /// A new fix. Returns false and stores nothing when sharing is stopped (a fix never restarts it),
    /// during a pause, or when the fix is older than the one stored (a late delivery).
    /// </summary>
    public bool TryRecordFix(double latitude, double longitude, double accuracyMeters, DateTime fixAtUtc, DateTime utcNow)
    {
        if (!SharingEnabled || PausedUntilUtc > utcNow) return false;
        LastSeenAtUtc = utcNow;
        UpdatedAtUtc = utcNow;
        var at = fixAtUtc > utcNow ? utcNow : fixAtUtc;
        if (FixAtUtc is { } last && at <= last) return false;
        Latitude = latitude;
        Longitude = longitude;
        AccuracyMeters = accuracyMeters;
        FixAtUtc = at;
        PausedUntilUtc = null;
        return true;
    }

    /// <summary>The sharing phone is still there, moving or not. Returns false when sharing is stopped.</summary>
    public bool Heartbeat(DateTime utcNow)
    {
        if (!SharingEnabled) return false;
        LastSeenAtUtc = utcNow;
        UpdatedAtUtc = utcNow;
        return true;
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

    /// <summary>Stops sharing and wipes the point: a stopped share keeps no position.</summary>
    public void StopSharing(DateTime utcNow)
    {
        SharingEnabled = false;
        PausedUntilUtc = null;
        ClearFix();
        UpdatedAtUtc = utcNow;
    }

    private void ClearFix()
    {
        Latitude = null;
        Longitude = null;
        AccuracyMeters = null;
        FixAtUtc = null;
    }
}
