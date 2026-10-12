using Twogether.Core.Enums;
using Twogether.Core.Seedwork;

namespace Twogether.Core.Entities;

/// <summary>
/// A pregnancy belongs to the person carrying it. Only they can change, end or delete it;
/// their partner sees it only while ShareWithPartner is on. Health data under GDPR Article 9.
/// </summary>
public sealed class Pregnancy : BaseEntity
{
    private Pregnancy() { }

    public Guid CoupleId { get; private set; }
    public Guid UserId { get; private set; }
    public DateOnly DueDate { get; private set; }
    public PregnancyStatus Status { get; private set; } = PregnancyStatus.Active;
    public bool ShareWithPartner { get; private set; } = true;
    public DateTime? EndedAtUtc { get; private set; }

    public static Pregnancy Start(Guid coupleId, Guid userId, DateOnly dueDate, bool shareWithPartner, DateTime utcNow) => new()
    {
        CoupleId = coupleId,
        UserId = userId,
        DueDate = dueDate,
        ShareWithPartner = shareWithPartner,
        CreatedAtUtc = utcNow,
        UpdatedAtUtc = utcNow
    };

    public void Update(DateOnly dueDate, bool shareWithPartner, DateTime utcNow)
    {
        if (Status != PregnancyStatus.Active) throw new InvalidOperationException("An ended pregnancy cannot be changed.");
        DueDate = dueDate;
        ShareWithPartner = shareWithPartner;
        UpdatedAtUtc = utcNow;
    }

    /// <summary>No reason is asked or stored; how it ended is not the app's business.</summary>
    public void End(DateTime utcNow)
    {
        if (Status != PregnancyStatus.Active) throw new InvalidOperationException("This pregnancy has already ended.");
        Status = PregnancyStatus.Ended;
        EndedAtUtc = utcNow;
        UpdatedAtUtc = utcNow;
    }
}
