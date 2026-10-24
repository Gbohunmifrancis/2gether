using System.Security.Cryptography;
using System.Text.Json.Serialization;

namespace Twogether.Application.Features.Games;

public interface IGameQuestionProvider
{
    Task<IReadOnlyList<string>> GetQuestionsAsync(CancellationToken cancellationToken = default);

    /// <summary>Multiple-choice questions from Open Trivia DB, options shuffled; empty when the service can't be reached.</summary>
    Task<IReadOnlyList<TriviaQuestion>> GetTriviaAsync(int amount, CancellationToken cancellationToken = default);
}

/// <summary>
/// One trivia question: four options and the index of the right one. <see cref="Translate"/> marks the built-in
/// English bank (written with <c>Loc.N</c>), shown with <c>Loc.T</c>; fetched and Naija questions are shown as they are.
/// </summary>
public sealed record TriviaQuestion(string Text, IReadOnlyList<string> Options, int Answer, bool Translate = false)
{
    public const int OptionCount = 4;

    /// <summary>The right answer and three wrong ones, in random order.</summary>
    public static TriviaQuestion Shuffled(string text, string correct, IReadOnlyList<string> incorrect, bool translate = false)
    {
        var options = new List<string>(incorrect.Count + 1) { correct };
        options.AddRange(incorrect);
        for (var i = options.Count - 1; i > 0; i--)
        {
            var swap = RandomNumberGenerator.GetInt32(i + 1);
            (options[i], options[swap]) = (options[swap], options[i]);
        }
        return new TriviaQuestion(text, options, options.IndexOf(correct), translate);
    }

    /// <summary>A question the game can play: text, four distinct options, and an answer among them.</summary>
    [JsonIgnore]
    public bool IsPlayable =>
        !string.IsNullOrWhiteSpace(Text)
        && Options is { Count: OptionCount }
        && Options.All(option => !string.IsNullOrWhiteSpace(option))
        && Options.Distinct(StringComparer.OrdinalIgnoreCase).Count() == OptionCount
        && Answer is >= 0 and < OptionCount;
}
