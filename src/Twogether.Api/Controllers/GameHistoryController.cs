using System.Text.Json;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using Twogether.Api.Common;
using Twogether.Application.Common.Interfaces;
using Twogether.Application.Features.Games;
using Twogether.Core.Enums;

namespace Twogether.Api.Controllers;

/// <summary>
/// The couple's game history (docs/games.md), from the history rows every ending writes, so it reaches back past the
/// last 30 sessions that <c>GET api/game/sessions</c> lists. Each item carries the game as a session too (as the
/// signed-in partner may see it), so a phone shows a game from the history the way it shows any other.
/// </summary>
[Authorize]
public sealed class GameHistoryController(IApplicationDbContext db, ICurrentUser currentUser, IGameEngine engine) : ApiControllerBase
{
    public const int DefaultLimit = 20;
    public const int MaxLimit = 50;

    /// <summary>
    /// Newest first: <paramref name="limit"/> games (1 to 50, 20 by default) that ended before <paramref name="before"/>
    /// (for the next page: the last item's <c>endedAtUtc</c>). Games that ended before the history was written are
    /// added to it the first time it's read.
    /// </summary>
    [HttpGet("~/api/game/history")]
    public async Task<ActionResult<IReadOnlyList<GameHistoryItemDto>>> List([FromQuery] int? limit, [FromQuery] DateTime? before, CancellationToken cancellationToken)
    {
        if (!currentUser.CoupleId.HasValue) return Ok(Array.Empty<GameHistoryItemDto>());
        var coupleId = currentUser.CoupleId.Value;
        await GameHistory.BackfillAsync(db, engine, coupleId, cancellationToken);
        var take = Math.Clamp(limit ?? DefaultLimit, 1, MaxLimit);
        var beforeUtc = before?.ToUniversalTime();
        var rows = await db.GameResults.AsNoTracking()
            .Where(result => result.CoupleId == coupleId && (beforeUtc == null || result.EndedAtUtc < beforeUtc))
            .OrderByDescending(result => result.EndedAtUtc)
            .Take(take)
            .ToListAsync(cancellationToken);
        var ids = rows.Select(row => row.GameSessionId).ToList();
        var sessions = await db.GameSessions.AsNoTracking().Where(session => ids.Contains(session.Id)).ToDictionaryAsync(session => session.Id, cancellationToken);
        return Ok(rows.Select(row => new GameHistoryItemDto(
            row.GameSessionId,
            row.GameType,
            row.Outcome,
            row.WinnerUserId,
            Scores(row.ScoresJson),
            row.RoundsPlayed,
            row.StartedAtUtc,
            row.EndedAtUtc,
            sessions.TryGetValue(row.GameSessionId, out var session) ? GameController.ToDto(session, engine, currentUser.UserId) : null)).ToList());
    }

    private static IReadOnlyDictionary<Guid, int> Scores(string json)
    {
        try { return JsonSerializer.Deserialize<Dictionary<Guid, int>>(json) ?? []; }
        catch (JsonException) { return new Dictionary<Guid, int>(); }
    }
}

/// <summary>
/// One game in the history: how it ended (<c>completed</c>, <c>abandoned</c> or <c>timedOut</c>), its winner (null for
/// a draw, a game played as a team, or one that didn't finish), each player's score and the rounds played, and the
/// game itself as a session (null if that's gone).
/// </summary>
public sealed record GameHistoryItemDto(
    Guid SessionId,
    GameType GameType,
    GameOutcome Outcome,
    Guid? WinnerUserId,
    IReadOnlyDictionary<Guid, int> Scores,
    int RoundsPlayed,
    DateTime? StartedAtUtc,
    DateTime EndedAtUtc,
    GameSessionDto? Session);
