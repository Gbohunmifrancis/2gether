using Twogether.Infrastructure.Persistence;

namespace Twogether.Api.Background;

/// <summary>
/// Background workers must not spin on a database that is not there yet (local profile
/// without Postgres, or a deploy whose database comes up a few seconds later).
/// </summary>
public static class DatabaseReadiness
{
    private static readonly TimeSpan RetryDelay = TimeSpan.FromSeconds(30);

    /// <summary>Returns once a connection succeeds, or never when cancelled.</summary>
    public static async Task WaitUntilAvailableAsync(IServiceScopeFactory scopeFactory, ILogger logger, string workerName, CancellationToken cancellationToken)
    {
        var warned = false;
        while (!cancellationToken.IsCancellationRequested)
        {
            try
            {
                await using var scope = scopeFactory.CreateAsyncScope();
                var database = scope.ServiceProvider.GetRequiredService<ApplicationDbContext>();
                if (await database.Database.CanConnectAsync(cancellationToken))
                {
                    if (warned) logger.LogInformation("{Worker}: database is reachable, starting.", workerName);
                    return;
                }
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                return;
            }
            catch (Exception exception)
            {
                if (!warned) logger.LogWarning(exception, "{Worker}: database unavailable, retrying every {Seconds}s.", workerName, RetryDelay.TotalSeconds);
            }
            if (!warned)
            {
                logger.LogWarning("{Worker}: database unavailable, retrying every {Seconds}s.", workerName, RetryDelay.TotalSeconds);
                warned = true;
            }
            try { await Task.Delay(RetryDelay, cancellationToken); }
            catch (OperationCanceledException) { return; }
        }
    }
}
