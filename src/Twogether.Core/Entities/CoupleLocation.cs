using Twogether.Core.Seedwork;

namespace Twogether.Core.Entities;

public sealed class CoupleLocation : BaseEntity
{
    private CoupleLocation() { }

    public Guid CoupleId { get; private set; }
    public Guid UserId { get; private set; }
    public double Latitude { get; private set; }
    public double Longitude { get; private set; }
    public double AccuracyMeters { get; private set; }
    public bool SharingEnabled { get; private set; }

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

    public void Update(double latitude, double longitude, double accuracyMeters, DateTime utcNow)
    {
        Latitude = latitude;
        Longitude = longitude;
        AccuracyMeters = accuracyMeters;
        SharingEnabled = true;
        UpdatedAtUtc = utcNow;
    }

    public void StopSharing(DateTime utcNow)
    {
        SharingEnabled = false;
        UpdatedAtUtc = utcNow;
    }
}
