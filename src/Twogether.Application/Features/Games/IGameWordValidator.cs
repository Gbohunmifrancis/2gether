using Twogether.Core.Enums;

namespace Twogether.Application.Features.Games;

public interface IGameWordValidator
{
    Task<IReadOnlyDictionary<string, bool?>> ValidateAsync(
        string stateJson,
        string answersJson,
        CancellationToken cancellationToken = default);

    /// <summary>
    /// Whether <paramref name="word"/> is a real English word (Word dash): true or false, or null when the dictionary
    /// can't be reached or doesn't answer in time, so the game lets the word stand.
    /// </summary>
    Task<bool?> IsWordAsync(string word, CancellationToken cancellationToken = default) => Task.FromResult<bool?>(null);
}

public static class GameWordChecks
{
    /// <summary>
    /// The dictionary's verdicts a move needs, whichever way it arrives (REST or the game hub): I call on's answers, and
    /// the word played in Word dash. Null for any other move.
    /// </summary>
    public static async Task<IReadOnlyDictionary<string, bool?>?> ForMoveAsync(this IGameWordValidator validator, GameType gameType, string stateJson,
        string action, string? value, CancellationToken cancellationToken)
    {
        if (value is null) return null;
        if (gameType == GameType.ICallOn && action.Equals("submit", StringComparison.OrdinalIgnoreCase))
            return await validator.ValidateAsync(stateJson, value, cancellationToken);
        // Word dash: null when the dictionary can't be reached, and the word stands.
        if (gameType == GameType.WordDash && action.Equals("play", StringComparison.OrdinalIgnoreCase))
            return new Dictionary<string, bool?> { [WordDashRules.WordKey] = await validator.IsWordAsync(value, cancellationToken) };
        return null;
    }
}
