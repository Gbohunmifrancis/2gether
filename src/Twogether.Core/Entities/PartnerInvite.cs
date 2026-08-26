using Twogether.Core.Enums;
using Twogether.Core.Seedwork;

namespace Twogether.Core.Entities;

public sealed class PartnerInvite : BaseEntity
{
    private PartnerInvite() { }

    public Guid CoupleId { get; private set; }
    public Guid InviterUserId { get; private set; }
    public string CodeHash { get; private set; } = string.Empty;
    public string LinkTokenHash { get; private set; } = string.Empty;
    public DateTime ExpiresAtUtc { get; private set; }
    public PartnerInviteStatus Status { get; private set; } = PartnerInviteStatus.Pending;

    public static PartnerInvite Create(Guid coupleId, Guid inviterUserId, string codeHash, string linkTokenHash, DateTime expiresAtUtc, DateTime utcNow) => new()
    {
        CoupleId = coupleId,
        InviterUserId = inviterUserId,
        CodeHash = codeHash,
        LinkTokenHash = linkTokenHash,
        ExpiresAtUtc = expiresAtUtc,
        CreatedAtUtc = utcNow,
        UpdatedAtUtc = utcNow
    };

    public bool IsUsable(DateTime utcNow) => Status == PartnerInviteStatus.Pending && ExpiresAtUtc > utcNow;
    public void Accept(DateTime utcNow) { Status = PartnerInviteStatus.Accepted; UpdatedAtUtc = utcNow; }
    public void Revoke(DateTime utcNow) { Status = PartnerInviteStatus.Revoked; UpdatedAtUtc = utcNow; }
}
