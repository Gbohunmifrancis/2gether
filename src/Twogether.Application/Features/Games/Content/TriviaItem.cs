namespace Twogether.Application.Features.Games;

/// <summary>A bank question as written: the right answer first, then three wrong ones. Options are shuffled when drawn.</summary>
public sealed record TriviaItem(string Question, string Answer, string Wrong1, string Wrong2, string Wrong3)
{
    public TriviaQuestion Shuffled(bool translate) => TriviaQuestion.Shuffled(Question, Answer, [Wrong1, Wrong2, Wrong3], translate);

    /// <summary>
    /// <paramref name="count"/> different questions from <paramref name="bank"/>, in random order; questions in
    /// <paramref name="seen"/> (the couple's last few games) only once the rest run out.
    /// </summary>
    public static IReadOnlyList<TriviaQuestion> Draw(IReadOnlyList<TriviaItem> bank, int count, bool translate, IReadOnlySet<string>? seen = null)
        => Content.FreshPick.Pick(bank, count, item => item.Question, seen).Select(item => item.Shuffled(translate)).ToList();
}
