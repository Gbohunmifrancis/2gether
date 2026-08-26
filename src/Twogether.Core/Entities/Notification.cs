using Twogether.Core.Enums;
using Twogether.Core.Seedwork;

namespace Twogether.Core.Entities;

public sealed class Notification : BaseEntity
{
    private Notification() { }

    public Guid UserId { get; private set; }
    public Guid CoupleId { get; private set; }
    public NotificationType Type { get; private set; }
    public string Title { get; private set; } = string.Empty;
    public string Body { get; private set; } = string.Empty;
    public string? DataJson { get; private set; }
    public DateTime? ReadAtUtc { get; private set; }

    public static Notification Create(Guid userId, Guid coupleId, NotificationType type, string title, string body, string? dataJson, DateTime utcNow) => new()
    {
        UserId = userId,
        CoupleId = coupleId,
        Type = type,
        Title = title,
        Body = body,
        DataJson = dataJson,
        CreatedAtUtc = utcNow,
        UpdatedAtUtc = utcNow
    };

    public void MarkRead(DateTime utcNow)
    {
        if (ReadAtUtc.HasValue) return;
        ReadAtUtc = utcNow;
        UpdatedAtUtc = utcNow;
    }
}
