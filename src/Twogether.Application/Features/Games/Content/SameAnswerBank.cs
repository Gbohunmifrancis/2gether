using Twogether.Shared.Localization;

namespace Twogether.Application.Features.Games.Content;

/// <summary>
/// Same answer's prompts: questions about the two of you, which both partners answer in their own words
/// (docs/games.md). Every prompt is about "us", never about "you", so both can give the same answer. A prompt that
/// starts with "Who" asks which of you two, and is answered by picking one (<see cref="IsWho"/>). Global ones are
/// English marked with <c>Loc.N</c> and translated where they're shown; the Nigerian ones are English and Pidgin by design.
/// </summary>
public static class SameAnswerBank
{
    public static IReadOnlyList<string> Global { get; } =
    [
        Loc.N("What is our ideal date night?"),
        Loc.N("Which board game would we play tonight?"),
        Loc.N("What always makes us laugh?"),
        Loc.N("What is our best shared memory?"),
        Loc.N("Which meal would we happily repeat?"),
        Loc.N("What's our favourite ice cream flavour?"),
        Loc.N("Which animal are we most like as a couple?"),
        Loc.N("What would our perfect slow Sunday look like?"),
        Loc.N("Which new skill should we learn together?"),
        Loc.N("What place feels like home to us?"),
        Loc.N("What is one dream we should make time for?"),
        Loc.N("What's our favourite way to unwind after a long day?"),
        Loc.N("What's our song?"),
        Loc.N("Where should our next trip be?"),
        Loc.N("Who said \"I love you\" first?"),
        Loc.N("Who is the better cook?"),
        Loc.N("What would we name a pet?"),
        Loc.N("What's our go-to takeaway?"),
        Loc.N("Who falls asleep first?"),
        Loc.N("Which film should we watch again together?"),
        Loc.N("Where did we first meet?"),
        Loc.N("What's our favourite thing to do on a rainy day?"),
        Loc.N("Who is more likely to get lost?"),
        Loc.N("What colour should our front door be?"),
        Loc.N("What must our dream house have?"),
        Loc.N("Who is the bigger romantic?"),
        Loc.N("Which dish should we learn to cook together?"),
        Loc.N("Who apologises first after an argument?"),
        Loc.N("What's the best gift we've given each other?"),
        Loc.N("Which season suits us best?"),
        Loc.N("Which city should we live in one day?"),
        Loc.N("Who takes longer to get ready?"),
        Loc.N("What's our favourite snack to share?"),
        Loc.N("What would we do with a free day tomorrow?"),
        Loc.N("Who is the funnier one?"),
        Loc.N("What one word sums us up?"),
        Loc.N("Who is better with money?"),
        Loc.N("How should we celebrate our next anniversary?"),
        Loc.N("What's the first thing we'd buy if we won the lottery?"),
        Loc.N("Which singer should we see live together?"),
        Loc.N("Who wakes up first?"),
        Loc.N("What's our favourite way to spend a Friday night?"),
        Loc.N("Who is more likely to cry at a film?"),
        Loc.N("Who picks the restaurant most often?"),
        Loc.N("What did we eat on our first date?"),
        Loc.N("Who remembers birthdays better?"),
        Loc.N("Which series should we watch next?"),
        Loc.N("What's our favourite breakfast?"),
    ];

    public static IReadOnlyList<string> Nigerian { get; } =
    [
        "Which Nigerian city should we visit next?",
        "Our wedding jollof: party or smoky?",
        "Who would win a jollof cook-off between us?",
        "Which Afrobeats song is our song?",
        "Who dances better at an owambe?",
        "What colour should our aso ebi be?",
        "Which swallow could we eat every day?",
        "Detty December: Lagos or the village?",
        "Who would survive Lagos traffic better?",
        "Which Nollywood film should we watch together?",
        "What's our favourite small chops?",
        "Who is more likely to spray money at a party?",
        "Where in Nigeria should we go for a honeymoon?",
        "Which soup do we both love?",
        "Who handles NEPA taking light better?",
        "What Naija name would we give our dog?",
        "Who tells the best stories from back home?",
        "Which Super Eagles player would we both love to meet?",
        "Zobo or Chapman at our wedding?",
        "Who is the bigger foodie?",
        "What's the best Naija street food?",
        "Which beach should we spend a weekend at?",
        "Who would win at Ludo?",
        "Where should we eat on our next date night?",
        "Which Pidgin word do we use the most?",
        "Which festival should we go to together?",
        "Who is more likely to price things at the market?",
        "Danfo, keke or okada: which would we take together?",
        "Which Naija artist should play at our wedding?",
        "What must be on our owambe menu?",
        "Who is the life of the party in our family?",
        "Suya or asun for our next movie night?",
    ];

    public static IReadOnlyList<string> For(string region) => region == GameRegion.Nigeria ? Nigerian : Global;

    /// <summary><paramref name="count"/> different prompts at random for one game, those in <paramref name="seen"/> last.</summary>
    public static IReadOnlyList<string> Pick(string region, int count, IReadOnlySet<string>? seen = null)
        => FreshPick.Pick(For(region), count, prompt => prompt, seen);

    /// <summary>
    /// A prompt answered by picking one of the two of you ("Who wakes up first?"), not in words. Only the bank's own
    /// prompts: an old game's trivia question ("Who painted the Mona Lisa?") is still answered in words.
    /// </summary>
    public static bool IsWho(string prompt) => prompt.StartsWith("Who ", StringComparison.Ordinal) && Known.Contains(prompt);

    private static readonly HashSet<string> Known = [.. Global, .. Nigerian];
}
