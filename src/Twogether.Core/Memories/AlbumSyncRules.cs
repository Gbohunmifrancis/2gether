using Twogether.Core.Entities;

namespace Twogether.Core.Memories;

/// <summary>
/// Offline-first album sync. Photos stay on the phone until the person taps Sync; the server only
/// knows how many are waiting (as the app reports it) and nudges at most once a day.
/// </summary>
public static class AlbumSyncRules
{
    /// <summary>Items per POST sync and per POST sync/complete.</summary>
    public const int MaxBatchItems = 50;

    /// <summary>The most photos the app may report as waiting. Also a CHECK on the table.</summary>
    public const int MaxPendingCount = 100_000;

    /// <summary>Photos must have been waiting this long before the first reminder.</summary>
    public static readonly TimeSpan WaitingBeforeReminder = TimeSpan.FromHours(6);

    /// <summary>At most one reminder per this interval.</summary>
    public static readonly TimeSpan ReminderInterval = TimeSpan.FromHours(24);

    /// <summary>A phone that has not reported for this long has probably lost the app: stop nudging.</summary>
    public static readonly TimeSpan ReportFreshness = TimeSpan.FromDays(7);

    /// <summary>
    /// A sync started longer ago than this is treated as abandoned; the next one starts afresh.
    /// Long on purpose: a batch of videos on mobile data can take hours, and restarting mid-sync
    /// would leave the earlier batches out of the partner's "added N photos".
    /// </summary>
    public static readonly TimeSpan SyncSessionLifetime = TimeSpan.FromHours(24);

    /// <summary>
    /// A report is plausible when the count is in range and the bytes could belong to that many
    /// files: no bytes for nothing, and no more than a full-size video each.
    /// </summary>
    public static bool IsValidPending(int count, long bytes)
        => count is >= 0 and <= MaxPendingCount && bytes >= 0 && bytes <= count * MemoryRules.MaxVideoBytes;

    /// <summary>
    /// Whether the reminder is due, by time alone. Whether the person is in an active couple, has
    /// the album category on and is outside quiet hours is checked separately.
    /// </summary>
    public static bool IsDueForReminder(AlbumSyncState state, DateTime utcNow)
        => state.PendingCount > 0
           && state.PendingSinceUtc is { } since && since <= utcNow - WaitingBeforeReminder
           && state.ReportedAtUtc >= utcNow - ReportFreshness
           && (state.LastPromptedAtUtc is not { } prompted || prompted <= utcNow - ReminderInterval);
}
