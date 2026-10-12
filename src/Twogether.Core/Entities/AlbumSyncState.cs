using Twogether.Core.Memories;

namespace Twogether.Core.Entities;

/// <summary>
/// One row per person: what their phone says is waiting to sync, and when the server last nudged
/// them or saw them sync. The photos themselves stay on the phone until the person taps Sync.
/// </summary>
public sealed class AlbumSyncState
{
    private AlbumSyncState() { }

    public Guid UserId { get; private set; }
    public int PendingCount { get; private set; }
    public long PendingBytes { get; private set; }
    /// <summary>When the queue last went from empty to non-empty. Null while nothing is waiting.</summary>
    public DateTime? PendingSinceUtc { get; private set; }
    /// <summary>When the app last reported its queue (or when the row was created).</summary>
    public DateTime ReportedAtUtc { get; private set; }
    public DateTime? LastPromptedAtUtc { get; private set; }
    public DateTime? LastSyncedAtUtc { get; private set; }
    /// <summary>When the current sync began; the partner's "added N photos" counts from here.</summary>
    public DateTime? SyncStartedAtUtc { get; private set; }

    public static AlbumSyncState Create(Guid userId, DateTime utcNow)
    {
        if (userId == Guid.Empty) throw new ArgumentException("A sync state belongs to a user.", nameof(userId));
        return new AlbumSyncState { UserId = userId, ReportedAtUtc = utcNow };
    }

    public void Report(int count, long bytes, DateTime utcNow)
    {
        if (!AlbumSyncRules.IsValidPending(count, bytes)) throw new ArgumentOutOfRangeException(nameof(count), "Pending count or bytes out of range.");
        if (count == 0) PendingSinceUtc = null;
        else if (PendingCount == 0 || PendingSinceUtc is null) PendingSinceUtc = utcNow;
        PendingCount = count;
        PendingBytes = bytes;
        ReportedAtUtc = utcNow;
    }

    public void MarkPrompted(DateTime utcNow) => LastPromptedAtUtc = utcNow;

    /// <summary>Keeps the start of a sync in progress; one older than a day is abandoned and restarts now.</summary>
    public void StartSync(DateTime utcNow)
    {
        if (SyncStartedAtUtc is not { } started || started <= utcNow - AlbumSyncRules.SyncSessionLifetime) SyncStartedAtUtc = utcNow;
    }

    public void FinishSync(DateTime utcNow)
    {
        LastSyncedAtUtc = utcNow;
        SyncStartedAtUtc = null;
    }
}
