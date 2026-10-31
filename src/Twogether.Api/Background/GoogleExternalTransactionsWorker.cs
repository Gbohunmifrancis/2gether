using Twogether.Application.Features.Admin.Ops;
using Twogether.Application.Features.Billing;

namespace Twogether.Api.Background;

/// <summary>
/// Reports card payments made in the app (Google Play billing choice) to Google, from the outbox the Bachs webhook
/// fills (docs/billing.md). Every few minutes, well inside Google's 24 hours; failures back off per row and stay
/// queued. Without Google credentials it only counts what is waiting.
/// </summary>
public sealed class GoogleExternalTransactionsWorker(
    IServiceScopeFactory scopeFactory,
    ILogger<GoogleExternalTransactionsWorker> logger,
    WorkerHeartbeats? heartbeats = null) : BackgroundService
{
    private static readonly TimeSpan Interval = TimeSpan.FromMinutes(5);
    private static readonly TimeSpan StartupDelay = TimeSpan.FromSeconds(45);
    public const string HeartbeatName = "googleExternalTransactions";

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        heartbeats?.Register(HeartbeatName, Interval);
        await DatabaseReadiness.WaitUntilAvailableAsync(scopeFactory, logger, nameof(GoogleExternalTransactionsWorker), stoppingToken);
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
                await using var scope = scopeFactory.CreateAsyncScope();
                var outcome = await scope.ServiceProvider.GetRequiredService<GoogleExternalTransactionSender>().RunAsync(stoppingToken);
                if (outcome.Reported > 0) logger.LogInformation("Reported {Count} external transactions to Google.", outcome.Reported);
                heartbeats?.Ok(HeartbeatName, Interval);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                return;
            }
            catch (Exception exception)
            {
                logger.LogError(exception, "Google external transaction reporting failed.");
                heartbeats?.Failed(HeartbeatName, Interval, exception);
            }
        } while (await timer.WaitForNextTickAsync(stoppingToken));
    }
}
