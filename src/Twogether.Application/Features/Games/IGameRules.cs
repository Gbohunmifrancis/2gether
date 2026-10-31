using Twogether.Core.Enums;

namespace Twogether.Application.Features.Games;

/// <summary>
/// One game's rules (docs/games.md). The engine finds a game's rules by its type; a new game is one class
/// registered in <see cref="GameEngine"/>. State is JSON the rules own; the server stores it and never reads it.
/// </summary>
public interface IGameRules
{
    GameType Type { get; }

    /// <summary>Either partner may move at any time (both answer at once); otherwise only the player whose turn it is.</summary>
    bool AnyoneMayAct => false;

    /// <summary>Moves have a deadline; once it passes, the server plays <see cref="GameEngine.TimeoutAction"/>.</summary>
    bool Timed => false;

    /// <summary>
    /// Both partners' moves may cross in flight: a move is judged against the state as it is when it arrives, instead
    /// of being refused for naming an older <c>expectedStateVersion</c>. The rules then check everything themselves.
    /// </summary>
    bool MovesMayCross => false;

    /// <summary>The first state. <paramref name="setupJson"/> comes from <see cref="GameSetupFactory"/>.</summary>
    GameEngineResult Start(IReadOnlyList<Guid> players, DateTime utcNow, string? setupJson);

    /// <summary>The next state, or <see cref="InvalidOperationException"/> (with a <c>Loc.T</c> message) for a move that isn't allowed.</summary>
    GameEngineResult Apply(GameMove move);

    /// <summary>
    /// What <paramref name="viewer"/> may see. Anything secret from one partner (the word to draw, an answer not yet
    /// revealed) is left out for them; a null viewer sees no secrets at all.
    /// </summary>
    string ToPublicState(string stateJson, Guid? viewer);

    GameScoreSummary Summarise(string stateJson);
}

public sealed record GameMove(
    string StateJson,
    Guid Actor,
    string Action,
    string? Value,
    int? Index,
    DateTime UtcNow,
    IReadOnlyDictionary<string, bool?>? Validations = null,
    IReadOnlyDictionary<Guid, CoSubmission>? CoSubmissions = null);
