using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.SignalR;
using Microsoft.EntityFrameworkCore;
using Twogether.Api.Controllers;
using Twogether.Application.Common.Interfaces;
using Twogether.Application.Features.Games;
using Twogether.Core.Enums;

namespace Twogether.Api.Hubs;

[Authorize]
public sealed class GameHub(IApplicationDbContext db, ICurrentUser currentUser, IDateTimeProvider clock, IGameEngine engine, IGameWordValidator wordValidator) : Hub
{
    public static string GroupName(Guid sessionId) => $"game-{sessionId:N}";

    public async Task JoinSession(Guid sessionId)
    {
        await GetOwnedSession(sessionId);
        await Groups.AddToGroupAsync(Context.ConnectionId, GroupName(sessionId));
    }

    public Task LeaveSession(Guid sessionId)
        => Groups.RemoveFromGroupAsync(Context.ConnectionId, GroupName(sessionId));

    public async Task<GameSessionDto> ApplyAction(Guid sessionId, long expectedStateVersion, string action, string? value, int? index)
    {
        var session = await GetOwnedSession(sessionId);
        if (session.Status != GameSessionStatus.InProgress) throw new HubException("The game is not in progress.");
        if (session.CountdownEndsAtUtc > clock.UtcNow) throw new HubException("Wait for the countdown to finish.");
        if (session.StateVersion != expectedStateVersion) throw new HubException("The game state changed. Reload before acting.");
        if (session.GameType != GameType.OnGame && session.CurrentTurnUserId != currentUser.UserId!.Value) throw new HubException("It is not this player's turn.");
        GameEngineResult next;
        var expired = session.DeadlineUtc is { } deadline && deadline <= clock.UtcNow;
        if (expired && session.GameType != GameType.OnGame) throw new HubException("This round expired. Start another game.");
        action = expired && session.GameType == GameType.OnGame ? "timeout" : action;
        IReadOnlyDictionary<string, bool?>? validations = null;
        if (session.GameType == GameType.OnGame && action.Equals("submit", StringComparison.OrdinalIgnoreCase) && value is not null)
            validations = await wordValidator.ValidateAsync(session.StateJson, value, Context.ConnectionAborted);
        try { next = engine.Apply(session.GameType, session.StateJson, currentUser.UserId.Value, action, value, index, clock.UtcNow, validations); }
        catch (InvalidOperationException exception) { throw new HubException(exception.Message); }
        if (next.Completed) session.Complete(next.StateJson, clock.UtcNow);
        else session.UpdateState(next.CurrentTurnUserId!.Value, next.DeadlineUtc, next.StateJson, clock.UtcNow);
        try
        {
            await db.SaveChangesAsync(Context.ConnectionAborted);
        }
        catch (DbUpdateConcurrencyException)
        {
            throw new HubException("Your partner just updated the game. Reload the latest board and try again.");
        }
        // Same event names and same payload shape as GameController and
        // GameTimeoutWorker, so a client can handle a game update from any
        // source with one code path.
        var result = GameController.ToDto(session, engine);
        var eventName = session.Status == GameSessionStatus.Completed ? "GameCompleted" : "GameStateChanged";
        await Clients.Group(GroupName(session.Id)).SendAsync(eventName, result, Context.ConnectionAborted);
        return result;
    }

    private async Task<Twogether.Core.Entities.GameSession> GetOwnedSession(Guid sessionId)
    {
        if (!currentUser.CoupleId.HasValue) throw new HubException("A linked couple is required.");
        var session = await db.GameSessions.SingleOrDefaultAsync(item => item.Id == sessionId && item.CoupleId == currentUser.CoupleId.Value, Context.ConnectionAborted);
        return session ?? throw new HubException("Game session not found.");
    }
}
