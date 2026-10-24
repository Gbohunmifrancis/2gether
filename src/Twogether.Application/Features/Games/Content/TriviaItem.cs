using System.Security.Cryptography;

namespace Twogether.Application.Features.Games;

/// <summary>A bank question as written: the right answer first, then three wrong ones. Options are shuffled when drawn.</summary>
public sealed record TriviaItem(string Question, string Answer, string Wrong1, string Wrong2, string Wrong3)
{
    public TriviaQuestion Shuffled(bool translate) => TriviaQuestion.Shuffled(Question, Answer, [Wrong1, Wrong2, Wrong3], translate);

    /// <summary><paramref name="count"/> different questions from <paramref name="bank"/>, in random order.</summary>
    public static IReadOnlyList<TriviaQuestion> Draw(IReadOnlyList<TriviaItem> bank, int count, bool translate)
    {
        var order = Enumerable.Range(0, bank.Count).ToArray();
        RandomNumberGenerator.Shuffle(order.AsSpan());
        return order.Take(count).Select(index => bank[index].Shuffled(translate)).ToList();
    }
}
