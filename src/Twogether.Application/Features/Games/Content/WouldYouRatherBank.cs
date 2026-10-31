using Twogether.Shared.Localization;

namespace Twogether.Application.Features.Games.Content;

/// <summary>One Would you rather question: option A or option B.</summary>
public sealed record RatherPair(string A, string B);

/// <summary>
/// Would you rather's questions (docs/games.md). Global ones are English marked with <c>Loc.N</c> and translated where
/// they're shown; the Nigerian ones are English and Pidgin by design.
/// </summary>
public static class WouldYouRatherBank
{
    public static IReadOnlyList<RatherPair> Global { get; } =
    [
        P(Loc.N("Breakfast in bed"), Loc.N("Dinner under the stars")),
        P(Loc.N("A beach holiday"), Loc.N("A mountain cabin")),
        P(Loc.N("Always be early"), Loc.N("Always be a little late")),
        P(Loc.N("A surprise party"), Loc.N("A quiet surprise for two")),
        P(Loc.N("Cook dinner together"), Loc.N("Order in together")),
        P(Loc.N("Travel back in time"), Loc.N("Travel to the future")),
        P(Loc.N("Dance like nobody's watching"), Loc.N("Sing like nobody's listening")),
        P(Loc.N("A long road trip"), Loc.N("A flight somewhere new")),
        P(Loc.N("A cosy rainy day in"), Loc.N("A sunny day out")),
        P(Loc.N("Live by the sea"), Loc.N("Live in the mountains")),
        P(Loc.N("Have a dog"), Loc.N("Have a cat")),
        P(Loc.N("Movie night"), Loc.N("Game night")),
        P(Loc.N("Give up dessert"), Loc.N("Give up coffee")),
        P(Loc.N("A sunrise walk"), Loc.N("A sunset picnic")),
        P(Loc.N("Get a love letter"), Loc.N("Get a love song")),
        P(Loc.N("A big wedding"), Loc.N("A small wedding")),
        P(Loc.N("Be able to fly"), Loc.N("Be able to turn invisible")),
        P(Loc.N("Speak every language"), Loc.N("Play every instrument")),
        P(Loc.N("An endless summer"), Loc.N("An endless spring")),
        P(Loc.N("A weekend in Paris"), Loc.N("A weekend in Tokyo")),
        P(Loc.N("A series marathon"), Loc.N("A night out dancing")),
        P(Loc.N("Never use social media again"), Loc.N("Never watch TV again")),
        P(Loc.N("Win the lottery"), Loc.N("Live twice as long")),
        P(Loc.N("Eat pizza every day"), Loc.N("Never eat pizza again")),
        P(Loc.N("Camp under the stars"), Loc.N("Stay in a fancy hotel")),
        P(Loc.N("A house full of plants"), Loc.N("A house full of books")),
        P(Loc.N("Learn to cook together"), Loc.N("Learn to dance together")),
        P(Loc.N("Have a private chef"), Loc.N("Have a cleaner")),
        P(Loc.N("Talk to animals"), Loc.N("Breathe underwater")),
        P(Loc.N("A flat in a big city"), Loc.N("A cottage in the countryside")),
        P(Loc.N("Matching outfits"), Loc.N("Matching tattoos")),
        P(Loc.N("Hot chocolate"), Loc.N("Iced coffee")),
        P(Loc.N("Be famous"), Loc.N("Be rich and unknown")),
        P(Loc.N("Relive our first date"), Loc.N("Skip ahead to our next holiday")),
        P(Loc.N("A spa day"), Loc.N("An adventure day")),
        P(Loc.N("Always be a little too warm"), Loc.N("Always be a little too cold")),
        P(Loc.N("Write a book together"), Loc.N("Start a business together")),
        P(Loc.N("Live without music"), Loc.N("Live without films")),
        P(Loc.N("Go on a cruise"), Loc.N("Go on a safari")),
        P(Loc.N("One long holiday a year"), Loc.N("Lots of short breaks")),
        P(Loc.N("Breakfast for dinner"), Loc.N("Dinner for breakfast")),
        P(Loc.N("Let your partner plan every date"), Loc.N("Plan every date yourself")),
        P(Loc.N("Sing karaoke together"), Loc.N("Dance together at a wedding")),
        P(Loc.N("Wake up at 5 a.m."), Loc.N("Go to bed at 3 a.m.")),
        P(Loc.N("A handwritten note"), Loc.N("A long voice note")),
        P(Loc.N("Always win arguments"), Loc.N("Never argue at all")),
        P(Loc.N("Ice cream"), Loc.N("Cake")),
        P(Loc.N("Explore space"), Loc.N("Explore the deep sea")),
        P(Loc.N("A tiny house by a lake"), Loc.N("A huge house in the city")),
        P(Loc.N("Teleport anywhere"), Loc.N("Pause time")),
        P(Loc.N("Cuddle on the sofa"), Loc.N("Go out for a walk")),
        P(Loc.N("Eat only sweet food"), Loc.N("Eat only savoury food")),
        P(Loc.N("Be a great singer"), Loc.N("Be a great cook")),
        P(Loc.N("Read each other's minds"), Loc.N("Finish each other's sentences")),
    ];

