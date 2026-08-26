namespace Twogether.Application.Features.Games;

public interface IGameWordValidator
{
    Task<IReadOnlyDictionary<string, bool?>> ValidateAsync(
        string stateJson,
        string answersJson,
        CancellationToken cancellationToken = default);
}
