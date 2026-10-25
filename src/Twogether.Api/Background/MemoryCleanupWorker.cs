using Microsoft.EntityFrameworkCore;
using Twogether.Application.Common.Interfaces;
using Twogether.Application.Features.Memories;
using Twogether.Core.Enums;

namespace Twogether.Api.Background;

/// <summary>
/// Removes upload slots nobody completed and finishes deletes whose storage call failed, so a
/// photo someone deleted never lingers in the bucket. Runs every few minutes in small batches.
/// </summary>
public sealed class MemoryCleanupWorker(
    IServiceScopeFactory scopeFactory,
    IMediaStorage storage,
    MediaSettings settings,
    IDateTimeProvider clock,
    ILogger<MemoryCleanupWorker> logger) : BackgroundService
{
    private const int BatchSize = 100;
    private static readonly TimeSpan Interval = TimeSpan.FromMinutes(5);

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        if (!storage.IsConfigured)
        {
            logger.LogInformation("Media storage is not configured; memory cleanup is off.");
            return;
        }
        await DatabaseReadiness.WaitUntilAvailableAsync(scopeFactory, logger, nameof(MemoryCleanupWorker), stoppingToken);
        using var timer = new PeriodicTimer(Interval);
        do
        {
            try
            {
                while (await RecheckClosedUploadWindowsAsync(stoppingToken) == BatchSize) { }
                // Drain: an ended album can hold thousands of photos.
                while (await SweepAsync(stoppingToken) == BatchSize) { }
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                return;
            }
            catch (Exception exception)
            {
                logger.LogError(exception, "Memory cleanup sweep failed.");
            }
        }
        while (await timer.WaitForNextTickAsync(stoppingToken));
    }

    /// <summary>
    /// A signed upload link keeps working until it expires, even after the upload it was for has
    /// been verified, so the uploader could replace a checked photo with anything (and any size)
    /// at the same key. Once every link for a memory has expired, compare what is stored with
    /// what was verified: a replaced photo takes its memory with it, and a thumbnail that does not
    /// match the confirmed one, or was never confirmed, is removed.
    /// </summary>
    public async Task<int> RecheckClosedUploadWindowsAsync(CancellationToken cancellationToken)
    {
        await using var scope = scopeFactory.CreateAsyncScope();
        var db = scope.ServiceProvider.GetRequiredService<IApplicationDbContext>();
        var now = clock.UtcNow;
        var due = await db.Memories
            .Where(item => item.Status == MemoryStatus.Ready && item.UploadWindowEndsAtUtc != null && item.UploadWindowEndsAtUtc <= now)
            .OrderBy(item => item.UploadWindowEndsAtUtc)
            .Take(BatchSize)
            .ToListAsync(cancellationToken);
        var rechecked = 0;
        foreach (var memory in due)
        {
            try
            {
                var original = await storage.HeadAsync(memory.ObjectKey, cancellationToken);
                if (!Matches(original, memory.ContentType, memory.SizeBytes))
                {
                    logger.LogWarning("Memory {MemoryId} changed in storage after it was verified ({Bytes} bytes, {Type}); removing it.",
                        memory.Id, original?.SizeBytes, original?.ContentType);
                    memory.Describe(null, null, now);
                    memory.MarkDeleting(now);
                }
                else
                {
                    var thumbnail = await storage.HeadAsync(memory.ThumbnailKey, cancellationToken);
                    if (thumbnail is not null && !(memory.HasThumbnail && Matches(thumbnail, memory.ThumbnailContentType!, memory.ThumbnailBytes)))
                    {
                        logger.LogWarning("Thumbnail of memory {MemoryId} does not match the confirmed one; removing it.", memory.Id);
                        await storage.DeleteAsync(memory.ThumbnailKey, cancellationToken);
                        memory.DetachThumbnail(now);
                    }
                    else if (thumbnail is null && memory.HasThumbnail) memory.DetachThumbnail(now);
                }
                memory.CloseUploadWindow(now);
                rechecked++;
            }
            catch (Exception exception) when (exception is not OperationCanceledException)
            {
                // Left due: the next sweep tries again.
                logger.LogWarning(exception, "Could not recheck memory {MemoryId} in storage; will retry.", memory.Id);
            }
        }
        await db.SaveChangesAsync(cancellationToken);
        // Only what was actually rechecked: if storage is down, the drain loop must stop rather
        // than spin on the same failing batch.
        return rechecked;
    }

    private static bool Matches(StoredObjectInfo? stored, string contentType, long sizeBytes)
        => stored is not null
           && stored.SizeBytes == sizeBytes
           && string.Equals(stored.ContentType?.Split(';')[0].Trim(), contentType, StringComparison.OrdinalIgnoreCase);

    public async Task<int> SweepAsync(CancellationToken cancellationToken)
    {
        await using var scope = scopeFactory.CreateAsyncScope();
        var db = scope.ServiceProvider.GetRequiredService<IApplicationDbContext>();
        // Albums of couples that ended longer ago than the retention window: queue every photo,
        // wiping captions now so nothing personal lingers while the objects are removed.
        var endedBefore = clock.UtcNow - settings.BreakupRetention;
        var expired = await db.Memories
            .Where(item => item.Status != MemoryStatus.Deleting
                && db.Couples.Any(couple => couple.Id == item.CoupleId && couple.Status == CoupleStatus.Dissolved && couple.DissolvedAtUtc <= endedBefore))
            .ExecuteUpdateAsync(set => set
                .SetProperty(item => item.Status, MemoryStatus.Deleting)
                .SetProperty(item => item.Caption, (string?)null)
                .SetProperty(item => item.TakenAtUtc, (DateTime?)null)
                .SetProperty(item => item.UpdatedAtUtc, clock.UtcNow), cancellationToken);
        if (expired > 0) logger.LogInformation("Queued {Count} memory object(s) from albums past their post-breakup window.", expired);

        var now = clock.UtcNow;
        var abandonedBefore = now - settings.PendingLifetime;
        // Nothing goes while one of its upload links still works: a sync retry can hand out fresh
        // links for an old slot, and a late PUT could put a deleted memory's bytes back with no row
        // left to find them. So a deleted memory stays as a tombstone until its window has passed,
        // and its objects are deleted (again) right before the row.
        var batch = await db.Memories
            .Where(item => (item.UploadWindowEndsAtUtc == null || item.UploadWindowEndsAtUtc <= now)
                && (item.Status == MemoryStatus.Deleting || (item.Status == MemoryStatus.Pending && item.CreatedAtUtc < abandonedBefore)))
            .OrderBy(item => item.CreatedAtUtc)
            .Take(BatchSize)
            .ToListAsync(cancellationToken);
        var removed = 0;
        foreach (var memory in batch)
        {
            try
            {
                await storage.DeleteAsync(memory.ObjectKey, cancellationToken);
                // Always: a thumbnail may have been uploaded and never confirmed.
                await storage.DeleteAsync(memory.ThumbnailKey, cancellationToken);
                db.Memories.Remove(memory);
                removed++;
            }
            catch (Exception exception) when (exception is not OperationCanceledException)
            {
                logger.LogWarning(exception, "Could not remove memory {MemoryId} from storage; will retry.", memory.Id);
            }
        }
        await db.SaveChangesAsync(cancellationToken);
        if (removed > 0) logger.LogInformation("Memory cleanup removed {Count} object(s).", removed);
        return removed;
    }
}
