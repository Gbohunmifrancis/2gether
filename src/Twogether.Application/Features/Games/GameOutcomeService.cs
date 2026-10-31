using System.Text.Json;
using Microsoft.EntityFrameworkCore;
using Twogether.Application.Common.Interfaces;
using Twogether.Core.Entities;
using Twogether.Core.Enums;

namespace Twogether.Application.Features.Games;

public interface IGameOutcomeService
{
    /// <summary>
    /// Builds the history row for a game that has just ended. Call this once per terminal
    /// transition — the controller, the hub and the timeout worker all end games, and every
    /// one of them has to leave the same record behind.
    /// </summary>
    GameResult Record(GameSession session, GameOutcome outcome, Guid? forcedWinnerUserId, DateTime utcNow);
}

public sealed class GameOutcomeService(IGameEngine engine) : IGameOutcomeService
{
    public GameResult Record(GameSession session, GameOutcome outcome, Guid? forcedWinnerUserId, DateTime utcNow)
    {
        var summary = engine.Summarise(session.GameType, session.StateJson);
        // Only a game played to the end has a winner of its own; one ended part way or left to lapse has none.
        var winner = outcome == GameOutcome.Completed
            ? forcedWinnerUserId ?? summary.DeclaredWinnerUserId ?? HighestScorer(summary.Scores)
            : forcedWinnerUserId;
        return GameResult.Create(
            session.CoupleId,
            session.Id,
            session.GameType,
            outcome,
            winner,
            JsonSerializer.Serialize(summary.Scores),
            summary.RoundsPlayed,
            session.StartedAtUtc,
            utcNow);
    }

    /// <summary>The top score, or null when it is shared — a draw has no winner.</summary>
    internal static Guid? HighestScorer(IReadOnlyDictionary<Guid, int> scores)
    {
        if (scores.Count == 0) return null;
        var best = scores.Values.Max();
        var leaders = scores.Where(item => item.Value == best).Select(item => item.Key).ToList();
        return leaders.Count == 1 ? leaders[0] : null;
    }
}

/// <summary>
/// The couple's game history (docs/games.md): one <see cref="GameResult"/> per game that was played. Every path that
/// ends a game calls <see cref="KeepAsync"/> once its own save of the ending has gone through: a move (the controller
/// or the game hub), the timeout worker, an end by a partner, the couple ending, and a game left alone for a week.
/// Because the session's version is a concurrency token, of two endings that race only one save goes through, so only
/// that one writes the row; the unique index on the session is the backstop (a second row is refused and skipped). The
/// row goes in its own save, just after the session's, rather than in the same one: there it would be inserted first
/// (EF orders by table), and a losing ending would hit the index before its stale version, a 500 instead of the usual
/// 409. A row lost in between (the process stopped) is written the next time the history is read
/// (<see cref="BackfillAsync"/>). A game played to the end is <see cref="GameOutcome.Completed"/>; one that was accepted
/// and then ended part way is kept too, with no winner (<see cref="GameOutcome.Abandoned"/>, or
/// <see cref="GameOutcome.TimedOut"/> when the server ended it). An invite never accepted was never a game: nothing.
/// </summary>
public static class GameHistory
{
    /// <summary>
    /// Writes the history row for <paramref name="session"/>, whose ending (or a later change) has just been saved.
    /// Nothing for an invite never accepted or a session still open. A game played to the end that already has its row
    /// and has changed since (a move its rules allow after the end, such as Same answer's "That counts!") gets its
    /// scores and winner worked out again from the game as it now stands; its end and outcome stay. A failed write is
    /// left for the backfill.
    /// </summary>
    public static async Task<bool> KeepAsync(IApplicationDbContext db, IGameEngine engine, GameSession session, GameOutcome outcome, DateTime utcNow, CancellationToken cancellationToken)
    {
        if (!Kept(session)) return false;
        var existing = await db.GameResults.SingleOrDefaultAsync(result => result.GameSessionId == session.Id, cancellationToken);
        if (existing is not null) return await RescoreAsync(db, engine, session, existing, utcNow, cancellationToken);
        var row = new GameOutcomeService(engine).Record(session, outcome, null, utcNow);
        db.GameResults.Add(row);
        try
        {
            await db.SaveChangesAsync(cancellationToken);
            return true;
        }
        catch (DbUpdateException)
        {
            // Written by another ending or a backfill at the same moment (the unique index), or the database is having a
            // moment: the game itself has ended either way, and a missing row is written when the history is next read.
            db.GameResults.Entry(row).State = EntityState.Detached;
            return false;
        }
    }

