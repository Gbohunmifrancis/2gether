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
                await SweepAsync(stoppingToken);
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
