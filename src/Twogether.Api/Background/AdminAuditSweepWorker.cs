using Twogether.Application.Common.Interfaces;
using Twogether.Application.Features.Admin;
using Twogether.Application.Features.Admin.Ops;

namespace Twogether.Api.Background;

/// <summary>Deletes admin audit entries older than two years (<see cref="AdminAuditRetention"/>). Runs once a day.</summary>
public sealed class AdminAuditSweepWorker(IServiceScopeFactory scopeFactory, IDateTimeProvider clock, ILogger<AdminAuditSweepWorker> logger, WorkerHeartbeats? heartbeats = null) : BackgroundService
{
    private static readonly TimeSpan Interval = TimeSpan.FromDays(1);
    public const string HeartbeatName = "adminAuditSweep";

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        heartbeats?.Register(HeartbeatName, Interval);
        await DatabaseReadiness.WaitUntilAvailableAsync(scopeFactory, logger, nameof(AdminAuditSweepWorker), stoppingToken);
        using var timer = new PeriodicTimer(Interval);
        do
        {
            try
            {
                await using var scope = scopeFactory.CreateAsyncScope();
                var db = scope.ServiceProvider.GetRequiredService<IApplicationDbContext>();
                var removed = await AdminAuditRetention.DeleteExpiredAsync(db, clock.UtcNow, stoppingToken);
                if (removed > 0) logger.LogInformation("Admin audit sweep removed {Count} entr(ies) older than two years.", removed);
                heartbeats?.Ok(HeartbeatName, Interval);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                return;
            }
            catch (Exception exception)
            {
                logger.LogError(exception, "Admin audit sweep failed.");
                heartbeats?.Failed(HeartbeatName, Interval, exception);
            }
        }
        while (await timer.WaitForNextTickAsync(stoppingToken));
    }
}
