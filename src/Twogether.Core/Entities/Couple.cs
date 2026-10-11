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
    /// <summary>When the couple ended. Starts the window in which each ex can still download their own photos.</summary>
    public DateTime? DissolvedAtUtc { get; private set; }

    public static Couple Create(Guid userAId, DateTime utcNow) => new()
    {
        UserAId = userAId,
        CreatedAtUtc = utcNow,
        UpdatedAtUtc = utcNow
    };

    /// <summary>Only a couple still waiting for its second person can take one; an ended couple never comes back.</summary>
    public bool CanAcceptPartner => Status == CoupleStatus.PendingInvite && UserBId is null;

    public void LinkPartner(Guid userBId, DateTime utcNow)
    {
        if (!CanAcceptPartner) throw new InvalidOperationException("This couple is not waiting for a partner.");
        if (userBId == UserAId) throw new InvalidOperationException("A person cannot be their own partner.");
        UserBId = userBId;
        Status = CoupleStatus.Active;
        LinkedAtUtc = utcNow;
        UpdatedAtUtc = utcNow;
    }

    public void Dissolve(DateTime utcNow)
    {
        if (Status == CoupleStatus.Dissolved) return;
        Status = CoupleStatus.Dissolved;
        DissolvedAtUtc = utcNow;
        UpdatedAtUtc = utcNow;
    }
}
