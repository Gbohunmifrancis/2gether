using Twogether.Application.Common.Interfaces;
using Twogether.Application.Features.Location;

namespace Twogether.Api.Background;

/// <summary>
/// Deletes location rows nothing has touched for a week (a share stopped long ago, or a phone
/// that went quiet without stopping), so no last point lingers. Runs every hour.
/// </summary>
public sealed class LocationSweepWorker(IServiceScopeFactory scopeFactory, IDateTimeProvider clock, ILogger<LocationSweepWorker> logger) : BackgroundService
{
    private static readonly TimeSpan Interval = TimeSpan.FromHours(1);

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        await DatabaseReadiness.WaitUntilAvailableAsync(scopeFactory, logger, nameof(LocationSweepWorker), stoppingToken);
        using var timer = new PeriodicTimer(Interval);
        do
        {
            try
            {
                await using var scope = scopeFactory.CreateAsyncScope();
                var db = scope.ServiceProvider.GetRequiredService<IApplicationDbContext>();
                var removed = await LocationRetention.DeleteUnusedAsync(db, clock.UtcNow, stoppingToken);
                if (removed > 0) logger.LogInformation("Location sweep removed {Count} unused row(s).", removed);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                return;
            }
            catch (Exception exception)
            {
                logger.LogError(exception, "Location sweep failed.");
            }
        }
        while (await timer.WaitForNextTickAsync(stoppingToken));
    }
}
