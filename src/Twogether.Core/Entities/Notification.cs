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

    // Push delivery. The notification row is its own outbox: saving it in the same transaction
    // as the change it describes means a push can never be lost or sent for a rolled-back change.
    public PushState PushState { get; private set; } = PushState.Pending;
    public int PushAttempts { get; private set; }
    public DateTime? PushNextAttemptAtUtc { get; private set; }
    /// <summary>Lease set by the dispatcher's claim so two instances never send the same push.</summary>
    public DateTime? PushLockedUntilUtc { get; private set; }
    public DateTime? PushedAtUtc { get; private set; }

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

    public void MarkPushSent(DateTime utcNow)
    {
        PushState = PushState.Sent;
        PushedAtUtc = utcNow;
        PushLockedUntilUtc = null;
        UpdatedAtUtc = utcNow;
    }

    public void SkipPush(DateTime utcNow)
    {
        PushState = PushState.Skipped;
        PushLockedUntilUtc = null;
        UpdatedAtUtc = utcNow;
    }

    public void FailPush(DateTime utcNow)
    {
        PushState = PushState.Failed;
        PushLockedUntilUtc = null;
        UpdatedAtUtc = utcNow;
    }

    /// <summary>Counts the attempt and either schedules the next one or gives up.</summary>
    public void RetryPush(DateTime retryAtUtc, int maxAttempts, DateTime utcNow)
    {
        PushAttempts++;
        PushLockedUntilUtc = null;
        UpdatedAtUtc = utcNow;
        if (PushAttempts >= maxAttempts) PushState = PushState.Failed;
        else PushNextAttemptAtUtc = retryAtUtc;
    }

    public void MarkRead(DateTime utcNow)
    {
        if (ReadAtUtc.HasValue) return;
        ReadAtUtc = utcNow;
        UpdatedAtUtc = utcNow;
    }
}
