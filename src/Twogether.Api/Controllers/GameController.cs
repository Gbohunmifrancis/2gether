using System.Text.Json;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.SignalR;
using Microsoft.EntityFrameworkCore;
using Twogether.Api.Common;
using Twogether.Api.Hubs;
using Twogether.Api.Realtime;
using Twogether.Application.Common.Interfaces;
using Twogether.Application.Features.Games;
using Twogether.Core.Entities;
using Twogether.Core.Enums;

namespace Twogether.Api.Controllers;

[Authorize]
public sealed class GameController(
    IApplicationDbContext db,
    ICurrentUser currentUser,
    IDateTimeProvider clock,
    IGameEngine engine,
    IGameWordValidator wordValidator,
    IGameQuestionProvider questionProvider,
    IHubContext<CoupleHub> hub) : ApiControllerBase
{
    [HttpGet("sessions")]
    public async Task<ActionResult<IReadOnlyList<GameSessionDto>>> List(CancellationToken cancellationToken)
    {
        if (!currentUser.CoupleId.HasValue) return Ok(Array.Empty<GameSessionDto>());
        var sessions = await db.GameSessions.AsNoTracking().Where(session => session.CoupleId == currentUser.CoupleId.Value).OrderByDescending(session => session.UpdatedAtUtc).Take(30).ToListAsync(cancellationToken);
        return Ok(sessions.Select(ToDto).ToList());
    }

    [HttpPost("sessions")]
    public async Task<ActionResult<GameSessionDto>> Create(CreateGameSessionRequest request, CancellationToken cancellationToken)
    {
        if (!currentUser.CoupleId.HasValue || !currentUser.UserId.HasValue) return BadRequest(new ApiError("game.no_couple", "Link a partner before starting a game."));
        var linkedCouple = await db.Couples.AsNoTracking().AnyAsync(couple => couple.Id == currentUser.CoupleId.Value && couple.Status == CoupleStatus.Active, cancellationToken);
        if (!linkedCouple) return BadRequest(new ApiError("game.no_partner", "Link a partner before starting a game."));
        var active = await db.GameSessions.AnyAsync(session => session.CoupleId == currentUser.CoupleId.Value && (session.Status == GameSessionStatus.WaitingForPartner || session.Status == GameSessionStatus.InProgress), cancellationToken);
        if (active) return Conflict(new ApiError("game.active_session", "Your couple already has an active game."));

        var inviter = await db.Users.AsNoTracking().SingleAsync(user => user.Id == currentUser.UserId.Value, cancellationToken);
        var partnerId = await db.Users.AsNoTracking().Where(user => user.CoupleId == currentUser.CoupleId.Value && user.Id != inviter.Id).Select(user => (Guid?)user.Id).SingleOrDefaultAsync(cancellationToken);
        if (!partnerId.HasValue) return Conflict(new ApiError("game.players_missing", "Both partners must be linked before a game is invited."));

        var setupJson = request.GameType == GameType.ICallOn
            ? JsonSerializer.Serialize(new { letter = request.Letter })
            : JsonSerializer.Serialize(new { questions = await questionProvider.GetQuestionsAsync(cancellationToken) });
        var session = GameSession.Create(currentUser.CoupleId.Value, request.GameType, inviter.Id, clock.UtcNow, setupJson);
        var notification = Notification.Create(partnerId.Value, session.CoupleId, NotificationType.GameInvite, "A game is waiting", $"{inviter.DisplayName} invited you to {Humanize(request.GameType)}.", JsonSerializer.Serialize(new { sessionId = session.Id }), clock.UtcNow);
        db.GameSessions.Add(session);
        db.Notifications.Add(notification);
        await db.SaveChangesAsync(cancellationToken);

        await hub.Clients.Group(CoupleHub.UserGroupName(partnerId.Value)).SendAsync("GameInvitation", new GameInvitationDto(session.Id, session.GameType, inviter.Id, inviter.DisplayName, session.CreatedAtUtc), cancellationToken);
        await hub.Clients.Group(CoupleHub.UserGroupName(partnerId.Value)).SendAsync("NotificationCreated", NotificationController.ToDto(notification), cancellationToken);
        return Ok(ToDto(session));
    }

    [HttpGet("sessions/{sessionId:guid}")]
    public async Task<ActionResult<GameSessionDto>> Get(Guid sessionId, CancellationToken cancellationToken)
    {
        var session = await OwnedSession(sessionId, cancellationToken);
        return session is null ? NotFound() : Ok(ToDto(session));
    }

    [HttpPost("sessions/{sessionId:guid}/accept")]
    public async Task<ActionResult<GameSessionDto>> Accept(Guid sessionId, CancellationToken cancellationToken)
    {
        var session = await OwnedSession(sessionId, cancellationToken);
        if (session is null || !currentUser.UserId.HasValue) return NotFound();
        if (session.Status != GameSessionStatus.WaitingForPartner) return Conflict(new ApiError("game.not_waiting", "This game is no longer waiting for a partner."));
        if (session.InvitedByUserId == currentUser.UserId.Value) return Conflict(new ApiError("game.self_accept", "The inviting partner cannot accept their own invitation."));

        var players = await db.Users.Where(user => user.CoupleId == session.CoupleId).OrderBy(user => user.CreatedAtUtc).Select(user => user.Id).ToListAsync(cancellationToken);
        if (players.Count != 2) return Conflict(new ApiError("game.players_missing", "Both partners must be linked before the game starts."));
        var countdownEnds = clock.UtcNow.AddSeconds(5);
        session.AcceptInvite(countdownEnds, clock.UtcNow);
        var initial = engine.Start(session.GameType, players, countdownEnds, session.SetupJson);
        session.Start(initial.CurrentTurnUserId!.Value, initial.DeadlineUtc, initial.StateJson, clock.UtcNow);
        await db.SaveChangesAsync(cancellationToken);

        var result = ToDto(session);
        await hub.Clients.Group(CoupleHub.GroupName(session.CoupleId)).SendAsync("GameCountdown", new GameCountdownDto(session.Id, countdownEnds), cancellationToken);
        await hub.Clients.Group(CoupleHub.GroupName(session.CoupleId)).SendAsync("GameStarted", result, cancellationToken);
        return Ok(result);
    }

    [HttpPost("sessions/{sessionId:guid}/start")]
    public async Task<ActionResult<GameSessionDto>> Start(Guid sessionId, CancellationToken cancellationToken)
    {
        var session = await OwnedSession(sessionId, cancellationToken);
        if (session is null) return NotFound();
        if (session.Status == GameSessionStatus.InProgress) return Ok(ToDto(session));
        return Conflict(new ApiError("game.accept_required", "The invited partner must accept the game first."));
    }

    [HttpPost("sessions/{sessionId:guid}/actions")]
    public async Task<ActionResult<GameSessionDto>> ApplyAction(Guid sessionId, ApplyGameActionRequest request, CancellationToken cancellationToken)
    {
        var session = await OwnedSession(sessionId, cancellationToken);
        if (session is null || !currentUser.UserId.HasValue) return NotFound();
        if (session.Status != GameSessionStatus.InProgress) return Conflict(new ApiError("game.not_in_progress", "The game is not in progress."));
        if (session.CountdownEndsAtUtc > clock.UtcNow) return Conflict(new ApiError("game.countdown", "Wait for the countdown to finish."));
        if (session.StateVersion != request.ExpectedStateVersion)
            return Conflict(new ApiError("game.version_conflict", "The game state changed. The latest board has been loaded."));
        if (session.GameType != GameType.ICallOn && session.CurrentTurnUserId != currentUser.UserId.Value) return Forbid();
        try
        {
            var expired = session.DeadlineUtc is { } deadline && deadline <= clock.UtcNow;
            if (expired && session.GameType != GameType.ICallOn) return Conflict(new ApiError("game.expired", "This round expired. Start another game."));
            var action = expired && session.GameType == GameType.ICallOn ? "timeout" : request.Action;
            var validations = session.GameType == GameType.ICallOn && action.Equals("submit", StringComparison.OrdinalIgnoreCase) && request.Value is not null
                ? await wordValidator.ValidateAsync(session.StateJson, request.Value, cancellationToken)
                : null;
            var next = engine.Apply(session.GameType, session.StateJson, currentUser.UserId.Value, action, request.Value, request.Index, clock.UtcNow, validations);
            if (next.Completed) session.Complete(next.StateJson, clock.UtcNow);
            else session.UpdateState(next.CurrentTurnUserId!.Value, next.DeadlineUtc, next.StateJson, clock.UtcNow);
        }
        catch (JsonException)
        {
            return UnprocessableEntity(new ApiError("game.invalid_state", "This game session is corrupted. End it and start a new game."));
        }
        catch (InvalidOperationException exception)
        {
            return UnprocessableEntity(new ApiError("game.invalid_action", exception.Message));
        }
        try
        {
            await db.SaveChangesAsync(cancellationToken);
        }
        catch (DbUpdateConcurrencyException)
        {
            return Conflict(new ApiError("game.version_conflict", "Your partner just updated the game. The latest board has been loaded."));
        }
        var result = ToDto(session);
        await hub.Clients.Group(CoupleHub.GroupName(session.CoupleId)).SendAsync(session.Status == GameSessionStatus.Completed ? "GameCompleted" : "GameStateChanged", result, cancellationToken);
        return Ok(result);
    }

    [HttpPost("sessions/{sessionId:guid}/abandon")]
    public async Task<ActionResult<GameSessionDto>> Abandon(Guid sessionId, CancellationToken cancellationToken)
    {
        var session = await OwnedSession(sessionId, cancellationToken);
        if (session is null || !currentUser.UserId.HasValue) return NotFound();
        session.EndBy(currentUser.UserId.Value, clock.UtcNow);
        var endedBy = await db.Users.AsNoTracking().SingleAsync(user => user.Id == currentUser.UserId.Value, cancellationToken);
        var partnerId = await db.Users.AsNoTracking().Where(user => user.CoupleId == session.CoupleId && user.Id != endedBy.Id).Select(user => (Guid?)user.Id).SingleOrDefaultAsync(cancellationToken);
        Notification? notification = null;
        if (partnerId.HasValue)
        {
            notification = Notification.Create(partnerId.Value, session.CoupleId, NotificationType.GameEnded, "Game ended", $"{endedBy.DisplayName} ended your game.", JsonSerializer.Serialize(new { sessionId = session.Id }), clock.UtcNow);
            db.Notifications.Add(notification);
        }
        await db.SaveChangesAsync(cancellationToken);
        var ended = new GameEndedDto(session.Id, endedBy.Id, endedBy.DisplayName, session.EndedAtUtc ?? clock.UtcNow);
        await hub.Clients.Group(CoupleHub.GroupName(session.CoupleId)).SendAsync("GameEnded", ended, cancellationToken);
        if (notification is not null) await hub.Clients.Group(CoupleHub.UserGroupName(notification.UserId)).SendAsync("NotificationCreated", NotificationController.ToDto(notification), cancellationToken);
        return Ok(ToDto(session));
    }

    private Task<GameSession?> OwnedSession(Guid sessionId, CancellationToken cancellationToken)
        => currentUser.CoupleId.HasValue
            ? db.GameSessions.SingleOrDefaultAsync(session => session.Id == sessionId && session.CoupleId == currentUser.CoupleId.Value, cancellationToken)
            : Task.FromResult<GameSession?>(null);

    private GameSessionDto ToDto(GameSession session) => ToDto(session, engine);
    internal static GameSessionDto ToDto(GameSession session, IGameEngine gameEngine) => new(session.Id, session.CoupleId, session.GameType, session.Status, session.StartedAtUtc, session.EndedAtUtc, session.CurrentTurnUserId, session.DeadlineUtc, session.StateVersion, gameEngine.ToPublicState(session.GameType, session.StateJson), session.InvitedByUserId, session.InviteAcceptedAtUtc, session.CountdownEndsAtUtc, session.EndedByUserId, session.SetupJson);
    private static string Humanize(GameType value) => string.Concat(value.ToString().Select((character, index) => index > 0 && char.IsUpper(character) ? $" {char.ToLowerInvariant(character)}" : char.ToLowerInvariant(character).ToString()));
}

public sealed record CreateGameSessionRequest(GameType GameType, string? Letter = null);
public sealed record ApplyGameActionRequest(long ExpectedStateVersion, string Action, string? Value, int? Index);
public sealed record GameSessionDto(Guid Id, Guid CoupleId, GameType GameType, GameSessionStatus Status, DateTime? StartedAtUtc, DateTime? EndedAtUtc, Guid? CurrentTurnUserId, DateTime? DeadlineUtc, long StateVersion, string StateJson, Guid? InvitedByUserId, DateTime? InviteAcceptedAtUtc, DateTime? CountdownEndsAtUtc, Guid? EndedByUserId, string SetupJson);
