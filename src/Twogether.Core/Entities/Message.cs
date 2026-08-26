using Twogether.Core.Seedwork;

namespace Twogether.Core.Entities;

public sealed class Message : BaseEntity
{
    private Message() { }

    public Guid CoupleId { get; private set; }
    public Guid SenderUserId { get; private set; }
    public string Body { get; private set; } = string.Empty;
    public DateTime? EditedAtUtc { get; private set; }
    public DateTime? DeletedAtUtc { get; private set; }
    public bool IsPrivate { get; private set; }

    public static Message Create(Guid coupleId, Guid senderUserId, string body, bool isPrivate, DateTime utcNow) => new()
    {
        CoupleId = coupleId,
        SenderUserId = senderUserId,
        Body = body,
        IsPrivate = isPrivate,
        CreatedAtUtc = utcNow,
        UpdatedAtUtc = utcNow
    };

    public void Edit(string body, DateTime utcNow)
    {
        Body = body;
        EditedAtUtc = utcNow;
        UpdatedAtUtc = utcNow;
    }

    public void Delete(DateTime utcNow)
    {
        DeletedAtUtc = utcNow;
        UpdatedAtUtc = utcNow;
    }
}