    public static IReadOnlyList<RatherPair> Nigerian { get; } =
    [
        P("Jollof at a party", "Suya at night"),
        P("Danfo in traffic", "Okada in the rain"),
        P("Owambe every weekend", "Quiet weekends at home"),
        P("Live in Lagos", "Live in Abuja"),
        P("Pounded yam", "Amala"),
        P("Zobo", "Chapman"),
        P("Detty December in Lagos", "Christmas in the village"),
        P("Front row at an Afrobeats concert", "Red carpet at a Nollywood premiere"),
        P("Puff-puff", "Chin chin"),
        P("A day without light", "A day without data"),
        P("Watch the Super Eagles at the stadium", "Watch them at a viewing centre with friends"),
        P("Egusi soup", "Ogbono soup"),
        P("Dodo", "Boli"),
        P("Party jollof every day", "Never eat jollof again"),
        P("Akara and pap", "Bread and tea"),
        P("Aso ebi for every wedding", "Never attend another wedding"),
        P("A weekend at the beach in Lekki", "A weekend at Obudu Ranch"),
        P("Third Mainland Bridge at rush hour", "Waiting for a keke in the sun"),
        P("Mama put", "A fancy restaurant"),
        P("A wedding with 1,000 guests", "A wedding with 50 guests"),
        P("Front row at Wizkid", "Front row at Burna Boy"),
        P("Suya", "Kilishi"),
        P("Small chops", "Asun"),
        P("Eat at a buka", "Cook at home"),
        P("Pepper soup on a rainy day", "Ice cream on a hot afternoon"),
        P("Gen noise all night", "No light all night"),
        P("Plan the owambe", "Just show up and dance"),
        P("Calabar carnival", "Eyo festival"),
        P("Matching Ankara with your partner", "Matching aso oke with your partner"),
        P("Moi moi", "Akara"),
        P("Fried rice", "Coconut rice"),
        P("Climb Zuma Rock", "Climb Olumo Rock"),
        P("Mama's cooking", "Your partner's cooking"),
        P("Spray money at the party", "Pick up the money"),
        P("A danfo with loud music", "A quiet BRT ride"),
        P("Two hours in Lagos traffic", "Two hours in a bank queue"),
        P("Gala and a soft drink", "Meat pie and a Malt"),
        P("Watch Nollywood together", "Watch football together"),
        P("A weekend in Calabar", "A weekend in Port Harcourt"),
        P("Eba", "Semo"),
        P("Banga soup", "Afang soup"),
        P("Garri and groundnut", "Roasted corn and ube"),
        P("Dance to Fuji", "Dance to highlife"),
    ];

    public static IReadOnlyList<RatherPair> For(string region) => region == GameRegion.Nigeria ? Nigerian : Global;

    /// <summary><paramref name="count"/> different questions at random for one game, those in <paramref name="seen"/> last.</summary>
    public static IReadOnlyList<RatherPair> Pick(string region, int count, IReadOnlySet<string>? seen = null)
        => FreshPick.Pick(For(region), count, pair => pair.A, seen);

    private static RatherPair P(string a, string b) => new(a, b);
}
