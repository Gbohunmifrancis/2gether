using Twogether.Core.Enums;
using Twogether.Core.Seedwork;

namespace Twogether.Core.Entities;

public sealed class Couple : BaseEntity
{
    private Couple() { }

    public Guid UserAId { get; private set; }
    public Guid? UserBId { get; private set; }
    public CoupleStatus Status { get; private set; } = CoupleStatus.PendingInvite;
    public DateTime? LinkedAtUtc { get; private set; }

    public static Couple Create(Guid userAId, DateTime utcNow) => new()
    {
        UserAId = userAId,
        CreatedAtUtc = utcNow,
        UpdatedAtUtc = utcNow
    };

    public void LinkPartner(Guid userBId, DateTime utcNow)
    {
        UserBId = userBId;
        Status = CoupleStatus.Active;
        LinkedAtUtc = utcNow;
        UpdatedAtUtc = utcNow;
    }

    public void Dissolve(DateTime utcNow)
    {
        Status = CoupleStatus.Dissolved;
        UpdatedAtUtc = utcNow;
    }
}
