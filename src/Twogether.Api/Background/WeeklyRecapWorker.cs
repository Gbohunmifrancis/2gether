using Microsoft.AspNetCore.SignalR;
using Twogether.Api.Hubs;
using Twogether.Api.Realtime;
using Twogether.Application.Features.Admin.Ops;
using Twogether.Infrastructure.Ai;

namespace Twogether.Api.Background;

/// <summary>
/// Every 15 minutes, writes the weekly recap for couples whose week just ended (Sunday 18:00 in their time zone; see
/// <see cref="WeeklyRecapWriter"/>). Outside Sunday and Monday in UTC a run costs nothing: it doesn't touch the database.
/// </summary>
public sealed class WeeklyRecapWorker(
    IServiceScopeFactory scopeFactory,
    IHubContext<CoupleHub> hub,
    ILogger<WeeklyRecapWorker> logger,
    WorkerHeartbeats? heartbeats = null) : BackgroundService
{
    private static readonly TimeSpan Interval = TimeSpan.FromMinutes(15);
    private static readonly TimeSpan StartupDelay = TimeSpan.FromMinutes(2);
    public const string HeartbeatName = "weeklyRecaps";

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        heartbeats?.Register(HeartbeatName, Interval);
        await DatabaseReadiness.WaitUntilAvailableAsync(scopeFactory, logger, nameof(WeeklyRecapWorker), stoppingToken);
        try
        {
            await Task.Delay(StartupDelay, stoppingToken);
        }
        catch (OperationCanceledException)
        {
            return;
        }
        using var timer = new PeriodicTimer(Interval);
        do
        {
            try
            {
                await RunAsync(stoppingToken);
                heartbeats?.Ok(HeartbeatName, Interval);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                return;
            }
            catch (Exception exception)
            {
                logger.LogError(exception, "Weekly recap run failed.");
                heartbeats?.Failed(HeartbeatName, Interval, exception);
            }
        }
        while (await timer.WaitForNextTickAsync(stoppingToken));
    }

    public async Task<WeeklyRecapWriter.RunSummary> RunAsync(CancellationToken cancellationToken)
    {
        await using var scope = scopeFactory.CreateAsyncScope();
        var (summary, notices) = await scope.ServiceProvider.GetRequiredService<WeeklyRecapWriter>().RunAsync(cancellationToken);
        foreach (var notice in notices)
        {
            try
            {
                await ExerciseEvents.NotificationAsync(hub, notice, cancellationToken);
            }
            catch (Exception exception) when (exception is not OperationCanceledException)
            {
                // The row is saved and the push goes out anyway; only the live in-app copy is lost.
                logger.LogWarning(exception, "Could not announce weekly recap notification {NotificationId} live.", notice.Id);
            }
        }
        return summary;
    }
}
