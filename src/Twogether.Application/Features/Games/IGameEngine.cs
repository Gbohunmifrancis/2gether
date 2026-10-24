using Twogether.Core.Enums;

namespace Twogether.Application.Features.Games;

public interface IGameEngine
{
    GameEngineResult Start(GameType gameType, IReadOnlyList<Guid> playerIds, DateTime utcNow, string? setupJson = null);
    GameEngineResult Apply(GameType gameType, string stateJson, Guid actorUserId, string action, string? value, int? index, DateTime utcNow, IReadOnlyDictionary<string, bool?>? validations = null, IReadOnlyDictionary<Guid, CoSubmission>? coSubmissions = null);
    /// <summary>The state with every secret left out, for anyone who isn't a player.</summary>
    string ToPublicState(GameType gameType, string stateJson);
    /// <summary>The state as <paramref name="viewer"/> may see it (docs/games.md).</summary>
    string ToPublicState(GameType gameType, string stateJson, Guid? viewer);
    /// <summary>Either partner may move at any time; otherwise only the player whose turn it is.</summary>
    bool AnyoneMayAct(GameType gameType);
    /// <summary>Moves have a deadline that the server enforces.</summary>
    bool IsTimed(GameType gameType);
    /// <summary>Scores and rounds played, for writing a game result when the game ends.</summary>
    GameScoreSummary Summarise(GameType gameType, string stateJson);
}

public sealed record GameEngineResult(string StateJson, Guid? CurrentTurnUserId, DateTime? DeadlineUtc, bool Completed);

/// <summary>A partner's in-progress answers, committed alongside the submitting player's.</summary>
public sealed record CoSubmission(string AnswersJson, IReadOnlyDictionary<string, bool?>? Validations = null);

public sealed record GameScoreSummary(IReadOnlyDictionary<Guid, int> Scores, int RoundsPlayed, Guid? DeclaredWinnerUserId);
