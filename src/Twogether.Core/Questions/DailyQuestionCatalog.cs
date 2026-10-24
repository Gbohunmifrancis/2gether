using Twogether.Shared.Localization;

namespace Twogether.Core.Questions;

/// <summary><c>Text</c> is English; <c>Loc.T(Text)</c> shows it in the reader's language.</summary>
public sealed record DailyQuestionDefinition(string Key, string Category, string Text);

/// <summary>
/// The daily question bank. Keys are permanent: answers point at them, so a key is never
/// reused or renamed. Fix wording freely; retire a question by removing it (old answers then
/// show a placeholder). Everyday, honest questions: nothing explicit.
/// </summary>
public static class DailyQuestionCatalog
{
    public static IReadOnlyList<DailyQuestionDefinition> All { get; } =
    [
        // Everyday
        Q("everyday-01", "everyday", Loc.N("What was the best part of your day today, even if it was small?")),
        Q("everyday-02", "everyday", Loc.N("What is one thing I could take off your plate this week?")),
        Q("everyday-03", "everyday", Loc.N("What song has been stuck in your head lately?")),
        Q("everyday-04", "everyday", Loc.N("What are you looking forward to tomorrow?")),
        Q("everyday-05", "everyday", Loc.N("What did you eat today that you would happily eat again?")),
        Q("everyday-06", "everyday", Loc.N("If you could skip one chore forever, which one would it be?")),
        Q("everyday-07", "everyday", Loc.N("What made you laugh today?")),
        Q("everyday-08", "everyday", Loc.N("What is something you noticed about me recently that you never mentioned?")),
        Q("everyday-09", "everyday", Loc.N("Which part of our evening routine do you like most?")),
        Q("everyday-10", "everyday", Loc.N("What is one small habit of mine that you secretly like?")),
        // Feelings
        Q("feelings-01", "feelings", Loc.N("When did you last feel really appreciated by me?")),
        Q("feelings-02", "feelings", Loc.N("What is something that has been on your mind that you have not said out loud yet?")),
        Q("feelings-03", "feelings", Loc.N("What does a good day between us look like to you?")),
        Q("feelings-04", "feelings", Loc.N("When you are stressed, what helps more: space or company?")),
        Q("feelings-05", "feelings", Loc.N("What is one thing I do that makes you feel safe?")),
        Q("feelings-06", "feelings", Loc.N("Is there anything I said recently that stayed with you, good or bad?")),
        Q("feelings-07", "feelings", Loc.N("What is a worry you would like me to help carry?")),
        Q("feelings-08", "feelings", Loc.N("How do you like to be comforted when you are sad?")),
        Q("feelings-09", "feelings", Loc.N("What is one thing you are proud of yourself for this month?")),
        Q("feelings-10", "feelings", Loc.N("When did you last feel really proud of us?")),
        // Memories
        Q("memories-01", "memories", Loc.N("What is your favourite memory of us from this year?")),
        Q("memories-02", "memories", Loc.N("What did you think of me the first time we met?")),
        Q("memories-03", "memories", Loc.N("Which trip or outing with me would you relive tomorrow?")),
        Q("memories-04", "memories", Loc.N("What is a moment you knew this was serious?")),
        Q("memories-05", "memories", Loc.N("What is the funniest thing that has happened to us?")),
        Q("memories-06", "memories", Loc.N("Which photo of us do you like most, and why?")),
        Q("memories-07", "memories", Loc.N("What is a meal we shared that you still think about?")),
        Q("memories-08", "memories", Loc.N("What was the hardest moment we got through together?")),
        Q("memories-09", "memories", Loc.N("What is a gift, big or small, that meant a lot to you?")),
        Q("memories-10", "memories", Loc.N("Which childhood memory do you wish I had been part of?")),
        // Future
        Q("future-01", "future", Loc.N("If we had one extra free weekend a month, what would you want us to do with it?")),
        Q("future-02", "future", Loc.N("Where would you like us to be living in five years?")),
        Q("future-03", "future", Loc.N("What is one skill you would like us to learn together?")),
        Q("future-04", "future", Loc.N("What tradition would you like us to start?")),
        Q("future-05", "future", Loc.N("What does a perfect slow Sunday look like for us a year from now?")),
        Q("future-06", "future", Loc.N("Which place in the world should be our next trip?")),
        Q("future-07", "future", Loc.N("What is one goal you would like me to cheer you on for?")),
        Q("future-08", "future", Loc.N("What would you like our home to feel like when people visit?")),
        Q("future-09", "future", Loc.N("What is something you want us to do before the end of this year?")),
        Q("future-10", "future", Loc.N("How do you picture an ordinary Tuesday when we are old?")),
        // Money
        Q("money-01", "money", Loc.N("What is one purchase you have never regretted?")),
        Q("money-02", "money", Loc.N("Are you more of a saver or a spender, and where did that come from?")),
        Q("money-03", "money", Loc.N("What would you do with an unexpected bonus this month?")),
        Q("money-04", "money", Loc.N("What is something worth spending more on, in your view?")),
        Q("money-05", "money", Loc.N("What did your family teach you about money, on purpose or not?")),
        Q("money-06", "money", Loc.N("Is there a money conversation we keep putting off?")),
        Q("money-07", "money", Loc.N("What would financial freedom let you do that you cannot do now?")),
        Q("money-08", "money", Loc.N("What is a cheap date idea you would genuinely enjoy?")),
        // Family and friends
        Q("family-01", "family", Loc.N("Which of your friends do you wish I knew better?")),
        Q("family-02", "family", Loc.N("What is a family habit you want to keep, and one you want to drop?")),
        Q("family-03", "family", Loc.N("How often would you like us to see family and friends?")),
        Q("family-04", "family", Loc.N("Who in your life taught you the most about love?")),
        Q("family-05", "family", Loc.N("What is something about my family that you appreciate?")),
        Q("family-06", "family", Loc.N("What kind of support do you want from me at big family events?")),
        Q("family-07", "family", Loc.N("Which couple we know do you admire, and why?")),
        Q("family-08", "family", Loc.N("What does home mean to you?")),
        // Growth
        Q("growth-01", "growth", Loc.N("What is a disagreement we had that you learned something from?")),
        Q("growth-02", "growth", Loc.N("What is one thing you would like us to do better as a team?")),
        Q("growth-03", "growth", Loc.N("What is a habit you are trying to build, and how can I help?")),
        Q("growth-04", "growth", Loc.N("When I am upset, what is the most helpful thing you can do?")),
        Q("growth-05", "growth", Loc.N("What is something you have changed your mind about since we met?")),
        Q("growth-06", "growth", Loc.N("What do you need more of from me right now?")),
        Q("growth-07", "growth", Loc.N("What is something I do that you would like me to keep doing forever?")),
        Q("growth-08", "growth", Loc.N("How can I make apologies easier for you to accept, or to give?")),
        Q("growth-09", "growth", Loc.N("What is a boundary that matters to you that I should know about?")),
        Q("growth-10", "growth", Loc.N("What would make our weekly check-ins feel worth it?")),
        // Fun
        Q("fun-01", "fun", Loc.N("If we swapped jobs for a day, how would you do mine?")),
        Q("fun-02", "fun", Loc.N("Which film character are we as a couple?")),
        Q("fun-03", "fun", Loc.N("What is a silly dare you would actually do with me?")),
        Q("fun-04", "fun", Loc.N("If we opened a small business together, what would it be?")),
        Q("fun-05", "fun", Loc.N("What would our couple name be on a cooking show?")),
        Q("fun-06", "fun", Loc.N("Which three songs belong on our soundtrack?")),
        Q("fun-07", "fun", Loc.N("If you could plan a surprise day for me, what would it include?")),
        Q("fun-08", "fun", Loc.N("What is the most underrated snack, and will you share it with me?")),
        // Closeness (gentle, never explicit)
        Q("close-01", "closeness", Loc.N("What small gesture makes you feel loved the most?")),
        Q("close-02", "closeness", Loc.N("When do you feel closest to me?")),
        Q("close-03", "closeness", Loc.N("What is your favourite way for us to reconnect after a busy week?")),
        Q("close-04", "closeness", Loc.N("What is a compliment you would like to hear more often?")),
        Q("close-05", "closeness", Loc.N("Which of my hugs is your favourite: morning, goodbye, or after a long day?")),
        Q("close-06", "closeness", Loc.N("What does romance look like to you on an ordinary weeknight?"))
    ];

    private static readonly Dictionary<string, DailyQuestionDefinition> ByKey = All.ToDictionary(item => item.Key);

    public static DailyQuestionDefinition? Find(string key) => ByKey.GetValueOrDefault(key);

    private static DailyQuestionDefinition Q(string key, string category, string text) => new(key, category, text);
}
