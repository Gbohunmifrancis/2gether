using Microsoft.AspNetCore.SignalR;
using Microsoft.EntityFrameworkCore;
using Twogether.Api.Controllers;
using Twogether.Api.Hubs;
using Twogether.Api.Presence;
using Twogether.Api.Realtime;
using Twogether.Application.Common.Interfaces;
using Twogether.Application.Features.Admin.Ops;
using Twogether.Application.Features.Games;
using Twogether.Core.Enums;

namespace Twogether.Api.Background;

public sealed class GameTimeoutWorker(
    IServiceScopeFactory scopeFactory,
    IHubContext<CoupleHub> hub,
    IGameEngine engine,
    IDateTimeProvider clock,
    ILogger<GameTimeoutWorker> logger,
    WorkerHeartbeats? heartbeats = null,
    GameLiveState? liveState = null) : BackgroundService
{
    private static readonly TimeSpan Interval = TimeSpan.FromSeconds(1);
    public const string HeartbeatName = "gameTimeouts";

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        // The default local profile may have no database. Wait for one instead of
        // crashing the loop every second; appsettings always supplies a connection
        // string, so a configuration check alone would never notice.
        heartbeats?.Register(HeartbeatName, Interval);
        await DatabaseReadiness.WaitUntilAvailableAsync(scopeFactory, logger, nameof(GameTimeoutWorker), stoppingToken);

        using var timer = new PeriodicTimer(Interval);
        while (await timer.WaitForNextTickAsync(stoppingToken))
        {
            try
            {
                await AdvanceExpiredRounds(stoppingToken);
                if (clock.UtcNow >= nextStaleSweep)
                {
                    nextStaleSweep = clock.UtcNow.AddMinutes(10);
                    await EndStaleGames(stoppingToken);
                }
                heartbeats?.Ok(HeartbeatName, Interval);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                return;
            }
            catch (Exception exception)
            {
                logger.LogError(exception, "Failed to advance expired game rounds.");
                heartbeats?.Failed(HeartbeatName, Interval, exception);
            }
        }
    }

    private DateTime nextStaleSweep = DateTime.MinValue;

    /// <summary>An unanswered invite lasts a day; a turn-by-turn game left alone for a week ends. Either would block new games.</summary>
    public static readonly TimeSpan InviteLifetime = TimeSpan.FromDays(1);
    public static readonly TimeSpan IdleGameLifetime = TimeSpan.FromDays(7);

    private async Task EndStaleGames(CancellationToken cancellationToken)
    {
        await using var scope = scopeFactory.CreateAsyncScope();
        var db = scope.ServiceProvider.GetRequiredService<IApplicationDbContext>();
        var now = clock.UtcNow;
        var inviteCutoff = now - InviteLifetime;
        var idleCutoff = now - IdleGameLifetime;
        var stale = await db.GameSessions
            .Where(session => (session.Status == GameSessionStatus.WaitingForPartner && session.UpdatedAtUtc < inviteCutoff)
                || ((session.Status == GameSessionStatus.InProgress || session.Status == GameSessionStatus.Paused) && session.UpdatedAtUtc < idleCutoff))
            .Take(100)
            .ToListAsync(cancellationToken);
        if (stale.Count == 0) return;
        foreach (var session in stale) { session.Abandon(now); liveState?.ClearSession(session.Id); }
        await db.SaveChangesAsync(cancellationToken);
        foreach (var session in stale)
        {
            await hub.Clients.Group(CoupleHub.GroupName(session.CoupleId))
                .SendAsync("GameEnded", new GameEndedDto(session.Id, Guid.Empty, "Heartpact", now), cancellationToken);
        }
        logger.LogInformation("Ended {Count} stale games.", stale.Count);
    }

    private async Task AdvanceExpiredRounds(CancellationToken cancellationToken)
    {
        await using var scope = scopeFactory.CreateAsyncScope();
        var db = scope.ServiceProvider.GetRequiredService<IApplicationDbContext>();
        var now = clock.UtcNow;
        // Only timed games set a deadline, so an expired deadline is always a timed game's.
        var sessions = (await db.GameSessions
            .Where(session => session.Status == GameSessionStatus.InProgress
                && session.DeadlineUtc != null
                && session.DeadlineUtc <= now)
            .ToListAsync(cancellationToken))
            .Where(session => engine.IsTimed(session.GameType))
            .ToList();
        if (sessions.Count == 0) return;

        foreach (var session in sessions)
        {
            // I call on: what each partner had typed when the clock ran out still counts.
            var drafts = GameController.LateAnswers(liveState, session, Guid.Empty, null, null);
            var next = engine.Apply(session.GameType, session.StateJson, Guid.Empty, GameEngine.TimeoutAction, null, null, now, coSubmissions: drafts);
            if (next.Completed) { session.Complete(next.StateJson, now); liveState?.ClearSession(session.Id); }
            else session.UpdateState(next.CurrentTurnUserId!.Value, next.DeadlineUtc, next.StateJson, now);
        }
        await db.SaveChangesAsync(cancellationToken);

        foreach (var session in sessions)
        {
            var eventName = session.Status == GameSessionStatus.Completed ? "GameCompleted" : "GameStateChanged";
            var players = await GameEvents.PlayersAsync(db, session.CoupleId, cancellationToken);
            await GameEvents.SendAsync(hub, engine, session, players, eventName, cancellationToken);
        }
    }
}
