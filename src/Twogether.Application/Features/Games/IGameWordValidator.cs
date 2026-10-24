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
