using Twogether.Core.Enums;
using Twogether.Core.Seedwork;

namespace Twogether.Core.Entities;

/// <summary>
/// One finished game, kept after the session itself is history. This is what the
/// scoreboard and the game-history panel read, so it records the final scores and
/// how the game ended — not just who won.
/// </summary>
public sealed class GameResult : BaseEntity
{
    private GameResult() { }

    public Guid CoupleId { get; private set; }
    public Guid GameSessionId { get; private set; }
    public GameType GameType { get; private set; }
    public GameOutcome Outcome { get; private set; }
    public DateTime? StartedAtUtc { get; private set; }
    public DateTime EndedAtUtc { get; private set; }
    /// <summary>Null means a draw.</summary>
    public Guid? WinnerUserId { get; private set; }
    public string ScoresJson { get; private set; } = "{}";
    public int RoundsPlayed { get; private set; }

    public static GameResult Create(
        Guid coupleId,
        Guid gameSessionId,
        GameType gameType,
        GameOutcome outcome,
        Guid? winnerUserId,
        string scoresJson,
        int roundsPlayed,
        DateTime? startedAtUtc,
        DateTime utcNow) => new()
        {
            CoupleId = coupleId,
            GameSessionId = gameSessionId,
            GameType = gameType,
            Outcome = outcome,
            WinnerUserId = winnerUserId,
            ScoresJson = string.IsNullOrWhiteSpace(scoresJson) ? "{}" : scoresJson,
            RoundsPlayed = roundsPlayed,
            StartedAtUtc = startedAtUtc,
            EndedAtUtc = utcNow,
            CreatedAtUtc = utcNow,
            UpdatedAtUtc = utcNow
        };
}
