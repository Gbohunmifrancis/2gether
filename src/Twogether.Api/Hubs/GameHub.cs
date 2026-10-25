using Twogether.Api.Presence;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.SignalR;
using Microsoft.EntityFrameworkCore;
using Twogether.Api.Controllers;
using Twogether.Application.Common.Interfaces;
using Twogether.Application.Features.Games;
using Twogether.Core.Enums;
using Twogether.Shared.Localization;

namespace Twogether.Api.Hubs;

[Authorize]
public sealed class GameHub(IApplicationDbContext db, ICurrentUser currentUser, IDateTimeProvider clock, IGameEngine engine, IGameWordValidator wordValidator, HubConnectionRegistry connections) : Hub
{
    public override Task OnConnectedAsync()
    {
        if (currentUser.UserId.HasValue) connections.Add(currentUser.UserId.Value, Context);
        return base.OnConnectedAsync();
    }

    public override Task OnDisconnectedAsync(Exception? exception)
    {
        if (currentUser.UserId.HasValue) connections.Remove(currentUser.UserId.Value, Context.ConnectionId);
        return base.OnDisconnectedAsync(exception);
    }

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
        if (!engine.AnyoneMayAct(session.GameType) && session.CurrentTurnUserId != currentUser.UserId!.Value) throw new HubException(Loc.T("It is not this player's turn."));
        GameEngineResult next;
        var expired = session.DeadlineUtc is { } deadline && deadline <= clock.UtcNow;
        if (expired && !engine.IsTimed(session.GameType)) throw new HubException(Loc.T("This round expired. Start another game."));
        if (GameEngine.IsServerOnlyAction(action) || string.IsNullOrWhiteSpace(action) || action.Length > 40 || value?.Length > GameEngine.MaxActionValueLength)
            throw new HubException("That action is not available.");
        action = expired && engine.IsTimed(session.GameType) ? GameEngine.TimeoutAction : action;
        var validations = await wordValidator.ForMoveAsync(session.GameType, session.StateJson, action, value, Context.ConnectionAborted);
        try { next = engine.Apply(session.GameType, session.StateJson, currentUser.UserId!.Value, action, value, index, clock.UtcNow, validations); }
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
        // Everyone in the session's group gets the state with no secrets; the mover gets their own view back.
        var eventName = session.Status == GameSessionStatus.Completed ? "GameCompleted" : "GameStateChanged";
        await Clients.Group(GroupName(session.Id)).SendAsync(eventName, GameController.ToDto(session, engine, null), Context.ConnectionAborted);
        return GameController.ToDto(session, engine, currentUser.UserId);
    }

    private async Task<Twogether.Core.Entities.GameSession> GetOwnedSession(Guid sessionId)
    {
        if (!currentUser.CoupleId.HasValue) throw new HubException("A linked couple is required.");
        var session = await db.GameSessions.SingleOrDefaultAsync(item => item.Id == sessionId && item.CoupleId == currentUser.CoupleId.Value, Context.ConnectionAborted);
        return session ?? throw new HubException("Game session not found.");
    }
}