    /// <summary>A finished game's row, from the game as it stands now in the database (the newest change wins).</summary>
    private static async Task<bool> RescoreAsync(IApplicationDbContext db, IGameEngine engine, GameSession session, GameResult row, DateTime utcNow, CancellationToken cancellationToken)
    {
        if (row.Outcome != GameOutcome.Completed || session.Status != GameSessionStatus.Completed) return false;
        var state = await db.GameSessions.AsNoTracking().Where(item => item.Id == session.Id).Select(item => item.StateJson).SingleOrDefaultAsync(cancellationToken) ?? session.StateJson;
        var summary = engine.Summarise(session.GameType, state);
        var winner = summary.DeclaredWinnerUserId ?? GameOutcomeService.HighestScorer(summary.Scores);
        if (!row.Rescore(winner, JsonSerializer.Serialize(summary.Scores), summary.RoundsPlayed, utcNow)) return false;
        try
        {
            await db.SaveChangesAsync(cancellationToken);
            return true;
        }
        catch (DbUpdateException)
        {
            db.GameResults.Entry(row).State = EntityState.Detached;
            return false;
        }
    }

    /// <summary>Whether an ended session belongs in the history: a game that was accepted (and so started).</summary>
    public static bool Kept(GameSession session)
        => session.StartedAtUtc is not null && session.Status is GameSessionStatus.Completed or GameSessionStatus.Abandoned;

    /// <summary>How a session that ended before the history was written ended, as near as it can tell.</summary>
    public static GameOutcome OutcomeOf(GameSession session) => session.Status switch
    {
        GameSessionStatus.Completed => GameOutcome.Completed,
        _ when session.EndedByUserId is null => GameOutcome.TimedOut,
        _ => GameOutcome.Abandoned,
    };

    /// <summary>How many old games one call writes at most; the next call carries on.</summary>
    public const int BackfillBatch = 200;

    /// <summary>
    /// Games that ended before the history was written (or on a server that didn't write it) get their row now, so
    /// the history reaches back past them. Safe to run at the same time as itself: a row another request wrote first
    /// is skipped. Returns how many it wrote.
    /// </summary>
    public static async Task<int> BackfillAsync(IApplicationDbContext db, IGameEngine engine, Guid coupleId, CancellationToken cancellationToken)
    {
        var missing = await db.GameSessions.AsNoTracking()
            .Where(session => session.CoupleId == coupleId && session.StartedAtUtc != null
                && (session.Status == GameSessionStatus.Completed || session.Status == GameSessionStatus.Abandoned)
                && !db.GameResults.Any(result => result.GameSessionId == session.Id))
            .OrderByDescending(session => session.EndedAtUtc)
            .Take(BackfillBatch)
            .ToListAsync(cancellationToken);
        if (missing.Count == 0) return 0;
        var service = new GameOutcomeService(engine);
        var rows = missing.Select(session => service.Record(session, OutcomeOf(session), null, session.EndedAtUtc ?? session.UpdatedAtUtc)).ToList();
        db.GameResults.AddRange(rows);
        try
        {
            await db.SaveChangesAsync(cancellationToken);
            return rows.Count;
        }
        catch (DbUpdateException)
        {
            // Another request wrote some of them first (the unique index): leave it to that one.
            foreach (var row in rows) db.GameResults.Entry(row).State = EntityState.Detached;
            return 0;
        }
    }
}
