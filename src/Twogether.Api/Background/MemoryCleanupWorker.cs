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

        var abandonedBefore = clock.UtcNow - settings.PendingLifetime;
        var batch = await db.Memories
            .Where(item => item.Status == MemoryStatus.Deleting || (item.Status == MemoryStatus.Pending && item.CreatedAtUtc < abandonedBefore))
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
