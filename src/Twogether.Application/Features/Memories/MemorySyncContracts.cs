using Twogether.Core.Enums;
using Twogether.Core.Memories;
using Twogether.Shared.Results;

namespace Twogether.Application.Features.Memories;

/// <summary>What the phone says is waiting to sync. Sent whenever its local queue changes and on app start.</summary>
public sealed record ReportPendingRequest(int PendingCount, long PendingBytes);

public sealed record AlbumSyncDto(
    int PendingCount,
    long PendingBytes,
    DateTime? PendingSinceUtc,
    DateTime? LastSyncedAtUtc,
    MemoryUsageDto Usage,
    int MaxBatchItems);

/// <summary>One photo from the phone's queue. ClientId is the phone's own id for it and makes a retried batch safe.</summary>
public sealed record SyncMemoryItem(
    Guid ClientId,
    string ContentType,
    long SizeBytes,
    string? Caption,
    DateTime? TakenAtUtc,
    MemorySource Source = MemorySource.Gallery,
    string? ThumbnailContentType = null)
{
    public ReserveMemoryRequest ToReserveRequest() => new(ContentType, SizeBytes, Caption, TakenAtUtc, Source, ThumbnailContentType);
}

public sealed record SyncMemoriesRequest(IReadOnlyList<SyncMemoryItem>? Items);

public enum SyncItemStatus
{
    /// <summary>PUT the bytes to the returned links, then complete.</summary>
    Upload,
    /// <summary>Already in the album; drop it from the queue.</summary>
    Done,
    /// <summary>Deleted from the album since; drop it from the queue.</summary>
    Removed,
    /// <summary>Not accepted; see error. Other items in the batch are unaffected.</summary>
    Rejected
}

public sealed record MemorySyncErrorDto(string Code, string Message);

public sealed record SyncItemResultDto(Guid ClientId, SyncItemStatus Status, Guid? MemoryId = null, MemoryUploadDto? Upload = null, MemorySyncErrorDto? Error = null);

public sealed record SyncMemoriesResponse(IReadOnlyList<SyncItemResultDto> Items, MemoryUsageDto Usage);

/// <summary>Finished marks the last batch of a sync: the partner hears about it once, then.</summary>
public sealed record CompleteSyncRequest(IReadOnlyList<Guid>? MemoryIds, bool Finished = true);

public enum SyncCompleteStatus
{
    Ready,
    NotUploaded,
    Rejected,
    NotFound
}

public sealed record SyncCompleteItemDto(Guid MemoryId, SyncCompleteStatus Status, MemoryDto? Memory = null, MemorySyncErrorDto? Error = null);

public sealed record CompleteSyncResponse(IReadOnlyList<SyncCompleteItemDto> Items);

public static class MemorySyncRules
{
    public static readonly Error InvalidPending = Error.Validation("memories.invalid_pending",
        $"Pending photos must be between 0 and {AlbumSyncRules.MaxPendingCount}, with a size that fits that many files.");

    public static readonly Error InvalidBatch = Error.Validation("memories.invalid_batch",
        $"Send between 1 and {AlbumSyncRules.MaxBatchItems} items, each with its own id.");

    public static Result ValidatePending(ReportPendingRequest request)
        => AlbumSyncRules.IsValidPending(request.PendingCount, request.PendingBytes) ? Result.Success() : InvalidPending;

    /// <summary>The batch as a whole; each item is validated on its own later so one bad photo does not block the rest.</summary>
    public static Result ValidateBatch(SyncMemoriesRequest request)
    {
        if (request.Items is not { Count: > 0 and <= AlbumSyncRules.MaxBatchItems } items) return InvalidBatch;
        if (items.Any(item => item is null || item.ClientId == Guid.Empty)) return InvalidBatch;
        return items.Select(item => item.ClientId).Distinct().Count() == items.Count ? Result.Success() : InvalidBatch;
    }

    public static Result ValidateComplete(CompleteSyncRequest request)
    {
        if (request.MemoryIds is not { Count: > 0 and <= AlbumSyncRules.MaxBatchItems } ids) return InvalidBatch;
        return ids.Distinct().Count() == ids.Count ? Result.Success() : InvalidBatch;
    }
}

/// <summary>Notification wording for sync, in one place so the worker and the endpoint agree.</summary>
public static class MemorySyncText
{
    public const string SyncedTitle = "New photos in your album";
    public const string ReminderTitle = "Photos waiting to sync";

    public static string Photos(int count) => count == 1 ? "1 photo" : $"{count} photos";

    public static string SyncedBody(string uploaderName, int count) => $"{uploaderName} added {Photos(count)} to your album.";

    public static string ReminderBody(int pendingCount, string partnerName)
        => pendingCount == 1
            ? $"1 photo is waiting on this phone. Sync it to share with {partnerName}."
            : $"{pendingCount} photos are waiting on this phone. Sync them to share with {partnerName}.";
}
