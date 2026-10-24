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
using Twogether.Shared.Localization;
using Twogether.Application.Features.Notifications;

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
        if (!GameEngine.IsValidLetter(request.Letter))
            return UnprocessableEntity(new ApiError("game.invalid_letter", Loc.T("Pick a single letter from A to Z.")));
        if (!currentUser.CoupleId.HasValue || !currentUser.UserId.HasValue) return BadRequest(new ApiError("game.no_couple", Loc.T("Link a partner before starting a game.")));
        var linkedCouple = await db.Couples.AsNoTracking().AnyAsync(couple => couple.Id == currentUser.CoupleId.Value && couple.Status == CoupleStatus.Active, cancellationToken);
        if (!linkedCouple) return BadRequest(new ApiError("game.no_partner", Loc.T("Link a partner before starting a game.")));
        var active = await db.GameSessions.AnyAsync(session => session.CoupleId == currentUser.CoupleId.Value && (session.Status == GameSessionStatus.WaitingForPartner || session.Status == GameSessionStatus.InProgress), cancellationToken);
        if (active) return Conflict(new ApiError("game.active_session", Loc.T("Your couple already has an active game.")));

        var inviter = await db.Users.AsNoTracking().SingleAsync(user => user.Id == currentUser.UserId.Value, cancellationToken);
        var partnerId = await db.Users.AsNoTracking().Where(user => user.CoupleId == currentUser.CoupleId.Value && user.Id != inviter.Id).Select(user => (Guid?)user.Id).SingleOrDefaultAsync(cancellationToken);
        if (!partnerId.HasValue) return Conflict(new ApiError("game.players_missing", Loc.T("Both partners must be linked before a game is invited.")));

        var setupJson = request.GameType == GameType.ICallOn
            ? JsonSerializer.Serialize(new { letter = request.Letter?.ToUpperInvariant() })
            : JsonSerializer.Serialize(new { questions = await questionProvider.GetQuestionsAsync(cancellationToken) });
        var session = GameSession.Create(currentUser.CoupleId.Value, request.GameType, inviter.Id, clock.UtcNow, setupJson);
        Notification notification;
        using (Loc.Use(await db.LanguageOfAsync(partnerId.Value, cancellationToken)))
            notification = Notification.Create(partnerId.Value, session.CoupleId, NotificationType.GameInvite, Loc.T("A game is waiting"),
                Invited(request.GameType, inviter.DisplayName), JsonSerializer.Serialize(new { sessionId = session.Id }), clock.UtcNow);
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
        if (session.Status != GameSessionStatus.WaitingForPartner) return Conflict(new ApiError("game.not_waiting", Loc.T("This game is no longer waiting for a partner.")));
        if (session.InvitedByUserId == currentUser.UserId.Value) return Conflict(new ApiError("game.self_accept", Loc.T("The inviting partner cannot accept their own invitation.")));

        var players = await db.Users.Where(user => user.CoupleId == session.CoupleId).OrderBy(user => user.CreatedAtUtc).Select(user => user.Id).ToListAsync(cancellationToken);
        if (players.Count != 2) return Conflict(new ApiError("game.players_missing", Loc.T("Both partners must be linked before the game starts.")));
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
        return Conflict(new ApiError("game.accept_required", Loc.T("The invited partner must accept the game first.")));
    }

    [HttpPost("sessions/{sessionId:guid}/actions")]
    public async Task<ActionResult<GameSessionDto>> ApplyAction(Guid sessionId, ApplyGameActionRequest request, CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(request.Action) || request.Action.Length > 40 || request.Value?.Length > GameEngine.MaxActionValueLength)
            return UnprocessableEntity(new ApiError("game.invalid_action", Loc.T("That action is not available.")));
        var session = await OwnedSession(sessionId, cancellationToken);
        if (session is null || !currentUser.UserId.HasValue) return NotFound();
        if (session.Status != GameSessionStatus.InProgress) return Conflict(new ApiError("game.not_in_progress", Loc.T("The game is not in progress.")));
        if (session.CountdownEndsAtUtc > clock.UtcNow) return Conflict(new ApiError("game.countdown", Loc.T("Wait for the countdown to finish.")));
        if (session.StateVersion != request.ExpectedStateVersion)
            return Conflict(new ApiError("game.version_conflict", Loc.T("The game state changed. The latest board has been loaded.")));
        if (session.GameType != GameType.ICallOn && session.CurrentTurnUserId != currentUser.UserId.Value) return Forbid();
        try
        {
            // Only I call on has a clock; turn-by-turn games wait for the player whose turn it is.
            var expired = session.GameType == GameType.ICallOn && session.DeadlineUtc is { } deadline && deadline <= clock.UtcNow;
            if (GameEngine.IsServerOnlyAction(request.Action))
                return UnprocessableEntity(new ApiError("game.invalid_action", Loc.T("That action is not available.")));
            var action = expired ? GameEngine.TimeoutAction : request.Action;
            var validations = session.GameType == GameType.ICallOn && action.Equals("submit", StringComparison.OrdinalIgnoreCase) && request.Value is not null
                ? await wordValidator.ValidateAsync(session.StateJson, request.Value, cancellationToken)
                : null;
            var next = engine.Apply(session.GameType, session.StateJson, currentUser.UserId.Value, action, request.Value, request.Index, clock.UtcNow, validations);
            if (next.Completed) session.Complete(next.StateJson, clock.UtcNow);
            else session.UpdateState(next.CurrentTurnUserId!.Value, next.DeadlineUtc, next.StateJson, clock.UtcNow);
        }
        catch (JsonException)
        {
            return UnprocessableEntity(new ApiError("game.invalid_state", Loc.T("This game session is corrupted. End it and start a new game.")));
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
            return Conflict(new ApiError("game.version_conflict", Loc.T("Your partner just updated the game. The latest board has been loaded.")));
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
        if (!session.IsOpen) return Conflict(new ApiError("game.already_ended", Loc.T("This game has already ended.")));
        session.EndBy(currentUser.UserId.Value, clock.UtcNow);
        var endedBy = await db.Users.AsNoTracking().SingleAsync(user => user.Id == currentUser.UserId.Value, cancellationToken);
        var partnerId = await db.Users.AsNoTracking().Where(user => user.CoupleId == session.CoupleId && user.Id != endedBy.Id).Select(user => (Guid?)user.Id).SingleOrDefaultAsync(cancellationToken);
        Notification? notification = null;
        if (partnerId.HasValue)
        {
            using var _ = Loc.Use(await db.LanguageOfAsync(partnerId.Value, cancellationToken));
            notification = Notification.Create(partnerId.Value, session.CoupleId, NotificationType.GameEnded, Loc.T("Game ended"),
                Loc.T("{name} ended your game.", ("name", endedBy.DisplayName)), JsonSerializer.Serialize(new { sessionId = session.Id }), clock.UtcNow);
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
    /// <summary>One whole sentence per game, so each language can name it its own way.</summary>
    private static string Invited(GameType game, string name) => game switch
    {
        GameType.CoupleQuiz => Loc.T("{name} invited you to play Same answer.", ("name", name)),
        GameType.MemoryMatch => Loc.T("{name} invited you to play Memory match.", ("name", name)),
        GameType.ICallOn => Loc.T("{name} invited you to play I call on.", ("name", name)),
        GameType.GuessMyAnswer => Loc.T("{name} invited you to play Guess my answer.", ("name", name)),
        GameType.WordDash => Loc.T("{name} invited you to play Word dash.", ("name", name)),
        GameType.Handwriting => Loc.T("{name} invited you to play Handwriting.", ("name", name)),
        GameType.TruthOrDare => Loc.T("{name} invited you to play Truth or dare.", ("name", name)),
        _ => Loc.T("{name} invited you to a game.", ("name", name)),
    };
}

public sealed record CreateGameSessionRequest(GameType GameType, string? Letter = null);
public sealed record ApplyGameActionRequest(long ExpectedStateVersion, string Action, string? Value, int? Index);
public sealed record GameSessionDto(Guid Id, Guid CoupleId, GameType GameType, GameSessionStatus Status, DateTime? StartedAtUtc, DateTime? EndedAtUtc, Guid? CurrentTurnUserId, DateTime? DeadlineUtc, long StateVersion, string StateJson, Guid? InvitedByUserId, DateTime? InviteAcceptedAtUtc, DateTime? CountdownEndsAtUtc, Guid? EndedByUserId, string SetupJson);
