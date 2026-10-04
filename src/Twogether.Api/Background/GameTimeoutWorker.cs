using Microsoft.AspNetCore.SignalR;
using Microsoft.EntityFrameworkCore;
using Twogether.Api.Controllers;
using Twogether.Api.Hubs;
using Twogether.Application.Common.Interfaces;
using Twogether.Application.Features.Games;
using Twogether.Core.Enums;

namespace Twogether.Api.Background;

public sealed class GameTimeoutWorker(
    IServiceScopeFactory scopeFactory,
    IHubContext<CoupleHub> hub,
    IGameEngine engine,
    IDateTimeProvider clock,
    IConfiguration configuration,
    ILogger<GameTimeoutWorker> logger) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        // The default local profile has no database. Keep the web app usable in
        // that profile while enabling the worker automatically in deployed setups.
        if (string.IsNullOrWhiteSpace(configuration["DATABASE_URL"])
            && string.IsNullOrWhiteSpace(configuration["PGHOST"])
            && string.IsNullOrWhiteSpace(configuration["Database:ConnectionString"]))
            return;

        using var timer = new PeriodicTimer(TimeSpan.FromSeconds(1));
        while (await timer.WaitForNextTickAsync(stoppingToken))
        {
            try
            {
                await AdvanceExpiredRounds(stoppingToken);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                return;
            }
            catch (Exception exception)
            {
                logger.LogError(exception, "Failed to advance expired game rounds.");
            }
        }
    }

    private async Task AdvanceExpiredRounds(CancellationToken cancellationToken)
    {
        await using var scope = scopeFactory.CreateAsyncScope();
        var db = scope.ServiceProvider.GetRequiredService<IApplicationDbContext>();
        var now = clock.UtcNow;
        var sessions = await db.GameSessions
            .Where(session => session.Status == GameSessionStatus.InProgress
                && session.GameType == GameType.ICallOn
                && session.DeadlineUtc != null
                && session.DeadlineUtc <= now)
            .ToListAsync(cancellationToken);
        if (sessions.Count == 0) return;

        foreach (var session in sessions)
        {
            var next = engine.Apply(session.GameType, session.StateJson, Guid.Empty, "timeout", null, null, now);
            if (next.Completed) session.Complete(next.StateJson, now);
            else session.UpdateState(next.CurrentTurnUserId!.Value, next.DeadlineUtc, next.StateJson, now);
        }
        await db.SaveChangesAsync(cancellationToken);

        foreach (var session in sessions)
        {
            var eventName = session.Status == GameSessionStatus.Completed ? "GameCompleted" : "GameStateChanged";
            await hub.Clients.Group(CoupleHub.GroupName(session.CoupleId))
                .SendAsync(eventName, GameController.ToDto(session, engine), cancellationToken);
        }
    }
}
