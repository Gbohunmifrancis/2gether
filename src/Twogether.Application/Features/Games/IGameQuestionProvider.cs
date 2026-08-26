namespace Twogether.Application.Features.Games;

public interface IGameQuestionProvider
{
    Task<IReadOnlyList<string>> GetQuestionsAsync(CancellationToken cancellationToken = default);
}
