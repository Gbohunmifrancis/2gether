using System.Text.Json;
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
        var winner = forcedWinnerUserId ?? summary.DeclaredWinnerUserId ?? HighestScorer(summary.Scores);
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
    private static Guid? HighestScorer(IReadOnlyDictionary<Guid, int> scores)
    {
        if (scores.Count == 0) return null;
        var best = scores.Values.Max();
        var leaders = scores.Where(item => item.Value == best).Select(item => item.Key).ToList();
        return leaders.Count == 1 ? leaders[0] : null;
    }
}
