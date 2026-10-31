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
        var ended = 0;
        foreach (var session in stale)
        {
            session.Abandon(now);
            // A move that lands at the same moment wins: the game wasn't stale after all.
            if (!await SavedAsync(db, cancellationToken)) continue;
            liveState?.ClearSession(session.Id);
            ended++;
            await hub.Clients.Group(CoupleHub.GroupName(session.CoupleId))
                .SendAsync("GameEnded", new GameEndedDto(session.Id, Guid.Empty, "Heartpact", now), cancellationToken);
        }
        logger.LogInformation("Ended {Count} stale games.", ended);
    }

    /// <summary>
    /// Saves one session's change. A partner's move that reached it first is a conflict: that change is dropped (the
    /// next tick looks again at the session as it now is) instead of failing every other game in the batch.
    /// </summary>
    public static async Task<bool> SavedAsync(IApplicationDbContext db, CancellationToken cancellationToken)
    {
        try
        {
            await db.SaveChangesAsync(cancellationToken);
            return true;
        }
        catch (DbUpdateConcurrencyException exception)
        {
            foreach (var entry in exception.Entries) entry.State = EntityState.Detached;
            return false;
        }
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
            GameEngineResult next;
            try
            {
                // I call on: what each partner had typed when the clock ran out still counts.
                var drafts = GameController.LateAnswers(liveState, session, Guid.Empty, null, null);
                next = engine.Apply(session.GameType, session.StateJson, Guid.Empty, GameEngine.TimeoutAction, null, null, now, coSubmissions: drafts);
            }
            catch (Exception exception) when (exception is System.Text.Json.JsonException or InvalidOperationException)
            {
                // A state the rules can't read would come back every second and hold up every other game: end it.
                logger.LogWarning(exception, "Ending game {SessionId}: its timeout could not be played.", session.Id);
                session.Abandon(now);
                if (await SavedAsync(db, cancellationToken))
                {
                    liveState?.ClearSession(session.Id);
                    await hub.Clients.Group(CoupleHub.GroupName(session.CoupleId)).SendAsync("GameEnded", new GameEndedDto(session.Id, Guid.Empty, "Heartpact", now), cancellationToken);
                }
                continue;
            }
            if (next.Completed) { session.Complete(next.StateJson, now); liveState?.ClearSession(session.Id); }
            else session.UpdateState(next.CurrentTurnUserId!.Value, next.DeadlineUtc, next.StateJson, now);
            // A late move that crossed this tick has already played the timeout: skip this one, as is.
            if (!await SavedAsync(db, cancellationToken)) continue;
            var eventName = session.Status == GameSessionStatus.Completed ? "GameCompleted" : "GameStateChanged";
            var players = await GameEvents.PlayersAsync(db, session.CoupleId, cancellationToken);
            await GameEvents.SendAsync(hub, engine, session, players, eventName, cancellationToken);
        }
    }
}
